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
    private var segments           = listOf<RouteSegment>()
    private var waypoints          = listOf<RouteWaypoint>()
    private var waypointCumDist    = DoubleArray(0)  // distanza cumulativa al waypoint[i]
    private var currentIndex       = 0
    private var consumedInCurrentM = 0.0
    private var traveledM          = 0.0             // metri percorsi dall'inizio del percorso

    /** Carica un nuovo percorso e riparte dall'inizio. */
    suspend fun setRoute(newWaypoints: List<RouteWaypoint>) = mutex.withLock {
        waypoints          = newWaypoints
        segments           = buildSegments(newWaypoints)
        waypointCumDist    = buildCumDist(newWaypoints)
        currentIndex       = 0
        consumedInCurrentM = 0.0
        traveledM          = 0.0
    }

    /** Azzera la posizione sul percorso (nuova uscita). */
    suspend fun reset() = mutex.withLock {
        currentIndex       = 0
        consumedInCurrentM = 0.0
        traveledM          = 0.0
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
                traveledM          += rem
                break
            } else {
                traveledM += available
                rem -= available
                currentIndex++
                consumedInCurrentM = 0.0
            }
        }
    }

    /**
     * Sincronizza la posizione basandosi sulle coordinate GPS reali.
     *
     * Usa la distanza cumulativa precalcolata per trovare la finestra di ricerca
     * corretta nei waypoint originali — evita di confondere l'indice dei segmenti
     * semplificati con l'indice dei waypoint originali.
     *
     * @return correzione applicata in metri (0.0 se nessuno snap effettuato)
     */
    suspend fun snapToLocation(lat: Double, lon: Double): Double = mutex.withLock {
        if (waypoints.isEmpty()) return@withLock 0.0

        // Binary search: primo waypoint con distanza cumulativa >= traveledM
        val searchStart = lowerBound(waypointCumDist, traveledM)
        val searchEnd   = (searchStart + 500).coerceAtMost(waypoints.size)

        var bestIdx = -1
        var minDist = Double.MAX_VALUE
        for (i in searchStart until searchEnd) {
            val d = haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon)
            if (d < minDist) { minDist = d; bestIdx = i }
        }

        if (bestIdx == -1 || minDist >= 100.0) return@withLock 0.0

        val prevTraveledM = traveledM
        repositionTo(waypointCumDist[bestIdx])
        kotlin.math.abs(traveledM - prevTraveledM)
    }

    /** True se ci sono segmenti rimanenti. */
    suspend fun hasSegments(): Boolean = mutex.withLock {
        currentIndex < segments.size
    }

    /** Restituisce i segmenti ancora da percorrere. */
    suspend fun remainingSegments(): List<RouteSegment> = mutex.withLock {
        if (currentIndex >= segments.size) return emptyList()
        val result = mutableListOf<RouteSegment>()
        val first  = segments[currentIndex]
        val firstRemaining = first.distanceM - consumedInCurrentM
        if (firstRemaining > 0.0) result.add(first.copy(distanceM = firstRemaining))
        for (i in currentIndex + 1 until segments.size) result.add(segments[i])
        return result
    }

    // ── private helpers ──────────────────────────────────────────────────

    /** Sposta traveledM/currentIndex/consumedInCurrentM al punto targetM dall'inizio. */
    private fun repositionTo(targetM: Double) {
        traveledM          = targetM
        currentIndex       = 0
        consumedInCurrentM = 0.0
        var rem = targetM
        while (rem > 0.0 && currentIndex < segments.size) {
            val seg = segments[currentIndex]
            if (rem < seg.distanceM) {
                consumedInCurrentM = rem
                break
            }
            rem -= seg.distanceM
            currentIndex++
        }
    }

    /** Lower bound: primo indice i per cui arr[i] >= value. */
    private fun lowerBound(arr: DoubleArray, value: Double): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo.coerceIn(0, (arr.size - 1).coerceAtLeast(0))
    }

    private fun buildCumDist(pts: List<RouteWaypoint>): DoubleArray {
        val arr = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            arr[i] = arr[i - 1] + haversineM(pts[i-1].lat, pts[i-1].lon, pts[i].lat, pts[i].lon)
        }
        return arr
    }

    // ── costruzione segmenti ─────────────────────────────────────────────

    private fun buildSegments(waypoints: List<RouteWaypoint>): List<RouteSegment> {
        if (waypoints.size < 2) return emptyList()
        val raw = mutableListOf<RouteSegment>()
        for (i in 0 until waypoints.size - 1) {
            val dist = haversineM(waypoints[i].lat, waypoints[i].lon, waypoints[i+1].lat, waypoints[i+1].lon)
            if (dist < 1.0) continue
            val elevDiff = waypoints[i+1].elevationM - waypoints[i].elevationM
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
                val total    = current.distanceM + next.distanceM
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
