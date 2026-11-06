package com.dillway.agent.inference

import com.dillway.agent.recording.MoveData
import com.dillway.agent.recording.RecordedPacket
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Kotlin port of `dw-engine`'s feature extraction (`features.py`).
 *
 * The exported ONNX model carries the feature order as `dw.features`
 * metadata, so [extract] builds a value per named feature and reorders it to
 * match the model. A model that asks for a feature this extractor does not
 * know is rejected rather than fed a misaligned vector.
 *
 * Keep this in lockstep with `dw-engine/src/dw_engine/features.py`. The
 * arithmetic mirrors NumPy: percentiles interpolate linearly and the standard
 * deviation is the population value (ddof=0).
 */
object FeatureExtractor {

    val MOVEMENT_TYPES = setOf("PacketPlayInPosition", "PacketPlayInPositionLook")

    /** Same order as `FEATURE_NAMES` in `features.py`; used by tests and docs. */
    val FEATURE_NAMES = listOf(
        "mean_speed", "max_speed", "std_speed",
        "p50_speed", "p75_speed", "p95_speed", "p99_speed",
        "mean_horiz_speed", "max_horiz_speed", "std_horiz_speed", "p95_horiz_speed",
        "mean_dt", "std_dt", "min_dt",
        "mean_accel", "max_accel",
        "mean_dy", "mean_horiz_step", "max_horiz_step",
        "on_ground_ratio", "flying_ratio", "sprinting_ratio",
        "mean_vel_x", "mean_vel_z", "mean_vel_y",
        "num_movement_packets", "total_packets",
        "ratio_position", "ratio_positionlook", "ratio_keepalive",
    )

    private val KNOWN_FEATURES = FEATURE_NAMES.toSet()

    /** True when every feature the model needs is implemented here. */
    fun supports(featureNames: List<String>): Boolean =
        featureNames.all { it in KNOWN_FEATURES }

    /**
     * Returns one value per entry in [featureNames], or null when the window
     * has fewer than two usable movement packets (the Python extractor also
     * rejects those).
     */
    fun extract(packets: List<RecordedPacket>, featureNames: List<String>): FloatArray? {
        val values = values(packets) ?: return null
        return FloatArray(featureNames.size) { index ->
            val name = featureNames[index]
            val value = values[name]
                ?: error("unsupported feature '$name' requested by model")
            value.toFloat()
        }
    }

    private fun values(packets: List<RecordedPacket>): Map<String, Double>? {
        val movement = packets.filter { it.type in MOVEMENT_TYPES }
        if (movement.size < 2) return null

        val speeds = ArrayList<Double>(movement.size - 1)
        val horizSpeeds = ArrayList<Double>(movement.size - 1)
        val dts = ArrayList<Double>(movement.size - 1)
        val dys = ArrayList<Double>(movement.size - 1)
        val horizSteps = ArrayList<Double>(movement.size - 1)

        for (i in 1 until movement.size) {
            val previous = movement[i - 1]
            val current = movement[i]
            val dt = (current.timestamp - previous.timestamp) / 1000.0
            if (dt <= 0.0 || dt > 2.0) continue

            val a = previous.data as? MoveData ?: continue
            val b = current.data as? MoveData ?: continue

            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            val horizontal = sqrt(dx * dx + dz * dz)

            speeds += distance / dt
            horizSpeeds += horizontal / dt
            dts += dt
            dys += abs(dy)
            horizSteps += horizontal
        }

        if (speeds.isEmpty()) return null

        val accelerations = ArrayList<Double>(maxOf(0, speeds.size - 1))
        for (i in 1 until speeds.size) {
            accelerations += abs(speeds[i] - speeds[i - 1])
        }

        val speedSorted = speeds.sorted()
        val horizSorted = horizSpeeds.sorted()
        val count = movement.size.toDouble()
        val total = packets.size.toDouble()

        val packetTypes = packets.groupingBy { it.type }.eachCount()

        return mapOf(
            "mean_speed" to mean(speeds),
            "max_speed" to speeds.max(),
            "std_speed" to std(speeds),
            "p50_speed" to percentile(speedSorted, 50.0),
            "p75_speed" to percentile(speedSorted, 75.0),
            "p95_speed" to percentile(speedSorted, 95.0),
            "p99_speed" to percentile(speedSorted, 99.0),
            "mean_horiz_speed" to mean(horizSpeeds),
            "max_horiz_speed" to horizSpeeds.max(),
            "std_horiz_speed" to std(horizSpeeds),
            "p95_horiz_speed" to percentile(horizSorted, 95.0),
            "mean_dt" to mean(dts),
            "std_dt" to std(dts),
            "min_dt" to dts.min(),
            "mean_accel" to if (accelerations.isEmpty()) 0.0 else mean(accelerations),
            "max_accel" to if (accelerations.isEmpty()) 0.0 else accelerations.max(),
            "mean_dy" to mean(dys),
            "mean_horiz_step" to mean(horizSteps),
            "max_horiz_step" to horizSteps.max(),
            "on_ground_ratio" to movement.count { it.context.onGround } / count,
            "flying_ratio" to movement.count { it.context.flying } / count,
            "sprinting_ratio" to movement.count { it.context.sprinting } / count,
            "mean_vel_x" to movement.map { abs(it.context.velocityX) }.average(),
            "mean_vel_z" to movement.map { abs(it.context.velocityZ) }.average(),
            "mean_vel_y" to movement.map { abs(it.context.velocityY) }.average(),
            "num_movement_packets" to count,
            "total_packets" to total,
            "ratio_position" to (packetTypes["PacketPlayInPosition"] ?: 0) / total,
            "ratio_positionlook" to (packetTypes["PacketPlayInPositionLook"] ?: 0) / total,
            "ratio_keepalive" to (packetTypes["PacketPlayInKeepAlive"] ?: 0) / total,
        )
    }

    private fun mean(values: List<Double>): Double = values.sum() / values.size

    /** NumPy's default std: population, not sample. */
    private fun std(values: List<Double>): Double {
        val average = mean(values)
        val variance = values.sumOf { (it - average) * (it - average) } / values.size
        return sqrt(variance)
    }

    /**
     * NumPy's default linear percentile: rank = (n - 1) * p / 100, then
     * interpolate between the neighbours.
     */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val rank = (sorted.size - 1) * p / 100.0
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        val weight = rank - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }
}
