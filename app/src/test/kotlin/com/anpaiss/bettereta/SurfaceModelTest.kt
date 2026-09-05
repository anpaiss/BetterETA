package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il modello per-fondo deve: imparare separatamente asfalto e sterrato,
 * ripiegare sui bucket globali quando il fondo è ignoto o povero di campioni,
 * e la pipeline deve rispettare i confini di fondo nei segmenti.
 */
class SurfaceModelTest {

    @Test
    fun `bucket per fondo divergono e la predizione li usa`() {
        val model = AdaptiveSpeedModel()
        // Stessa pendenza (4% → bucket salita media), fondi diversi:
        // asfalto veloce, sterrato lento
        repeat(50) { model.update(5.0, 4.0, Surface.PAVED) }
        repeat(50) { model.update(2.5, 4.0, Surface.GRAVEL) }

        val paved   = model.predictSpeed(4.0, Surface.PAVED)
        val unpaved = model.predictSpeed(4.0, Surface.GRAVEL)
        assertTrue("paved ($paved) deve essere ben più veloce di unpaved ($unpaved)",
            paved > unpaved + 1.0)
    }

    @Test
    fun `tre classi di fondo imparano separatamente`() {
        val model = AdaptiveSpeedModel()
        repeat(50) { model.update(5.0, 4.0, Surface.PAVED) }
        repeat(50) { model.update(3.5, 4.0, Surface.GRAVEL) }
        repeat(50) { model.update(1.5, 4.0, Surface.TRAIL) }
        assertTrue(model.predictSpeed(4.0, Surface.PAVED) > model.predictSpeed(4.0, Surface.GRAVEL))
        assertTrue(model.predictSpeed(4.0, Surface.GRAVEL) > model.predictSpeed(4.0, Surface.TRAIL))
        val back = AdaptiveSpeedModel().apply { restore(model.snapshot()) }
        assertEquals(model.predictSpeed(4.0, Surface.TRAIL), back.predictSpeed(4.0, Surface.TRAIL), 0.001)
    }

    @Test
    fun `fondo ignoto usa il bucket globale`() {
        val model = AdaptiveSpeedModel()
        repeat(50) { model.update(5.0, 4.0, Surface.PAVED) }
        repeat(50) { model.update(2.5, 4.0, Surface.GRAVEL) }

        val unknown = model.predictSpeed(4.0, Surface.UNKNOWN)
        // Il globale ha visto entrambi i fondi: deve stare in mezzo
        assertTrue("unknown ($unknown) deve stare tra 2.5 e 5.0",
            unknown > 2.6 && unknown < 4.9)
    }

    @Test
    fun `fondo con pochi campioni ripiega sul globale`() {
        val model = AdaptiveSpeedModel()
        repeat(50) { model.update(4.0, 4.0, Surface.PAVED) }
        // UNPAVED ha 2 campioni (< MIN_TRUSTED=5): predizione = globale
        repeat(2) { model.update(1.0, 4.0, Surface.GRAVEL) }

        val unpaved = model.predictSpeed(4.0, Surface.GRAVEL)
        val global  = model.predictSpeed(4.0, Surface.UNKNOWN)
        assertEquals(global, unpaved, 0.001)
    }

    @Test
    fun `snapshot e restore preservano i bucket per fondo`() {
        val model = AdaptiveSpeedModel()
        repeat(20) { model.update(5.0, 4.0, Surface.PAVED) }
        repeat(20) { model.update(2.0, 4.0, Surface.GRAVEL) }

        val restored = AdaptiveSpeedModel()
        restored.restore(model.snapshot())

        assertEquals(model.predictSpeed(4.0, Surface.PAVED),
            restored.predictSpeed(4.0, Surface.PAVED), 0.001)
        assertEquals(model.predictSpeed(4.0, Surface.GRAVEL),
            restored.predictSpeed(4.0, Surface.GRAVEL), 0.001)
    }

    @Test
    fun `snapshot legacy senza campi fondo si ripristina pulito`() {
        val legacy = AdaptiveSpeedModel.Snapshot(
            overallEma     = 4.0,
            overallSamples = 100,
            bucketEmas     = listOf(5.0, 4.5, 3.5, 2.8, 2.2),
            bucketCounts   = listOf(20, 20, 20, 20, 20),
        )
        val model = AdaptiveSpeedModel()
        model.restore(legacy)
        // Nessun bucket fondo → la predizione per fondo = globale
        assertEquals(model.predictSpeed(4.0, Surface.UNKNOWN),
            model.predictSpeed(4.0, Surface.PAVED), 0.001)
    }

    @Test
    fun `pipeline non fonde segmenti con fondo diverso e applySurfaces preserva la posizione`() = runBlocking {
        // Route rettilinea piatta: 10 waypoint a ~100m l'uno dall'altro
        val wps = (0..9).map { RouteWaypoint(46.0, 11.0 + it * 0.0013, 100.0) }
        val pipeline = SegmentPipeline { 0L }
        pipeline.setRoute(wps)

        // Senza fondi: pendenza costante → 1 segmento solo
        assertEquals(1, pipeline.remainingSegments().size)

        // Avanza di ~250m, poi applica fondi: metà paved, metà unpaved
        pipeline.advanceByDistance(250.0)
        val surfaces = (0..9).map { if (it < 5) Surface.PAVED else Surface.GRAVEL }
        pipeline.applySurfaces(surfaces)

        val remaining = pipeline.remainingSegments()
        // Due classi di fondo → almeno 2 segmenti, e la distanza rimanente
        // deve riflettere l'avanzamento già fatto (~250m su ~900m totali)
        assertTrue("attesi ≥2 segmenti, trovati ${remaining.size}", remaining.size >= 2)
        val remainingDist = remaining.sumOf { it.distanceM }
        assertTrue("rimanenti $remainingDist m, attesi ~650",
            remainingDist > 550 && remainingDist < 750)
        assertEquals(Surface.PAVED, remaining.first().surface)
        assertEquals(Surface.GRAVEL, remaining.last().surface)
    }
}
