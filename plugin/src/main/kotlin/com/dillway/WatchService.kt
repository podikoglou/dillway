package com.dillway

import com.dillway.inference.InferenceWindows
import com.dillway.recording.RecordedPacket
import com.dillway.recording.Recording
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Live predictions for one player.
 *
 * Packets arrive through `DillListener`, the same pipeline that feeds
 * recordings. A player cannot be recorded and watched at the same time, so
 * the two modes never share a player. A repeating task takes a rolling tail
 * of the buffer — long enough for the longest loaded window — and runs the
 * specialists off the main thread. A watch is ephemeral: it writes nothing
 * to disk and is dropped on quit, `/dill watch stop`, or shutdown.
 *
 * Main thread only, except for the inference call and the hop back with the
 * result.
 */
class WatchService(private val plugin: DillPlugin) {

    private class Watch(
        val playerId: UUID,
        val playerName: String,
        val controller: CommandSender,
        val controllerKey: String,
        val started: Long,
        val intervalMs: Long,
    ) {
        val packets = ArrayDeque<RecordedPacket>()
        var task: BukkitTask? = null

        /** True while an inference for this watch is in flight. */
        var busy = false

        /** Set after the idle notice, so it is not repeated every tick. */
        var toldIdle = false
    }

    private val byPlayer = HashMap<UUID, Watch>()

    fun isWatching(playerId: UUID): Boolean = playerId in byPlayer

    /** Mirrors one captured packet into the watch buffer, when one exists. */
    fun capture(playerId: UUID, packet: RecordedPacket) {
        byPlayer[playerId]?.packets?.addLast(packet)
    }

    /** Starts a watch, or tells the sender why it cannot start. */
    fun start(sender: CommandSender, player: Player) {
        if (byPlayer.containsKey(player.uniqueId)) {
            sender.sendMini(
                "<red><player> is already being watched.</red>",
                Placeholder.unparsed("player", player.name),
            )
            return
        }
        if (plugin.isRecording(player)) {
            sender.sendMini(
                "<red><player> is being recorded. Stop the recording first.</red>",
                Placeholder.unparsed("player", player.name),
            )
            return
        }
        if (plugin.models.summary().isEmpty()) {
            sender.sendMini(
                "<red>No models are loaded. Run <white>/dill models reload</white>.</red>",
            )
            return
        }

        val interval = plugin.config.getDouble("watch.interval-seconds", 5.0)
            .coerceIn(1.0, 60.0)
        val watch = Watch(
            playerId = player.uniqueId,
            playerName = player.name,
            controller = sender,
            controllerKey = senderKey(sender),
            started = System.currentTimeMillis(),
            intervalMs = (interval * 1000.0).roundToLong(),
        )
        byPlayer[player.uniqueId] = watch

        val ticks = (watch.intervalMs / 50L).coerceAtLeast(1L)
        watch.task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick(watch) },
            ticks,
            ticks,
        )

        val text = Component.text().append(
            mini(
                "<gray>Watching <yellow><player></yellow>.</gray>",
                Placeholder.unparsed("player", player.name),
            ),
        )
        if (sender is Player) {
            text.append(separator()).append(
                chatButton("[Stop]", "/dill watch stop ${player.name}", NamedTextColor.RED),
            )
        } else {
            text.append(Component.newline()).append(
                mini(
                    "<gray>Stop with <white>/dill watch stop <player></white>.</gray>",
                    Placeholder.unparsed("player", player.name),
                ),
            )
        }
        sender.sendMessage(text.build())
    }

    /**
     * Stops one watch, or every watch this sender controls when [target] is
     * null. Watches belong to the admin who started them.
     */
    fun stop(sender: CommandSender, target: Player?) {
        val ownerKey = senderKey(sender)
        val targets = if (target != null) {
            listOfNotNull(byPlayer[target.uniqueId])
        } else {
            byPlayer.values.filter { it.controllerKey == ownerKey }
        }

        if (targets.isEmpty()) {
            if (target == null) {
                sender.sendMini("<red>You are not watching any player.</red>")
            } else {
                sender.sendMini(
                    "<red><player> is not being watched.</red>",
                    Placeholder.unparsed("player", target.name),
                )
            }
            return
        }

        if (target != null && targets.any { it.controllerKey != ownerKey }) {
            sender.sendMini(
                "<red><player> is watched by another admin.</red>",
                Placeholder.unparsed("player", target.name),
            )
            return
        }

        val names = targets.map { it.playerName }
        targets.forEach { remove(it) }
        sender.sendMini(
            "<gray>Stopped watching <yellow><players></yellow>.</gray>",
            Placeholder.unparsed("players", names.joinToString(", ")),
        )
    }

    /**
     * Ends watches on a player who left, and watches controlled by an admin
     * who left: their reports would have nowhere to go.
     */
    fun playerQuit(player: Player) {
        val affected = byPlayer.values.filter { watch ->
            watch.playerId == player.uniqueId ||
                (watch.controller as? Player)?.uniqueId == player.uniqueId
        }

        for (watch in affected) {
            remove(watch)
            if (watch.playerId == player.uniqueId) {
                notify(
                    watch,
                    mini(
                        "<gray><player> left. Watch stopped.</gray>",
                        Placeholder.unparsed("player", watch.playerName),
                    ),
                )
            }
        }
    }

    /** Cancels every tick on shutdown; buffers are discarded. */
    fun stopAll() {
        byPlayer.values.forEach { it.task?.cancel() }
        byPlayer.clear()
    }

    private fun tick(watch: Watch) {
        prune(watch)
        if (watch.busy) return

        val packets = watch.packets.toList()
        if (InferenceWindows.movementCount(packets) < InferenceWindows.MIN_MOVEMENT_PACKETS) {
            if (!watch.toldIdle) {
                watch.toldIdle = true
                notify(
                    watch,
                    mini(
                        "<gray>Waiting for movement from <yellow><player></yellow>…</gray>",
                        Placeholder.unparsed("player", watch.playerName),
                    ),
                )
            }
            return
        }

        watch.busy = true
        val recording = Recording(
            id = watch.started,
            started = watch.started,
            player = watch.playerId.toString(),
            label = Labels.LEGIT, // live predictions never touch the dataset
            packets = packets,
        )

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val report = try {
                plugin.models.infer(recording)
            } catch (e: Exception) {
                plugin.logger.severe(
                    "Watch inference failed for ${watch.playerName}: " +
                        (e.message ?: e.javaClass.simpleName),
                )
                null
            }

            if (!plugin.isEnabled) return@Runnable
            plugin.server.scheduler.runTask(plugin, Runnable {
                watch.busy = false
                // The admin may have stopped the watch while inference ran.
                if (byPlayer[watch.playerId] !== watch || report == null) return@Runnable

                watch.toldIdle = false
                val threshold = plugin.config.getDouble("prediction.threshold", 0.5)
                // Clear play stays quiet; the admin asked to hear about flags.
                if (!PredictionView.flags(report, threshold)) return@Runnable

                notify(
                    watch,
                    PredictionView.render(
                        kind = "Watch",
                        subject = watch.playerName,
                        report = report,
                        threshold = threshold,
                    ),
                )
            })
        })
    }

    /**
     * Keeps the buffer at the longest loaded window plus one interval, so
     * every specialist can still fill a window while old packets go away.
     */
    private fun prune(watch: Watch) {
        val longestWindow = plugin.models.summary().maxOfOrNull { it.windowSeconds } ?: 0.0
        val horizon = (longestWindow * 1000.0).roundToLong() + watch.intervalMs + 1000L
        val cutoff = System.currentTimeMillis() - horizon
        while (watch.packets.isNotEmpty() && watch.packets.first().timestamp < cutoff) {
            watch.packets.removeFirst()
        }
    }

    private fun remove(watch: Watch) {
        watch.task?.cancel()
        byPlayer.remove(watch.playerId)
    }

    private fun notify(watch: Watch, component: Component) {
        val sender = watch.controller
        if (sender is Player && !sender.isOnline) return
        sender.sendMessage(component)
    }
}
