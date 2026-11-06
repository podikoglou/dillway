package com.dillway.agent

import com.dillway.agent.model.CheatVerdict
import com.dillway.agent.model.InferenceReport
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Locks the "speak only when the verdict moves" rule of live watch. */
class VerdictChangeTest {

    @Test
    fun `only models with a window at or above the threshold are flagged`() {
        val report = report(
            verdict("speed", probability = 0.51),
            verdict("aim", probability = 0.49),
            verdict("reach", probability = 0.95, windows = 0),
        )

        assertEquals(setOf("speed"), report.flagged(0.5))
    }

    @Test
    fun `a model without a usable window never flags`() {
        val report = report(verdict("speed", probability = Double.NaN, windows = 0))

        assertTrue(report.flagged(0.5).isEmpty())
    }

    @Test
    fun `the threshold is inclusive`() {
        val report = report(verdict("speed", probability = 0.5))

        assertEquals(setOf("speed"), report.flagged(0.5))
    }

    @Test
    fun `a new flag enters and a steady flag stays quiet`() {
        val first = VerdictChange.between(emptySet(), setOf("speed"))
        assertEquals(setOf("speed"), first.entered)
        assertTrue(first.cleared.isEmpty())
        assertTrue(!first.isQuiet)

        assertTrue(VerdictChange.between(setOf("speed"), setOf("speed")).isQuiet)
    }

    @Test
    fun `a cleared flag is reported separately from a new one`() {
        val change = VerdictChange.between(setOf("speed", "aim"), setOf("reach"))

        assertEquals(setOf("reach"), change.entered)
        assertEquals(setOf("speed", "aim"), change.cleared)
        assertTrue(!change.isQuiet)
    }

    private fun report(vararg verdicts: CheatVerdict) = InferenceReport(
        movementPackets = 100,
        modelCount = verdicts.size,
        verdicts = verdicts.toList(),
        failures = emptyList(),
    )

    private fun verdict(
        cheat: String,
        probability: Double,
        windows: Int = 3,
    ) = CheatVerdict(
        cheat = cheat,
        version = "test",
        windowSeconds = 3.5,
        windows = windows,
        meanProbability = probability / 2.0,
        maxProbability = probability,
    )
}
