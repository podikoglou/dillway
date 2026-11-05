package com.dillway

import com.dillway.recording.AnimationData
import com.dillway.recording.HeldItemData
import com.dillway.recording.MoveData
import com.dillway.recording.PlayerContext
import com.dillway.recording.RecordedPacket
import com.dillway.recording.Recording
import com.dillway.recording.RecordingJson
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerMoveEvent
import java.io.File
import java.io.IOException
import java.util.UUID

class DillListener(private val plugin: DillPlugin) : Listener {

    private val sessions = mutableMapOf<UUID, RecordingSession>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startRecording(player: Player, type: RecordingType): Boolean {
        if (sessions.containsKey(player.uniqueId)) return false
        sessions[player.uniqueId] = RecordingSession(player.uniqueId, type)
        return true
    }

    fun stopRecording(player: Player): Boolean {
        val session = sessions.remove(player.uniqueId) ?: return false
        saveSession(player, session)
        return true
    }

    fun isRecording(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    /** Called on shutdown — flushes every active session. */
    fun stopAll() {
        // Copy the keys; the removal inside the loop mutates the map.
        sessions.keys.toList().forEach { id ->
            plugin.server.getPlayer(id)?.let(::stopRecording)
        }
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return

        val from = event.from
        val to = event.to

        val posChanged = from.x != to.x || from.y != to.y || from.z != to.z
        val lookChanged = from.yaw != to.yaw || from.pitch != to.pitch
        if (!posChanged && !lookChanged) return

        // Reconstruct the client packet type from what actually changed
        val packetType = when {
            posChanged && lookChanged -> "PacketPlayInPositionLook"
            posChanged -> "PacketPlayInPosition"
            else -> "PacketPlayInLook"
        }

        session.packets += RecordedPacket(
            type = packetType,
            timestamp = System.currentTimeMillis(),
            data = to.toMoveData(player.clientOnGround(), posChanged, lookChanged),
            context = player.toPlayerContext(from),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAnimation(event: PlayerAnimationEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return

        // The legacy format carries a timestamp inside the data entry too.
        session.packets += RecordedPacket(
            type = "PacketPlayInArmAnimation",
            timestamp = System.currentTimeMillis(),
            data = AnimationData(timestamp = System.currentTimeMillis()),
            context = player.toPlayerContext(player.location),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeldItem(event: PlayerItemHeldEvent) {
        val player = event.player
        val session = sessions[player.uniqueId] ?: return

        session.packets += RecordedPacket(
            type = "PacketPlayInHeldItemSlot",
            timestamp = System.currentTimeMillis(),
            data = HeldItemData(itemInHandIndex = event.newSlot),
            context = player.toPlayerContext(player.location),
        )
    }

    // -------------------------------------------------------------------------
    // Mapping to the recording model
    // -------------------------------------------------------------------------

    /**
     * The client-reported ground state. Paper deprecates it as spoofable, but
     * the logged `f` flag and `on_ground` context field carry exactly this
     * client value, so the deprecation is deliberate.
     */
    @Suppress("DEPRECATION")
    private fun Player.clientOnGround(): Boolean = isOnGround

    private fun Location.toMoveData(
        onGround: Boolean,
        hasPos: Boolean,
        hasLook: Boolean,
    ) = MoveData(
        x = x,
        y = y,
        z = z,
        yaw = yaw,
        pitch = pitch,
        onGround = onGround,
        hasPos = hasPos,
        hasLook = hasLook,
    )

    /** Context is captured at the time of the packet ([loc] = server's current state). */
    private fun Player.toPlayerContext(loc: Location): PlayerContext {
        val velocity = velocity
        val direction = loc.direction
        return PlayerContext(
            flySpeed = flySpeed,
            velocityZ = velocity.z,
            locationX = loc.x,
            locationY = loc.y,
            locationDirectionY = direction.y,
            locationDirectionX = direction.x,
            velocityX = velocity.x,
            velocityY = velocity.y,
            saturation = saturation,
            locationDirectionZ = direction.z,
            locationZ = loc.z,
            foodLevel = foodLevel,
            eyeLocationYaw = loc.yaw,
            walkSpeed = walkSpeed,
            eyeHeight = eyeHeight,
            allowFlight = allowFlight,
            exhaustion = exhaustion,
            health = health,
            eyeLocationPitch = loc.pitch,
            locationPitch = loc.pitch,
            fallDistance = fallDistance,
            locationYaw = loc.yaw,
            healthScale = healthScale,
            sprinting = isSprinting,
            eyeLocationY = loc.y + eyeHeight,
            eyeLocationX = loc.x,
            flying = isFlying,
            eyeLocationZ = loc.z,
            sneaking = isSneaking,
            onGround = clientOnGround(),
        )
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private fun saveSession(player: Player, session: RecordingSession) {
        val dir = File(plugin.dataFolder, session.type.folder).apply { mkdirs() }
        val file = File(dir, "${session.started}.json")

        val recording = Recording(
            id = session.started,
            started = session.started,
            player = session.playerId.toString(),
            type = session.type,
            packets = session.packets.toList(),
        )

        try {
            file.bufferedWriter().use { RecordingJson.gson.toJson(recording, it) }
            plugin.logger.info(
                "Saved ${session.type} recording for ${player.name} → ${file.name} " +
                    "(${session.packets.size} packets)",
            )
        } catch (e: IOException) {
            plugin.logger.severe("Failed to save recording: ${e.message}")
        }
    }
}
