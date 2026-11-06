package com.dillway.agent.inference

import com.dillway.agent.recording.AnimationData
import com.dillway.agent.recording.MoveData
import com.dillway.agent.recording.PlayerContext
import com.dillway.agent.recording.RecordedPacket
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

/** Locks the NumPy-compatible arithmetic and the feature order. */
class FeatureExtractorTest {

    @Test
    fun `constant movement produces the expected summary statistics`() {
        val packets = (0..20).map { i ->
            movementPacket(
                timestamp = i * 100L,
                x = i.toDouble(),
                y = 64.0,
                z = 0.0,
            )
        }

        val features = FeatureExtractor.extract(packets, FeatureExtractor.FEATURE_NAMES)
        assertNotNull(features)

        fun at(name: String): Double = features[FeatureExtractor.FEATURE_NAMES.indexOf(name)].toDouble()

        assertEquals(10.0, at("mean_speed"), 1e-6)
        assertEquals(10.0, at("max_speed"), 1e-6)
        assertEquals(0.0, at("std_speed"), 1e-6)
        assertEquals(10.0, at("p50_speed"), 1e-6)
        assertEquals(10.0, at("p75_speed"), 1e-6)
        assertEquals(10.0, at("p95_speed"), 1e-6)
        assertEquals(10.0, at("p99_speed"), 1e-6)

        assertEquals(10.0, at("mean_horiz_speed"), 1e-6)
        assertEquals(10.0, at("max_horiz_speed"), 1e-6)
        assertEquals(0.0, at("std_horiz_speed"), 1e-6)
        assertEquals(10.0, at("p95_horiz_speed"), 1e-6)

        assertEquals(0.1, at("mean_dt"), 1e-6)
        assertEquals(0.0, at("std_dt"), 1e-6)
        assertEquals(0.1, at("min_dt"), 1e-6)

        assertEquals(0.0, at("mean_accel"), 1e-6)
        assertEquals(0.0, at("max_accel"), 1e-6)
        assertEquals(0.0, at("mean_dy"), 1e-6)
        assertEquals(1.0, at("mean_horiz_step"), 1e-6)
        assertEquals(1.0, at("max_horiz_step"), 1e-6)

        assertEquals(1.0, at("on_ground_ratio"), 1e-6)
        assertEquals(0.0, at("flying_ratio"), 1e-6)
        assertEquals(1.0, at("sprinting_ratio"), 1e-6)
        assertEquals(0.0, at("mean_vel_x"), 1e-6)
        assertEquals(0.0, at("mean_vel_z"), 1e-6)
        assertEquals(0.0, at("mean_vel_y"), 1e-6)

        assertEquals(21.0, at("num_movement_packets"), 1e-6)
        assertEquals(21.0, at("total_packets"), 1e-6)
        assertEquals(1.0, at("ratio_position"), 1e-6)
        assertEquals(0.0, at("ratio_positionlook"), 1e-6)
        assertEquals(0.0, at("ratio_keepalive"), 1e-6)
    }

    @Test
    fun `non-movement packets still count in total packets`() {
        val movement = (0..20).map { i ->
            movementPacket(timestamp = i * 100L, x = i.toDouble(), y = 64.0, z = 0.0)
        }
        val animation = RecordedPacket(
            type = "PacketPlayInArmAnimation",
            timestamp = 1050,
            data = AnimationData(timestamp = 1050),
            context = context(),
        )

        val features = FeatureExtractor.extract(movement + animation, FeatureExtractor.FEATURE_NAMES)
        assertNotNull(features)

        val total = features[FeatureExtractor.FEATURE_NAMES.indexOf("total_packets")]
        val movementCount = features[FeatureExtractor.FEATURE_NAMES.indexOf("num_movement_packets")]
        assertEquals(22.0f, total)
        assertEquals(21.0f, movementCount)
    }

    @Test
    fun `fewer than two movement packets are rejected`() {
        val packets = listOf(movementPacket(timestamp = 0, x = 0.0, y = 64.0, z = 0.0))
        assertNull(FeatureExtractor.extract(packets, FeatureExtractor.FEATURE_NAMES))
    }

    @Test
    fun `feature order follows the model metadata`() {
        val packets = (0..20).map { i ->
            movementPacket(timestamp = i * 100L, x = i.toDouble(), y = 64.0, z = 0.0)
        }
        val reversed = FeatureExtractor.FEATURE_NAMES.reversed()

        val features = FeatureExtractor.extract(packets, reversed)
        assertNotNull(features)

        assertEquals(
            FeatureExtractor.extract(packets, FeatureExtractor.FEATURE_NAMES)!!.reversed().toList(),
            features.toList(),
        )
    }

    @Test
    fun `models asking for unknown features are rejected`() {
        assertEquals(false, FeatureExtractor.supports(listOf("not_a_feature")))
        assertEquals(true, FeatureExtractor.supports(FeatureExtractor.FEATURE_NAMES))
    }

    private fun movementPacket(
        timestamp: Long,
        x: Double,
        y: Double,
        z: Double,
    ) = RecordedPacket(
        type = "PacketPlayInPosition",
        timestamp = timestamp,
        data = MoveData(
            x = x,
            y = y,
            z = z,
            yaw = 0f,
            pitch = 0f,
            onGround = true,
            hasPos = true,
            hasLook = false,
        ),
        context = context(),
    )

    private fun context() = PlayerContext(
        flySpeed = 0.05f,
        velocityZ = 0.0,
        locationX = 0.0,
        locationY = 64.0,
        locationDirectionY = 0.0,
        locationDirectionX = 0.0,
        velocityX = 0.0,
        velocityY = 0.0,
        saturation = 5.0f,
        locationDirectionZ = 0.0,
        locationZ = 0.0,
        foodLevel = 20,
        eyeLocationYaw = 0f,
        walkSpeed = 0.2f,
        eyeHeight = 1.62,
        allowFlight = false,
        exhaustion = 0.0f,
        health = 20.0,
        eyeLocationPitch = 0f,
        locationPitch = 0f,
        fallDistance = 0.0f,
        locationYaw = 0f,
        healthScale = 20.0,
        sprinting = true,
        eyeLocationY = 65.62,
        eyeLocationX = 0.0,
        flying = false,
        eyeLocationZ = 0.0,
        sneaking = false,
        onGround = true,
    )
}
