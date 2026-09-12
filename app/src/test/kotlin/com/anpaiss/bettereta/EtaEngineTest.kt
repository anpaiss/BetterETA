package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaEngineTest {

    private suspend fun engine(waypoints: List<RouteWaypoint> = emptyList()): Triple<EtaEngine, AdaptiveSpeedModel, SegmentPipeline> {
        val model    = AdaptiveSpeedModel()
        val pipeline = SegmentPipeline().also { it.setRoute(waypoints) }
        return Triple(EtaEngine(model, pipeline), model, pipeline)
    }

    // ── test ──────────────────────────────────────────────────────────

    @Test
    fun `empty route returns -1`() = runBlocking {
        val (eng) = engine()
        assertEquals(-1L, eng.computeRemainingSeconds())
    }

    @Test
    fun `single flat segment at default speed`() = runBlocking {
        // ~1111m, velocità default 5 m/s → ~222s
        val (eng) = engine(listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),
        ))
        val secs = eng.computeRemainingSeconds()
        assertTrue("secs=$secs deve essere in range 150-300", secs in 150L..300L)
    }

    @Test
    fun `trained model at higher speed gives shorter eta`() = runBlocking {
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),
        )
        val (engFast, modelFast) = engine(waypoints)
        val (engSlow, modelSlow) = engine(waypoints)

        repeat(10) { modelFast.update(speedMs = 10.0, gradePct = 0.0) }
        repeat(10) { modelSlow.update(speedMs = 3.0, gradePct = 0.0) }

        val secsFast = engFast.computeRemainingSeconds()
        val secsSlow = engSlow.computeRemainingSeconds()
        assertTrue("fast=$secsFast deve essere < slow=$secsSlow", secsFast < secsSlow)
    }

    @Test
    fun `after advancing half route eta is halved approximately`() = runBlocking {
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),
        )
        val model    = AdaptiveSpeedModel()
        val pipeline = SegmentPipeline().also { it.setRoute(waypoints) }
        val eng      = EtaEngine(model, pipeline)

        val totalSecs = eng.computeRemainingSeconds()
        val totalDist = pipeline.remainingSegments().sumOf { it.distanceM }

        pipeline.advanceByDistance(totalDist / 2.0)

        val halfSecs = eng.computeRemainingSeconds()
        // Metà percorso → circa metà del tempo (±30%)
        val ratio = halfSecs.toDouble() / totalSecs.toDouble()
        assertTrue("ratio=$ratio deve essere in 0.3..0.7", ratio in 0.3..0.7)
    }

    @Test
    fun `multi-segment route with varying grade sums correctly`() = runBlocking {
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),  // pianura
            RouteWaypoint(45.0100, 9.0, 300.0),
            RouteWaypoint(45.0200, 9.0, 400.0),  // salita ~9%
            RouteWaypoint(45.0300, 9.0, 300.0),  // discesa ~9%
        )
        val (eng) = engine(waypoints)
        val secs = eng.computeRemainingSeconds()
        // Il percorso ha ~3.3km, deve richiedere almeno qualche minuto
        assertTrue("secs=$secs deve essere > 200", secs > 200L)
        assertTrue("secs=$secs non deve essere negativo", secs > 0L)
    }

    @Test
    fun `completed route returns -1`() = runBlocking {
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),
        )
        val model    = AdaptiveSpeedModel()
        val pipeline = SegmentPipeline().also { it.setRoute(waypoints) }
        val eng      = EtaEngine(model, pipeline)

        pipeline.advanceByDistance(999_999.0)

        assertEquals(-1L, eng.computeRemainingSeconds())
    }

    // ── physicsFirst: la modalità dello shadow engine ─────────────────

    private val climb = listOf(          // ~1111 m con +89 m: ~8 %
        RouteWaypoint(45.0000, 9.0, 300.0),
        RouteWaypoint(45.0100, 9.0, 389.0),
    )

    @Test
    fun `untrained physics leaves the eta identical to the bucket engine`() = runBlocking {
        val model    = AdaptiveSpeedModel()
        val pipeline = SegmentPipeline().also { it.setRoute(climb) }
        val segs     = pipeline.remainingSegments()
        val plain    = EtaEngine(model, pipeline).computeBreakdown(segs)
        val shadow   = EtaEngine(model, pipeline, physicsFirst = PhysicsSpeedModel()).computeBreakdown(segs)
        assertEquals(plain.seconds, shadow.seconds)
        assertEquals(0.0, shadow.physicsDistanceShare, 1e-9)
    }

    @Test
    fun `trained physics answers the climb and the production engine is untouched`() = runBlocking {
        val model    = AdaptiveSpeedModel()
        val pipeline = SegmentPipeline().also { it.setRoute(climb) }
        val segs     = pipeline.remainingSegments()
        val physics  = PhysicsSpeedModel().apply {
            repeat(PhysicsSpeedModel.MIN_POWER_SAMPLES) { update(2.0, 8.0, Surface.PAVED) }   // 7,2 km/h sull'8 %
        }
        val before   = EtaEngine(model, pipeline).computeBreakdown(segs)
        val shadow   = EtaEngine(model, pipeline, physicsFirst = physics).computeBreakdown(segs)
        val after    = EtaEngine(model, pipeline).computeBreakdown(segs)

        assertEquals(before.seconds, after.seconds)                 // il motore di produzione non cambia
        assertEquals(0.0, before.physicsDistanceShare, 1e-9)
        assertTrue("share=${shadow.physicsDistanceShare}", shadow.physicsDistanceShare > 0.99)
        // ~1111 m a ~2 m/s: ben distinto dal bucket di default (fallback 0.65 × 5 m/s)
        assertTrue("shadow=${shadow.seconds} before=${before.seconds}", shadow.seconds > before.seconds)
        assertTrue("shadow=${shadow.seconds}", shadow.seconds in 450L..650L)
    }
}
