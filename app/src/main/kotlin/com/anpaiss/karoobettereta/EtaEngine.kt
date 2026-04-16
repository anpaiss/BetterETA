package com.anpaiss.karoobettereta

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
    fun computeRemainingSeconds(): Long {
        val segments = pipeline.remainingSegments()
        if (segments.isEmpty()) return -1L

        var totalSec = 0.0
        for (seg in segments) {
            val speedMs = speedModel.predictSpeed(seg.gradePct)
            if (speedMs < 0.1) continue
            totalSec += seg.distanceM / speedMs
        }
        return totalSec.toLong()
    }
}
