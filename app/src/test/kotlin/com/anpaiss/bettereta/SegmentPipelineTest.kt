package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPipelineTest {

    private fun assertApprox(expected: Double, actual: Double, delta: Double = 1.0) =
        assertEquals(expected, actual, delta)

    // ── helpers percorso ──────────────────────────────────────────────

    /** ~1111m a nord, pianura */
    private fun flatWaypoints() = listOf(
        RouteWaypoint(45.0000, 9.0, 300.0),
        RouteWaypoint(45.0100, 9.0, 300.0),
    )

    /** ~1111m pianura + ~1111m salita ~5% = ~2222m totali con pendenze diverse
     *  (le grade differiscono di >2% quindi simplify() NON le unisce) */
    private fun twoSegmentRoute() = listOf(
        RouteWaypoint(45.0000, 9.0, 300.0),
        RouteWaypoint(45.0100, 9.0, 300.0),  // flat 0%
        RouteWaypoint(45.0200, 9.0, 360.0),  // climb ~5.4%
    )

    // ── test ──────────────────────────────────────────────────────────

    @Test
    fun `empty route has no segments`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(emptyList())
        assertFalse(p.hasSegments())
        assertTrue(p.remainingSegments().isEmpty())
    }

    @Test
    fun `single waypoint has no segments`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(listOf(RouteWaypoint(45.0, 9.0, 300.0)))
        assertFalse(p.hasSegments())
    }

    @Test
    fun `flat two-point route builds one segment`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(flatWaypoints())
        assertTrue(p.hasSegments())
        // 0.01° lat ≈ 1111m
        val segs = p.remainingSegments()
        assertEquals(1, segs.size)
        assertTrue(segs[0].distanceM > 1000.0)
        assertApprox(0.0, segs[0].gradePct, delta = 0.5)
    }

    @Test
    fun `advance past full distance leaves no segments`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(flatWaypoints())
        p.advanceByDistance(999_999.0)
        assertFalse(p.hasSegments())
        assertTrue(p.remainingSegments().isEmpty())
    }

    @Test
    fun `partial advance reduces remaining distance correctly`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val totalBefore = p.remainingSegments().sumOf { it.distanceM }

        p.advanceByDistance(500.0)

        val totalAfter = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(totalBefore - 500.0, totalAfter, delta = 2.0)
    }

    @Test
    fun `advance across segment boundary continues into next segment`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val firstSegDist = p.remainingSegments().first().distanceM

        // Avanza oltre il primo segmento
        p.advanceByDistance(firstSegDist + 200.0)

        assertTrue(p.hasSegments())
        val remaining = p.remainingSegments().sumOf { it.distanceM }
        // Deve restare circa (secondo segmento - 200m)
        val secondSegDist = twoSegmentRoute().let {
            // distanza approssimativa secondo segmento ≈ firstSegDist
            firstSegDist
        }
        assertTrue(remaining < secondSegDist - 100.0)
    }

    @Test
    fun `climbing route has positive grade`() = runBlocking {
        val p = SegmentPipeline()
        // ~1111m, +100m di dislivello → ~9% di pendenza
        p.setRoute(listOf(
            RouteWaypoint(45.0000, 9.0, 100.0),
            RouteWaypoint(45.0100, 9.0, 200.0),
        ))
        val grade = p.remainingSegments().first().gradePct
        assertTrue("grade=$grade deve essere positivo", grade > 4.0)
        assertTrue("grade=$grade deve essere < 20", grade < 20.0)
    }

    @Test
    fun `descending route has negative grade`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(listOf(
            RouteWaypoint(45.0000, 9.0, 200.0),
            RouteWaypoint(45.0100, 9.0, 100.0),
        ))
        val grade = p.remainingSegments().first().gradePct
        assertTrue("grade=$grade deve essere negativo", grade < -4.0)
    }

    @Test
    fun `reset restores position to start`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(twoSegmentRoute())
        val totalBefore = p.remainingSegments().sumOf { it.distanceM }

        p.advanceByDistance(500.0)
        p.reset()

        val totalAfter = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(totalBefore, totalAfter, delta = 1.0)
    }

    // ── snapToLocation ────────────────────────────────────────────────

    /** Route rettilinea verso nord, waypoint ogni ~111m. */
    private fun denseRoute(n: Int = 21) =
        (0 until n).map { RouteWaypoint(45.0 + it * 0.001, 9.0, 300.0) }

    @Test
    fun `snap corrects position backward after dead-reckoning overshoot`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(denseRoute())
        val total = p.remainingSegments().sumOf { it.distanceM }
        p.snapToLocation(45.0, 9.0)          // aggancio iniziale
        p.advanceByDistance(500.0)           // dead-reckoning sovrastimato
        val correction = p.snapToLocation(45.002, 9.0)  // GPS reale a ~222m

        assertTrue("correction=$correction deve essere > 100", correction > 100.0)
        val remaining = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(total - 222.0, remaining, delta = 60.0)
    }

    @Test
    fun `snap leaves dead-reckoning alone while GPS agrees with it`() = runBlocking {
        val p = SegmentPipeline()
        // Waypoint ogni ~33 m (come una route vera), ciclista a 8 m/s con il GPS
        // esattamente sulla route: la posizione deve avanzare a ogni tick, non
        // restare inchiodata al waypoint e poi saltare (giro 13/09).
        p.setRoute((0 until 60).map { RouteWaypoint(45.0 + it * 0.0003, 9.0, 300.0) })
        p.snapToLocation(45.0, 9.0)
        var prev = p.distanceAlongRouteM()
        var truthM = 0.0
        repeat(30) {
            p.advanceByDistance(8.0)
            truthM += 8.0
            p.snapToLocation(45.0 + truthM / 111_195.0, 9.0)
            val now = p.distanceAlongRouteM()
            assertTrue("tick $it: posizione ferma a $now", now > prev)
            prev = now
        }
        assertApprox(truthM, p.distanceAlongRouteM(), delta = 20.0)
    }

    @Test
    fun `snap does not jump to return leg of out-and-back route`() = runBlocking {
        val p = SegmentPipeline()
        // Andata verso nord + ritorno parallelo spostato di ~8m (entro i 100m di snap)
        val out  = (0..20).map { RouteWaypoint(45.0 + it * 0.001, 9.0, 300.0) }
        val back = (20 downTo 0).map { RouteWaypoint(45.0 + it * 0.001, 9.0001, 300.0) }
        p.setRoute(out + back)
        val total = p.remainingSegments().sumOf { it.distanceM }
        p.snapToLocation(45.0, 9.0)
        p.advanceByDistance(555.0)
        // Rider sull'andata a ~556m: il ramo di ritorno è a pochi metri dal fix
        // ma NON va scelto (correzione piccola, non un salto verso fine route)
        val correction = p.snapToLocation(45.005, 9.0)

        assertTrue("correction=$correction deve essere piccola", correction < 100.0)
        val remaining = p.remainingSegments().sumOf { it.distanceM }
        assertTrue("remaining=$remaining deve restare quasi tutto il percorso ($total)",
            remaining > total * 0.7)
    }

    @Test
    fun `rescue re-engages pipeline after premature exhaustion`() = runBlocking {
        var fakeNow = 0L
        val p = SegmentPipeline { fakeNow }
        p.setRoute(denseRoute())
        val total = p.remainingSegments().sumOf { it.distanceM }
        p.snapToLocation(45.0, 9.0)
        p.advanceByDistance(999_999.0)       // posizione (erroneamente) a fine percorso
        assertTrue(p.remainingSegments().isEmpty())

        // Il rider è in realtà a metà route: dopo RESCUE_AFTER_TICKS fuori finestra
        // + RESCUE_CONFIRM_TICKS conferme la ricerca globale riaggancia la posizione.
        // Il tempo trascorso rende plausibile il salto (~1100m in 2 minuti).
        fakeNow += 120_000
        repeat(35) { p.snapToLocation(45.010, 9.0) }

        assertTrue("la pipeline deve riagganciarsi", p.hasSegments())
        val remaining = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(total - 1112.0, remaining, delta = 60.0)
    }

    @Test
    fun `route loaded mid-ride locks onto rider position within few ticks`() = runBlocking {
        val p = SegmentPipeline()
        p.setRoute(denseRoute(31))           // ~3335m
        val total = p.remainingSegments().sumOf { it.distanceM }
        // Rider a ~2780m dall'inizio, oltre la finestra locale: senza mai essere
        // stati agganciati la ricerca globale parte subito (niente attesa di 30 tick)
        // — se il Karoo ha già detto dove siamo; senza suggerimento aspetterebbe un minuto
        repeat(3) { p.snapToLocation(45.025, 9.0, hintM = 2700.0) }

        val remaining = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(total - 2780.0, remaining, delta = 60.0)
    }

    @Test
    fun `rescue on loop route picks branch coherent with estimated position`() = runBlocking {
        var fakeNow = 0L
        val p = SegmentPipeline { fakeNow }
        // Anello: andata verso nord su lon 9.0, ritorno su lon 9.002 (~157m a est,
        // fuori dal raggio di snap), ultimo waypoint = punto di partenza.
        val out  = (0..20).map { RouteWaypoint(45.0 + it * 0.001, 9.0, 300.0) }
        val back = (19 downTo 1).map { RouteWaypoint(45.0 + it * 0.001, 9.002, 300.0) } +
                   RouteWaypoint(45.0, 9.0, 300.0)
        p.setRoute(out + back)

        p.snapToLocation(45.0, 9.0)          // aggancio iniziale a inizio route
        p.advanceByDistance(2800.0)          // stima: sul ramo di ritorno
        fakeNow += 120_000                   // rende plausibile il salto di ~1800m
        // Fix GPS sul punto partenza=arrivo, fuori dalla finestra locale: la
        // ricerca globale trova sia il waypoint 0 sia l'ultimo. Deve scegliere
        // l'arrivo (coerente con la stima a 2800m), non riportare la posizione a 0.
        repeat(35) { p.snapToLocation(45.0, 9.0) }

        val remaining = p.remainingSegments().sumOf { it.distanceM }
        assertTrue("remaining=$remaining deve essere ~0 (arrivo), non l'intera route",
            remaining < 50.0)
    }

    @Test
    fun `implausible rescue jump is rejected until enough time has passed`() = runBlocking {
        var fakeNow = 0L
        val p = SegmentPipeline { fakeNow }
        p.setRoute(denseRoute())             // ~2223m rettilinei
        val total = p.remainingSegments().sumOf { it.distanceM }
        p.snapToLocation(45.0, 9.0)          // agganciato all'inizio, t=0

        // Fix GPS vicino a fine route pochi secondi dopo: un salto di ~2,2km
        // non è fisicamente possibile → il rescue deve rifiutarlo
        repeat(40) { p.snapToLocation(45.020, 9.0) }
        val remainingAfterReject = p.remainingSegments().sumOf { it.distanceM }
        assertApprox(total, remainingAfterReject, delta = 60.0)

        // Dopo 3 minuti il salto diventa plausibile → riaggancio accettato
        fakeNow += 180_000
        repeat(3) { p.snapToLocation(45.020, 9.0) }
        val remainingAfterAccept = p.remainingSegments().sumOf { it.distanceM }
        assertTrue("remaining=$remainingAfterAccept deve essere ~0 dopo il riaggancio",
            remainingAfterAccept < 60.0)
    }

    @Test
    fun `adjacent similar grade segments are merged by simplify`() = runBlocking {
        val p = SegmentPipeline()
        // 5 punti con pendenza quasi identica → dovrebbero essere mergiati in meno segmenti
        val waypoints = listOf(
            RouteWaypoint(45.0000, 9.0, 100.0),
            RouteWaypoint(45.0100, 9.0, 110.0),
            RouteWaypoint(45.0200, 9.0, 120.0),
            RouteWaypoint(45.0300, 9.0, 130.0),
            RouteWaypoint(45.0400, 9.0, 140.0),
        )
        p.setRoute(waypoints)
        // La semplificazione deve ridurre 4 segmenti a meno di 4
        assertTrue(p.remainingSegments().size < 4)
    }
}
