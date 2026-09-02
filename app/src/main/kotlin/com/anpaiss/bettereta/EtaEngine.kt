package com.anpaiss.bettereta

/**
 * Calcola il tempo rimanente in secondi sommando il tempo stimato
 * per ciascun segmento rimanente del percorso.
 *
 *   tempo_segmento = distanza_m / velocità_prevista(pendenza)
 *
 * Restituisce -1L se non ci sono segmenti disponibili.
 *
 * @param fallback rung 3 of the speed cascade, for gradients with no learned
 *   data. Two engines on the same [AdaptiveSpeedModel] with different fallbacks
 *   are how a candidate fallback is judged against the production one on real
 *   rides: the learned buckets are shared, so the fallback is the only variable.
 */
class EtaEngine(
    private val speedModel: AdaptiveSpeedModel,
    private val pipeline:   SegmentPipeline,
    private val fallback:   FallbackSpeedProvider = GradientFactorFallback,
) {
    /**
     * @param seconds               predicted moving time for the segments
     * @param fallbackDistanceShare fraction (0..1) of the distance whose speed
     *   came from the fallback rather than a learned bucket
     */
    data class Breakdown(val seconds: Long, val fallbackDistanceShare: Double)

    /** Versione suspend: fetcha i segmenti dalla pipeline. */
    suspend fun computeRemainingSeconds(): Long =
        computeFromSegments(pipeline.remainingSegments())

    /** Versione sincrona: accetta segmenti già fetchati — evita un secondo lock sulla pipeline. */
    fun computeFromSegments(segments: List<RouteSegment>): Long =
        computeBreakdown(segments).seconds

    fun computeBreakdown(segments: List<RouteSegment>): Breakdown {
        if (segments.isEmpty()) return Breakdown(-1L, 0.0)
        var totalSec = 0.0
        var totalM = 0.0
        var fallbackM = 0.0
        for (seg in segments) {
            // predict guarantees >= 0.5 m/s (coerceAtLeast in AdaptiveSpeedModel)
            val p = speedModel.predict(seg.gradePct, seg.surface, fallback)
            totalSec += seg.distanceM / p.speedMs
            totalM   += seg.distanceM
            if (p.fromFallback) fallbackM += seg.distanceM
        }
        return Breakdown(totalSec.toLong(), if (totalM > 0.0) fallbackM / totalM else 0.0)
    }
}
