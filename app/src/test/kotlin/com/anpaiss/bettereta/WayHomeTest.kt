package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WayHomeTest {

    private val lat0 = 46.0
    private val lon0 = 11.0
    private val dLat = 1.0 / 111_320.0

    /** A model that rides 5 m/s on the flat, 2.5 up an 8 % climb, 10 down it. */
    private fun model(): AdaptiveSpeedModel {
        val m = AdaptiveSpeedModel()
        // In blocks: the model smooths the gradient, alternating samples would all land in one bucket
        for (i in 0 until 400) m.update(5.0, 0.0, Surface.UNKNOWN)
        for (i in 0 until 400) m.update(2.5, 8.0, Surface.UNKNOWN)
        for (i in 0 until 400) m.update(10.0, -8.0, Surface.UNKNOWN)
        m.newRide()
        return m
    }

    @Test
    fun `climbing out, the allowance counts the fast descent home at its measured growth`() = runBlocking {
        var now = 1_000_000_000_000L
        val wh = WayHome(nowMs = { now })
        val engine = EtaEngine(model(), SegmentPipeline())
        val limit = now + 60 * 60_000L   // one hour of light
        var lat = lat0
        var last = WayHomeState()
        // 20 minutes north at 2.5 m/s on an 8 % climb, one tick per second
        repeat(20 * 60) {
            now += 1000
            lat += 2.5 * dLat
            if (wh.onTick(lat to lon0, 2.5, 8.0, 1.0, true, Surface.UNKNOWN, 0L)) last = wh.recompute(engine, limit)
        }
        assertFalse(last.following)
        assertEquals(3000.0, last.returnDistM, 150.0)   // recomputed every 30 s: up to 75 m behind
        // Home is 3 km downhill at 10 m/s: five minutes
        assertTrue("return ${last.returnSec} s", kotlin.math.abs(last.returnSec - 300L) <= 40L)
        // Every outbound second adds 0.25 s of way back: growth near 0.25
        assertEquals(0.25, last.growth, 0.08)
        val a = last.allowanceSec!!
        // 40 min of light left minus 5 of way back, divided by 1.25
        assertTrue("allowance $a s", kotlin.math.abs(a - ((40 * 60 - 300) / 1.25).toLong()) <= 90L)
    }

    @Test
    fun `on the way back there is no allowance and the arrival follows the estimate`() = runBlocking {
        var now = 1_000_000_000_000L
        val wh = WayHome(nowMs = { now })
        val engine = EtaEngine(model(), SegmentPipeline())
        var lat = lat0
        repeat(20 * 60) { now += 1000; lat += 2.5 * dLat; if (wh.onTick(lat to lon0, 2.5, 8.0, 1.0, true, Surface.UNKNOWN, 0L)) wh.recompute(engine, 0L) }
        var last = WayHomeState()
        repeat(5 * 60) { now += 1000; lat -= 5.0 * dLat; if (wh.onTick(lat to lon0, 5.0, -8.0, 1.0, true, Surface.UNKNOWN, 0L)) last = wh.recompute(engine, now + 3_600_000L) }
        assertTrue(last.following)
        assertNull(last.allowanceSec)
        assertEquals(1500.0, last.returnDistM, 200.0)
        // Arrival = time of the last recompute + way back; the recompute is at most 30 s old
        assertTrue(now - (last.arrivalEpochMs - last.returnSec * 1000L) in 0L..30_000L)
    }

    @Test
    fun `alerts fire once per crossing and re-arm after five minutes of slack`() {
        val g = WayHomeAlertGate(leadSec = 15 * 60L)
        val dusk = 2_000_000_000_000L
        fun s(allowance: Long?, arrival: Long = dusk - 3_600_000L, following: Boolean = false) =
            WayHomeState(returnSec = 600, arrivalEpochMs = arrival, allowanceSec = allowance, following = following)
        assertTrue(g.onState(s(20 * 60L), dusk).isEmpty())
        assertEquals(listOf(WayHomeAlertGate.Alert.TURN_SOON), g.onState(s(14 * 60L), dusk))
        assertTrue("not twice", g.onState(s(13 * 60L), dusk).isEmpty())
        assertTrue("wobble back over the line does not re-arm", g.onState(s(16 * 60L), dusk).isEmpty())
        assertTrue(g.onState(s(14 * 60L), dusk).isEmpty())
        assertEquals(listOf(WayHomeAlertGate.Alert.TURN_NOW), g.onState(s(-60L), dusk))
        // Back over lead + 5 min: re-armed
        assertTrue(g.onState(s(21 * 60L), dusk).isEmpty())
        assertEquals(listOf(WayHomeAlertGate.Alert.TURN_SOON), g.onState(s(10 * 60L), dusk))
        // No turn-back alerts on the way back, but "after dusk" still fires
        assertTrue(g.onState(s(-60L, following = true), dusk).isEmpty())
        assertEquals(listOf(WayHomeAlertGate.Alert.AFTER_DUSK), g.onState(s(null, arrival = dusk + 60_000L, following = true), dusk))
        assertTrue(g.onState(s(null, arrival = dusk + 120_000L, following = true), dusk).isEmpty())
    }

    @Test
    fun `without sun times the field still has the way back`() = runBlocking {
        var now = 1_000_000_000_000L
        val wh = WayHome(nowMs = { now })
        val engine = EtaEngine(model(), SegmentPipeline())
        var lat = lat0
        var last = WayHomeState()
        repeat(10 * 60) { now += 1000; lat += 5.0 * dLat; if (wh.onTick(lat to lon0, 5.0, 0.0, 1.0, true, Surface.UNKNOWN, 0L)) last = wh.recompute(engine, 0L) }
        assertNotNull(last.returnSec)
        assertTrue("return ${last.returnSec} s", kotlin.math.abs(last.returnSec - 600L) <= 60L)   // up to 30 s of track not yet counted
        assertNull(last.allowanceSec)
        assertEquals(0L, last.limitEpochMs)
    }
}
