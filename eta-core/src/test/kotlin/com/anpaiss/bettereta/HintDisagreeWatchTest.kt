package com.anpaiss.bettereta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride of 11/10: the loop at the top was skipped, the Karoo re-matched in one
 * tick, we stayed on the outbound branch for 7.5 minutes while both said
 * "on route". The watch fires after a minute of disagreement with both sides
 * advancing; a Karoo stuck at "end of route" (20/09) never passes.
 */
class HintDisagreeWatchTest {

    private fun run(w: HintDisagreeWatch, ticks: Int, ours: (Int) -> Double, karoo: (Int) -> Double, speedMs: Double = 2.5): Int? {
        for (i in 1..ticks) if (w.onTick(ours(i), karoo(i), speedMs, 1.0)) return i
        return null
    }

    @Test
    fun `fires after a minute when both sides advance on different branches`() {
        val w = HintDisagreeWatch()
        // ours at km 15.1 climbing at 2.5 m/s, Karoo at km 19.5 climbing at the same pace
        val at = run(w, 120, { 15_100.0 + 2.5 * it }, { 19_500.0 + 2.5 * it })
        assertTrue("fired at $at", at == 60)
    }

    @Test
    fun `a gap under a kilometre never fires`() {
        val w = HintDisagreeWatch()
        assertTrue(run(w, 300, { 1000.0 + 2.5 * it }, { 1900.0 + 2.5 * it }) == null)
    }

    @Test
    fun `a Karoo stuck at the end of the route never fires`() {
        val w = HintDisagreeWatch()
        assertTrue(run(w, 300, { 2.5 * it }, { 30_000.0 }) == null)
    }

    @Test
    fun `a Karoo creeping at less than half our pace never fires`() {
        val w = HintDisagreeWatch()
        assertTrue(run(w, 300, { 2.5 * it }, { 20_000.0 + 1.0 * it }) == null)
    }

    @Test
    fun `standing still with a gap never fires and the count restarts when the gap closes`() {
        val w = HintDisagreeWatch()
        assertTrue(run(w, 300, { 1000.0 }, { 5000.0 }, speedMs = 0.0) == null)
        // gap closes for one tick, then reopens: a fresh minute is needed
        assertFalse(w.onTick(5000.0, 5000.0, 2.5, 1.0))
        val at = run(w, 120, { 1000.0 + 2.5 * it }, { 5000.0 + 2.5 * it })
        assertTrue("fired at $at", at == 60)
    }
}
