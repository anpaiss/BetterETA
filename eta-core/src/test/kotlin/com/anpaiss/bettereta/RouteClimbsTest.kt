package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same results as RouteProfile's ClimbDetector on the same real routes (lagorai.bike GPX, smoothed every 50 m). */
class RouteClimbsTest {

    private fun ele(name: String): DoubleArray =
        javaClass.classLoader!!.getResourceAsStream("$name.ele")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.toDouble() }.toDoubleArray()

    @Test fun `Menador, the climb from km 13,7 and two smaller ones`() {
        val c = RouteClimbs.find(ele("menador-drago-vaia-passo-vezzena"), 50.0)
        assertEquals(3, c.size)
        assertEquals(13_650.0, c[0].startM, 1.0); assertEquals(21_300.0, c[0].endM, 1.0); assertEquals(725.6, c[0].gainM, 1.5)
        assertEquals(30_750.0, c[2].startM, 1.0); assertEquals(179.2, c[2].gainM, 1.5)
    }

    @Test fun `Manghen in three pieces`() {
        val c = RouteClimbs.find(ele("passo-manghen-telve"), 50.0)
        assertEquals(listOf(97.0, 372.0, 748.0), c.map { Math.round(it.gainM).toDouble() })
    }

    @Test fun `next top skips the small ramps and the tops already passed`() {
        val c = RouteClimbs.find(ele("menador-drago-vaia-passo-vezzena"), 50.0)
        assertEquals(21_300.0, RouteClimbs.nextTop(c, 5_000.0)!!.endM, 1.0)    // before the big climb
        assertEquals(21_300.0, RouteClimbs.nextTop(c, 18_000.0)!!.endM, 1.0)   // on it
        // the 97 m ramp at km 24.2 is a Cat 4: it is the next top; past it, the Vezzena one
        assertEquals(25_650.0, RouteClimbs.nextTop(c, 22_000.0)!!.endM, 1.0)
        assertEquals(34_500.0, RouteClimbs.nextTop(c, 26_000.0)!!.endM, 1.0)
        assertNull(RouteClimbs.nextTop(c, 35_000.0))
    }

    @Test fun `elevation rebuilt from segments`() {
        // 2 km flat, 2 km at 10 %, 1 km flat: one climb of 200 m from km 2 to km 4
        val segs = listOf(RouteSegment(2_000.0, 0.0), RouteSegment(2_000.0, 10.0), RouteSegment(1_000.0, 0.0))
        val c = RouteClimbs.find(segs)
        assertEquals(1, c.size)
        assertEquals(2_000.0, c[0].startM, 150.0)
        assertEquals(4_000.0, c[0].endM, 150.0)
        assertEquals(200.0, c[0].gainM, 25.0)
    }
}
