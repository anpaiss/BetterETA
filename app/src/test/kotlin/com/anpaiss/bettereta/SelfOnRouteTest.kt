package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Giro 27/09: partenza da casa, route presa al km 20; il Karoo ha tenuto
 * ON_ROUTE=0 per un'ora e mezza. BetterEta deve riconoscere da sé di essere
 * sulla traccia (SelfOnRouteDetector + SegmentPipeline.matchOnRoute).
 */
class SelfOnRouteTest {

    // ── Rilevatore ────────────────────────────────────────────────────────

    /** Un tick al secondo a [speed] m/s lungo la route da [fromM], per [secs] secondi. */
    private fun ride(d: SelfOnRouteDetector, fromM: Double, speed: Double, secs: Int, startMs: Long = 0L): Boolean {
        var on = false
        for (s in 0..secs) on = d.onTick(startMs + s * 1000L, fromM + s * speed)
        return on
    }

    @Test fun `confirms after 30 s on the track moving forward`() {
        val d = SelfOnRouteDetector()
        assertFalse(ride(d, 19_800.0, 4.0, 29))
        assertTrue(d.onTick(30_000L, 19_800.0 + 30 * 4.0))
    }

    @Test fun `standing still on the track does not confirm`() {
        assertFalse(ride(SelfOnRouteDetector(), 19_800.0, 0.0, 60))
    }

    @Test fun `riding the track backwards does not confirm`() {
        assertFalse(ride(SelfOnRouteDetector(), 19_800.0, -4.0, 60))
    }

    @Test fun `a gap in the matches restarts the confirmation`() {
        val d = SelfOnRouteDetector()
        ride(d, 19_800.0, 4.0, 20)
        d.onTick(21_000L, null)                         // la traccia si allontana un attimo
        assertFalse(ride(d, 19_900.0, 4.0, 25, startMs = 22_000L))
        assertTrue(ride(d, 20_000.0, 4.0, 30, startMs = 48_000L))
    }

    @Test fun `once confirmed it survives a stop and a short loss, not a long one`() {
        val d = SelfOnRouteDetector()
        assertTrue(ride(d, 19_800.0, 4.0, 30))
        assertTrue(ride(d, 19_920.0, 0.0, 120, startMs = 31_000L))   // sosta sulla traccia
        assertTrue(d.onTick(160_000L, null))                          // 9 s senza traccia
        assertFalse(d.onTick(172_000L, null))                         // 21 s: perso
    }

    @Test fun `going backwards restarts the confirmation, then forward confirms`() {
        val d = SelfOnRouteDetector()
        assertFalse(ride(d, 20_000.0, -4.0, 40))                       // ramo sbagliato: all'indietro
        assertTrue(ride(d, 19_840.0, 4.0, 30, startMs = 41_000L))      // poi avanti per 30 s
    }

    // ── Proiezione sulla traccia ─────────────────────────────────────────

    // Anello quadrato di 4 km (lato 1 km, punti ogni 50 m), partenza e arrivo
    // nell'angolo sud-ovest.
    private val mLat = 1.0 / 111_320.0
    private val lat0 = 46.0
    private val lon0 = 11.2
    private val mLon = 1.0 / (111_320.0 * Math.cos(Math.toRadians(lat0)))

    private fun at(eastM: Double, northM: Double) = Pair(lat0 + northM * mLat, lon0 + eastM * mLon)

    private fun square(): List<RouteWaypoint> {
        val pts = mutableListOf<RouteWaypoint>()
        fun add(e: Double, n: Double) { val (la, lo) = at(e, n); pts += RouteWaypoint(la, lo, 500.0) }
        for (i in 0 until 20) add(0.0, i * 50.0)            // nord
        for (i in 0 until 20) add(i * 50.0, 1000.0)         // est
        for (i in 0 until 20) add(1000.0, 1000.0 - i * 50.0) // sud
        for (i in 0..20) add(1000.0 - i * 50.0, 0.0)        // ovest, torna alla partenza
        return pts
    }

    @Test fun `a fix near the track gives its position along the route`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(square())
        val (la, lo) = at(1000.0 + 12.0, 400.0)              // lato est, 12 m fuori, a 600 m dall'angolo NE
        val m = p.matchOnRoute(la, lo, 30.0)
        assertNotNull(m)
        assertTrue("along ${m!!.alongM}", abs(m.alongM - 2600.0) < 15.0)
        assertTrue("dist ${m.distM}", abs(m.distM - 12.0) < 2.0)
    }

    @Test fun `a fix far from the track gives nothing`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(square())
        val (la, lo) = at(500.0, 500.0)                       // centro dell'anello
        assertNull(p.matchOnRoute(la, lo, 30.0))
    }

    @Test fun `at the start-finish corner the reference picks the branch`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(square())
        val (la, lo) = at(5.0, 5.0)
        assertTrue(p.matchOnRoute(la, lo, 30.0, refM = 3_990.0)!!.alongM > 3_900.0)
        assertTrue(p.matchOnRoute(la, lo, 30.0, refM = 10.0)!!.alongM < 100.0)
    }

    // ── Scenario del 27/09 ───────────────────────────────────────────────

    @Test fun `route joined mid-way is recognised and the position moves there`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(square())
        val d = SelfOnRouteDetector()
        // Il Karoo dice fuori route; si entra nell'anello sul lato est a 2,2 km
        // e lo si percorre verso sud a 5 m/s: dopo 30 s il GPS basta.
        var on = false
        for (s in 0..30) {
            val (la, lo) = at(1000.0 + 8.0, 800.0 - s * 5.0)
            val m = p.matchOnRoute(la, lo, 30.0, d.lastAlongM)
            on = d.onTick(s * 1000L, m?.alongM)
        }
        assertTrue(on)
        p.locateAt(d.lastAlongM!!)
        assertTrue(p.isLocated())
        assertEquals(2_350.0, p.distanceAlongRouteM(), 15.0)
    }
}
