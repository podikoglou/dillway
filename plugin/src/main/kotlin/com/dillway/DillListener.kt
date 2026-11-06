package com.dillway

import com.dillway.recording.AnimationData
import com.dillway.recording.HeldItemData
import com.dillway.recording.MoveData
import com.dillway.recording.PlayerContext
import com.dillway.recording.RecordedPacket
import com.dillway.recording.Recording
import com.dillway.recording.RecordingParquet
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.io.IOException
import java.util.UUID

class DillListener(
    private val plugin: DillPlugin,
    private val watches: WatchService,
) : Listener {

    private val sessions = mutableMapOf<UUID, RecordingSession>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun startRecording(player: Player): Boolean {
        if (sessions.containsKey(player.uniqueId)) return false
        sessions[player.uniqueId] = RecordingSession(player.uniqueId)
        return true
    }

    /**
     * Detaches the session from the event pipeline. The caller decides what
     * happens next: label it for training, run inference, or discard it.
     */
    fun stopRecording(player: Player): StoppedRecording? {
        val session = sessions.remove(player.uniqueId) ?: return null
        return StoppedRecording(player.name, session)
    }

    fun isRecording(player: Player): Boolean = sessions.containsKey(player.uniqueId)

    /**
     * Writes one parquet copy for [label] and returns it, or null on failure.
     * A multi-cheat recording is written once per selected label, which keeps
     * the trainer's directory-per-label contract. Call off the main thread.
     */
    fun write(stopped: StoppedRecording, label: String): File? {
        val dir = File(plugin.dataFolder, label).apply { mkdirs() }
        val file = File(dir, "${stopped.session.started}.parquet")

        val recording = Recording(
            id = stopped.session.started,
            started = stopped.session.started,
            player = stopped.session.playerId.toString(),
            label = label,
            packets = stopped.session.packets.toList(),
        )

        return try {
            RecordingParquet.writeRecording(file, recording)
            plugin.logger.info(
                "Saved $label recording for ${stopped.playerName} → ${file.name} " +
                    "(${stopped.session.packets.size} packets)",
            )
            file
        } catch (e: IOException) {
            plugin.logger.severe("Failed to save recording: ${e.message}")
            null
        }
    }

    /**
     * Called on shutdown. Active sessions cannot be labeled anymore, so they
     * are written locally as `unlabeled` for later manual sorting and are not
     * uploaded.
     */
    fun stopAll() {
        // Copy the keys; the removal inside the loop mutates the map.
        sessions.keys.toList().forEach { id ->
            val session = sessions.remove(id) ?: return@forEach
            val name = plugin.server.getPlayer(id)?.name ?: id.toString()
            plugin.logger.warning(
                "Server stopping: saving active recording of $name as ${Labels.UNLABELED}",
            )
            write(StoppedRecording(name, session), Labels.UNLABELED)
        }
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val player = event.player
        if (!isCapturing(player)) return

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

        capture(
            player,
            RecordedPacket(
                type = packetType,
                timestamp = System.currentTimeMillis(),
                data = to.toMoveData(player.clientOnGround(), posChanged, lookChanged),
                context = player.toPlayerContext(from),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAnimation(event: PlayerAnimationEvent) {
        val player = event.player
        if (!isCapturing(player)) return

        // The legacy format carries a timestamp inside the data entry too.
        capture(
            player,
            RecordedPacket(
                type = "PacketPlayInArmAnimation",
                timestamp = System.currentTimeMillis(),
                data = AnimationData(timestamp = System.currentTimeMillis()),
                context = player.toPlayerContext(player.location),
            ),
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeldItem(event: PlayerItemHeldEvent) {
        val player = event.player
        if (!isCapturing(player)) return

        capture(
            player,
            RecordedPacket(
                type = "PacketPlayInHeldItemSlot",
                timestamp = System.currentTimeMillis(),
                data = HeldItemData(itemInHandIndex = event.newSlot),
                context = player.toPlayerContext(player.location),
            ),
        )
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        watches.playerQuit(event.player)
    }

    // -------------------------------------------------------------------------
    // Mapping to the recording model
    // -------------------------------------------------------------------------

    /** True when a recording or a live watch needs this player's packets. */
    private fun isCapturing(player: Player): Boolean =
        sessions.containsKey(player.uniqueId) || watches.isWatching(player.uniqueId)

    /** Feeds both pipelines: a pending recording and any live watch. */
    private fun capture(player: Player, packet: RecordedPacket) {
        sessions[player.uniqueId]?.packets?.add(packet)
        watches.capture(player.uniqueId, packet)
    }

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
}
