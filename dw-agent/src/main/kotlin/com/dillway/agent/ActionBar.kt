package com.dillway.agent

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * The line above the hotbar, shared by every feature that writes there.
 *
 * The client fades an action bar after about two seconds, so a repeating task
 * re-sends the combined line. Each controller has one bar; features add lines
 * under their own owner id. A watch and a running compaction therefore appear
 * together instead of overwriting each other.
 *
 * Main thread only. An async caller must hop before touching it.
 */
class ActionBar(private val plugin: DwAgentPlugin) {

    private data class Entry(val order: String, val line: Component)

    private class Bar(val player: Player) {
        val entries = LinkedHashMap<String, Entry>()
    }

    private val bars = HashMap<String, Bar>()
    private val tasks = HashMap<String, BukkitTask>()

    /**
     * Adds or replaces [owner]'s line for [controller]. [order] sorts the
     * owners' lines; equal orders keep insertion order.
     */
    fun set(
        controller: CommandSender,
        owner: String,
        line: Component,
        order: String = "",
    ) {
        if (controller !is Player) return
        val key = senderKey(controller)
        val bar = bars.getOrPut(key) { Bar(controller) }
        bar.entries[owner] = Entry(order, line)

        if (tasks[key] == null) {
            tasks[key] = plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { send(key) },
                REFRESH_TICKS,
                REFRESH_TICKS,
            )
        }
        send(key)
    }

    /** Drops [owner]'s line; the bar clears when no line is left. */
    fun clear(controller: CommandSender, owner: String) {
        if (controller !is Player) return
        val key = senderKey(controller)
        val bar = bars[key] ?: return
        bar.entries.remove(owner)

        if (bar.entries.isNotEmpty()) {
            send(key)
            return
        }

        forget(key)
        if (controller.isOnline) controller.sendActionBar(Component.text(""))
    }

    /** Cancels every timer; used on shutdown. */
    fun stopAll() {
        tasks.values.forEach { it.cancel() }
        tasks.clear()
        bars.clear()
    }

    private fun send(key: String) {
        val bar = bars[key] ?: return
        if (!bar.player.isOnline) {
            forget(key)
            return
        }

        val text = Component.text()
        var started = false
        bar.entries.values.sortedBy { it.order }.forEach { entry ->
            if (started) text.append(separator())
            text.append(entry.line)
            started = true
        }
        if (started) bar.player.sendActionBar(text.build())
    }

    private fun forget(key: String) {
        bars.remove(key)
        tasks.remove(key)?.cancel()
    }

    private companion object {
        /** The client fades an action bar after about two seconds. */
        const val REFRESH_TICKS = 30L
    }
}
