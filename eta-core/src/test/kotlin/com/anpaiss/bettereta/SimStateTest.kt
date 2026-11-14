package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimStateTest {

    @Test
    fun `not active until a read says so`() {
        val s = SimState(refreshMs = 10_000L)
        assertFalse(s.active)
        assertTrue(s.claimRefresh(1_000L))
        s.onRead(null, 1_000L)          // KarooSim not installed
        assertFalse(s.active)
    }

    @Test
    fun `reads at most every refresh interval and never twice at once`() {
        val s = SimState(refreshMs = 10_000L)
        assertTrue(s.claimRefresh(1L))
        assertFalse(s.claimRefresh(2L))            // read still running
        s.onRead(true, 5_000L)
        assertTrue(s.active)
        assertFalse(s.claimRefresh(14_999L))
        assertTrue(s.claimRefresh(15_000L))
    }

    @Test
    fun `broadcast updates at once and restarts the interval`() {
        val s = SimState(refreshMs = 10_000L)
        s.onBroadcast(true, 100L)
        assertTrue(s.active)
        assertFalse(s.claimRefresh(5_000L))
        s.onBroadcast(false, 6_000L)
        assertFalse(s.active)
    }

    @Test
    fun `failed read is not active`() {
        val s = SimState()
        s.onBroadcast(true, 1L)
        s.onRead(null, 20_000L)
        assertFalse(s.active)
    }

    @Test
    fun `a moment of simulation marks the whole ride, logged once`() {
        val g = SimRideGuard()
        assertFalse(g.newRide(false))
        assertTrue(g.learning)
        assertFalse(g.observe(false))
        assertTrue(g.observe(true))
        assertFalse(g.observe(true))   // log once
        assertFalse(g.observe(false))  // real GPS again: the ride stays simulated
        assertFalse(g.learning)
        assertFalse(g.newRide(false))  // next ride learns again
        assertTrue(g.learning)
    }

    @Test
    fun `ride started in simulation`() {
        val g = SimRideGuard()
        assertTrue(g.newRide(true))
        assertFalse(g.observe(true))
        assertFalse(g.learning)
    }

    @Test
    fun `speed model and way memory ignore samples of a simulated ride`() {
        val model = AdaptiveSpeedModel()
        val memory = WaySpeedMemory()
        val g = SimRideGuard()
        g.newRide(true)
        val before = model.snapshot()
        repeat(200) {
            assertFalse(g.learn { model.update(8.0, 5.0, Surface.GRAVEL) })
        }
        assertFalse(g.learn { memory.record(42L, 6.0, 1_000L, 500.0) })
        assertEquals(before, model.snapshot())
        assertEquals(0, memory.size)

        g.newRide(false)
        repeat(200) { assertTrue(g.learn { model.update(8.0, 5.0, Surface.GRAVEL) }) }
        assertTrue(g.learn { memory.record(42L, 6.0, 1_000L, 500.0) })
        assertTrue(model.snapshot() != before)
        assertEquals(1, memory.size)
    }
}
