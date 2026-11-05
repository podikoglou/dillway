package com.dillway

import com.dillway.recording.RecordedPacket
import java.util.UUID

/** Holds the in-progress state of one player recording. */
class RecordingSession(
    val playerId: UUID,
    val type: RecordingType,
    val started: Long = System.currentTimeMillis(),
) {
    val packets: MutableList<RecordedPacket> = mutableListOf()
}
