package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il parsing GPX usando il file campione in src/test/resources/sample_route.gpx.
 *
 * Nota: richiede kxml2 come testImplementation per avere un'implementazione
 * di XmlPullParserFactory disponibile nella JVM (fuori da Android).
 */
class RouteParsingTest {

    private fun loadSampleGpx() = RouteParsingTest::class.java.classLoader!!
        .getResourceAsStream("sample_route.gpx")

    @Test
    fun `sample gpx file is present in test resources`() {
        val stream = loadSampleGpx()
        assertNotNull("sample_route.gpx non trovato in src/test/resources/", stream)
        stream?.close()
    }

    @Test
    fun `parses all 28 trackpoints`() {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        assertEquals(28, waypoints.size)
    }

    @Test
    fun `first point has expected coordinates`() {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        val first = waypoints.first()
        assertEquals(45.6500, first.lat,        0.0001)
        assertEquals(9.7000,  first.lon,        0.0001)
        assertEquals(300.0,   first.elevationM, 0.5)
    }

    @Test
    fun `last point has expected coordinates`() {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        val last = waypoints.last()
        assertEquals(45.7492, last.lat,        0.0001)
        assertEquals(9.8594,  last.lon,        0.0001)
        assertEquals(310.0,   last.elevationM, 0.5)
    }

    @Test
    fun `elevation values are read correctly`() {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        val maxEle = waypoints.maxOf { it.elevationM }
        assertEquals(643.0, maxEle, 0.5)
        val minEle = waypoints.minOf { it.elevationM }
        assertEquals(300.0, minEle, 0.5)
    }

    @Test
    fun `route builds segments correctly via pipeline`() = runBlocking {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        val pipeline = SegmentPipeline()
        pipeline.setRoute(waypoints)

        assertTrue(pipeline.hasSegments())
        val totalKm = pipeline.remainingSegments().sumOf { it.distanceM } / 1000.0
        // Il percorso è ~27km
        assertTrue("totalKm=$totalKm deve essere tra 15 e 35", totalKm in 15.0..35.0)
    }

    @Test
    fun `route has both climbing and descending segments`() = runBlocking {
        val waypoints = RouteReader().parseFromStream(loadSampleGpx()!!)
        val pipeline = SegmentPipeline()
        pipeline.setRoute(waypoints)

        val segments = pipeline.remainingSegments()
        assertTrue(segments.any { it.gradePct > 2.0 })   // ci sono salite
        assertTrue(segments.any { it.gradePct < -2.0 })  // ci sono discese
    }
}
