package com.dillway.recording

import com.dillway.RecordingType
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Locks the typed recording model to the legacy JSON byte format used by the
 * files in `data-main`. Key order matters: Gson serializes data class
 * properties in declaration order, and the pipeline data was recorded in this
 * order.
 */
class RecordingJsonTest {

    private val gson = RecordingJson.gson

    @Test
    fun `move packet serializes to the legacy bytes`() {
        val recording = Recording(
            id = 1674590916014,
            started = 1674590916014,
            player = "78de5f59-ba63-45af-a9a8-8d111e155c29",
            type = RecordingType.LEGIT,
            packets = listOf(
                RecordedPacket(
                    type = "PacketPlayInPosition",
                    timestamp = 1674590916019,
                    data = MoveData(
                        x = 50.922640679476665,
                        y = 201.0,
                        z = 39.91171675963879,
                        yaw = 0.0f,
                        pitch = 0.0f,
                        onGround = true,
                        hasPos = true,
                        hasLook = false,
                    ),
                    context = PlayerContext(
                        flySpeed = 0.1f,
                        velocityZ = 0.0,
                        locationX = 51.19542895656041,
                        locationY = 201.0,
                        locationDirectionY = -0.19956846917130244,
                        locationDirectionX = -0.9784812622903266,
                        velocityX = 0.0,
                        velocityY = -0.0784000015258789,
                        saturation = 5.0f,
                        locationDirectionZ = 0.05241035641313858,
                        locationZ = 39.90488002539233,
                        foodLevel = 20,
                        eyeLocationYaw = 86.934f,
                        walkSpeed = 0.2f,
                        eyeHeight = 1.62,
                        allowFlight = true,
                        exhaustion = 0.0f,
                        health = 20.0,
                        eyeLocationPitch = 11.511725f,
                        locationPitch = 11.511725f,
                        fallDistance = 0.0f,
                        locationYaw = 86.934f,
                        healthScale = 20.0,
                        sprinting = true,
                        eyeLocationY = 202.62,
                        eyeLocationX = 51.19542895656041,
                        flying = false,
                        eyeLocationZ = 39.90488002539233,
                        sneaking = false,
                        onGround = true,
                    ),
                ),
            ),
        )
        assertEquals(fixture("move.json"), gson.toJson(recording))
    }


    @Test
    fun `arm-animation packet serializes to the legacy bytes`() {
        val recording = Recording(
            id = 1674590916014,
            started = 1674590916014,
            player = "78de5f59-ba63-45af-a9a8-8d111e155c29",
            type = RecordingType.LEGIT,
            packets = listOf(
                RecordedPacket(
                    type = "PacketPlayInArmAnimation",
                    timestamp = 1674590919969,
                    data = AnimationData(
                        timestamp = 1674590919969,
                    ),
                    context = PlayerContext(
                        flySpeed = 0.1f,
                        velocityZ = -0.05189166215768134,
                        locationX = 25.626434951259846,
                        locationY = 202.00133597911216,
                        locationDirectionY = -0.1747813810072417,
                        locationDirectionX = -0.9192915191687319,
                        velocityX = -0.19696545635772325,
                        velocityY = 0.24813599859094576,
                        saturation = 5.0f,
                        locationDirectionZ = -0.35263943573804496,
                        locationZ = 44.04194392823208,
                        foodLevel = 20,
                        eyeLocationYaw = 110.98677f,
                        walkSpeed = 0.2f,
                        eyeHeight = 1.62,
                        allowFlight = true,
                        exhaustion = 0.0f,
                        health = 20.0,
                        eyeLocationPitch = 10.065936f,
                        locationPitch = 10.065936f,
                        fallDistance = 0.0f,
                        locationYaw = 110.98677f,
                        healthScale = 20.0,
                        sprinting = true,
                        eyeLocationY = 203.62133597911216,
                        eyeLocationX = 25.626434951259846,
                        flying = false,
                        eyeLocationZ = 44.04194392823208,
                        sneaking = false,
                        onGround = false,
                    ),
                ),
            ),
        )
        assertEquals(fixture("arm-animation.json"), gson.toJson(recording))
    }


    @Test
    fun `held-item packet serializes to the legacy bytes`() {
        val recording = Recording(
            id = 1674590916014,
            started = 1674590916014,
            player = "78de5f59-ba63-45af-a9a8-8d111e155c29",
            type = RecordingType.LEGIT,
            packets = listOf(
                RecordedPacket(
                    type = "PacketPlayInHeldItemSlot",
                    timestamp = 1674590916318,
                    data = HeldItemData(
                        itemInHandIndex = 2,
                    ),
                    context = PlayerContext(
                        flySpeed = 0.1f,
                        velocityZ = 0.009749340342072643,
                        locationX = 49.323092743884615,
                        locationY = 201.7531999805212,
                        locationDirectionY = -0.19900647778129738,
                        locationDirectionX = -0.9785953890850674,
                        velocityX = -0.18173870565734251,
                        velocityY = 0.33319999363422365,
                        saturation = 5.0f,
                        locationDirectionZ = 0.0524164693825118,
                        locationZ = 39.96075666894236,
                        foodLevel = 20,
                        eyeLocationYaw = 86.934f,
                        walkSpeed = 0.2f,
                        eyeHeight = 1.62,
                        allowFlight = true,
                        exhaustion = 0.0f,
                        health = 20.0,
                        eyeLocationPitch = 11.478867f,
                        locationPitch = 11.478867f,
                        fallDistance = 0.0f,
                        locationYaw = 86.934f,
                        healthScale = 20.0,
                        sprinting = true,
                        eyeLocationY = 203.3731999805212,
                        eyeLocationX = 49.323092743884615,
                        flying = false,
                        eyeLocationZ = 39.96075666894236,
                        sneaking = false,
                        onGround = false,
                    ),
                ),
            ),
        )
        assertEquals(fixture("held-item.json"), gson.toJson(recording))
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/recording/$name")!!
            .use { stream -> stream.readBytes().decodeToString() }
            .trim()
}
