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
 * @param physicsFirst when given, every segment the physics model can answer
 *   (climbs and flats above its minimum gradient) takes its speed from the
 *   physics instead of the learned buckets; descents and unanswered segments
 *   fall through to the cascade above. This is the shadow engine's mode: the
 *   production engine never sets it, so its code path is unchanged.
 */
class EtaEngine(
    private val speedModel: AdaptiveSpeedModel,
    private val pipeline:   SegmentPipeline,
    private val fallback:   FallbackSpeedProvider = GradientFactorFallback,
    private val wayMemory:  WaySpeedMemory? = null,
    private val physicsFirst: PhysicsSpeedModel? = null,
) {
    /**
     * @param seconds               predicted moving time for the segments
     * @param fallbackDistanceShare fraction (0..1) of the distance whose speed
     *   came from the fallback rather than a learned bucket
     * @param memoryDistanceShare   fraction (0..1) of the distance on ways already
     *   ridden in this direction, where the remembered speed blended in
     * @param physicsDistanceShare  fraction (0..1) of the distance answered by
     *   [physicsFirst]; always 0 on the production engine
     */
    data class Breakdown(
        val seconds: Long,
        val fallbackDistanceShare: Double,
        val memoryDistanceShare: Double = 0.0,
        val physicsDistanceShare: Double = 0.0,
    )

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
        var physicsM = 0.0
        for (seg in segments) {
            // predict guarantees >= 0.5 m/s (coerceAtLeast in AdaptiveSpeedModel)
            val p = speedModel.predict(seg.gradePct, seg.surface, fallback)
            val phys = physicsFirst?.predictSpeedMs(seg.gradePct, seg.surface)?.coerceAtLeast(0.5)
            var v = phys ?: p.speedMs
            if (phys != null) physicsM += seg.distanceM
            // Memoria per tratto: velocità di base ricordata × fattore di giornata,
            // fusa col bucket pesando i passaggi (n/(n+1)). Il fattore è quello della
            // classe di fondo, lo stesso con cui closeWayPass ha normato la velocità
            // salvata: con quello per pendenza la memoria tornava scalata di
            // f_foglia/f_classe (17/10, replay: 2,3 % medio per passaggio).
            val mem = if (seg.wayKey != 0L) wayMemory?.estimate(seg.wayKey) else null
            if (mem != null) {
                val w = mem.passes / (mem.passes + 1.0)
                v = (w * mem.speedMs * speedModel.dayFactorForSurface(seg.surface) + (1.0 - w) * v).coerceAtLeast(0.5)
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
            if (totalM > 0.0) physicsM / totalM else 0.0,
        )
    }
}
