package com.dillway.agent

import com.dillway.agent.inference.InferenceWindows
import com.dillway.agent.model.InferenceReport
import com.dillway.agent.recording.RecordedPacket
import com.dillway.agent.recording.Recording
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.roundToLong

/**
 * Live predictions for watched players.
 *
 * Packets arrive through `DillListener`, the same pipeline that feeds
 * recordings. Recording and watching are independent, so a player can do both
 * at once. A repeating task takes a rolling tail of the buffer — long enough
 * for the longest loaded window — and runs the specialists off the main
 * thread. A watch is ephemeral: it writes nothing to disk and ends when the
 * player leaves, when the last viewer stops, or at shutdown.
 *
 * A watch belongs to the watched player, not to the admin. Any number of
 * admins can watch the same player, and one admin can watch several players;
 * everyone watching a player sees the same shared buffer and prediction.
 *
 * The live state goes to the action bar, which overwrites itself, so a watch
 * is silent in chat. Chat output exists only in `chat` mode and for consoles,
 * which have no action bar; there it prints the full report when the set of
 * flagged models changes. The full report is available on demand and when the
 * watch stops.
 *
 * Main thread only, except for the inference call and the hop back with the
 * result.
 */
class WatchService(private val plugin: DwAgentPlugin) {

    /** Where live state goes between verdict changes. */
    private enum class Display { ACTION_BAR, CHAT }

    /** One admin's view of one watch. */
    private class Subscriber(
        val controller: CommandSender,
        val controllerKey: String,
        val display: Display,
    ) {
        /** Cheats over the threshold at the last inference, for chat mode. */
        var lastFlagged: Set<String> = emptySet()
    }

    /**
     * One watched player, shared by every subscriber. The buffer and the
     * inference task belong to the player, so two admins watching the same
     * player cost one prediction.
     */
    private class Watch(
        val playerId: UUID,
        val playerName: String,
        val started: Long,
        val intervalMs: Long,
    ) {
        val packets = ArrayDeque<RecordedPacket>()
        val subscribers = LinkedHashMap<String, Subscriber>()
        var task: BukkitTask? = null

        /** True while an inference for this watch is in flight. */
        var busy = false

        /** Set after the idle notice, so it is not repeated every tick. */
        var toldIdle = false

        /** Newest full report, for new subscribers, the report button and the stop summary. */
        var lastReport: InferenceReport? = null
    }

    private val byPlayer = HashMap<UUID, Watch>()

    fun isWatching(playerId: UUID): Boolean = playerId in byPlayer

    /** Number of players with a live watch. */
    fun activeCount(): Int = byPlayer.size

    /** Mirrors one captured packet into the watch buffer, when one exists. */
    fun capture(playerId: UUID, packet: RecordedPacket) {
        byPlayer[playerId]?.packets?.addLast(packet)
    }

    /** Starts a watch for this sender, or tells them why it cannot start. */
    fun start(sender: CommandSender, player: Player) {
        val key = senderKey(sender)
        val existing = byPlayer[player.uniqueId]

        if (existing != null && key in existing.subscribers) {
            // Idempotent: asking again just shows the watch that is already there.
            sendWelcome(sender, player)
            return
        }

        if (existing == null) {
            if (plugin.models.summary().isEmpty()) {
                sender.sendMini(
                    "<red>No models are loaded. Run <white>/dill models reload</white>.</red>",
                )
                return
            }
        }

        val watch = existing ?: newWatch(player)
        val subscriber = Subscriber(
            controller = sender,
            controllerKey = key,
            display = display(sender),
        )
        watch.subscribers[key] = subscriber

        // A late viewer starts with the newest state instead of an empty bar.
        if (subscriber.display == Display.ACTION_BAR) {
            watch.lastReport?.let { report -> showBar(subscriber, watch, report) }
        }

        sendWelcome(sender, player)
    }

    /**
     * Stops one watch of this sender, or every watch they control when
     * [target] is null. The watch itself continues while another admin still
     * watches the same player. The last report of each stopped watch is
     * printed, so the stop leaves a record behind.
     */
    fun stop(sender: CommandSender, target: Player?) {
        val key = senderKey(sender)

        if (target != null) {
            val watch = byPlayer[target.uniqueId]
            val subscriber = watch?.subscribers?.get(key)
            if (watch == null || subscriber == null) {
                sender.sendMini(
                    "<red>You are not watching <player>.</red>",
                    Placeholder.unparsed("player", target.name),
                )
                return
            }

            val report = watch.lastReport
            unsubscribe(watch, subscriber)
            sender.sendMini(
                "<gray>Stopped watching <yellow><player></yellow>.</gray>",
                Placeholder.unparsed("player", target.name),
            )
            report?.let {
                sender.sendMessage(
                    PredictionView.render("Watch", watch.playerName, it, plugin.threshold()),
                )
            }
            return
        }

        val mine = byPlayer.values.mapNotNull { watch ->
            watch.subscribers[key]?.let { watch to it }
        }
        if (mine.isEmpty()) {
            sender.sendMini("<red>You are not watching any player.</red>")
            return
        }

        val names = mine.map { it.first.playerName }
        val reports = mine.mapNotNull { (watch, _) ->
            watch.lastReport?.let { watch.playerName to it }
        }
        mine.forEach { (watch, subscriber) -> unsubscribe(watch, subscriber) }

        sender.sendMini(
            "<gray>Stopped watching <yellow><players></yellow>.</gray>",
            Placeholder.unparsed("players", names.joinToString(", ")),
        )
        reports.forEach { (name, report) ->
            sender.sendMessage(
                PredictionView.render("Watch", name, report, plugin.threshold()),
            )
        }
    }

    /** Prints the newest full report of one watch, from the `[Details]` button. */
    fun details(sender: CommandSender, target: Player) {
        val watch = byPlayer[target.uniqueId]
        if (watch == null || senderKey(sender) !in watch.subscribers) {
            sender.sendMini(
                "<red>You are not watching <player>.</red>",
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
            PredictionView.render("Watch", watch.playerName, report, plugin.threshold()),
        )
    }

    /**
     * Ends watches on a player who left, and drops the subscriptions of an
     * admin who left. The other viewers of a shared watch stay.
     */
    fun playerQuit(player: Player) {
        byPlayer[player.uniqueId]?.let { watch ->
            val subscribers = watch.subscribers.values.toList()
            remove(watch)
            val text = mini(
                "<gray><player> left. Watch stopped.</gray>",
                Placeholder.unparsed("player", watch.playerName),
            )
            subscribers.forEach { notify(it, text) }
        }

        val key = senderKey(player)
        byPlayer.values.mapNotNull { watch ->
            watch.subscribers[key]?.let { watch to it }
        }.forEach { (watch, subscriber) -> unsubscribe(watch, subscriber) }
    }

    /** Cancels every tick on shutdown; buffers are discarded. */
    fun stopAll() {
        byPlayer.values.toList().forEach { remove(it) }
        byPlayer.clear()
    }

    private fun newWatch(player: Player): Watch {
        val interval = plugin.config.getDouble("watch.interval-seconds", 5.0)
            .coerceIn(1.0, 60.0)
        val watch = Watch(
            playerId = player.uniqueId,
            playerName = player.name,
            started = System.currentTimeMillis(),
            intervalMs = (interval * 1000.0).roundToLong(),
        )
        byPlayer[player.uniqueId] = watch
        plugin.metrics.watchStarted()

        val ticks = (watch.intervalMs / 50L).coerceAtLeast(1L)
        watch.task = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { tick(watch) },
            ticks,
            ticks,
        )
        return watch
    }

    private fun sendWelcome(sender: CommandSender, player: Player) {
        val text = Component.text().append(
            mini(
                "<green>Watching <yellow><player></yellow>.</green>",
                Placeholder.unparsed("player", player.name),
            ),
        )
        if (sender is Player) {
            text.append(separator()).append(
                chatButton("[Details]", "/dill watch details ${player.name}", NamedTextColor.AQUA),
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

    private fun tick(watch: Watch) {
        prune(watch)
        if (watch.busy) return

        val packets = watch.packets.toList()
        if (InferenceWindows.movementCount(packets) < InferenceWindows.MIN_MOVEMENT_PACKETS) {
            if (!watch.toldIdle) {
                watch.toldIdle = true
                // Stop repeating the last line: the numbers no longer describe
                // the current state, and the idle notice says why.
                val idle = mini(
                    "<gray>Waiting for movement from <yellow><player></yellow>…</gray>",
                    Placeholder.unparsed("player", watch.playerName),
                )
                for (subscriber in watch.subscribers.values) {
                    if (subscriber.display == Display.ACTION_BAR) {
                        plugin.actionBar.clear(subscriber.controller, barOwner(watch))
                    }
                    notify(subscriber, idle)
                }
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
                plugin.models.infer(recording, plugin.threshold())
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
                // Every viewer may have stopped while inference ran.
                if (byPlayer[watch.playerId] !== watch || report == null) return@Runnable

                watch.toldIdle = false
                watch.lastReport = report
                val threshold = plugin.threshold()
                val flagged = report.flagged(threshold)

                for (subscriber in watch.subscribers.values) {
                    // The bar is a live view, not an event log: it changes
                    // every interval and never touches chat.
                    if (subscriber.display == Display.ACTION_BAR) {
                        showBar(subscriber, watch, report)
                        continue
                    }

                    // A chat watch has no bar, so the full report is its only
                    // output. It still speaks only when the verdict moves.
                    val change = VerdictChange.between(subscriber.lastFlagged, flagged)
                    subscriber.lastFlagged = flagged
                    if (change.isQuiet) continue
                    notify(
                        subscriber,
                        PredictionView.render("Watch", watch.playerName, report, threshold),
                    )
                }
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

    /** Drops one viewer; the watch itself ends with its last viewer. */
    private fun unsubscribe(watch: Watch, subscriber: Subscriber) {
        watch.subscribers.remove(subscriber.controllerKey)
        clearBar(watch, subscriber)
        if (watch.subscribers.isEmpty()) remove(watch)
    }

    private fun remove(watch: Watch) {
        watch.task?.cancel()
        if (byPlayer.remove(watch.playerId) == null) return

        val subscribers = watch.subscribers.values.toList()
        watch.subscribers.clear()
        subscribers.forEach { clearBar(watch, it) }
    }

    /** Writes this watch's line into the shared action bar. */
    private fun showBar(subscriber: Subscriber, watch: Watch, report: InferenceReport) {
        plugin.actionBar.set(
            controller = subscriber.controller,
            owner = barOwner(watch),
            line = PredictionView.liveLine(watch.playerName, report, plugin.threshold()),
            order = watch.playerName,
        )
    }

    private fun clearBar(watch: Watch, subscriber: Subscriber) {
        if (subscriber.display != Display.ACTION_BAR) return
        plugin.actionBar.clear(subscriber.controller, barOwner(watch))
    }

    private fun barOwner(watch: Watch): String = "watch:${watch.playerId}"

    private fun display(sender: CommandSender): Display {
        // A console has no hotbar to write above; chat is its only channel.
        if (sender !is Player) return Display.CHAT
        val configured = plugin.config.getString("watch.display", "actionbar")
        return if (configured.equals("chat", ignoreCase = true)) Display.CHAT else Display.ACTION_BAR
    }

    private fun notify(subscriber: Subscriber, component: Component) {
        val sender = subscriber.controller
        if (sender is Player && !sender.isOnline) return
        sender.sendMessage(component)
    }
}
