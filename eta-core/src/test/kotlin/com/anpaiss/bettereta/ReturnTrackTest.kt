package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnTrackTest {

    private val lat0 = 46.0
    private val lon0 = 11.0
    private val dLat = 1.0 / 111_320.0                              // degrees per metre, north
    private val dLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))  // degrees per metre, east

    /** Feed a straight leg of [metres] in steps of 10 m, heading (north, east) in metres per step. */
    private fun ReturnTrack.leg(from: Pair<Double, Double>, northM: Double, eastM: Double, elevStart: Double = 0.0, elevEnd: Double = 0.0): Pair<Double, Double> {
        val len = Math.hypot(northM, eastM)
        val steps = (len / 10.0).toInt()
        var p = from
        for (i in 1..steps) {
            p = (from.first + northM * i / steps * dLat) to (from.second + eastM * i / steps * dLon)
            add(p.first, p.second, elevStart + (elevEnd - elevStart) * i / steps)
        }
        return p
    }

    @Test
    fun `out and back - the way home shrinks on the return leg and the gradients invert`() = runBlocking {
        val t = ReturnTrack()
        val start = lat0 to lon0
        val top = t.leg(start, 1000.0, 0.0, 500.0, 580.0)     // 1 km north, +80 m
        assertFalse(t.isFollowing)
        assertEquals(1000.0, t.returnDistanceM, 30.0)
        val segs = t.returnSegments()
        assertTrue("return is a descent", segs.all { it.gradePct < 0 })
        assertEquals(-8.0, segs.sumOf { it.gradePct * it.distanceM } / segs.sumOf { it.distanceM }, 1.0)

        t.leg(top, -500.0, 0.0, 580.0, 540.0)                  // halfway back
        assertTrue("following the spine on the way back", t.isFollowing)
        assertEquals(500.0, t.returnDistanceM, 40.0)
        assertEquals(1, t.rejoins)
        t.leg(top.first - 500 * dLat to top.second, -500.0, 0.0, 540.0, 500.0)
        assertTrue(t.returnDistanceM < 40.0)
    }

    @Test
    fun `a closed loop rejoins near the start and the way home is short`() = runBlocking {
        val t = ReturnTrack()
        var p = lat0 to lon0
        p = t.leg(p, 1000.0, 0.0)
        p = t.leg(p, 0.0, 1000.0)
        p = t.leg(p, -1000.0, 0.0)
        assertEquals(3000.0, t.returnDistanceM, 50.0)
        p = t.leg(p, 0.0, -1000.0)                              // back to the start
        assertTrue(t.isFollowing)
        assertTrue("way home ${t.returnDistanceM} m", t.returnDistanceM < 40.0)
        assertEquals(1, t.rejoins)
        // The whole loop is dropped: home is the first point, the way back is nil
        assertTrue("dropped ${t.droppedM} m", t.droppedM in 3850.0..4000.0)
    }

    @Test
    fun `leaving the spine again truncates it at the head and extends from there`() = runBlocking {
        val t = ReturnTrack()
        var p = lat0 to lon0
        val top = t.leg(p, 1000.0, 0.0)
        p = t.leg(top, -600.0, 0.0)                             // 600 m back down the same way
        assertTrue(t.isFollowing)
        p = t.leg(p, 0.0, 500.0)                                // then off east on new ground
        assertFalse(t.isFollowing)
        assertEquals(400.0 + 500.0, t.returnDistanceM, 50.0)
        // The abandoned 600 m beyond the head are gone from the spine
        assertEquals(t.returnDistanceM, t.returnWaypoints().let { w -> (1 until w.size).sumOf { i ->
            Math.hypot((w[i].lat - w[i - 1].lat) / dLat, (w[i].lon - w[i - 1].lon) / dLon) } }, 30.0)
    }

    @Test
    fun `hairpin legs 40 m apart do not count as a rejoin`() {
        val t = ReturnTrack()
        var p = lat0 to lon0
        p = t.leg(p, 0.0, 500.0)
        p = t.leg(p, 40.0, 0.0)
        p = t.leg(p, 0.0, -500.0)
        assertFalse(t.isFollowing)
        assertEquals(0, t.rejoins)
        assertEquals(1040.0, t.returnDistanceM, 30.0)
    }

    @Test
    fun `a rider standing still does not grow the spine`() {
        val t = ReturnTrack()
        t.leg(lat0 to lon0, 200.0, 0.0)
        val n = t.size
        repeat(50) { t.add(lat0 + 200 * dLat + (it % 3) * 3 * dLat, lon0 + (it % 2) * 4 * dLon, 0.0) }
        assertEquals(n, t.size)
    }
}
