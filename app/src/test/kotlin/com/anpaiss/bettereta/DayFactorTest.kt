package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two time scales: a long base per bucket plus a day factor learned during
 * the ride. The factor is what lets ten slow minutes of climbing lower the
 * flat and the descent still ahead, and what dies with the ride so the next
 * one starts from the base again.
 */
class DayFactorTest {

    private fun trained(): AdaptiveSpeedModel = AdaptiveSpeedModel().apply {
        restore(AdaptiveSpeedModel.Snapshot(
            overallEma = 7.63, overallSamples = 67661,
            bucketEmas   = listOf(7.7, 6.5, 6.0, 3.8, 3.4, 3.0),
            bucketCounts = listOf(15785, 9000, 9994, 8499, 16288, 8095),
        ))
    }

    @Test
    fun `a fresh ride predicts the base`() {
        val m = trained()
        assertEquals(1.0, m.currentDayFactor, 1e-9)
        assertEquals(6.0, m.predictSpeed(0.0), 1e-9)
        assertEquals(3.4, m.predictSpeed(8.0), 1e-9)
    }

    @Test
    fun `slow climbing lowers the prediction for gradients not yet ridden`() {
        val m = trained()
        repeat(600) { m.update(3.4 * 0.6, 8.0) }       // ten minutes at 60% of the base on the steep bucket
        // Global prior 400 (17/09, was 120): (400 + 600 × 0.6) / 1000 = 0.76 — it used to reach 0.67
        assertTrue("factor ${m.currentDayFactor}", m.currentDayFactor in 0.7..0.85)
        val flat = m.predictSpeed(0.0)
        assertTrue("flat $flat should be scaled by the global factor", flat < 6.0 * 0.85 && flat > 6.0 * 0.6)
        val descent = m.predictSpeed(-10.0)
        assertTrue("descent $descent", descent < 7.7 * 0.85)
    }

    @Test
    fun `a bucket with its own samples uses its own factor over the global one`() {
        val m = trained()
        repeat(600) { m.update(3.4 * 0.6, 8.0) }       // climbing at 60%
        repeat(600) { m.update(7.7 * 1.0, -10.0) }     // descending at 100% of the base
        val descent = m.predictSpeed(-10.0)
        assertTrue("descent $descent should be near its base", descent > 7.7 * 0.9)
        val climb = m.predictSpeed(8.0)
        assertTrue("climb $climb should stay slow", climb < 3.4 * 0.7)
    }

    @Test
    fun `the base moves slowly while the factor moves fast`() {
        val m = trained()
        repeat(600) { m.update(3.0, 0.0) }             // ten minutes on the flat at half the base speed
        m.newRide()
        // Base after 600 samples over a 21600 window: nudged, not rewritten
        val base = m.predictSpeed(0.0)
        assertTrue("base $base", base > 5.6 && base < 6.0)
    }

    @Test
    fun `newRide resets the factor and keeps the base`() {
        val m = trained()
        repeat(600) { m.update(3.0, 0.0) }
        assertTrue(m.currentDayFactor < 0.75)   // (400 + 600 × 0.5) / 1000 = 0.70 with the global prior at 400
        m.newRide()
        assertEquals(1.0, m.currentDayFactor, 1e-9)
        assertTrue(m.predictSpeed(8.0) > 3.3)
    }

    @Test
    fun `factor state survives a snapshot round trip and a legacy snapshot starts at 1`() {
        val m = trained()
        repeat(300) { m.update(3.4 * 0.6, 8.0) }
        val back = AdaptiveSpeedModel().apply { restore(m.snapshot()) }
        assertEquals(m.currentDayFactor, back.currentDayFactor, 1e-9)
        assertEquals(m.predictSpeed(8.0), back.predictSpeed(8.0), 1e-9)
        assertEquals(m.predictSpeed(0.0), back.predictSpeed(0.0), 1e-9)

        val legacy = AdaptiveSpeedModel().apply {
            restore(AdaptiveSpeedModel.Snapshot(7.0, 100, listOf(7.7, 6.0, 3.8, 3.4, 3.0), listOf(20, 20, 20, 20, 20)))
        }
        assertEquals(1.0, legacy.currentDayFactor, 1e-9)
        assertEquals(AdaptiveSpeedModel.BUCKETS, legacy.effectiveFactors().size)
    }

    private fun trainedBySurface(): AdaptiveSpeedModel = AdaptiveSpeedModel().apply {
        restore(AdaptiveSpeedModel.Snapshot(
            overallEma = 4.5, overallSamples = 100000,
            bucketEmas    = listOf(5.8, 5.8, 4.9, 3.5, 2.9, 2.5, 2.1), bucketCounts  = List(7) { 10000 },
            pavedEmas     = listOf(9.2, 6.9, 6.3, 4.5, 3.6, 2.9, 2.1), pavedCounts   = List(7) { 2000 },
            unpavedEmas   = listOf(4.7, 4.7, 3.8, 3.0, 2.75, 2.5, 2.2), unpavedCounts = List(7) { 2000 },
        ))
    }

    @Test
    fun `a slow rough descent does not slow the tarmac ridden at its usual pace`() {
        // 29/09, Pasubio: tarmac climb at the base, then the Scarubbi descent at 75% of the base
        val m = trainedBySurface()
        repeat(750) { m.update(3.6, 8.0, Surface.PAVED) }
        repeat(1200) { m.update(4.7 * 0.75, -10.0, Surface.GRAVEL) }
        assertTrue("global ${m.currentDayFactor}", m.currentDayFactor < 0.9)
        val tarmac = m.predictSpeed(-4.0, Surface.PAVED)
        assertTrue("tarmac $tarmac should stay near its base", tarmac > 6.9 * 0.95)
        val gravel = m.predictSpeed(-10.0, Surface.GRAVEL)
        assertTrue("gravel descent $gravel should be slow", gravel < 4.7 * 0.82)
        // Off tarmac the slowdown still reaches gradients not ridden yet, trail included
        val trail = m.predictSpeed(0.0, Surface.TRAIL)
        assertTrue("trail flat $trail", trail < 4.9 * 0.9)
        assertTrue(m.dayFactorForSurface(Surface.PAVED) > 0.95)
        assertTrue(m.dayFactorForSurface(Surface.GRAVEL) < 0.82)
    }

    @Test
    fun `a surface not ridden yet inherits the global factor`() {
        val m = trainedBySurface()
        repeat(600) { m.update(2.75 * 0.6, 8.0, Surface.GRAVEL) }
        assertEquals(m.currentDayFactor, m.dayFactorForSurface(Surface.PAVED), 1e-9)
        assertEquals(6.3 * m.currentDayFactor, m.predictSpeed(0.0, Surface.PAVED), 1e-9)
    }

    @Test
    fun `surface factors survive a snapshot round trip and newRide resets them`() {
        val m = trainedBySurface()
        repeat(300) { m.update(3.6 * 1.2, 8.0, Surface.PAVED) }
        repeat(300) { m.update(4.7 * 0.7, -10.0, Surface.GRAVEL) }
        val back = AdaptiveSpeedModel().apply { restore(m.snapshot()) }
        assertEquals(m.predictSpeed(8.0, Surface.PAVED), back.predictSpeed(8.0, Surface.PAVED), 1e-9)
        assertEquals(m.predictSpeed(-10.0, Surface.GRAVEL), back.predictSpeed(-10.0, Surface.GRAVEL), 1e-9)
        assertEquals(m.dayFactorForSurface(Surface.GRAVEL), back.dayFactorForSurface(Surface.GRAVEL), 1e-9)
        m.newRide()
        assertEquals(1.0, m.dayFactorForSurface(Surface.PAVED), 1e-9)
        assertEquals(1.0, m.dayFactorFor(-10.0, Surface.GRAVEL), 1e-9)
    }

    @Test
    fun `an untrained model still answers through the fallback`() {
        val m = AdaptiveSpeedModel()
        assertEquals(5.0, m.predictSpeed(0.0), 1e-9)
        repeat(50) { m.update(4.0, 0.0) }
        assertTrue(m.predictSpeed(0.0) > 0.5)
    }
}
