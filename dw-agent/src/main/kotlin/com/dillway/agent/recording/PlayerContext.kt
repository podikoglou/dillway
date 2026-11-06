package com.dillway.agent.recording

/**
 * Player state at the moment of a packet. The property names below are the
 * legacy JSON key names; the parquet layout in `RecordingParquet` writes them
 * as columns.
 *
 * Float properties stay float to match the Bukkit API — widening them
 * widens the collected data, not the precision the server had.
 */
data class PlayerContext(
    val flySpeed: Float,
    val velocityZ: Double,
    val locationX: Double,
    val locationY: Double,
    val locationDirectionY: Double,
    val locationDirectionX: Double,
    val velocityX: Double,
    val velocityY: Double,
    val saturation: Float,
    val locationDirectionZ: Double,
    val locationZ: Double,
    val foodLevel: Int,
    val eyeLocationYaw: Float,
    val walkSpeed: Float,
    val eyeHeight: Double,
    val allowFlight: Boolean,
    val exhaustion: Float,
    val health: Double,
    val eyeLocationPitch: Float,
    val locationPitch: Float,
    val fallDistance: Float,
    val locationYaw: Float,
    val healthScale: Double,
    val sprinting: Boolean,
    val eyeLocationY: Double,
    val eyeLocationX: Double,
    val flying: Boolean,
    val eyeLocationZ: Double,
    val sneaking: Boolean,
    val onGround: Boolean,
)
