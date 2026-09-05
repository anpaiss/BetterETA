package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Ride of 05/09: the parking sat 310 m from the route start but a few metres
 * from km 7.6 of the loop; the initial rescue locked onto km 7.6 and the
 * position kept advancing on the wrong branch for 32 minutes while the Karoo
 * itself reported ON_ROUTE=0. The Karoo's own along-route position is the
 * tiebreaker the pipeline lacked.
 */
class RelocateTest {

    // Out-and-back on a meridian: north for 5 km (50 points every 100 m), then
    // back over the same points. Any fix on the line has two candidates:
    // km x on the way out and km (10 - x) on the way back.
    private val stepDeg = 100.0 / 111_320.0
    private fun outAndBack(): List<RouteWaypoint> {
        val out  = (0..50).map { RouteWaypoint(45.0 + it * stepDeg, 9.0, 300.0) }
        val back = (49 downTo 0).map { RouteWaypoint(45.0 + it * stepDeg, 9.0, 300.0) }
        return out + back
    }
    private fun latAtM(m: Double) = 45.0 + (m / 100.0) * stepDeg

    private fun assertApprox(expected: Double, actual: Double, delta: Double = 120.0) =
        assertTrue("expected ≈$expected, got $actual", abs(expected - actual) <= delta)

    @Test
    fun `initial rescue prefers the candidate coherent with the Karoo hint`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        // Fix at km 2.5 on the line (outside the 2 km local window): candidates
        // 2500 (out) and 7500 (back). Never located, so the hint decides:
        // the Karoo says we are at km 7.4.
        repeat(3) { p.snapToLocation(latAtM(2500.0), 9.0, hintM = 7400.0) }
        assertApprox(7500.0, p.distanceAlongRouteM())
        assertTrue(p.isLocated())
    }

    @Test
    fun `initial rescue without a hint waits a minute, then picks the candidate nearest to the start`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        repeat(60) { p.snapToLocation(latAtM(2500.0), 9.0) }
        assertTrue("still waiting for the Karoo hint", !p.isLocated())
        repeat(3) { p.snapToLocation(latAtM(2500.0), 9.0) }
        assertTrue(p.isLocated())
        assertApprox(2500.0, p.distanceAlongRouteM())
    }

    @Test
    fun `near the start with no hint, the start wins over a closer inner waypoint`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        // 350 m east of the line at km 0: no waypoint within 100 m, start within 400 m
        val lonEast = 9.0 + 350.0 / (111_320.0 * Math.cos(Math.toRadians(45.0)))
        p.snapToLocation(45.0, lonEast)
        assertTrue(p.isLocated())
        assertApprox(0.0, p.distanceAlongRouteM(), 1.0)
        // The Karoo's first hint says "start" too: no relocation to km 5 (the turnaround is far anyway)
        assertEquals(0.0, p.relocateWithHint(45.0, lonEast, hintM = 0.0), 0.0)
    }

    @Test
    fun `relocateWithHint keeps the position when the only nearby waypoint is farther from the hint`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        p.snapToLocation(latAtM(50.0), 9.0)          // local window: locked at km 0.05
        assertApprox(50.0, p.distanceAlongRouteM())
        // Fix now sits on km 7.5 (back leg) but the hint says km 0: km 7.5 is the only
        // waypoint near the fix and it is farther from the hint than where we are → stay
        assertEquals(0.0, p.relocateWithHint(latAtM(2500.0), 9.0, hintM = 0.0), 0.0)
        assertApprox(50.0, p.distanceAlongRouteM())
    }

    @Test
    fun `relocateWithHint jumps to the branch the Karoo indicates`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        repeat(63) { p.snapToLocation(latAtM(2500.0), 9.0) }     // locked on km 2.5 (out) after the hint wait
        assertApprox(2500.0, p.distanceAlongRouteM())
        val corr = p.relocateWithHint(latAtM(2500.0), 9.0, hintM = 7500.0)
        assertApprox(5000.0, corr)
        assertApprox(7500.0, p.distanceAlongRouteM())
    }

    @Test
    fun `relocateWithHint is a no-op when already coherent or when no waypoint is near`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(outAndBack())
        repeat(63) { p.snapToLocation(latAtM(2500.0), 9.0) }
        assertEquals(0.0, p.relocateWithHint(latAtM(2500.0), 9.0, hintM = 2600.0), 0.0)   // < 300 m: untouched
        assertEquals(0.0, p.relocateWithHint(45.0, 9.5, hintM = 7500.0), 0.0)             // 40 km east of the line
        assertApprox(2500.0, p.distanceAlongRouteM())
    }
}
