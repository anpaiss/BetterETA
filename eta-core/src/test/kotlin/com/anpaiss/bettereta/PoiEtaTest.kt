package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiEtaTest {

    private val pois = listOf(
        RoutePoi("Fountain", 2_000.0),
        RoutePoi("Meeting point", 5_000.0),
        RoutePoi("Fountain", 9_000.0),   // the same POI passed again on the way back
    )

    @Test
    fun `next is the first POI ahead and moves on once each is passed`() {
        assertEquals("Fountain",      PoiEta.next(pois, 0.0)?.name)
        assertEquals(2_000.0,         PoiEta.next(pois, 0.0)?.alongM)
        assertEquals("Meeting point", PoiEta.next(pois, 2_100.0)?.name)
        assertEquals(9_000.0,         PoiEta.next(pois, 5_100.0)?.alongM)
        assertNull(PoiEta.next(pois, 9_100.0))
        assertNull(PoiEta.next(emptyList(), 0.0))
    }

    @Test
    fun `a POI counts as passed within the margin before its point`() {
        val justBefore = 2_000.0 - PoiEta.PASSED_MARGIN_M + 1.0
        assertEquals("Meeting point", PoiEta.next(pois, justBefore)?.name)
        assertEquals("Fountain",      PoiEta.next(pois, 2_000.0 - PoiEta.PASSED_MARGIN_M - 1.0)?.name)
    }

    @Test
    fun `a POI keeps its name, coordinates or nothing become POI n`() {
        assertEquals("Meeting point", PoiEta.nameFor(" Meeting point ", 1))
        assertEquals("POI 2", PoiEta.nameFor("46.332551,11.710235", 2))
        assertEquals("POI 3", PoiEta.nameFor("46.3, 11.7", 3))
        assertEquals("POI 4", PoiEta.nameFor(null, 4))
        assertEquals("POI 5", PoiEta.nameFor("  ", 5))
        assertEquals("Km 46", PoiEta.nameFor("Km 46", 6))   // a number alone is still a name
    }

    @Test
    fun `segments up to a distance keep whole segments and cut the last one`() {
        val segs = listOf(
            RouteSegment(1_000.0, 2.0),
            RouteSegment(1_000.0, 8.0),
            RouteSegment(1_000.0, -3.0),
        )
        val cut = PoiEta.segmentsUpTo(segs, 1_400.0)
        assertEquals(2, cut.size)
        assertEquals(1_000.0, cut[0].distanceM, 1e-9)
        assertEquals(400.0,   cut[1].distanceM, 1e-9)
        assertEquals(8.0,     cut[1].gradePct, 1e-9)   // the cut piece keeps its gradient
        assertEquals(1_400.0, cut.sumOf { it.distanceM }, 1e-9)
    }

    @Test
    fun `segments up to the end or beyond return everything, up to nothing returns nothing`() {
        val segs = listOf(RouteSegment(500.0, 0.0), RouteSegment(500.0, 0.0))
        assertEquals(2, PoiEta.segmentsUpTo(segs, 1_000.0).size)
        assertEquals(2, PoiEta.segmentsUpTo(segs, 5_000.0).size)
        assertEquals(0, PoiEta.segmentsUpTo(segs, 0.0).size)
        assertEquals(0, PoiEta.segmentsUpTo(emptyList(), 100.0).size)
    }
}
