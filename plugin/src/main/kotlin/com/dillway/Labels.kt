package com.dillway

import java.util.Locale

/**
 * Recording labels. A label is also the dataset directory name and, for
 * cheats, the model name under the models prefix, so it must stay safe for
 * both. `legit` is the negative class; every other label is a cheat.
 */
object Labels {
    const val LEGIT = "legit"

    /** Local-only label for sessions interrupted by a server shutdown. */
    const val UNLABELED = "unlabeled"

    private val UNSAFE = Regex("[^a-z0-9_-]")

    /** Lowercases and replaces anything unsafe for a directory or object key. */
    fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(UNSAFE, "_")
}
