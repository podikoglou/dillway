package com.dillway

/** A recording label. The label also selects the output folder. */
enum class RecordingType(val folder: String) {
    LEGIT("legit"),
    SPEED("speed"),
}
