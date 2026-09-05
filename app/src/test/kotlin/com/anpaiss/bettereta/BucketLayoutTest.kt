package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Six gradient buckets since 05/09. The old flat bucket (-5..+2%) mixed
 * gentle descents at 15 km/h with true flat at 10; the split gives each its
 * own number, and a saved five-bucket model must come back sensible.
 */
class BucketLayoutTest {

    @Test
    fun `thresholds place gradients in the six buckets`() {
        assertEquals(0, AdaptiveSpeedModel.bucketIndex(-12.0))
        assertEquals(0, AdaptiveSpeedModel.bucketIndex(-6.1))
        assertEquals(1, AdaptiveSpeedModel.bucketIndex(-6.0))
        assertEquals(1, AdaptiveSpeedModel.bucketIndex(-4.0))
        assertEquals(2, AdaptiveSpeedModel.bucketIndex(-2.0))
        assertEquals(2, AdaptiveSpeedModel.bucketIndex(0.0))
        assertEquals(2, AdaptiveSpeedModel.bucketIndex(1.9))
        assertEquals(3, AdaptiveSpeedModel.bucketIndex(2.0))
        assertEquals(4, AdaptiveSpeedModel.bucketIndex(6.0))
        assertEquals(5, AdaptiveSpeedModel.bucketIndex(10.0))
        assertEquals(5, AdaptiveSpeedModel.bucketIndex(25.0))
        assertEquals(AdaptiveSpeedModel.BUCKETS, AdaptiveSpeedModel.bucketIndex(99.0) + 1)
    }

    @Test
    fun `a five-bucket list is split at the old flat bucket`() {
        assertEquals(listOf(7.7, 6.0, 6.0, 3.8, 3.4, 3.0),
            AdaptiveSpeedModel.migrateLegacyFive(listOf(7.7, 6.0, 3.8, 3.4, 3.0)))
        assertEquals(emptyList<Int>(), AdaptiveSpeedModel.migrateLegacyFive(emptyList<Int>()))
        val six = listOf(1, 2, 3, 4, 5, 6)
        assertEquals(six, AdaptiveSpeedModel.migrateLegacyFive(six))
    }

    @Test
    fun `restoring a five-bucket snapshot predicts the old flat speed on both new buckets`() {
        val model = AdaptiveSpeedModel()
        model.restore(AdaptiveSpeedModel.Snapshot(
            overallEma = 7.63, overallSamples = 67661,
            bucketEmas   = listOf(7.7, 6.0, 3.8, 3.4, 3.0),
            bucketCounts = listOf(15785, 18994, 8499, 16288, 8095),
        ))
        assertEquals(7.7, model.predictSpeed(-10.0), 0.001)   // descent
        assertEquals(6.0, model.predictSpeed(-4.0), 0.001)    // gentle descent ← old flat
        assertEquals(6.0, model.predictSpeed(0.0), 0.001)     // flat ← old flat
        assertEquals(3.8, model.predictSpeed(4.0), 0.001)     // climb
        assertEquals(3.4, model.predictSpeed(8.0), 0.001)     // steep
        assertEquals(3.0, model.predictSpeed(15.0), 0.001)    // wall
        assertEquals(AdaptiveSpeedModel.BUCKETS, model.snapshot().bucketEmas.size)
    }

    @Test
    fun `gentle descent and flat learn separately after the split`() {
        val model = AdaptiveSpeedModel()
        // The grade is smoothed (α = 0.1), so the first samples after the switch
        // still land in the previous bucket: enough samples to make that noise.
        repeat(300) { model.update(4.3, -4.0) }
        repeat(300) { model.update(2.7, 0.5) }
        assertEquals(4.3, model.predictSpeed(-4.0), 0.15)
        assertEquals(2.7, model.predictSpeed(0.5), 0.05)
    }
}
