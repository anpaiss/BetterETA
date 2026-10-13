package com.anpaiss.bettereta

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

    // ── decodeDistanceElevation ───────────────────────────────────────

    @Test
    fun `decodes distance-elevation pairs`() {
        // Coppie di delta (distanza, quota) in decimi di metro (precision 1):
        // (1000, 3000), (500, 20), (500, -50)
        // → punti assoluti: (100m, 300m), (150m, 302m), (200m, 297m)
        val encoded = encodeDeltas(listOf(1000, 3000, 500, 20, 500, -50))
        val samples = PolylineDecoder.decodeDistanceElevation(encoded)
        assertEquals(3, samples.size)
        assertApprox(100.0, samples[0].first,  0.01)
        assertApprox(300.0, samples[0].second, 0.01)
        assertApprox(150.0, samples[1].first,  0.01)
        assertApprox(302.0, samples[1].second, 0.01)
        assertApprox(200.0, samples[2].first,  0.01)
        assertApprox(297.0, samples[2].second, 0.01)
    }

    @Test
    fun `empty elevation string returns empty list`() {
        assertTrue(PolylineDecoder.decodeDistanceElevation("").isEmpty())
    }

    @Test
    fun `decoded distances are monotone and elevations plausible`() {
        // Route tipica: campioni ogni ~100m, quote attorno ai 300m
        val deltas = listOf(0, 3000, 1000, 200, 1000, 150, 1000, -100, 1000, 500, 1000, -300)
        val samples = PolylineDecoder.decodeDistanceElevation(encodeDeltas(deltas))
        for (i in 1 until samples.size) {
            assertTrue("le distanze devono crescere", samples[i].first >= samples[i - 1].first)
        }
        for ((_, ele) in samples) {
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

    // ── interpolateElevations ─────────────────────────────────────────

    @Test
    fun `interpolateElevations with matching positions returns same values`() {
        val coords  = listOf(45.0 to 9.0, 45.01 to 9.0, 45.02 to 9.0)
        val samples = listOf(0.0 to 100.0, 500.0 to 200.0, 1000.0 to 300.0)
        val result  = interpolateElevations(coords, samples)
        assertApprox(100.0, result[0], 0.5)
        assertApprox(200.0, result[1], 0.5)
        assertApprox(300.0, result[2], 0.5)
    }

    @Test
    fun `interpolateElevations with more samples than coords covers full range`() {
        // 3 coord equidistanti, 5 campioni uniformi 0..100 → 0, 50, 100
        val coords  = listOf(45.0 to 9.0, 45.01 to 9.0, 45.02 to 9.0)
        val samples = listOf(0.0 to 0.0, 250.0 to 25.0, 500.0 to 50.0, 750.0 to 75.0, 1000.0 to 100.0)
        val result  = interpolateElevations(coords, samples)
        assertEquals(3, result.size)
        assertApprox(0.0,   result[0], 0.5)
        assertApprox(50.0,  result[1], 0.5)
        assertApprox(100.0, result[2], 0.5)
    }

    @Test
    fun `interpolateElevations with empty samples returns all zeros`() {
        val coords = listOf(45.0 to 9.0, 45.01 to 9.0)
        val result = interpolateElevations(coords, emptyList())
        assertTrue(result.all { it == 0.0 })
    }

    @Test
    fun `interpolateElevations monotone input produces monotone output`() {
        // 4 coord, 10 campioni con quote crescenti → output deve essere crescente
        val coords  = listOf(45.0 to 9.0, 45.01 to 9.0, 45.02 to 9.0, 45.03 to 9.0)
        val samples = (0..9).map { it * 100.0 to it * 10.0 }  // quote 0,10,...,90
        val result  = interpolateElevations(coords, samples)
        for (i in 1 until result.size) {
            assertTrue("result[$i]=${result[i]} deve essere >= result[${i-1}]=${result[i-1]}",
                result[i] >= result[i - 1])
        }
    }

    @Test
    fun `interpolateElevations with fewer samples than coords interpolates correctly`() {
        // 5 coord, 3 campioni: dovrebbe interpolare negli intervalli mancanti
        val coords  = listOf(45.0 to 9.0, 45.005 to 9.0, 45.01 to 9.0, 45.015 to 9.0, 45.02 to 9.0)
        val samples = listOf(0.0 to 0.0, 500.0 to 50.0, 1000.0 to 100.0)
        val result  = interpolateElevations(coords, samples)
        assertApprox(0.0,   result[0], 1.0)
        assertApprox(100.0, result[4], 1.0)
        // I punti intermedi devono essere tra i valori estremi
        assertTrue(result[1] in 0.0..100.0)
        assertTrue(result[2] in 0.0..100.0)
        assertTrue(result[3] in 0.0..100.0)
    }

    @Test
    fun `interpolateElevations respects non-uniform sample spacing`() {
        // Campioni NON uniformi: quota piatta per i primi 900m, poi salita ripida.
        // Il punto a metà percorso (500m) deve avere quota ~0, non ~50 come
        // darebbe l'assunzione (errata) di campionamento uniforme.
        val coords  = listOf(45.0 to 9.0, 45.005 to 9.0, 45.01 to 9.0)  // ~0, ~556, ~1112 m
        val samples = listOf(0.0 to 0.0, 900.0 to 0.0, 1000.0 to 100.0)
        val result  = interpolateElevations(coords, samples)
        assertApprox(0.0,   result[0], 0.5)
        assertApprox(0.0,   result[1], 5.0)
        assertApprox(100.0, result[2], 0.5)
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
