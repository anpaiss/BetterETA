package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FarthestPointTest {

    private fun assertApprox(expected: Double, actual: Double, delta: Double = 1.0) =
        assertEquals(expected, actual, delta)

    @Test
    fun `out-and-back turnaround is the farthest point, not the highest`() = runBlocking {
        // Va a nord ~1111m (salendo), poi devia a est ~500m scendendo:
        // il punto più lontano (in linea d'aria) dalla partenza è l'ultimo,
        // non il più alto (che è a metà, sulla svolta nord)
        val wps = listOf(
            RouteWaypoint(45.0000, 9.0000, 300.0),   // partenza
            RouteWaypoint(45.0100, 9.0000, 500.0),   // punto più alto, a metà strada
            RouteWaypoint(45.0100, 9.0090, 200.0),   // punto più lontano geograficamente
        )
        val p = SegmentPipeline()
        p.setRoute(wps)

        // Distanza cumulativa totale del percorso: ~1111 + ~700 = ~1811m
        val totalRemaining = p.remainingSegments().sumOf { it.distanceM }
        val toFarthest = p.distanceToFarthestPointM()

        // Il punto più lontano è l'ULTIMO waypoint: la distanza rimanente per
        // raggiungerlo deve coincidere con la distanza totale del percorso,
        // non fermarsi al punto più alto (a metà)
        assertApprox(totalRemaining, toFarthest, 5.0)
    }

    @Test
    fun `distance to farthest point decreases while approaching and floors at zero after`() = runBlocking {
        val wps = listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),  // ~1111m, punto più lontano
        )
        val p = SegmentPipeline()
        p.setRoute(wps)

        val atStart = p.distanceToFarthestPointM()
        p.advanceByDistance(600.0)
        val halfway = p.distanceToFarthestPointM()
        p.advanceByDistance(1000.0)  // ben oltre il punto più lontano
        val afterPassing = p.distanceToFarthestPointM()

        assertApprox(1111.0, atStart, 10.0)
        assertApprox(511.0, halfway, 10.0)
        assertEquals(0.0, afterPassing, 0.001)
    }

    @Test
    fun `no route yields -1`() = runBlocking {
        val p = SegmentPipeline()
        assertEquals(-1.0, p.distanceToFarthestPointM(), 0.001)
    }
}
