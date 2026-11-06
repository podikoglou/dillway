package com.dillway

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The two appearances of the kit item. The item container stores the id. */
private enum class KitState(val id: String) {
    IDLE("idle"),
    RECORDING("recording"),
}

/**
 * The self-recording toggle handed out by `/dill kit`.
 *
 * A right click starts recording the holder; the next right click stops it and
 * opens the usual save menu. The item stays in its slot and only changes its
 * look, so the player never has to find the stop button.
 */
class RecordingKit(
    private val plugin: DillPlugin,
    private val listener: DillListener,
    private val flow: RecordingFlow,
) : Listener {

    private val key = NamespacedKey(plugin, "recording-kit")

    /** Puts the kit in the first free slot, already showing the current state. */
    fun give(player: Player) {
        if (hasKit(player)) {
            player.sendMini("<gray>You already have a recording kit.</gray>")
            return
        }

        val state = if (listener.isRecording(player)) KitState.RECORDING else KitState.IDLE
        if (player.inventory.addItem(item(state)).isNotEmpty()) {
            player.sendMini("<red>Your inventory is full.</red>")
            return
        }

        player.sendMini("<gray>Kit given. Right-click it to record yourself.</gray>")
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (stateOf(event.item) == null) return

        // The kit is a tool, not a dye: no block placement or item use.
        event.isCancelled = true
        toggle(event.player)
    }

    private fun toggle(player: Player) {
        if (listener.isRecording(player)) {
            val stopped = listener.stopRecording(player) ?: return
            render(player, KitState.IDLE)
            flow.stopped(player, stopped)
            return
        }

        when (listener.startRecording(player)) {
            RecordingStart.STARTED -> render(player, KitState.RECORDING)
            // Another action started the session; the click adopts it.
            RecordingStart.ALREADY_RECORDING -> render(player, KitState.RECORDING)
            RecordingStart.ALREADY_WATCHED -> player.sendMini(
                "<red>You are being watched. Stop the watch first.</red>",
            )
        }
    }

    private fun hasKit(player: Player): Boolean =
        player.inventory.contents.any { stateOf(it) != null }

    /** Replaces every kit stack in place, so the item never leaves its slot. */
    private fun render(player: Player, state: KitState) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val current = inventory.getItem(slot) ?: continue
            if (stateOf(current) == null) continue
            inventory.setItem(slot, item(state))
        }
    }

    private fun stateOf(stack: ItemStack?): KitState? {
        if (stack == null || stack.type == Material.AIR) return null
        val id = stack.itemMeta.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return null
        return KitState.entries.firstOrNull { it.id == id }
    }

    private fun item(state: KitState): ItemStack {
        val recording = state == KitState.RECORDING
        val stack = ItemStack(if (recording) Material.RED_DYE else Material.LIME_DYE)
        stack.editMeta { meta ->
            meta.displayName(
                if (recording) {
                    mini("<red><b>Recording</b></red>")
                } else {
                    mini("<green><b>Record</b></green>")
                },
            )
            meta.lore(
                listOf(
                    if (recording) {
                        mini("<gray>Right-click to stop</gray>")
                    } else {
                        mini("<gray>Right-click to record yourself</gray>")
                    },
                ),
            )
            meta.setEnchantmentGlintOverride(recording)
            meta.persistentDataContainer.set(key, PersistentDataType.STRING, state.id)
        }
        return stack
    }
}
