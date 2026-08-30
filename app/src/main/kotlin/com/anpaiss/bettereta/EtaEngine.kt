package com.anpaiss.bettereta

/**
 * Calcola il tempo rimanente in secondi sommando il tempo stimato
 * per ciascun segmento rimanente del percorso.
 *
 *   tempo_segmento = distanza_m / velocità_prevista(pendenza)
 *
 * Restituisce -1L se non ci sono segmenti disponibili.
 */
class EtaEngine(
    private val speedModel: AdaptiveSpeedModel,
    private val pipeline:   SegmentPipeline,
) {
    /** Versione suspend: fetcha i segmenti dalla pipeline. */
    suspend fun computeRemainingSeconds(): Long =
        computeFromSegments(pipeline.remainingSegments())

    /** Versione sincrona: accetta segmenti già fetchati — evita un secondo lock sulla pipeline. */
    fun computeFromSegments(segments: List<RouteSegment>): Long {
        if (segments.isEmpty()) return -1L
        var totalSec = 0.0
        for (seg in segments) {
            // predictSpeed garantisce sempre ≥ 0.5 m/s (coerceAtLeast in AdaptiveSpeedModel)
            val speedMs = speedModel.predictSpeed(seg.gradePct, seg.surface)
            totalSec += seg.distanceM / speedMs
        }
        return totalSec.toLong()
    }
}
