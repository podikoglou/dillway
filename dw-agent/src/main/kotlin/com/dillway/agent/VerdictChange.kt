package com.dillway.agent

import com.dillway.agent.model.CheatVerdict
import com.dillway.agent.model.InferenceReport

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
 * A chat watch announces only this, so the full report is printed once per
 * verdict change instead of on every interval.
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
