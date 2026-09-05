package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route's direction along each OSM way must come out of the match, and
 * the ETA engine must blend the remembered speed in, scaled by the day factor
 * and weighted by the number of passes.
 */
class WayDirectionTest {

    private val lat0 = 46.0
    private val lon0 = 11.0
    private val degPerMLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))
    private fun pt(xM: Double) = lat0 to lon0 + xM * degPerMLon
    private fun wps(xs: List<Double>) = xs.map { RouteWaypoint(pt(it).first, pt(it).second, 0.0) }

    /** One west→east way of 1 km, points every 100 m. */
    private fun store() = SurfaceWayStore().apply {
        add(SurfaceWayStore.Batch(1L,
            runs = listOf((0..10).map { pt(it * 100.0) }),
            ways = listOf(OsmWay(42L, Surface.PAVED, (0..10).map { pt(it * 100.0) }))))
    }

    @Test
    fun `route running with the way's point order is forward, against it is backward`() {
        val s = store()
        val out  = s.match(wps(listOf(50.0, 250.0, 450.0, 650.0)))
        val back = s.match(wps(listOf(650.0, 450.0, 250.0, 50.0)))
        assertTrue(out.wayKeys.all { it == WaySpeedMemory.wayKey(42L, true) })
        assertTrue(back.wayKeys.all { it == WaySpeedMemory.wayKey(42L, false) })
    }

    @Test
    fun `waypoints off every way get key zero`() {
        val s = store()
        val far = s.match(wps(listOf(50.0, 250.0)).map { RouteWaypoint(it.lat + 0.01, it.lon, 0.0) })   // 1.1 km north
        assertTrue(far.wayKeys.all { it == 0L })
    }

    @Test
    fun `segments keep the way key and are not merged across ways`() = runBlocking {
        val p = SegmentPipeline()
        val route = wps(listOf(0.0, 200.0, 400.0, 600.0))
        p.setRoute(route)
        val keys = listOf(WaySpeedMemory.wayKey(1L, true), WaySpeedMemory.wayKey(1L, true),
                          WaySpeedMemory.wayKey(2L, true), WaySpeedMemory.wayKey(2L, true))
        p.applySurfaces(List(route.size) { Surface.PAVED }, keys)
        val segs = p.remainingSegments()
        assertEquals(2, segs.size)                     // flat and same surface, but two ways
        assertEquals(WaySpeedMemory.wayKey(1L, true), segs[0].wayKey)
        assertEquals(WaySpeedMemory.wayKey(2L, true), segs[1].wayKey)
        assertEquals(WaySpeedMemory.wayKey(1L, true), p.currentWayKey())
    }

    @Test
    fun `remembered speed blends in, scaled by the day factor, weighted by passes`() = runBlocking {
        val model = AdaptiveSpeedModel().apply {
            restore(AdaptiveSpeedModel.Snapshot(6.0, 1000, listOf(7.7, 6.5, 6.0, 3.8, 3.4, 3.0), List(6) { 1000 }))
        }
        val memory = WaySpeedMemory()
        val key = WaySpeedMemory.wayKey(5L, true)
        val seg = RouteSegment(distanceM = 1000.0, gradePct = 0.0, surface = Surface.UNKNOWN, wayKey = key)
        val engine = EtaEngine(model, SegmentPipeline(), wayMemory = memory)

        // No memory: bucket speed 6.0 → 166 s
        assertEquals(166L, engine.computeBreakdown(listOf(seg)).seconds)
        assertEquals(0.0, engine.computeBreakdown(listOf(seg)).memoryDistanceShare, 1e-9)

        // One pass at 3.0 m/s base: weight 1/2 → (3.0 + 6.0) / 2 = 4.5 → 222 s
        memory.record(key, 3.0, 1L)
        val one = engine.computeBreakdown(listOf(seg))
        assertEquals(222L, one.seconds)
        assertEquals(1.0, one.memoryDistanceShare, 1e-9)

        // Three passes: weight 3/4 → 0.75×3.0 + 0.25×6.0 = 3.75 → 266 s
        memory.record(key, 3.0, 2L); memory.record(key, 3.0, 3L)
        assertEquals(266L, engine.computeBreakdown(listOf(seg)).seconds)

        // A segment on an unknown way is untouched
        val other = RouteSegment(1000.0, 0.0, Surface.UNKNOWN, wayKey = 0L)
        assertEquals(166L, engine.computeBreakdown(listOf(other)).seconds)
    }
}
