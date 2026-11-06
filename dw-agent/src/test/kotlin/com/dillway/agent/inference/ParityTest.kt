package com.dillway.agent.inference

import com.dillway.agent.recording.AnimationData
import com.dillway.agent.recording.HeldItemData
import com.dillway.agent.recording.MoveData
import com.dillway.agent.recording.PlayerContext
import com.dillway.agent.recording.RecordedPacket
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

/**
 * Cross-language parity with `dw-engine`'s Python extractor.
 *
 * `parity/parity-stream.json` was generated with `features.py` against a
 * 60-packet stream that exercises every packet kind, dt=0, a >2 s gap, and
 * mixed player state. This test rebuilds the same packets as `RecordedPacket`s
 * and asserts the Kotlin extractor returns the Python feature values.
 */
class ParityTest {

    @Test
    fun `kotlin extractor matches the python values for a generated stream`() {
        val payload = JsonParser.parseString(resource()).asJsonObject
        val packets = packetsOf(payload["packets"].asJsonArray)

        val features = FeatureExtractor.extract(packets, FeatureExtractor.FEATURE_NAMES)
        assertNotNull(features)

        for (expected in payload["expected"].asJsonArray) {
            val entry = expected.asJsonObject
            val name = entry.get("name").asString
            val value = entry.get("value").asDouble
            val index = FeatureExtractor.FEATURE_NAMES.indexOf(name)
            val actual = features[index].toDouble()
            assertEquals(value, actual, 1e-6, "feature $name")
        }
    }

    private fun packetsOf(array: JsonArray): List<RecordedPacket> =
        array.map { element ->
            val packet = element.asJsonObject
            val type = packet.get("type").asString
            RecordedPacket(
                type = type,
                timestamp = packet.get("timestamp").asLong,
                data = dataOf(type, packet.getAsJsonObject("data")),
                context = contextOf(packet.getAsJsonObject("context")),
            )
        }

    private fun dataOf(type: String, data: JsonObject): com.dillway.agent.recording.PacketData =
        when {
            type in FeatureExtractor.MOVEMENT_TYPES || type == "PacketPlayInLook" -> MoveData(
                x = data.num("x"),
                y = data.num("y"),
                z = data.num("z"),
                yaw = data.num("yaw").toFloat(),
                pitch = data.num("pitch").toFloat(),
                onGround = data.bool("f"),
                hasPos = data.bool("hasPos"),
                hasLook = data.bool("hasLook"),
            )
            type == "PacketPlayInArmAnimation" -> AnimationData(
                timestamp = data.num("animation_timestamp").toLong(),
            )
            type == "PacketPlayInHeldItemSlot" -> HeldItemData(
                itemInHandIndex = data.num("itemInHandIndex").toInt(),
            )
            else -> error("unexpected packet type $type")
        }

    private fun contextOf(context: JsonObject) = PlayerContext(
        flySpeed = context.num("fly_speed").toFloat(),
        velocityZ = context.num("velocity_z"),
        locationX = context.num("location_x"),
        locationY = context.num("location_y"),
        locationDirectionY = context.num("location_direction_y"),
        locationDirectionX = context.num("location_direction_x"),
        velocityX = context.num("velocity_x"),
        velocityY = context.num("velocity_y"),
        saturation = context.num("saturation").toFloat(),
        locationDirectionZ = context.num("location_direction_z"),
        locationZ = context.num("location_z"),
        foodLevel = context.num("food_level").toInt(),
        eyeLocationYaw = context.num("eye_location_yaw").toFloat(),
        walkSpeed = context.num("walk_speed").toFloat(),
        eyeHeight = context.num("eye_height"),
        allowFlight = context.bool("allow_flight"),
        exhaustion = context.num("exhaustion").toFloat(),
        health = context.num("health"),
        eyeLocationPitch = context.num("eye_location_pitch").toFloat(),
        locationPitch = context.num("location_pitch").toFloat(),
        fallDistance = context.num("fall_distance").toFloat(),
        locationYaw = context.num("location_yaw").toFloat(),
        healthScale = context.num("health_scale"),
        sprinting = context.bool("sprinting"),
        eyeLocationY = context.num("eye_location_y"),
        eyeLocationX = context.num("eye_location_x"),
        flying = context.bool("flying"),
        eyeLocationZ = context.num("eye_location_z"),
        sneaking = context.bool("sneaking"),
        onGround = context.bool("on_ground"),
    )

    private fun resource(): String = javaClass.classLoader
        .getResourceAsStream("parity/parity-stream.json")
        ?.readBytes()
        ?.decodeToString()
        ?: error("parity fixture missing")

    private fun JsonObject.num(name: String, fallback: Double = 0.0): Double =
        asDouble(name, fallback)

    private fun JsonObject.bool(name: String, fallback: Boolean = false): Boolean {
        val element = get(name) ?: return fallback
        if (element.isJsonNull || !element.isJsonPrimitive) return fallback
        return element.asBoolean
    }

    private fun JsonObject.asDouble(name: String, fallback: Double = 0.0): Double {
        val element = get(name) ?: return fallback
        if (element.isJsonNull || !element.isJsonPrimitive) return fallback
        return element.asDouble
    }
}
