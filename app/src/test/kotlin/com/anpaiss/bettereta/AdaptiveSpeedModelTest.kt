package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSpeedModelTest {

    // Tolleranza usata negli assert numerici (m/s)
    private fun assertApprox(expected: Double, actual: Double, delta: Double = 0.3) =
        assertEquals(expected, actual, delta)

    @Test
    fun `fresh model returns default flat speed on flat terrain`() {
        val model = AdaptiveSpeedModel()
        // Bucket pianura (−5%..+2%) → factor 1.0 × DEFAULT_FLAT_MS (5.0)
        assertApprox(5.0, model.predictSpeed(0.0))
    }

    @Test
    fun `fresh model applies grade factor for steep climb`() {
        val model = AdaptiveSpeedModel()
        // Bucket >10% → factor 0.50
        val predicted = model.predictSpeed(12.0)
        assertApprox(5.0 * 0.50, predicted)
    }

    @Test
    fun `fresh model applies grade factor for descent`() {
        val model = AdaptiveSpeedModel()
        // Bucket <-5% → factor 1.15
        val predicted = model.predictSpeed(-8.0)
        assertApprox(5.0 * 1.15, predicted)
    }

    @Test
    fun `after enough flat samples returns observed ema`() {
        val model = AdaptiveSpeedModel()
        repeat(10) { model.update(speedMs = 8.0, gradePct = 0.0) }
        val predicted = model.predictSpeed(0.0)
        // EMA converge a ~8 m/s dopo 10 campioni con ALPHA=0.25
        assertTrue("predicted=$predicted deve essere > 6", predicted > 6.0)
        assertTrue("predicted=$predicted deve essere < 9", predicted < 9.0)
    }

    @Test
    fun `slow samples below 0_5 ms are ignored`() {
        val model = AdaptiveSpeedModel()
        repeat(20) { model.update(speedMs = 0.3, gradePct = 0.0) }
        // Nessun campione accettato → deve usare il default
        assertApprox(5.0, model.predictSpeed(0.0))
    }

    @Test
    fun `brief slowdown does not corrupt long-run bucket average`() {
        val model = AdaptiveSpeedModel()
        // 10 minuti a 8 m/s, poi 20s lenti (semaforo, manovra): la previsione
        // deve restare vicina alla media di lungo periodo, non agli ultimi secondi
        repeat(600) { model.update(speedMs = 8.0, gradePct = 0.0) }
        repeat(20)  { model.update(speedMs = 2.0, gradePct = 0.0) }
        val predicted = model.predictSpeed(0.0)
        assertTrue("predicted=$predicted deve restare > 7", predicted > 7.0)
    }

    @Test
    fun `reset clears all learned buckets`() {
        val model = AdaptiveSpeedModel()
        repeat(10) { model.update(speedMs = 10.0, gradePct = 0.0) }
        model.reset()
        // Dopo reset, ritorna ai valori di default
        assertApprox(5.0, model.predictSpeed(0.0))
    }

    @Test
    fun `different grade samples fill different buckets independently`() {
        val model = AdaptiveSpeedModel()
        // Allena bucket pianura a 8 m/s
        repeat(10) { model.update(speedMs = 8.0, gradePct = 0.0) }
        // Allena bucket salita ripida (>10%) a 4 m/s
        repeat(10) { model.update(speedMs = 4.0, gradePct = 12.0) }

        val flat  = model.predictSpeed(0.0)
        val climb = model.predictSpeed(12.0)
        assertTrue("flat=$flat deve essere > climb=$climb", flat > climb)
        assertTrue("flat deve essere vicino a 8", flat > 6.0)
        assertTrue("climb deve essere vicino a 4", climb < 6.0)
    }
}
