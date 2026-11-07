package com.dillway.agent

import com.dillway.agent.recording.RecordedPacket
import java.util.UUID

/** Holds the in-progress state of one player recording. */
class RecordingSession(
    val playerId: UUID,
    val started: Long = System.currentTimeMillis(),
) {
    val packets: MutableList<RecordedPacket> = mutableListOf()
}

/**
 * A recording that has left the event pipeline but has not been saved yet.
 * The admin still has to choose a label or ask for a prediction.
 */
data class StoppedRecording(
    val playerName: String,
    val session: RecordingSession,
)

/** Why `/dill rec start` did or did not open a session. */
enum class RecordingStart {
    STARTED,
    ALREADY_RECORDING,
}
