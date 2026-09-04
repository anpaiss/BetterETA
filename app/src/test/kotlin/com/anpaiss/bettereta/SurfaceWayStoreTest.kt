package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache is geographic, not per route: a recalculated polyline or a new
 * route sharing a stretch with an old one must reuse what was fetched, and
 * only the stretches never queried may go back to Overpass.
 */
class SurfaceWayStoreTest {

    // Around 46°N: 1e-5 deg lat ≈ 1.1 m, 1e-5 deg lon ≈ 0.77 m
    private val lat0 = 46.0
    private val lon0 = 11.0
    private val degPerMLat = 1.0 / 111_320.0
    private val degPerMLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    /** Points along a west→east line at [northM] metres north of the origin. */
    private fun line(fromM: Double, toM: Double, stepM: Double, northM: Double = 0.0): List<Pair<Double, Double>> {
        val pts = mutableListOf<Pair<Double, Double>>()
        var x = fromM
        while (x <= toM + 1e-9) { pts.add(lat0 + northM * degPerMLat to lon0 + x * degPerMLon); x += stepM }
        return pts
    }
    private fun wps(points: List<Pair<Double, Double>>) = points.map { RouteWaypoint(it.first, it.second, 0.0) }

    /** One Overpass call that covered the first kilometre: a paved road on it, a track 20 m north of the second half. */
    private fun firstKmBatch() = SurfaceWayStore.Batch(
        timeMs = 1L,
        runs = listOf(line(0.0, 1000.0, 100.0)),
        ways = listOf(
            OsmWay(1, Surface.PAVED,   line(0.0, 500.0, 250.0)),
            OsmWay(2, Surface.UNPAVED, line(500.0, 1000.0, 250.0, northM = 5.0)),
        ),
    )

    @Test
    fun `a route on the queried corridor is covered and matched to the nearest way`() {
        val store = SurfaceWayStore().apply { add(firstKmBatch()) }
        val m = store.match(wps(line(0.0, 1000.0, 50.0, northM = 3.0)))
        assertTrue(m.missing.isEmpty())
        assertEquals(Surface.PAVED,   m.surfaces[0])     // x = 0
        assertEquals(Surface.PAVED,   m.surfaces[8])     // x = 400
        assertEquals(Surface.UNPAVED, m.surfaces[14])    // x = 700
        assertEquals(Surface.UNPAVED, m.surfaces[20])    // x = 1000
    }

    @Test
    fun `a recalculated route shifted by a few metres still hits the cache`() {
        val store = SurfaceWayStore().apply { add(firstKmBatch()) }
        val m = store.match(wps(line(2.0, 998.0, 37.0, northM = 12.0)))
        assertTrue(m.missing.isEmpty())
        assertTrue(m.surfaces.none { it == Surface.UNKNOWN })
    }

    @Test
    fun `only the stretch never queried is reported missing`() {
        val store = SurfaceWayStore().apply { add(firstKmBatch()) }
        // Continues 1 km beyond the queried corridor
        val m = store.match(wps(line(0.0, 2000.0, 100.0)))
        assertEquals((11..20).toList(), m.missing)          // x = 1100 .. 2000
        assertEquals(11, m.coveredCount)
        assertEquals(Surface.UNKNOWN, m.surfaces[15])       // no way there yet
        assertEquals(Surface.UNPAVED, m.surfaces[9])        // x = 900, cached way
    }

    @Test
    fun `a covered waypoint with no way nearby is UNKNOWN but not missing`() {
        val batch = SurfaceWayStore.Batch(1L, runs = listOf(line(0.0, 1000.0, 100.0)), ways = emptyList())
        val store = SurfaceWayStore().apply { add(batch) }
        val m = store.match(wps(line(0.0, 1000.0, 100.0)))
        assertTrue(m.missing.isEmpty())
        assertTrue(m.surfaces.all { it == Surface.UNKNOWN })
    }

    @Test
    fun `a way farther than the match distance does not classify`() {
        val batch = SurfaceWayStore.Batch(1L, runs = listOf(line(0.0, 1000.0, 100.0)),
            ways = listOf(OsmWay(9, Surface.PAVED, line(0.0, 1000.0, 100.0, northM = 60.0))))
        val store = SurfaceWayStore().apply { add(batch) }
        val m = store.match(wps(line(0.0, 1000.0, 100.0)))
        assertTrue(m.surfaces.all { it == Surface.UNKNOWN })
    }

    @Test
    fun `serialization round-trips batches, runs and ways`() {
        val store = SurfaceWayStore().apply { add(firstKmBatch()) }
        val text = store.serialize()
        val back = SurfaceWayStore.parse(text)
        assertEquals(1, back.batchCount)
        assertEquals(2, back.wayCount)
        assertEquals(store.wayPointCount, back.wayPointCount)
        val a = store.match(wps(line(0.0, 1000.0, 50.0, northM = 3.0)))
        val b = back.match(wps(line(0.0, 1000.0, 50.0, northM = 3.0)))
        assertEquals(a.surfaces, b.surfaces)
        assertTrue(b.missing.isEmpty())
    }

    @Test
    fun `the legacy JSON cache and garbage parse to an empty store`() {
        assertEquals(0, SurfaceWayStore.parse("""{"r1192617548_955":[0,0,1]}""").batchCount)
        assertEquals(0, SurfaceWayStore.parse("").batchCount)
        assertEquals(0, SurfaceWayStore.parse("SURF2\nB x\n").batchCount)   // corrupt → empty, not crash
    }

    @Test
    fun `oldest batches are evicted past the point cap, never the newest`() {
        val store = SurfaceWayStore(maxWayPoints = 10)
        fun batch(t: Long) = SurfaceWayStore.Batch(t, runs = listOf(line(0.0, 100.0, 100.0)),
            ways = listOf(OsmWay(t, Surface.PAVED, line(0.0, 100.0, 20.0))))   // 6 points
        store.add(batch(1))
        assertEquals(1, store.batchCount)
        store.add(batch(2))                                // 12 > 10 → batch 1 evicted
        assertEquals(1, store.batchCount)
        assertEquals(6, store.wayPointCount)
        assertEquals(2L, store.serialize().lines().first { it.startsWith("B ") }.substring(2).toLong())
        // A single oversized batch is always kept
        val big = SurfaceWayStore(maxWayPoints = 3).apply { add(batch(1)) }
        assertEquals(1, big.batchCount)
    }
}
