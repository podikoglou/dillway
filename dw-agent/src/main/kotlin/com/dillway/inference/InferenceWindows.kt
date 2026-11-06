package com.dillway.inference

import com.dillway.recording.RecordedPacket
import kotlin.math.roundToLong

/**
 * Slices a finished recording into inference windows.
 *
 * The bounds repeat `dw-engine`'s `dataset.split_windows()` so inference sees
 * the same window shape as training: the first movement packet opens a
 * window, and the first movement packet at or after the window length closes
 * it and opens the next one. When a recording is shorter than one window the
 * whole recording is used, so a short test recording still gets a prediction.
 */
object InferenceWindows {

    /** A feature vector needs at least two movement packets. */
    const val MIN_MOVEMENT_PACKETS = 2

    fun split(packets: List<RecordedPacket>, windowSeconds: Double): List<List<RecordedPacket>> {
        val movement = packets.filter { it.type in FeatureExtractor.MOVEMENT_TYPES }
        if (movement.isEmpty() || windowSeconds <= 0.0) {
            return listOf(packets)
        }

        val windowMs = (windowSeconds * 1000.0).roundToLong()
        val bounds = ArrayList<Pair<Long, Long>>()
        var start = movement.first().timestamp
        for (packet in movement.drop(1)) {
            if (packet.timestamp - start >= windowMs) {
                bounds += start to packet.timestamp
                start = packet.timestamp
            }
        }

        if (bounds.isEmpty()) return listOf(packets)

        return bounds.map { (from, to) ->
            packets.filter { it.timestamp in from..to }
        }
    }

    fun movementCount(packets: List<RecordedPacket>): Int =
        packets.count { it.type in FeatureExtractor.MOVEMENT_TYPES }
}
