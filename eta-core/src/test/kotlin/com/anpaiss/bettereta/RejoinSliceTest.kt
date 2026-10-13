package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Off-route ETA = detour back to the route + the route from the rejoin point.
 * The rejoin point is a distance along the route, so the pipeline must be able
 * to slice the segments from an arbitrary point without moving the rider.
 */
class RejoinSliceTest {

    // Three flat legs of ~1113 m each (0.01° of latitude)
    private fun route() = listOf(
        RouteWaypoint(45.00, 9.0, 300.0),
        RouteWaypoint(45.01, 9.0, 300.0),
        RouteWaypoint(45.02, 9.0, 300.0),
        RouteWaypoint(45.03, 9.0, 300.0),
    )

    @Test
    fun `slice from inside a segment keeps only the part ahead`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(route())
        val total = p.remainingSegments().sumOf { it.distanceM }
        val from  = 1500.0
        val slice = p.remainingSegmentsFrom(from)
        assertEquals(total - from, slice.sumOf { it.distanceM }, 1.0)   // legs of equal grade merge into one segment
    }

    @Test
    fun `slice from zero is the whole route and from the end is empty`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(route())
        val all = p.remainingSegments()
        assertEquals(all.sumOf { it.distanceM }, p.remainingSegmentsFrom(0.0).sumOf { it.distanceM }, 0.001)
        assertTrue(p.remainingSegmentsFrom(all.sumOf { it.distanceM } + 10.0).isEmpty())
    }

    @Test
    fun `slicing does not move the rider`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(route())
        p.advanceByDistance(400.0)
        p.remainingSegmentsFrom(2500.0)
        assertEquals(400.0, p.distanceAlongRouteM(), 0.001)
    }
}
