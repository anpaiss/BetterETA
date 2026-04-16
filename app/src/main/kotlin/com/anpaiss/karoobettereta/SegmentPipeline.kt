package com.anpaiss.karoobettereta

import kotlin.math.*

/**
 * Converte i waypoint grezzi in segmenti semplificati e traccia
 * la posizione del ciclista sul percorso man mano che avanza.
 *
 * Flusso:
 *   setRoute(waypoints) → costruisce segmenti e resetta la posizione
 *   advanceByDistance(m) → avanza di [m] metri sul percorso
 *   remainingSegments()  → restituisce i segmenti ancora da percorrere
 */
class SegmentPipeline {

    private var segments              = listOf<RouteSegment>()
    private var currentIndex          = 0
    private var consumedInCurrentM    = 0.0

    /** Carica un nuovo percorso e riparte dall'inizio. */
    fun setRoute(waypoints: List<RouteWaypoint>) {
        segments           = buildSegments(waypoints)
        currentIndex       = 0
        consumedInCurrentM = 0.0
    }

    /** Azzera la posizione sul percorso (nuova uscita). */
    fun reset() {
        currentIndex       = 0
        consumedInCurrentM = 0.0
    }

    /** Avanza di [meters] metri sul percorso. */
    fun advanceByDistance(meters: Double) {
        if (meters <= 0.0) return
        var rem = meters
        while (rem > 0.0 && currentIndex < segments.size) {
            val seg       = segments[currentIndex]
            val available = seg.distanceM - consumedInCurrentM
            if (rem < available) {
                consumedInCurrentM += rem
                break
            } else {
                rem -= available
                currentIndex++
                consumedInCurrentM = 0.0
            }
        }
    }

    /** True se ci sono segmenti rimanenti. */
    fun hasSegments(): Boolean = currentIndex < segments.size

    /**
     * Restituisce i segmenti ancora da percorrere.
     * Il primo elemento è la parte rimanente del segmento corrente.
     */
    fun remainingSegments(): List<RouteSegment> {
        if (currentIndex >= segments.size) return emptyList()
        val result = mutableListOf<RouteSegment>()

        val first = segments[currentIndex]
        val firstRemaining = first.distanceM - consumedInCurrentM
        if (firstRemaining > 0.0) {
            result.add(first.copy(distanceM = firstRemaining))
        }
        for (i in currentIndex + 1 until segments.size) {
            result.add(segments[i])
        }
        return result
    }

    // ── costruzione segmenti ─────────────────────────────────────────

    private fun buildSegments(waypoints: List<RouteWaypoint>): List<RouteSegment> {
        if (waypoints.size < 2) return emptyList()

        val raw = mutableListOf<RouteSegment>()
        for (i in 0 until waypoints.size - 1) {
            val dist = haversineM(waypoints[i], waypoints[i + 1])
            if (dist < 1.0) continue   // ignora punti duplicati
            val elevDiff = waypoints[i + 1].elevationM - waypoints[i].elevationM
            val grade    = (elevDiff / dist * 100.0).coerceIn(-30.0, 30.0)
            raw.add(RouteSegment(distanceM = dist, gradePct = grade))
        }
        return simplify(raw)
    }

    /**
     * Unisce segmenti consecutivi con pendenza simile (±2%) per ridurre
     * il numero di elementi da elaborare in [EtaEngine].
     */
    private fun simplify(raw: List<RouteSegment>): List<RouteSegment> {
        if (raw.isEmpty()) return emptyList()
        val out     = mutableListOf<RouteSegment>()
        var current = raw[0]

        for (i in 1 until raw.size) {
            val next = raw[i]
            if (abs(next.gradePct - current.gradePct) <= 2.0) {
                val total   = current.distanceM + next.distanceM
                val avgGrade = (current.gradePct * current.distanceM +
                                next.gradePct    * next.distanceM) / total
                current = RouteSegment(distanceM = total, gradePct = avgGrade)
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }

    // ── Haversine ────────────────────────────────────────────────────

    private fun haversineM(a: RouteWaypoint, b: RouteWaypoint): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val x    = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2.0 * R * asin(sqrt(x))
    }
}
