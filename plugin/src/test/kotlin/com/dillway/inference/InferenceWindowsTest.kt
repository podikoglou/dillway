package com.dillway.inference

import com.dillway.recording.MoveData
import com.dillway.recording.PlayerContext
import com.dillway.recording.RecordedPacket
import kotlin.test.assertEquals
import org.junit.Test

/** Locks the training-compatible window boundaries. */
class InferenceWindowsTest {

    @Test
    fun `windows close at the first packet at or after the window length`() {
        // 8 seconds of 20 Hz movement, so 3.5 s windows close at 3500 and 7000 ms.
        val packets = (0..160).map { i -> packet(i * 50L) }

        val windows = InferenceWindows.split(packets, 3.5)

        assertEquals(2, windows.size)
        assertEquals(0L, windows[0].first().timestamp)
        assertEquals(3500L, windows[0].last().timestamp)
        assertEquals(3500L, windows[1].first().timestamp)
        assertEquals(7000L, windows[1].last().timestamp)
    }

    @Test
    fun `a recording shorter than one window is used whole`() {
        val packets = (0..40).map { i -> packet(i * 50L) }

        val windows = InferenceWindows.split(packets, 3.5)

        assertEquals(1, windows.size)
        assertEquals(packets, windows.single())
    }

    @Test
    fun `non-movement packets stay inside their window`() {
        val movement = (0..80).map { i -> packet(i * 50L) }
        val animation = RecordedPacket(
            type = "PacketPlayInArmAnimation",
            timestamp = 3550,
            data = com.dillway.recording.AnimationData(timestamp = 3550),
            context = context(),
        )

        val windows = InferenceWindows.split(movement + animation, 3.5)

        assertEquals(2, windows.size)
        assertEquals(true, windows[1].any { it.type == "PacketPlayInArmAnimation" })
    }

    private fun packet(timestamp: Long) = RecordedPacket(
        type = "PacketPlayInPosition",
        timestamp = timestamp,
        data = MoveData(
            x = 0.0,
            y = 64.0,
            z = 0.0,
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
