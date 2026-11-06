package com.dillway

import com.dillway.recording.Recording
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
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
        pending.put(senderKey(sender), Pending(stopped))?.let { old ->
            plugin.logger.warning(
                "Discarded unresolved recording of ${old.stopped.playerName} " +
                    "when a new one stopped",
            )
        }

        val text = Component.text()
            .append(
                mini(
                    "<gray>Recording stopped <dark_gray>·</dark_gray> <yellow><player></yellow> " +
                        "<dark_gray>·</dark_gray> <aqua><packets></aqua> packets</gray>",
                    Placeholder.unparsed("player", stopped.playerName),
                    Placeholder.unparsed("packets", count(stopped.session.packets.size)),
                ),
            )
            .append(Component.newline())
            .append(mini("<gray>Choose an action:</gray>"))
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
                    "<gray>  /dill rec train <dark_gray>·</dark_gray> /dill rec predict " +
                        "<dark_gray>·</dark_gray> /dill rec discard</gray>",
                ),
            )
        }

        sender.sendMessage(text.build())
    }

    /** Opens the multi-select label menu. */
    fun train(sender: CommandSender) {
        val entry = pending[senderKey(sender)] ?: return noPending(sender)
        sendLabelMenu(sender, entry)
    }

    /** Toggles one label, keeping `legit` exclusive of cheats. */
    fun toggleLabel(sender: CommandSender, rawLabel: String) {
        val entry = pending[senderKey(sender)] ?: return noPending(sender)
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
        val ownerKey = senderKey(sender)
        val entry = pending.remove(ownerKey) ?: return noPending(sender)
        val labels = entry.labels.toList()

        if (labels.isEmpty()) {
            pending[ownerKey] = entry
            sender.sendMini("<red>Select at least one label first.</red>")
            return
        }

        sender.sendMini("<gray>Saving recording…</gray>")

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
                            "<gray>Saved <yellow><player></yellow></gray>",
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
        val ownerKey = senderKey(sender)
        val entry = pending.remove(ownerKey) ?: return noPending(sender)

        if (plugin.models.summary().isEmpty()) {
            pending[ownerKey] = entry
            sender.sendMini("<red>No models are loaded. Run <white>/dill models reload</white>.</red>")
            return
        }

        sender.sendMini("<gray>Running inference…</gray>")

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
                    sender.sendMessage(
                        PredictionView.render(
                            kind = "Prediction",
                            playerName = entry.stopped.playerName,
                            report = report,
                            threshold = plugin.config.getDouble("prediction.threshold", 0.5),
                        ),
                    )
                }
            }
        })
    }

    /** Drops the pending recording. */
    fun discard(sender: CommandSender) {
        val entry = pending.remove(senderKey(sender)) ?: return noPending(sender)
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
                    "<gray>Labels <dark_gray>·</dark_gray> <yellow><player></yellow> " +
                        "<dark_gray>·</dark_gray> click a label to toggle it</gray>",
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
                    "/dill rec label $label",
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
            "<red>No stopped recording is waiting.</red>" +
                "\n<gray>Start one with <white>/dill rec start <player></white>, " +
                "stop it with <white>/dill rec stop <player></white>.</gray>",
        )
    }

    private fun sync(sender: CommandSender, action: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable { action() })
    }
}
