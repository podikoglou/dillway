package com.dillway

import com.dillway.model.InferenceReport
import com.dillway.recording.Recording
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The admin-facing lifecycle of a stopped recording.
 *
 * `/dill rec stop` detaches the session and offers three actions in chat:
 * label it as training data, run inference, or discard it. A recording waits
 * in memory until the admin picks one. Chat buttons run the same commands an
 * admin can type, so the flow also works from the console.
 */
class RecordingFlow(
    private val plugin: DillPlugin,
    private val listener: DillListener,
) {

    private class Pending(
        val stopped: StoppedRecording,
        val labels: MutableSet<String> = linkedSetOf(Labels.LEGIT),
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Shows the action menu for a just-stopped recording. */
    fun offer(sender: CommandSender, stopped: StoppedRecording) {
        pending.put(key(sender), Pending(stopped))?.let { old ->
            plugin.logger.warning(
                "Discarded unresolved recording of ${old.stopped.playerName} " +
                    "when a new one stopped",
            )
        }

        val text = Component.text()
            .append(
                mini(
                    "<gray>Recording of <yellow><player></yellow> stopped with " +
                        "<aqua><packets></aqua> packets.",
                    Placeholder.unparsed("player", stopped.playerName),
                    Placeholder.unparsed("packets", stopped.session.packets.size.toString()),
                ),
            )
            .append(Component.newline())
            .append(mini("<gray>What should happen to it?"))
            .append(Component.newline())

        if (sender is Player) {
            text.append(chatButton("[Train]", "/dill rec train", NamedTextColor.GREEN))
                .append(separator())
                .append(chatButton("[Predict]", "/dill rec predict", NamedTextColor.AQUA))
                .append(separator())
                .append(chatButton("[Discard]", "/dill rec discard", NamedTextColor.RED))
        } else {
            text.append(
                mini(
                    "<gray>Run <white>/dill rec train</white>, " +
                        "<white>/dill rec predict</white> " +
                        "or <white>/dill rec discard</white>.",
                ),
            )
        }

        sender.sendMessage(text.build())
    }

    /** Opens the multi-select label menu. */
    fun train(sender: CommandSender) {
        val entry = pending[key(sender)] ?: return noPending(sender)
        sendLabelMenu(sender, entry)
    }

    /** Toggles one label, keeping `legit` exclusive of cheats. */
    fun toggleLabel(sender: CommandSender, rawLabel: String) {
        val entry = pending[key(sender)] ?: return noPending(sender)
        val label = Labels.normalize(rawLabel)

        if (label !in plugin.availableLabels()) {
            sender.sendMini(
                "<red>Unknown label <white><label></white>.</red>",
                Placeholder.unparsed("label", rawLabel),
            )
            return
        }

        if (label == Labels.LEGIT) {
            entry.labels.clear()
            entry.labels += Labels.LEGIT
        } else {
            entry.labels.remove(Labels.LEGIT)
            if (!entry.labels.remove(label)) entry.labels += label
            if (entry.labels.isEmpty()) entry.labels += Labels.LEGIT
        }

        sendLabelMenu(sender, entry)
    }

    /** Writes one parquet per selected label, off the main thread. */
    fun save(sender: CommandSender) {
        val senderKey = key(sender)
        val entry = pending.remove(senderKey) ?: return noPending(sender)
        val labels = entry.labels.toList()

        if (labels.isEmpty()) {
            pending[senderKey] = entry
            sender.sendMini("<red>Select at least one label first.</red>")
            return
        }

        sender.sendMini("<gray>Saving recording ...</gray>")

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val uploaded = mutableListOf<String>()
            val kept = mutableListOf<String>()
            val failed = mutableListOf<String>()

            for (label in labels) {
                val file = listener.write(entry.stopped, label)
                if (file == null) {
                    failed += label
                    continue
                }
                if (!plugin.storage.enabled) {
                    kept += label
                } else {
                    plugin.storage.upload(file, label, file.name)
                    // On an async worker upload runs inline; a surviving file
                    // means the upload failed and the local copy was kept.
                    if (file.exists()) kept += label else uploaded += label
                }
            }

            sync(sender) {
                val text = Component.text()
                    .append(
                        mini(
                            "<gray>Recording of <yellow><player></yellow>:</gray>",
                            Placeholder.unparsed("player", entry.stopped.playerName),
                        ),
                    )
                appendList(text, "uploaded", uploaded, NamedTextColor.GREEN)
                appendList(
                    text,
                    if (plugin.storage.enabled) "kept locally" else "stored locally (s3 disabled)",
                    kept,
                    NamedTextColor.YELLOW,
                )
                appendList(text, "failed", failed, NamedTextColor.RED)
                sender.sendMessage(text.build())
            }
        })
    }

    /** Runs every loaded specialist over the recording, off the main thread. */
    fun predict(sender: CommandSender) {
        val senderKey = key(sender)
        val entry = pending.remove(senderKey) ?: return noPending(sender)

        if (plugin.models.summary().isEmpty()) {
            pending[senderKey] = entry
            sender.sendMini("<red>No models are loaded. Run <white>/dill models reload</white>.</red>")
            return
        }

        sender.sendMini("<gray>Running inference ...</gray>")

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val recording = Recording(
                id = entry.stopped.session.started,
                started = entry.stopped.session.started,
                player = entry.stopped.session.playerId.toString(),
                label = Labels.LEGIT, // prediction only; never written to disk
                packets = entry.stopped.session.packets.toList(),
            )

            val report = try {
                plugin.models.infer(recording)
            } catch (e: Exception) {
                plugin.logger.severe("Inference failed: ${e.message}")
                null
            }

            sync(sender) {
                if (report == null) {
                    sender.sendMini("<red>Inference failed. See the server log.</red>")
                } else {
                    sendReport(sender, entry.stopped.playerName, report)
                }
            }
        })
    }

    /** Drops the pending recording. */
    fun discard(sender: CommandSender) {
        val entry = pending.remove(key(sender)) ?: return noPending(sender)
        sender.sendMini(
            "<gray>Discarded recording of <yellow><player></yellow>.</gray>",
            Placeholder.unparsed("player", entry.stopped.playerName),
        )
    }

    /**
     * Called on shutdown. Unresolved recordings cannot be labeled anymore,
     * so they are written locally as `unlabeled` instead of being dropped.
     */
    fun clear() {
        pending.values.forEach { entry ->
            plugin.logger.warning(
                "Server stopping: saving waiting recording of " +
                    "${entry.stopped.playerName} as ${Labels.UNLABELED}",
            )
            listener.write(entry.stopped, Labels.UNLABELED)
        }
        pending.clear()
    }

    private fun sendLabelMenu(sender: CommandSender, entry: Pending) {
        val selected = entry.labels.joinToString(", ")
        val text = Component.text()
            .append(
                mini(
                    "<gray>Labels for <yellow><player></yellow>'s recording. " +
                        "Click to toggle, then <white>Done</white>.</gray>",
                    Placeholder.unparsed("player", entry.stopped.playerName),
                ),
            )
            .append(Component.newline())
            .append(
                mini(
                    "<gray>Selected: <aqua><labels></aqua></gray>",
                    Placeholder.unparsed("labels", selected),
                ),
            )
            .append(Component.newline())

        plugin.availableLabels().forEachIndexed { index, label ->
            if (index > 0) text.append(Component.text(" "))
            val isSelected = label in entry.labels
            val labelText = if (isSelected) "[x] $label" else "[ ] $label"
            text.append(
                chatButton(
                    labelText,
                    "/dill label $label",
                    if (isSelected) NamedTextColor.GREEN else NamedTextColor.GRAY,
                ),
            )
        }

        text.append(Component.newline())
            .append(chatButton("[Done]", "/dill save"))
            .append(separator())
            .append(chatButton("[Discard]", "/dill rec discard", NamedTextColor.RED))

        sender.sendMessage(text.build())
    }

    private fun sendReport(sender: CommandSender, playerName: String, report: InferenceReport) {
        val threshold = plugin.config.getDouble("prediction.threshold", 0.5)
        val text = Component.text()
            .append(
                mini(
                    "<gray>Prediction for <yellow><player></yellow> over " +
                        "<aqua><packets></aqua> movement packets " +
                        "(<aqua><models></aqua> models).</gray>",
                    Placeholder.unparsed("player", playerName),
                    Placeholder.unparsed("packets", report.movementPackets.toString()),
                    Placeholder.unparsed("models", report.modelCount.toString()),
                ),
            )

        for (verdict in report.verdicts) {
            text.append(Component.newline())
            if (verdict.windows == 0) {
                text.append(
                    mini(
                        "<gray><cheat>: <yellow>no usable movement windows</yellow></gray>",
                        Placeholder.unparsed("cheat", verdict.cheat),
                    ),
                )
                continue
            }

            val flagged = verdict.maxProbability >= threshold
            text.append(
                mini(
                    "<gray><cheat>: <white>mean <mean>%</white>, " +
                        "<white>max <max>%</white> over <aqua><windows></aqua> windows — </gray>",
                    Placeholder.unparsed("cheat", verdict.cheat),
                    Placeholder.unparsed("mean", percent(verdict.meanProbability)),
                    Placeholder.unparsed("max", percent(verdict.maxProbability)),
                    Placeholder.unparsed("windows", verdict.windows.toString()),
                ),
            ).append(
                Component.text(
                    if (flagged) "FLAGGED" else "clear",
                    if (flagged) NamedTextColor.RED else NamedTextColor.GREEN,
                ),
            ).append(
                mini(
                    " <dark_gray>(model <version>, <window>s window)</dark_gray>",
                    Placeholder.unparsed("version", verdict.version),
                    Placeholder.unparsed("window", verdict.windowSeconds.toString()),
                ),
            )
        }

        for (failure in report.failures) {
            text.append(Component.newline())
                .append(
                    mini(
                        "<red><failure></red>",
                        Placeholder.unparsed("failure", failure),
                    ),
                )
        }

        sender.sendMessage(text.build())
    }

    private fun appendList(
        text: TextComponent.Builder,
        title: String,
        values: List<String>,
        color: NamedTextColor,
    ) {
        if (values.isEmpty()) return
        text.append(Component.newline())
            .append(Component.text("$title: ", NamedTextColor.GRAY))
            .append(Component.text(values.joinToString(", "), color))
    }

    private fun noPending(sender: CommandSender) {
        sender.sendMini(
            "<red>No stopped recording is waiting. Start one with " +
                "<white>/dill rec start</white> and stop it with " +
                "<white>/dill rec stop</white>.</red>",
        )
    }

    private fun sync(sender: CommandSender, action: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable { action() })
    }

    private fun key(sender: CommandSender): String =
        (sender as? Player)?.uniqueId?.toString() ?: "console:${sender.name}"

    private fun percent(value: Double): String =
        String.format(Locale.ROOT, "%.1f", value * 100.0)
}
