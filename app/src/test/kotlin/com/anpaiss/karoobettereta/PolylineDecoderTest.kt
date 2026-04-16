package com.anpaiss.karoobettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineDecoderTest {

    private fun assertApprox(expected: Double, actual: Double, delta: Double = 0.0001) =
        assertEquals(expected, actual, delta)

    // ── decodeLatLng ──────────────────────────────────────────────────

    @Test
    fun `decodes single point`() {
        // Google example: (38.5, -120.2) → "_p~iF~ps|U"
        val points = PolylineDecoder.decodeLatLng("_p~iF~ps|U")
        assertEquals(1, points.size)
        assertApprox(38.5,   points[0].first)
        assertApprox(-120.2, points[0].second)
    }

    @Test
    fun `decodes google example polyline`() {
        // Google's canonical example: (38.5,-120.2), (40.7,-120.95), (43.252,-126.453)
        // Encoded: "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val points = PolylineDecoder.decodeLatLng("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertApprox(38.5,    points[0].first,  0.001)
        assertApprox(-120.2,  points[0].second, 0.001)
        assertApprox(40.7,    points[1].first,  0.001)
        assertApprox(-120.95, points[1].second, 0.001)
        assertApprox(43.252,  points[2].first,  0.001)
        assertApprox(-126.453,points[2].second, 0.001)
    }

    @Test
    fun `empty string returns empty list`() {
        assertTrue(PolylineDecoder.decodeLatLng("").isEmpty())
        assertTrue(PolylineDecoder.decodeLatLng("   ").isEmpty())
    }

    @Test
    fun `decoded coords are in plausible lat-lon range`() {
        val points = PolylineDecoder.decodeLatLng("_p~iF~ps|U")
        val (lat, lon) = points[0]
        assertTrue("lat=$lat deve essere in -90..90", lat in -90.0..90.0)
        assertTrue("lon=$lon deve essere in -180..180", lon in -180.0..180.0)
    }

    // ── decodeElevations ──────────────────────────────────────────────

    @Test
    fun `decodes elevation sequence`() {
        // 100dm = 10m, 50dm = 5m, -30dm = -3m → elevazioni assolute: 10, 15, 12
        val encoded = encodeDeltas(listOf(100, 50, -30))
        val elevations = PolylineDecoder.decodeElevations(encoded)
        assertEquals(3, elevations.size)
        assertApprox(10.0, elevations[0], 0.01)
        assertApprox(15.0, elevations[1], 0.01)
        assertApprox(12.0, elevations[2], 0.01)
    }

    @Test
    fun `empty elevation string returns empty list`() {
        assertTrue(PolylineDecoder.decodeElevations("").isEmpty())
    }

    @Test
    fun `elevation values from typical cycling route are plausible`() {
        val deltas = listOf(3000, 200, 150, -100, 500, -300)  // in dm
        val encoded = encodeDeltas(deltas)
        val elevations = PolylineDecoder.decodeElevations(encoded)
        for (ele in elevations) {
            assertTrue("ele=$ele deve essere in range -500..9000 m", ele in -500.0..9000.0)
        }
    }

    // ── SegmentPipeline advance + remaining ──────────────────────────

    @Test
    fun `advance positions pipeline correctly`() = runBlocking {
        val pipeline = SegmentPipeline()
        pipeline.setRoute(listOf(
            RouteWaypoint(45.0000, 9.0, 300.0),
            RouteWaypoint(45.0100, 9.0, 300.0),
            RouteWaypoint(45.0200, 9.0, 300.0),
        ))
        val total = pipeline.remainingSegments().sumOf { it.distanceM }

        // Avanza fino a ~500m rimanenti
        pipeline.advanceByDistance(total - 500.0)
        val afterAdvance = pipeline.remainingSegments().sumOf { it.distanceM }

        assertEquals(500.0, afterAdvance, 2.0)
    }

    @Test
    fun `advance past end leaves no remaining distance`() = runBlocking {
        val pipeline = SegmentPipeline()
        pipeline.setRoute(listOf(
            RouteWaypoint(45.0, 9.0, 300.0),
            RouteWaypoint(45.01, 9.0, 300.0),
        ))
        pipeline.advanceByDistance(999_999.0)
        assertTrue(pipeline.remainingSegments().sumOf { it.distanceM } < 1.0)
    }

    // ── helper per test ───────────────────────────────────────────────

    /** Codifica una lista di delta interi nel formato Google Polyline. */
    private fun encodeDeltas(deltas: List<Int>): String {
        val sb = StringBuilder()
        for (delta in deltas) {
            var v = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (v >= 0x20) {
                sb.append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v shr 5
            }
            sb.append((v + 63).toChar())
        }
        return sb.toString()
    }
}
