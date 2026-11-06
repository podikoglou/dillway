package com.dillway

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
 * Capture always lands in a staging file with a stable ID. Every action takes
 * that ID, so no recording is "current": a prediction, a later stop, or an old
 * chat button cannot make an earlier recording disappear. Label selections
 * are keyed by ID and live only in memory.
 *
 * Disk work and inference run on async workers; chat runs back on the main
 * thread.
 */
class RecordingFlow(private val plugin: DillPlugin) {

    private val selections = ConcurrentHashMap<Long, MutableSet<String>>()

    /** IDs with a save in flight, so a double click cannot race itself. */
    private val saving = ConcurrentHashMap.newKeySet<Long>()

    /** Writes the stopped session, then offers the actions for its ID. */
    fun stopped(sender: CommandSender, stopped: StoppedRecording) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val id = plugin.recordings.saveStaging(stopped)
            sync(sender) {
                if (id == null) {
                    sender.sendMini("<red>Could not save the recording. See the server log.</red>")
                    return@sync
                }
                offer(sender, id, stopped.playerName)
            }
        })
    }

    /** Runs inference over the recording, off the main thread. */
    fun predict(sender: CommandSender, id: Long) {
        if (!plugin.recordings.exists(id)) return unknown(sender, id)
        if (plugin.models.summary().isEmpty()) return noModels(sender)

        sender.sendMini(
            "<gray>Predicting <aqua><id></aqua>…</gray>",
            Placeholder.unparsed("id", id.toString()),
        )

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val recording = plugin.recordings.load(id)
            val report = recording?.let {
                try {
                    plugin.models.infer(it)
                } catch (e: Exception) {
                    plugin.logger.severe("Inference failed for $id: ${e.message}")
                    null
                }
            }

            sync(sender) {
                if (report == null) {
                    sender.sendMini(
                        "<red>Could not predict <aqua><id></aqua>.</red>",
                        Placeholder.unparsed("id", id.toString()),
                    )
                } else {
                    sender.sendMessage(
                        PredictionView.render(
                            kind = "Prediction",
                            subject = id.toString(),
                            report = report,
                            threshold = threshold(),
                        ),
                    )
                }
            }
        })
    }

    /** Opens the label menu, or toggles one label first when [rawLabel] is set. */
    fun labels(sender: CommandSender, id: Long, rawLabel: String?) {
        if (!plugin.recordings.exists(id)) return unknown(sender, id)

        val selected = selections.computeIfAbsent(id) { linkedSetOf(Labels.LEGIT) }
        if (rawLabel != null) {
            val label = Labels.normalize(rawLabel)
            if (label !in plugin.availableLabels()) {
                sender.sendMini(
                    "<red>Unknown label <white><label></white>.</red>",
                    Placeholder.unparsed("label", rawLabel),
                )
                return
            }

            if (label == Labels.LEGIT) {
                selected.clear()
                selected += Labels.LEGIT
            } else {
                selected.remove(Labels.LEGIT)
                if (!selected.remove(label)) selected += label
                if (selected.isEmpty()) selected += Labels.LEGIT
            }
        }

        sendLabelMenu(sender, id, selected)
    }

    /**
     * Writes one copy per selected label. A copy of the same ID under any
     * other label, locally or in the bucket, needs an explicit confirm; the
     * confirmed save replaces the old label set instead of adding to it.
     */
    fun save(sender: CommandSender, id: Long, confirmed: Boolean) {
        if (!plugin.recordings.exists(id)) return unknown(sender, id)

        val selected = selections[id]?.toList() ?: listOf(Labels.LEGIT)
        if (!saving.add(id)) {
            sender.sendMini(
                "<red>Already saving <aqua><id></aqua>.</red>",
                Placeholder.unparsed("id", id.toString()),
            )
            return
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                val existing = duplicates(id)
                if (existing.isNotEmpty() && !confirmed) {
                    sync(sender) { overwritePrompt(sender, id, existing) }
                    return@Runnable
                }

                // Load before deleting any copy: a labeled file may be the
                // only local source for a recording from an earlier version.
                val recording = plugin.recordings.load(id)
                if (recording == null) {
                    sync(sender) { unknown(sender, id) }
                    return@Runnable
                }

                sync(sender) {
                    sender.sendMini(
                        "<gray>Saving <aqua><id></aqua>…</gray>",
                        Placeholder.unparsed("id", id.toString()),
                    )
                }

                for (label in existing.filterNot { it in selected }) {
                    plugin.recordings.removeLabel(id, label)
                    plugin.storage.delete(plugin.storage.recordingKey(label, "$id.parquet"))
                }

                val uploaded = mutableListOf<String>()
                val kept = mutableListOf<String>()
                val failed = mutableListOf<String>()

                for (label in selected) {
                    val file = plugin.recordings.writeLabel(recording, label)
                    if (file == null) {
                        failed += label
                        continue
                    }
                    if (!plugin.storage.enabled) {
                        kept += label
                        continue
                    }
                    plugin.storage.upload(file, label, file.name)
                    // A surviving file means the upload failed and the local copy stayed.
                    if (file.exists()) kept += label else uploaded += label
                }

                sync(sender) { saveReport(sender, id, selected, uploaded, kept, failed) }
            } finally {
                saving.remove(id)
            }
        })
    }

    /** Removes local copies. Uploaded objects are never touched. */
    fun discard(sender: CommandSender, id: Long) {
        if (!plugin.recordings.exists(id)) return unknown(sender, id)
        plugin.recordings.remove(id)
        selections.remove(id)
        sender.sendMini(
            "<gray>Removed <aqua><id></aqua>.</gray>",
            Placeholder.unparsed("id", id.toString()),
        )
    }

    private fun offer(sender: CommandSender, id: Long, playerName: String) {
        val text = Component.text().append(
            mini(
                "<gray>Saved <yellow><player></yellow> <dark_gray>·</dark_gray> <aqua><id></aqua></gray>",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("id", id.toString()),
            ),
        )

        if (sender is Player) {
            text.append(Component.newline())
                .append(chatButton("[Predict]", "/dill rec predict $id", NamedTextColor.AQUA))
                .append(separator())
                .append(chatButton("[Labels]", "/dill rec label $id", NamedTextColor.GREEN))
                .append(separator())
                .append(chatButton("[Remove]", "/dill rec discard $id", NamedTextColor.RED))
        } else {
            text.append(Component.newline()).append(
                mini(
                    "<gray>  /dill rec predict <id> <dark_gray>·</dark_gray> " +
                        "/dill rec label <id> <dark_gray>·</dark_gray> " +
                        "/dill rec discard <id></gray>",
                    Placeholder.unparsed("id", id.toString()),
                ),
            )
        }

        sender.sendMessage(text.build())
    }

    private fun sendLabelMenu(sender: CommandSender, id: Long, selected: Set<String>) {
        val text = Component.text()
            .append(
                mini(
                    "<gray>Labels <dark_gray>·</dark_gray> <aqua><id></aqua></gray>",
                    Placeholder.unparsed("id", id.toString()),
                ),
            )

        plugin.availableLabels().forEachIndexed { index, label ->
            if (index == 0) text.append(Component.newline()) else text.append(Component.text(" "))
            val isSelected = label in selected
            text.append(
                chatButton(
                    "${if (isSelected) "[x]" else "[ ]"} $label",
                    "/dill rec label $id $label",
                    if (isSelected) NamedTextColor.GREEN else NamedTextColor.GRAY,
                ),
            )
        }

        text.append(Component.newline())
            .append(chatButton("[Save]", "/dill save $id"))
            .append(separator())
            .append(chatButton("[Remove]", "/dill rec discard $id", NamedTextColor.RED))

        sender.sendMessage(text.build())
    }

    private fun overwritePrompt(sender: CommandSender, id: Long, labels: List<String>) {
        val text = Component.text()
            .append(
                mini(
                    "<gray><id> already exists as <yellow><labels></yellow>.</gray>",
                    Placeholder.unparsed("id", id.toString()),
                    Placeholder.unparsed("labels", labels.joinToString(", ")),
                ),
            )
            .append(Component.newline())
            .append(chatButton("[Overwrite]", "/dill save $id confirm", NamedTextColor.RED))

        sender.sendMessage(text.build())
    }

    private fun saveReport(
        sender: CommandSender,
        id: Long,
        labels: List<String>,
        uploaded: List<String>,
        kept: List<String>,
        failed: List<String>,
    ) {
        val text = Component.text()
            .append(
                mini(
                    "<gray>Saved <aqua><id></aqua> as <yellow><labels></yellow>.</gray>",
                    Placeholder.unparsed("id", id.toString()),
                    Placeholder.unparsed("labels", labels.joinToString(", ")),
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

    /** Labels that already hold this ID, locally or in the bucket. */
    private fun duplicates(id: Long): List<String> {
        val local = plugin.recordings.localLabels(id)
        val remote = plugin.storage.labelsContaining("$id.parquet")
        return (local + remote).distinct().sorted()
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

    private fun unknown(sender: CommandSender, id: Long) {
        sender.sendMini(
            "<red>Unknown recording <aqua><id></aqua>.</red>",
            Placeholder.unparsed("id", id.toString()),
        )
    }

    private fun noModels(sender: CommandSender) {
        sender.sendMini(
            "<red>No models are loaded. Run <white>/dill models reload</white>.</red>",
        )
    }

    private fun threshold(): Double = plugin.config.getDouble("prediction.threshold", 0.5)

    private fun sync(sender: CommandSender, action: () -> Unit) {
        if (!plugin.isEnabled) return
        plugin.server.scheduler.runTask(plugin, Runnable { action() })
    }
}
