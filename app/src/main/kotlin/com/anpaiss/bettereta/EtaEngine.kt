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
    private val wayMemory:  WaySpeedMemory? = null,
) {
    /**
     * @param seconds               predicted moving time for the segments
     * @param fallbackDistanceShare fraction (0..1) of the distance whose speed
     *   came from the fallback rather than a learned bucket
     * @param memoryDistanceShare   fraction (0..1) of the distance on ways already
     *   ridden in this direction, where the remembered speed blended in
     */
    data class Breakdown(val seconds: Long, val fallbackDistanceShare: Double, val memoryDistanceShare: Double = 0.0)

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
        var memoryM = 0.0
        for (seg in segments) {
            // predict guarantees >= 0.5 m/s (coerceAtLeast in AdaptiveSpeedModel)
            val p = speedModel.predict(seg.gradePct, seg.surface, fallback)
            var v = p.speedMs
            // Memoria per tratto: velocità di base ricordata × fattore di giornata,
            // fusa col bucket pesando i passaggi (n/(n+1))
            val mem = if (seg.wayKey != 0L) wayMemory?.estimate(seg.wayKey) else null
            if (mem != null) {
                val w = mem.passes / (mem.passes + 1.0)
                v = (w * mem.speedMs * speedModel.dayFactorFor(seg.gradePct) + (1.0 - w) * v).coerceAtLeast(0.5)
                memoryM += seg.distanceM
            }
            totalSec += seg.distanceM / v
            totalM   += seg.distanceM
            if (p.fromFallback) fallbackM += seg.distanceM
        }
        return Breakdown(
            totalSec.toLong(),
            if (totalM > 0.0) fallbackM / totalM else 0.0,
            if (totalM > 0.0) memoryM / totalM else 0.0,
        )
    }
}
