package com.dillway

import com.dillway.model.CheatVerdict
import com.dillway.model.InferenceReport

/** A model that could not build a window has no verdict yet, so it never flags. */
fun CheatVerdict.isFlagged(threshold: Double): Boolean =
    windows > 0 && maxProbability >= threshold

/** Cheats that [report] flags at [threshold]. */
fun InferenceReport.flagged(threshold: Double): Set<String> =
    verdicts.filter { it.isFlagged(threshold) }
        .mapTo(LinkedHashSet()) { it.cheat }

/**
 * What changed between two consecutive predictions.
 *
 * Live watch announces only this: a steady verdict stays silent, and a flag is
 * reported once instead of on every interval.
 */
data class VerdictChange(
    val entered: Set<String> = emptySet(),
    val cleared: Set<String> = emptySet(),
) {
    val isQuiet: Boolean get() = entered.isEmpty() && cleared.isEmpty()

    companion object {
        fun between(previous: Set<String>, current: Set<String>) =
            VerdictChange(entered = current - previous, cleared = previous - current)
    }
}
