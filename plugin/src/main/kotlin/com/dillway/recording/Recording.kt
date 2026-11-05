package com.dillway.recording

import com.dillway.RecordingType
import com.google.gson.annotations.SerializedName

/**
 * The typed recording model. Gson serializes data class properties in
 * declaration order, so the order below is the legacy key order of the files
 * in `data-main`. Renaming a property or reordering it changes the output
 * bytes; the golden test in `RecordingJsonTest` catches it.
 */
data class Recording(
    val id: Long,
    val started: Long,
    val player: String,
    val type: RecordingType,
    val packets: List<RecordedPacket>,
)

data class RecordedPacket(
    val type: String,
    val timestamp: Long,
    val data: PacketData,
    val context: PlayerContext,
)

/**
 * One recorded packet payload. Gson resolves the runtime class, so the
 * declared type can stay an interface. Legacy client packets that this plugin
 * does not observe yet map to new implementations of this interface.
 */
sealed interface PacketData

data class MoveData(
    val x: Double,
    val y: Double,
    val z: Double,
    // Location stores yaw and pitch as float. Keep them as float; widening to
    // double changes the JSON text (0.1 becomes 0.10000000149011612).
    val yaw: Float,
    val pitch: Float,
    // Legacy key names. Naming-policy behavior would map these to
    // on_ground / has_pos / has_look, so they are pinned explicitly.
    @SerializedName("f") val onGround: Boolean,
    @SerializedName("hasPos") val hasPos: Boolean,
    @SerializedName("hasLook") val hasLook: Boolean,
) : PacketData

data class AnimationData(
    val timestamp: Long,
) : PacketData

data class HeldItemData(
    @SerializedName("itemInHandIndex") val itemInHandIndex: Int,
) : PacketData
