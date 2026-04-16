package com.anpaiss.karoobettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPipelineTest {

    private fun assertApprox(expected: Double, actual: Double, delta: Double = 1.0) =
        assertEquals(expected, actual, delta)

    // ── helpers percorso ──────────────────────────────────────────────

    /** ~1111m a nord, pianura */
    private fun flatWaypoints() = listOf(
        RouteWaypoint(45.0000, 9.0, 300.0),
        RouteWaypoint(45.0100, 9.0, 300.0),
    )

    /** ~1111m + ~1111m = ~2222m totali, pianura */
    private fun twoSegmentRoute() = listOf(
        RouteWaypoint(45.0000, 9.0, 300.0),
        RouteWaypoint(45.0100, 9.0, 300.0),
        RouteWaypoint(45.0200, 9.0, 300.0),
    )

    // ── test ──────────────────────────────────────────────────────────

    @Test
    fun `empty route has no segments`() {
        val p = SegmentPipeline()
        p.setRoute(emptyList())
        assertFalse(p.hasSegments())
        assertTrue(p.remainingSegments().isEmpty())
    }

    @Test
    fun `single waypoint has no segments`() {
        val p = SegmentPipeline()
        p.setRoute(listOf(RouteWaypoint(45.0, 9.0, 300.0)))
        assertFalse(p.hasSegments())
    }

    @Test
    fun `flat two-point route builds one segment`() {
        val p = SegmentPipeline()
        p.setRoute(flatWaypoints())
        assertTrue(p.hasSegments())
        // 0.01° lat ≈ 1111m
        val segs = p.remainingSegments()
        assertEquals(1, segs.size)
        assertTrue(segs[0].distanceM > 1000.0)
        assertApprox(0.0, segs[0].gradePct, delta = 0.5)
    }

    @Test
    fun `advance past full distance leaves no segments`() {
        val p = SegmentPipeline()
        p.setRoute(flatWaypoints())
        p.advanceByDistance(999_999.0)
        assertFalse(p.hasSegments())
        assertTrue(p.remainingSegments().isEmpty())
    }

    @Test
    fun `partial advance reduces remaining distance correctly`() {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val totalBefore = p.remainingSegments().sumOf { it.distanceM }

        p.advanceByDistance(500.0)

        val totalAfter = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(totalBefore - 500.0, totalAfter, delta = 2.0)
    }

    @Test
    fun `advance across segment boundary continues into next segment`() {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val firstSegDist = p.remainingSegments().first().distanceM

        // Avanza oltre il primo segmento
        p.advanceByDistance(firstSegDist + 200.0)

        assertTrue(p.hasSegments())
        val remaining = p.remainingSegments().sumOf { it.distanceM }
        // Deve restare circa (secondo segmento - 200m)
        val secondSegDist = twoSegmentRoute().let {
            // distanza approssimativa secondo segmento ≈ firstSegDist
            firstSegDist
        }
        assertTrue(remaining < secondSegDist - 100.0)
    }

    @Test
    fun `climbing route has positive grade`() {
        val p = SegmentPipeline()
        // ~1111m, +100m di dislivello → ~9% di pendenza
        p.setRoute(listOf(
            RouteWaypoint(45.0000, 9.0, 100.0),
            RouteWaypoint(45.0100, 9.0, 200.0),
        ))
        val grade = p.remainingSegments().first().gradePct
        assertTrue("grade=$grade deve essere positivo", grade > 4.0)
        assertTrue("grade=$grade deve essere < 20", grade < 20.0)
    }

    @Test
    fun `descending route has negative grade`() {
        val p = SegmentPipeline()
        p.setRoute(listOf(
            RouteWaypoint(45.0000, 9.0, 200.0),
            RouteWaypoint(45.0100, 9.0, 100.0),
        ))
        val grade = p.remainingSegments().first().gradePct
        assertTrue("grade=$grade deve essere negativo", grade < -4.0)
    }

    @Test
    fun `reset restores position to start`() {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val totalBefore = p.remainingSegments().sumOf { it.distanceM }

        p.advanceByDistance(500.0)
        p.reset()

        val totalAfter = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(totalBefore, totalAfter, delta = 1.0)
    }

    @Test
    fun `adjacent similar grade segments are merged by simplify`() {
        val p = SegmentPipeline()
        // 5 punti con pendenza quasi identica → dovrebbero essere mergiati in meno segmenti
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 100.0),
            RouteWaypoint(45.0100, 9.0, 110.0),
            RouteWaypoint(45.0200, 9.0, 120.0),
            RouteWaypoint(45.0300, 9.0, 130.0),
            RouteWaypoint(45.0400, 9.0, 140.0),
        )
        p.setRoute(waypoints)
        // La semplificazione deve ridurre 4 segmenti a meno di 4
        assertTrue(p.remainingSegments().size < 4)
    }
}
