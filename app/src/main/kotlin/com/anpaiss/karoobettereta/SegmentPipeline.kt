package com.anpaiss.karoobettereta

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/**
 * Converte i waypoint grezzi in segmenti semplificati e traccia
 * la posizione del ciclista sul percorso man mano che avanza.
 */
class SegmentPipeline {

    private val mutex = Mutex()
    private var segments              = listOf<RouteSegment>()
    private var waypoints             = listOf<RouteWaypoint>() // Teniamo i waypoint per lo snap GPS
    private var currentIndex          = 0
    private var consumedInCurrentM    = 0.0

    /** Carica un nuovo percorso e riparte dall'inizio. */
    suspend fun setRoute(newWaypoints: List<RouteWaypoint>) = mutex.withLock {
        waypoints          = newWaypoints
        segments           = buildSegments(newWaypoints)
        currentIndex       = 0
        consumedInCurrentM = 0.0
    }

    /** Azzera la posizione sul percorso (nuova uscita). */
    suspend fun reset() = mutex.withLock {
        currentIndex       = 0
        consumedInCurrentM = 0.0
    }

    /** Avanza di [meters] metri sul percorso. */
    suspend fun advanceByDistance(meters: Double) = mutex.withLock {
        if (meters <= 0.0) return@withLock
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

    /**
     * Sincronizza la posizione basandosi sulle coordinate GPS reali.
     * Cerca il punto più vicino sul percorso originale.
     */
    suspend fun snapToLocation(lat: Double, lon: Double) = mutex.withLock {
        if (waypoints.isEmpty()) return@withLock

        // Cerchiamo il waypoint più vicino (semplificato: entro i prossimi 2km dal punto attuale per performance)
        var bestIdx = -1
        var minSourceDist = Double.MAX_VALUE
        
        // Cerchiamo in un intorno della posizione attuale per evitare salti all'indietro o troppo avanti
        val searchStart = (currentIndex).coerceIn(0, waypoints.size)
        val searchEnd   = (searchStart + 500).coerceAtMost(waypoints.size) 

        for (i in searchStart until searchEnd) {
            val d = haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon)
            if (d < minSourceDist) {
                minSourceDist = d
                bestIdx = i
            }
        }

        // Se troviamo un punto entro 100m, sincronizziamo
        if (bestIdx != -1 && minSourceDist < 100.0) {
            // Calcoliamo la distanza totale dal waypoint 0 al waypoint trovato
            var distFromStart = 0.0
            for (i in 0 until bestIdx) {
                distFromStart += haversineM(waypoints[i].lat, waypoints[i].lon, waypoints[i+1].lat, waypoints[i+1].lon)
            }
            
            // Applichiamo la nuova posizione
            val currentSegmentsTotal = segments.sumOf { it.distanceM }
            val targetTraveled = distFromStart.coerceIn(0.0, currentSegmentsTotal)
            
            // Reset virtuale e avanzamento fino al punto GPS
            currentIndex = 0
            consumedInCurrentM = 0.0
            
            var rem = targetTraveled
            while (rem > 0.0 && currentIndex < segments.size) {
                val seg = segments[currentIndex]
                if (rem < seg.distanceM) {
                    consumedInCurrentM = rem
                    break
                } else {
                    rem -= seg.distanceM
                    currentIndex++
                }
            }
        }
    }

    /** True se ci sono segmenti rimanenti. */
    suspend fun hasSegments(): Boolean = mutex.withLock {
        currentIndex < segments.size
    }

    /**
     * Restituisce i segmenti ancora da percorrere.
     */
    suspend fun remainingSegments(): List<RouteSegment> = mutex.withLock {
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
            val dist = haversineM(waypoints[i].lat, waypoints[i].lon, waypoints[i + 1].lat, waypoints[i + 1].lon)
            if (dist < 1.0) continue
            val elevDiff = waypoints[i + 1].elevationM - waypoints[i].elevationM
            val grade    = (elevDiff / dist * 100.0).coerceIn(-30.0, 30.0)
            raw.add(RouteSegment(distanceM = dist, gradePct = grade))
        }
        return simplify(raw)
    }

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

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val x    = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        return 2.0 * R * asin(sqrt(x))
    }
}
