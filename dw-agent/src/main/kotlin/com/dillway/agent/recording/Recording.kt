package com.dillway.agent.recording

/**
 * The typed recording model. The parquet layout in `RecordingParquet` writes
 * one row per packet, carrying the recording header plus the packet's payload
 * and context.
 *
 * [label] is the dataset directory the file belongs to (`legit` or a cheat
 * name). It is chosen when the recording stops, not when it starts.
 */
data class Recording(
    val id: Long,
    val started: Long,
    val player: String,
    val label: String,
    val packets: List<RecordedPacket>,
)

data class RecordedPacket(
    val type: String,
    val timestamp: Long,
    val data: PacketData,
    val context: PlayerContext,
)

/**
 * One recorded packet payload. The parquet writer resolves the runtime class in
 * a sealed `when`, so the declared type can stay an interface. Legacy client
 * packets that this plugin does not observe yet map to new implementations of
 * this interface.
 */
sealed interface PacketData

data class MoveData(
    val x: Double,
    val y: Double,
    val z: Double,
    // Location stores yaw and pitch as float. Keep them as float; widening to
    // double would capture the float's 0.1 artifacts instead of the player
    // state as the JVM reports it.
    val yaw: Float,
    val pitch: Float,
    // Legacy key names: `f` is the client-reported ground flag, `hasPos` and
    // `hasLook` mark what changed since the last movement packet.
    val onGround: Boolean,
    val hasPos: Boolean,
    val hasLook: Boolean,
) : PacketData

data class AnimationData(
    val timestamp: Long,
) : PacketData

data class HeldItemData(
    val itemInHandIndex: Int,
) : PacketData

/**
 * A packet kind with no modelled payload: keep-alive, flying, block place, and
 * other legacy packets. The flat file stores such a row with every payload
 * column null, and the engine reads it as a context-only packet. Compaction
 * must preserve these rows, so they get a variant instead of an error.
 */
data object NoPayload : PacketData
