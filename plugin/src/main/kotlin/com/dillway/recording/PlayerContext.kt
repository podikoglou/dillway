package com.dillway.recording

import com.google.gson.annotations.SerializedName

/**
 * Player state at the moment of a packet. The context keys are all legacy
 * snake_case, so they are pinned by explicit `@SerializedName` values; the
 * naming policy in `RecordingJson` only acts as the default for new fields.
 *
 * Float properties stay float for the same reason as `MoveData.yaw`.
 */
data class PlayerContext(
    @SerializedName("fly_speed") val flySpeed: Float,
    @SerializedName("velocity_z") val velocityZ: Double,
    @SerializedName("location_x") val locationX: Double,
    @SerializedName("location_y") val locationY: Double,
    @SerializedName("location_direction_y") val locationDirectionY: Double,
    @SerializedName("location_direction_x") val locationDirectionX: Double,
    @SerializedName("velocity_x") val velocityX: Double,
    @SerializedName("velocity_y") val velocityY: Double,
    @SerializedName("saturation") val saturation: Float,
    @SerializedName("location_direction_z") val locationDirectionZ: Double,
    @SerializedName("location_z") val locationZ: Double,
    @SerializedName("food_level") val foodLevel: Int,
    @SerializedName("eye_location_yaw") val eyeLocationYaw: Float,
    @SerializedName("walk_speed") val walkSpeed: Float,
    @SerializedName("eye_height") val eyeHeight: Double,
    @SerializedName("allow_flight") val allowFlight: Boolean,
    @SerializedName("exhaustion") val exhaustion: Float,
    @SerializedName("health") val health: Double,
    @SerializedName("eye_location_pitch") val eyeLocationPitch: Float,
    @SerializedName("location_pitch") val locationPitch: Float,
    @SerializedName("fall_distance") val fallDistance: Float,
    @SerializedName("location_yaw") val locationYaw: Float,
    @SerializedName("health_scale") val healthScale: Double,
    @SerializedName("sprinting") val sprinting: Boolean,
    @SerializedName("eye_location_y") val eyeLocationY: Double,
    @SerializedName("eye_location_x") val eyeLocationX: Double,
    @SerializedName("flying") val flying: Boolean,
    @SerializedName("eye_location_z") val eyeLocationZ: Double,
    @SerializedName("sneaking") val sneaking: Boolean,
    @SerializedName("on_ground") val onGround: Boolean,
)
