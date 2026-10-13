package com.anpaiss.bettereta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGateTest {

    @Test
    fun `passes while the window is not full yet`() {
        val g = ProgressGate(windowTicks = 60, minProgressM = 20.0)
        repeat(60) { assertTrue(g.onTick(100.0)) }   // 60 ticks stationary, still no reference
    }

    @Test
    fun `stationary GPS jitter is blocked once the window is full`() {
        val g = ProgressGate(windowTicks = 60, minProgressM = 20.0)
        var pos = 100.0
        repeat(61) { g.onTick(pos) }
        // jitter of a few metres around the same spot
        repeat(120) { i ->
            pos = 100.0 + (i % 5) * 2.0
            assertFalse(g.onTick(pos))
        }
    }

    @Test
    fun `walking pace along the route passes`() {
        val g = ProgressGate(windowTicks = 60, minProgressM = 20.0)
        var pos = 0.0
        repeat(200) {
            pos += 0.6                      // 0.6 m/s → 36 m per minute
            val r = g.onTick(pos)
            if (it >= 61) assertTrue("tick $it", r)
        }
    }

    @Test
    fun `resumes after a stop and reset clears the reference`() {
        val g = ProgressGate(windowTicks = 10, minProgressM = 20.0)
        repeat(30) { g.onTick(500.0) }
        assertFalse(g.onTick(500.0))
        var pos = 500.0
        repeat(11) { pos += 5.0; g.onTick(pos) }
        assertTrue(g.onTick(pos + 5.0))     // 60 m over the window
        g.reset()
        assertTrue(g.onTick(0.0))           // reference gone → passes
    }
}
