package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web Mercator tile arithmetic, the addressing of Mapsforge tiles. */
class SlippyTilesTest {

    @Test
    fun `known tile for Trento at zoom 16`() {
        // 46.0665°N 11.1216°E → x = floor((11.1216+180)/360 * 65536) = 34792
        val t = SlippyTiles.tileFor(46.0665, 11.1216, 16)
        assertEquals(16, t.zoom)
        assertEquals(34792, t.x)
        assertTrue("y=${t.y}", t.y in 23250..23350)
    }

    @Test
    fun `origin and center invert tileFor`() {
        val t = SlippyTiles.tileFor(46.0665, 11.1216, 16)
        val (cLat, cLon) = SlippyTiles.tileCenter(t)
        assertEquals(t, SlippyTiles.tileFor(cLat, cLon, 16))
        val (oLat, oLon) = SlippyTiles.tileOrigin(t)
        assertTrue(oLat > cLat && oLon < cLon)      // origin is north-west of the center
        assertTrue(oLat - cLat < 0.002 && cLon - oLon < 0.003)
    }

    @Test
    fun `neighbouring points share a tile, distant ones do not`() {
        val a = SlippyTiles.tileFor(46.0665, 11.1216, 16)
        val b = SlippyTiles.tileFor(46.0666, 11.1217, 16)
        val c = SlippyTiles.tileFor(46.1665, 11.2216, 16)
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
