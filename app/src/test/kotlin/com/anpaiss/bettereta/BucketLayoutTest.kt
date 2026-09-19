package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Seven gradient buckets since 19/09: the wall above 16% split from 10..16%
 * (ride 19/09, 2 km at 18-21% ridden at 7.7 km/h against a bucket at 9.0).
 * Six gradient buckets since 05/09. The old flat bucket (-5..+2%) mixed
 * gentle descents at 15 km/h with true flat at 10; the split gives each its
 * own number, and a saved five-bucket model must come back sensible.
 */
class BucketLayoutTest {

    @Test
    fun `thresholds place gradients in the seven buckets`() {
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
        assertEquals(5, AdaptiveSpeedModel.bucketIndex(15.9))
        assertEquals(6, AdaptiveSpeedModel.bucketIndex(16.0))
        assertEquals(6, AdaptiveSpeedModel.bucketIndex(25.0))
        assertEquals(AdaptiveSpeedModel.BUCKETS, AdaptiveSpeedModel.bucketIndex(99.0) + 1)
    }

    @Test
    fun `older lists are split at the old flat bucket and at the old wall`() {
        assertEquals(listOf(7.7, 6.0, 6.0, 3.8, 3.4, 3.0, 3.0),
            AdaptiveSpeedModel.migrateLegacy(listOf(7.7, 6.0, 3.8, 3.4, 3.0)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 6), AdaptiveSpeedModel.migrateLegacy(listOf(1, 2, 3, 4, 5, 6)))
        assertEquals(emptyList<Int>(), AdaptiveSpeedModel.migrateLegacy(emptyList<Int>()))
        val seven = listOf(1, 2, 3, 4, 5, 6, 7)
        assertEquals(seven, AdaptiveSpeedModel.migrateLegacy(seven))
        assertEquals(seven, AdaptiveSpeedModel.migrateLegacyCounts(seven))
    }

    @Test
    fun `the bucket born from a split starts with few samples so it can move`() {
        assertEquals(listOf(10, 20, 30, 40, 50, 34498, 600),
            AdaptiveSpeedModel.migrateLegacyCounts(listOf(10, 20, 30, 40, 50, 34498)))
        assertEquals(listOf(10, 20, 30, 40, 50, 80, 80),
            AdaptiveSpeedModel.migrateLegacyCounts(listOf(10, 20, 30, 40, 50, 80)))

        val model = AdaptiveSpeedModel()
        model.restore(AdaptiveSpeedModel.Snapshot(
            overallEma = 4.4, overallSamples = 230000,
            bucketEmas   = listOf(5.9, 5.8, 4.8, 3.5, 2.9, 2.5),
            bucketCounts = listOf(40000, 22000, 53000, 31000, 52000, 34000),
        ))
        assertEquals(2.5, model.predictSpeed(20.0), 0.001)          // starts from its parent
        repeat(900) { model.update(2.1, 20.0) }                     // fifteen minutes on a real wall
        model.newRide()                                             // judge the base, not the day factor
        assertEquals(2.26, model.predictSpeed(20.0), 0.03)          // (600 × 2.5 + 900 × 2.1) / 1500
        assertEquals(2.5, model.predictSpeed(12.0), 0.01)           // the 10..16% bucket did not move
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
        assertEquals(3.0, model.predictSpeed(20.0), 0.001)    // wall above 16% ← old wall
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
