package com.dillway

import com.dillway.inference.InferenceWindows
import com.dillway.model.InferenceReport
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
 * The live state goes to the action bar, which overwrites itself, so a steady
 * verdict sends no chat messages. Chat carries one line only when the set of
 * flagged models changes. Consoles have no action bar and get the full report
 * on a change instead.
 *
 * Main thread only, except for the inference call and the hop back with the
 * result.
 */
class WatchService(private val plugin: DillPlugin) {

    /** Where live state goes between verdict changes. */
    private enum class Display { ACTION_BAR, CHAT }

    private class Watch(
        val playerId: UUID,
        val playerName: String,
        val controller: CommandSender,
        val controllerKey: String,
        val started: Long,
        val intervalMs: Long,
        val display: Display,
    ) {
        val packets = ArrayDeque<RecordedPacket>()
        var task: BukkitTask? = null

        /** True while an inference for this watch is in flight. */
        var busy = false

        /** Set after the idle notice, so it is not repeated every tick. */
        var toldIdle = false

        /** Last action bar line, re-sent by the controller's bar timer. */
        var barLine: Component? = null

        /** Newest full report, for the report button and the stop summary. */
        var lastReport: InferenceReport? = null

        /** Cheats over the threshold at the last inference. */
        var lastFlagged: Set<String> = emptySet()
    }

    private val byPlayer = HashMap<UUID, Watch>()

    /**
     * One action bar timer per controller. The client fades the action bar
     * after a couple of seconds, so it must be repeated between predictions.
     * Watches that share a controller share the timer and the line.
     */
    private val barTasks = HashMap<String, BukkitTask>()

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
            display = display(sender),
        )
        byPlayer[player.uniqueId] = watch

        val ticks = (watch.intervalMs / 50L).coerceAtLeast(1L)
        watch.task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick(watch) },
            ticks,
            ticks,
        )
        if (watch.display == Display.ACTION_BAR) ensureBar(watch)

        val text = Component.text().append(
            mini(
                "<gray>Watching <yellow><player></yellow>.</gray>",
                Placeholder.unparsed("player", player.name),
            ),
        )
        if (watch.display == Display.ACTION_BAR) {
            text.append(separator()).append(
                mini("<dark_gray>live above the hotbar</dark_gray>"),
            )
        }
        if (sender is Player) {
            text.append(separator()).append(
                chatButton("[Report]", "/dill watch report ${player.name}", NamedTextColor.AQUA),
            )
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
     * null. Watches belong to the admin who started them. The last report of
     * each stopped watch is printed, so the stop leaves a record behind.
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
        val predictions = targets.mapNotNull { watch ->
            watch.lastReport?.let { watch.playerName to it }
        }
        targets.forEach { remove(it) }

        sender.sendMini(
            "<gray>Stopped watching <yellow><players></yellow>.</gray>",
            Placeholder.unparsed("players", names.joinToString(", ")),
        )
        predictions.forEach { (name, report) ->
            sender.sendMessage(
                PredictionView.render("Watch", name, report, threshold()),
            )
        }
    }

    /** Prints the newest full report of one watch, from the `[Report]` button. */
    fun report(sender: CommandSender, target: Player) {
        val watch = byPlayer[target.uniqueId]
        if (watch == null || watch.controllerKey != senderKey(sender)) {
            sender.sendMini(
                "<red><player> is not being watched by you.</red>",
                Placeholder.unparsed("player", target.name),
            )
            return
        }

        val report = watch.lastReport
        if (report == null) {
            sender.sendMini(
                "<gray>No prediction for <yellow><player></yellow> yet.</gray>",
                Placeholder.unparsed("player", target.name),
            )
            return
        }

        sender.sendMessage(
            PredictionView.render("Watch", watch.playerName, report, threshold()),
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
        byPlayer.values.toList().forEach { remove(it) }
        barTasks.values.forEach { it.cancel() }
        barTasks.clear()
        byPlayer.clear()
    }

    private fun tick(watch: Watch) {
        prune(watch)
        if (watch.busy) return

        val packets = watch.packets.toList()
        if (InferenceWindows.movementCount(packets) < InferenceWindows.MIN_MOVEMENT_PACKETS) {
            if (!watch.toldIdle) {
                watch.toldIdle = true
                // Stop repeating the last line: the numbers no longer describe
                // the current state, and the idle notice says why.
                watch.barLine = null
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
                watch.lastReport = report
                val threshold = threshold()
                val flagged = report.flagged(threshold)
                val change = VerdictChange.between(watch.lastFlagged, flagged)
                watch.lastFlagged = flagged

                // The live state moves every interval, even while clear, so
                // the admin sees the numbers rather than a silent screen.
                if (watch.display == Display.ACTION_BAR) {
                    watch.barLine = PredictionView.liveLine(watch.playerName, report, threshold)
                    refreshBar(watch.controllerKey)
                }

                // Chat speaks only when the verdict itself moves.
                if (change.isQuiet) return@Runnable
                notify(watch, announcement(watch, report, threshold, change))
            })
        })
    }

    /** One chat entry for a verdict change, in the detail the display allows. */
    private fun announcement(
        watch: Watch,
        report: InferenceReport,
        threshold: Double,
        change: VerdictChange,
    ): Component {
        if (watch.display == Display.CHAT) {
            return PredictionView.render("Watch", watch.playerName, report, threshold)
        }

        val text = Component.text().append(
            PredictionView.changeLine(
                subject = watch.playerName,
                report = report,
                entered = change.entered,
                cleared = change.cleared,
            ),
        )
        if (watch.controller is Player) {
            text.append(separator()).append(
                chatButton(
                    "[Report]",
                    "/dill watch report ${watch.playerName}",
                    NamedTextColor.AQUA,
                ),
            )
        }
        return text.build()
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
        releaseBar(watch)
    }

    private fun display(sender: CommandSender): Display {
        // A console has no hotbar to write above; chat is its only channel.
        if (sender !is Player) return Display.CHAT
        val configured = plugin.config.getString("watch.display", "actionbar")
        return if (configured.equals("chat", ignoreCase = true)) Display.CHAT else Display.ACTION_BAR
    }

    private fun threshold(): Double = plugin.config.getDouble("prediction.threshold", 0.5)

    /** Starts the controller's bar timer, if it does not run already. */
    private fun ensureBar(watch: Watch) {
        if (watch.controllerKey in barTasks) return
        barTasks[watch.controllerKey] = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { refreshBar(watch.controllerKey) },
            BAR_REFRESH_TICKS,
            BAR_REFRESH_TICKS,
        )
    }

    /**
     * Re-sends the action bar of one controller, sorted by player name so the
     * order does not jump between predictions.
     */
    private fun refreshBar(controllerKey: String) {
        val watches = byPlayer.values
            .filter {
                it.display == Display.ACTION_BAR &&
                    it.controllerKey == controllerKey &&
                    it.barLine != null
            }
            .sortedBy { it.playerName }
        if (watches.isEmpty()) return

        val player = watches.first().controller as? Player ?: return
        if (!player.isOnline) return

        val text = Component.text()
        var started = false
        for (watch in watches) {
            val line = watch.barLine ?: continue
            if (started) text.append(separator())
            text.append(line)
            started = true
        }
        if (started) player.sendActionBar(text.build())
    }

    /** Drops a stopped watch from its controller's bar, or clears the bar. */
    private fun releaseBar(watch: Watch) {
        if (watch.display != Display.ACTION_BAR) return

        val shared = byPlayer.values.any {
            it.display == Display.ACTION_BAR && it.controllerKey == watch.controllerKey
        }
        if (shared) {
            refreshBar(watch.controllerKey)
            return
        }

        barTasks.remove(watch.controllerKey)?.cancel()
        val player = watch.controller as? Player ?: return
        if (player.isOnline) player.sendActionBar(Component.text(""))
    }

    private fun notify(watch: Watch, component: Component) {
        val sender = watch.controller
        if (sender is Player && !sender.isOnline) return
        sender.sendMessage(component)
    }

    private companion object {
        /** The client fades an action bar after about two seconds. */
        const val BAR_REFRESH_TICKS = 30L
    }
}
