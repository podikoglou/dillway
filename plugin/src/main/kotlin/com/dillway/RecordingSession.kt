package com.dillway

import java.util.UUID

/** Holds the in-progress state of one player recording. */
class RecordingSession(
    val playerId: UUID,
    val type: RecordingType,
    val started: Long = System.currentTimeMillis(),
) {
    // Entries are untyped lookup maps until the typed recording model lands.
    val packets: MutableList<Map<String, Any>> = mutableListOf()
}
