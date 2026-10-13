package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-way memory: the rung above the gradient buckets. Speeds are stored as
 * base speeds (already divided by the day factor), the last three passes
 * count, and the direction is part of the key.
 */
class WaySpeedMemoryTest {

    @Test
    fun `direction is part of the key`() {
        val fwd = WaySpeedMemory.wayKey(123L, forward = true)
        val bwd = WaySpeedMemory.wayKey(123L, forward = false)
        assertTrue(fwd != bwd)
        assertEquals(123L, WaySpeedMemory.wayIdOf(fwd))
        assertEquals(123L, WaySpeedMemory.wayIdOf(bwd))
        assertTrue(WaySpeedMemory.isForward(fwd))
        assertTrue(!WaySpeedMemory.isForward(bwd))
    }

    @Test
    fun `estimate averages the last three passes and counts them all`() {
        val m = WaySpeedMemory()
        val k = WaySpeedMemory.wayKey(7L, true)
        assertNull(m.estimate(k))
        m.record(k, 4.0, 1L)
        m.record(k, 6.0, 2L)
        assertEquals(5.0, m.estimate(k)!!.speedMs, 1e-9)
        assertEquals(2, m.estimate(k)!!.passes)
        m.record(k, 8.0, 3L)
        m.record(k, 10.0, 4L)                       // the 4.0 pass falls out of the window
        assertEquals(8.0, m.estimate(k)!!.speedMs, 1e-9)
        assertEquals(4, m.estimate(k)!!.passes)
        assertNull(m.estimate(WaySpeedMemory.wayKey(7L, false)))   // other direction untouched
    }

    @Test
    fun `serialization round-trips and garbage loads as empty`() {
        val m = WaySpeedMemory()
        m.record(WaySpeedMemory.wayKey(7L, true), 4.25, 10L)
        m.record(WaySpeedMemory.wayKey(7L, true), 4.75, 20L)
        m.record(WaySpeedMemory.wayKey(9L, false), 2.0, 30L)
        val back = WaySpeedMemory.parse(m.serialize())
        assertEquals(2, back.size)
        assertEquals(4.5, back.estimate(WaySpeedMemory.wayKey(7L, true))!!.speedMs, 1e-9)
        assertEquals(2, back.estimate(WaySpeedMemory.wayKey(7L, true))!!.passes)
        assertEquals(2.0, back.estimate(WaySpeedMemory.wayKey(9L, false))!!.speedMs, 1e-9)
        assertEquals(0, WaySpeedMemory.parse("").size)
        assertEquals(0, WaySpeedMemory.parse("WAYS1\nK x").size)
    }

    @Test
    fun `oldest entries are evicted past the cap`() {
        val m = WaySpeedMemory(maxEntries = 3)
        for (i in 1L..5L) m.record(WaySpeedMemory.wayKey(i, true), 3.0, i)
        assertEquals(3, m.size)
        assertNull(m.estimate(WaySpeedMemory.wayKey(1L, true)))
        assertNull(m.estimate(WaySpeedMemory.wayKey(2L, true)))
        assertTrue(m.estimate(WaySpeedMemory.wayKey(5L, true)) != null)
    }

    @Test
    fun `zero key and non-positive speeds are ignored`() {
        val m = WaySpeedMemory()
        m.record(0L, 5.0, 1L)
        m.record(WaySpeedMemory.wayKey(3L, true), 0.0, 1L)
        assertEquals(0, m.size)
    }

    @Test
    fun `a pass split by a pause is one pass weighted by time`() {
        val m = WaySpeedMemory()
        val key = WaySpeedMemory.wayKey(42L, true)
        // Ride 19/09: 597 m at 2.6 m/s, auto-pause, 85 m at 3.1 m/s on the same way
        assertEquals(false, m.record(key, 2.6, 1_000_000L, 597.0))
        assertEquals(true,  m.record(key, 3.1, 1_000_000L + 60_000L, 85.0))
        val est = m.estimate(key)!!
        assertEquals(1, est.passes)
        assertEquals(682.0 / (597.0 / 2.6 + 85.0 / 3.1), est.speedMs, 1e-9)   // 2.654, not the 2.85 of a plain mean
    }

    @Test
    fun `a later pass on the same way is a new pass`() {
        val m = WaySpeedMemory()
        val key = WaySpeedMemory.wayKey(42L, true)
        m.record(key, 2.6, 1_000_000L, 597.0)
        assertEquals(false, m.record(key, 3.0, 1_000_000L + WaySpeedMemory.MERGE_WINDOW_MS + 1, 600.0))
        assertEquals(2, m.estimate(key)!!.passes)
        assertEquals(2.8, m.estimate(key)!!.speedMs, 1e-9)
        // An import replaying an older ride (time going backwards) never merges
        assertEquals(false, m.record(key, 3.2, 500_000L, 600.0))
        assertEquals(3, m.estimate(key)!!.passes)
    }

    @Test
    fun `entries loaded from file and callers without a distance do not merge`() {
        val m = WaySpeedMemory()
        val key = WaySpeedMemory.wayKey(7L, false)
        m.record(key, 4.0, 1_000L, 300.0)
        val back = WaySpeedMemory.parse(m.serialize())
        assertEquals(false, back.record(key, 5.0, 2_000L, 300.0))     // distance of the stored pass unknown
        assertEquals(2, back.estimate(key)!!.passes)
        assertEquals(false, m.record(key, 5.0, 2_000L))               // legacy call, no distance
        assertEquals(2, m.estimate(key)!!.passes)
    }
}
