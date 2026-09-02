package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicsSpeedModelTest {

    private val cutoff = PhysicsSpeedModel.ASSIST_CUTOFF_MS
    private val paved  = PhysicsSpeedModel.crrFor(Surface.PAVED)

    /** Feeds [n] identical samples so the relevant power average becomes trusted. */
    private fun PhysicsSpeedModel.learn(speedMs: Double, gradePct: Double, n: Int = PhysicsSpeedModel.MIN_POWER_SAMPLES) =
        repeat(n) { update(speedMs, gradePct, Surface.PAVED) }

    // ── forward / inverse consistency ────────────────────────────────────

    @Test
    fun `solving the power equation and evaluating it again round-trips`() {
        val m = PhysicsSpeedModel()
        for (grade in listOf(-1.9, -1.0, 0.0, 2.0, 6.0, 12.0, 20.0)) {
            for (power in listOf(80.0, 150.0, 300.0, 600.0)) {
                val v = m.solveSpeed(power, grade, paved)
                if (v > 0.5 && v < 16.7) {   // interior solution: must reproduce the power
                    assertEquals("grade=$grade P=$power", power, m.powerAt(v, grade, paved), 1e-6)
                }
            }
        }
    }

    @Test
    fun `power is monotonic in speed on climbs`() {
        val m = PhysicsSpeedModel()
        var prev = m.powerAt(0.5, 5.0, paved)
        var v = 1.0
        while (v < 16.0) {
            val p = m.powerAt(v, 5.0, paved)
            assertTrue(p > prev); prev = p; v += 0.5
        }
    }

    // ── the property the e-bike argument rests on ───────────────────────

    @Test
    fun `climb-to-climb prediction is nearly independent of the assumed mass`() {
        // Two riders whose configured weight differs by 25 kg, both observed
        // at 3 m/s on an 8 % climb. Predicting 12 % must give the same answer:
        // the gravity term dominates and m cancels (v2 = v1 * s1 / s2).
        val light = PhysicsSpeedModel().apply { setRiderMassKg(60.0); learn(3.0, 8.0) }
        val heavy = PhysicsSpeedModel().apply { setRiderMassKg(85.0); learn(3.0, 8.0) }
        val vLight = light.predictSpeedMs(12.0, Surface.PAVED)!!
        val vHeavy = heavy.predictSpeedMs(12.0, Surface.PAVED)!!
        assertEquals(vLight, vHeavy, vLight * 0.03)      // within 3 %
        assertEquals(3.0 * 8.0 / 12.0, vLight, 0.15)     // and close to the ideal ratio
    }

    // ── assist-cutoff regime selection ──────────────────────────────────

    @Test
    fun `regime selection covers all three outcomes`() {
        // Assisted regime: 5 m/s up 6 % (a lot of effective power, motor included).
        // Unassisted regime: 6.8 m/s on the flat (rider alone, little power).
        val m = PhysicsSpeedModel().apply {
            learn(5.0, 6.0)
            learn(6.8, 0.0)
        }
        assertTrue(m.lowSamples >= PhysicsSpeedModel.MIN_POWER_SAMPLES)
        assertTrue(m.highSamples >= PhysicsSpeedModel.MIN_POWER_SAMPLES)

        // 1. Steep enough that the assisted answer sits below the cutoff: use it.
        val steep = m.predictSpeedMs(6.0, Surface.PAVED)!!
        assertEquals(5.0, steep, 0.05)
        assertTrue(steep < cutoff)

        // 2. Flat: assisted power would give > cutoff, but there the rider is
        //    unassisted; the unassisted answer is itself >= cutoff, so use that.
        val flat = m.predictSpeedMs(0.0, Surface.PAVED)!!
        assertEquals(6.8, flat, 0.05)

        // 3. Gentle climb: assisted answer > cutoff, unassisted answer < cutoff.
        //    Neither regime is self-consistent: the rider sits pinned at the cutoff.
        assertEquals(cutoff, m.predictSpeedMs(2.0, Surface.PAVED)!!, 1e-9)
    }

    @Test
    fun `with only the assisted regime learned, the answer is used even above the cutoff`() {
        val m = PhysicsSpeedModel().apply { learn(5.0, 6.0) }
        val flat = m.predictSpeedMs(0.0, Surface.PAVED)!!
        assertTrue("expected > cutoff, got $flat", flat > cutoff)
    }

    // ── what the model refuses to answer ────────────────────────────────

    @Test
    fun `no answer until enough samples`() {
        val m = PhysicsSpeedModel()
        assertNull(m.predictSpeedMs(5.0, Surface.PAVED))
        m.learn(4.0, 5.0, n = PhysicsSpeedModel.MIN_POWER_SAMPLES - 1)
        assertNull(m.predictSpeedMs(5.0, Surface.PAVED))
        m.update(4.0, 5.0, Surface.PAVED)
        assertNotNull(m.predictSpeedMs(5.0, Surface.PAVED))
    }

    @Test
    fun `descents are left to the learned buckets`() {
        val m = PhysicsSpeedModel().apply { learn(4.0, 5.0) }
        assertNull(m.predictSpeedMs(PhysicsSpeedModel.MIN_GRADE_PCT - 0.1, Surface.PAVED))
        assertNotNull(m.predictSpeedMs(PhysicsSpeedModel.MIN_GRADE_PCT, Surface.PAVED))
    }

    @Test
    fun `samples that carry no information are rejected`() {
        val m = PhysicsSpeedModel()
        m.update(0.3, 5.0, Surface.PAVED)                                  // standing still
        m.update(5.0, PhysicsSpeedModel.MIN_GRADE_PCT - 1, Surface.PAVED)  // real descent
        m.update(3.0, -1.9, Surface.PAVED)                                 // coasting: implied power < 0
        assertEquals(0, m.lowSamples + m.highSamples)
        m.update(10.0, -1.9, Surface.PAVED)                                // pedalling hard downhill: P > 0
        assertEquals(1, m.highSamples)
    }

    // ── mass handling and persistence ───────────────────────────────────

    @Test
    fun `a material weight change resets what was learned, a trivial one does not`() {
        val m = PhysicsSpeedModel().apply { learn(4.0, 5.0) }
        assertFalse(m.setRiderMassKg(PhysicsSpeedModel.DEFAULT_RIDER_KG + 0.4))
        assertTrue(m.lowSamples > 0)
        assertTrue(m.setRiderMassKg(PhysicsSpeedModel.DEFAULT_RIDER_KG + 10.0))
        assertEquals(0, m.lowSamples + m.highSamples)
        assertEquals(PhysicsSpeedModel.DEFAULT_RIDER_KG + 10.0 + PhysicsSpeedModel.BIKE_MASS_KG, m.massKg, 1e-9)
        assertFalse(m.setRiderMassKg(0.0))
    }

    @Test
    fun `snapshot and restore round-trip`() {
        val a = PhysicsSpeedModel().apply { setRiderMassKg(70.0); learn(5.0, 6.0); learn(6.8, 0.0) }
        val b = PhysicsSpeedModel().apply { restore(a.snapshot()) }
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.predictSpeedMs(4.0, Surface.UNPAVED), b.predictSpeedMs(4.0, Surface.UNPAVED))
    }

    // ── wiring into the cascade ─────────────────────────────────────────

    @Test
    fun `physics fallback delegates when the model cannot answer`() {
        val physics  = PhysicsSpeedModel()
        val fallback = PhysicsFallback(physics)
        val flat = 6.0
        // Cold: identical to the factors.
        assertEquals(GradientFactorFallback.fallbackSpeedMs(8.0, Surface.UNKNOWN, flat),
            fallback.fallbackSpeedMs(8.0, Surface.UNKNOWN, flat), 1e-9)
        physics.learn(4.0, 5.0)
        // Warm: climbs come from the physics, descents still from the factors.
        assertEquals(physics.predictSpeedMs(8.0, Surface.UNKNOWN)!!,
            fallback.fallbackSpeedMs(8.0, Surface.UNKNOWN, flat), 1e-9)
        assertEquals(GradientFactorFallback.fallbackSpeedMs(-8.0, Surface.UNKNOWN, flat),
            fallback.fallbackSpeedMs(-8.0, Surface.UNKNOWN, flat), 1e-9)
    }

    @Test
    fun `the engine reports how much distance the fallback served`() {
        val learned = AdaptiveSpeedModel()
        repeat(10) { learned.update(6.0, 0.0) }   // flat bucket trusted, nothing else
        val engine = EtaEngine(learned, SegmentPipeline())
        val segs = listOf(
            RouteSegment(distanceM = 3000.0, gradePct = 0.0),   // learned
            RouteSegment(distanceM = 1000.0, gradePct = 8.0),   // fallback
        )
        val b = engine.computeBreakdown(segs)
        assertEquals(0.25, b.fallbackDistanceShare, 1e-9)
        assertTrue(b.seconds > 0)
        assertEquals(engine.computeFromSegments(segs), b.seconds)
    }
}
