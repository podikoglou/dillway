package com.dillway

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The two appearances of the recorder. The item container stores the id. */
private enum class KitState(val id: String) {
    IDLE("idle"),
    RECORDING("recording"),
}

/**
 * The self-recording kit handed out by `/dill kit`.
 *
 * Two items share the kit: a recorder and a class selector. A right click on
 * the recorder starts recording the holder; the next right click stops it and
 * saves the session straight under the type shown by the selector, with no
 * menu. The selector cycles through [DillPlugin.availableLabels]. Both items
 * stay in their slots and only change their look.
 *
 * The selector stores its type in the item itself, so a dropped or moved
 * selector still carries the choice; no per-player state is kept.
 */
class RecordingKit(
    private val plugin: DillPlugin,
    private val listener: DillListener,
    private val flow: RecordingFlow,
) : Listener {

    private val recordingKey = NamespacedKey(plugin, "recording-kit")
    private val labelKey = NamespacedKey(plugin, "recording-label")

    /** Puts any missing kit item in the first free slots. */
    fun give(player: Player) {
        val missing = buildList {
            if (!hasRecordingItem(player)) add(recordingItem(initialState(player)))
            if (!hasLabelItem(player)) add(labelItem(selectedLabel(player)))
        }
        if (missing.isEmpty()) {
            player.sendMini("<gray>You already have a recording kit.</gray>")
            return
        }

        if (freeSlots(player) < missing.size) {
            player.sendMini("<red>Your inventory is full.</red>")
            return
        }
        player.inventory.addItem(*missing.toTypedArray())

        player.sendMini(
            "<gray>Kit given. Right-click the recorder to record; " +
                "right-click the book to choose the type.</gray>",
        )
    }

    // No ignoreCancelled: Paper fires a right-click on air as cancelled,
    // because the server predicts that nothing will happen. The item-use
    // result tells a real denial from that prediction.
    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val recorder = stateOf(event.item) != null
        val selector = labelOf(event.item) != null
        if (!recorder && !selector) return
        if (event.useItemInHand() == Event.Result.DENY) return
        // One block click sends both a use-item-on and a use-item packet. The
        // second one arrives as a block event with the block use already
        // denied; only the first is the real click.
        if (event.action == Action.RIGHT_CLICK_BLOCK &&
            event.useInteractedBlock() == Event.Result.DENY
        ) {
            return
        }

        // The kit is a tool, not a dye or a book: no block placement or item use.
        event.isCancelled = true
        if (recorder) toggle(event.player) else cycleLabel(event.player)
    }

    private fun toggle(player: Player) {
        if (listener.isRecording(player)) {
            // Read the type before detaching the session: the selector may
            // change while the write is in flight.
            val label = selectedLabel(player)
            val stopped = listener.stopRecording(player) ?: return
            renderRecorder(player, KitState.IDLE)
            flow.stopped(player, stopped, label)
            return
        }

        when (listener.startRecording(player)) {
            RecordingStart.STARTED -> renderRecorder(player, KitState.RECORDING)
            // Another action started the session; the click adopts it.
            RecordingStart.ALREADY_RECORDING -> renderRecorder(player, KitState.RECORDING)
            RecordingStart.ALREADY_WATCHED -> player.sendMini(
                "<red>You are being watched. Stop the watch first.</red>",
            )
        }
    }

    private fun cycleLabel(player: Player) {
        val labels = plugin.availableLabels()
        val current = selectedLabel(player)
        val index = labels.indexOf(current)
        val next = labels[if (index < 0) 0 else (index + 1) % labels.size]

        renderLabel(player, next)
        player.sendMini(
            "<gray>Kit type: <yellow><label></yellow></gray>",
            Placeholder.unparsed("label", next),
        )
    }

    private fun initialState(player: Player): KitState =
        if (listener.isRecording(player)) KitState.RECORDING else KitState.IDLE

    /** The selected type, falling back to `legit` when the item is gone or stale. */
    private fun selectedLabel(player: Player): String {
        val labels = plugin.availableLabels()
        val stored = player.inventory.contents.asSequence()
            .mapNotNull { labelOf(it) }
            .firstOrNull()
        return stored?.takeIf { it in labels } ?: Labels.LEGIT
    }

    private fun hasRecordingItem(player: Player): Boolean =
        player.inventory.contents.any { stateOf(it) != null }

    private fun hasLabelItem(player: Player): Boolean =
        player.inventory.contents.any { labelOf(it) != null }

    private fun freeSlots(player: Player): Int =
        player.inventory.storageContents.count { it == null || it.type == Material.AIR }

    /** Replaces every recorder stack in place, so the item never leaves its slot. */
    private fun renderRecorder(player: Player, state: KitState) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val current = inventory.getItem(slot) ?: continue
            if (stateOf(current) == null) continue
            inventory.setItem(slot, recordingItem(state))
        }
    }

    /** Replaces every selector stack in place with [label]. */
    private fun renderLabel(player: Player, label: String) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val current = inventory.getItem(slot) ?: continue
            if (labelOf(current) == null) continue
            inventory.setItem(slot, labelItem(label))
        }
    }

    private fun stateOf(stack: ItemStack?): KitState? {
        if (stack == null || stack.type == Material.AIR) return null
        val id = stack.itemMeta.persistentDataContainer
            .get(recordingKey, PersistentDataType.STRING) ?: return null
        return KitState.entries.firstOrNull { it.id == id }
    }

    private fun labelOf(stack: ItemStack?): String? {
        if (stack == null || stack.type == Material.AIR) return null
        return stack.itemMeta.persistentDataContainer.get(labelKey, PersistentDataType.STRING)
    }

    private fun recordingItem(state: KitState): ItemStack {
        val recording = state == KitState.RECORDING
        val stack = ItemStack(if (recording) Material.RED_DYE else Material.LIME_DYE)
        stack.editMeta { meta ->
            meta.displayName(
                if (recording) {
                    mini("<red><!italic><b>Recording</b></red>")
                } else {
                    mini("<green><!italic><b>Record</b></green>")
                },
            )
            meta.lore(
                listOf(
                    if (recording) {
                        mini("<gray>Right-click to stop and save</gray>")
                    } else {
                        mini("<gray>Right-click to record yourself</gray>")
                    },
                ),
            )
            meta.setEnchantmentGlintOverride(recording)
            meta.setMaxStackSize(1)
            meta.persistentDataContainer.set(recordingKey, PersistentDataType.STRING, state.id)
        }
        return stack
    }

    private fun labelItem(label: String): ItemStack {
        val stack = ItemStack(Material.BOOK)
        stack.editMeta { meta ->
            meta.displayName(
                mini(
                    "<aqua><!italic><b>Type: <label></b></aqua>",
                    Placeholder.unparsed("label", label),
                ),
            )
            meta.lore(
                listOf(
                    mini("<gray>Right-click to cycle</gray>"),
                    mini("<dark_gray>Used by the recorder</dark_gray>"),
                ),
            )
            meta.setMaxStackSize(1)
            meta.persistentDataContainer.set(labelKey, PersistentDataType.STRING, label)
        }
        return stack
    }
}
