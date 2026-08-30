package com.anpaiss.bettereta

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/**
 * Converte i waypoint grezzi in segmenti semplificati e traccia
 * la posizione del ciclista sul percorso man mano che avanza.
 *
 * @param nowMs sorgente dell'orologio, iniettabile nei test.
 */
class SegmentPipeline(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()
    private var segments           = listOf<RouteSegment>()
    private var waypoints          = listOf<RouteWaypoint>()
    private var waypointSurfaces   = listOf<Surface>()   // parallelo a waypoints; vuoto = non classificato
    private var waypointCumDist    = DoubleArray(0)  // distanza cumulativa al waypoint[i]
    private var currentIndex       = 0
    private var consumedInCurrentM = 0.0
    private var traveledM          = 0.0             // metri percorsi dall'inizio del percorso

    // Distanza cumulativa (lungo il percorso) del waypoint geograficamente più
    // lontano dalla partenza — tipicamente il punto di svolta di un andata/ritorno,
    // non necessariamente il più alto. -1 finché non c'è una route.
    private var farthestPointCumDistM = -1.0

    // Stato dello snap GPS
    private var located            = false  // false finché il GPS non ha mai agganciato la route
    private var offWindowTicks     = 0      // snap falliti consecutivi nella finestra locale
    private var rescueCandidateM   = -1.0   // posizione proposta dalla ricerca globale
    private var rescueConfirmCount = 0      // conferme consecutive coerenti della proposta
    private var lastGoodSnapMs     = 0L     // orologio reale dell'ultimo aggancio riuscito

    /** Carica un nuovo percorso e riparte dall'inizio. */
    suspend fun setRoute(newWaypoints: List<RouteWaypoint>) = mutex.withLock {
        waypoints          = newWaypoints
        waypointSurfaces   = emptyList()
        segments           = buildSegments(newWaypoints, emptyList())
        waypointCumDist    = buildCumDist(newWaypoints)
        currentIndex       = 0
        consumedInCurrentM = 0.0
        traveledM          = 0.0
        located            = false
        offWindowTicks     = 0
        rescueCandidateM   = -1.0
        rescueConfirmCount = 0
        farthestPointCumDistM = findFarthestPointCumDist(newWaypoints, waypointCumDist)
    }

    /** Azzera la posizione sul percorso (nuova uscita). */
    suspend fun reset() = mutex.withLock {
        currentIndex       = 0
        consumedInCurrentM = 0.0
        traveledM          = 0.0
        located            = false
        offWindowTicks     = 0
        rescueCandidateM   = -1.0
        rescueConfirmCount = 0
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
     * Cerca in una finestra CENTRATA sulla posizione stimata (quindi può correggere
     * anche all'indietro) e tra i waypoint vicini al fix sceglie quello più coerente
     * con la posizione stimata, non il più vicino al GPS: su percorsi andata/ritorno
     * o che si incrociano evita di saltare sul ramo sbagliato.
     *
     * Se il fix resta fuori finestra a lungo (route caricata a metà giro, posizione
     * agganciata male fino a "fine percorso"), una ricerca su tutta la route
     * riaggancia la posizione dopo [RESCUE_CONFIRM_TICKS] proposte coerenti.
     *
     * @return correzione applicata in metri (0.0 se nessuno snap effettuato)
     */
    suspend fun snapToLocation(lat: Double, lon: Double): Double = mutex.withLock {
        if (waypoints.isEmpty()) return@withLock 0.0

        val windowStart = lowerBound(waypointCumDist, traveledM - SNAP_BACK_WINDOW_M)
        val windowEnd   = lowerBound(waypointCumDist, traveledM + SNAP_FWD_WINDOW_M)

        var bestIdx    = -1
        var bestOffset = Double.MAX_VALUE
        for (i in windowStart until windowEnd) {
            if (haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon) >= SNAP_MAX_DIST_M) continue
            val offset = abs(waypointCumDist[i] - traveledM)
            if (offset < bestOffset) { bestOffset = offset; bestIdx = i }
        }

        if (bestIdx != -1) {
            located            = true
            offWindowTicks     = 0
            rescueCandidateM   = -1.0
            rescueConfirmCount = 0
            lastGoodSnapMs     = nowMs()
            val prevTraveledM = traveledM
            repositionTo(waypointCumDist[bestIdx])
            return@withLock abs(traveledM - prevTraveledM)
        }

        // Nessun aggancio nella finestra locale
        offWindowTicks++
        if (located && offWindowTicks < RESCUE_AFTER_TICKS) return@withLock 0.0

        // Ricerca globale: tra i waypoint di tutta la route vicini al fix sceglie
        // quello più coerente con la posizione stimata (stesso criterio dello snap
        // locale). Il waypoint in assoluto più vicino al fix può stare sul ramo
        // sbagliato — anelli con partenza=arrivo, andata/ritorno — e teleportare
        // la posizione a inizio o fine route.
        var globalIdx    = -1
        var globalOffset = Double.MAX_VALUE
        for (i in waypoints.indices) {
            if (haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon) >= SNAP_MAX_DIST_M) continue
            val offset = abs(waypointCumDist[i] - traveledM)
            if (offset < globalOffset) { globalOffset = offset; globalIdx = i }
        }
        if (globalIdx == -1) {
            rescueCandidateM   = -1.0
            rescueConfirmCount = 0
            return@withLock 0.0
        }

        // Accetta il riaggancio solo dopo proposte consecutive coerenti tra loro
        val candidateM = waypointCumDist[globalIdx]
        rescueConfirmCount =
            if (rescueCandidateM >= 0 && abs(candidateM - rescueCandidateM) < RESCUE_COHERENCE_M)
                rescueConfirmCount + 1
            else 1
        rescueCandidateM = candidateM
        if (rescueConfirmCount < RESCUE_CONFIRM_TICKS) return@withLock 0.0

        // Guardia di plausibilità: dall'ultimo aggancio buono il ciclista può
        // essersi spostato al massimo di tempo_trascorso × velocità_massima.
        // Orologio reale, così una pausa lunga con bici in movimento (Karoo in
        // auto-pausa) allarga la finestra e il riaggancio legittimo passa,
        // mentre un teletrasporto a inizio/fine route dopo pochi secondi
        // fuori traccia viene rifiutato finché non diventa plausibile.
        if (located) {
            val elapsedSec = (nowMs() - lastGoodSnapMs) / 1000.0
            val maxJumpM   = elapsedSec * RESCUE_MAX_SPEED_MS + RESCUE_JUMP_MARGIN_M
            if (abs(candidateM - traveledM) > maxJumpM) return@withLock 0.0
        }

        located            = true
        offWindowTicks     = 0
        rescueCandidateM   = -1.0
        rescueConfirmCount = 0
        lastGoodSnapMs     = nowMs()
        val prevTraveledM = traveledM
        repositionTo(candidateM)
        return@withLock abs(traveledM - prevTraveledM)
    }

    /**
     * Applica la classificazione fondo (parallela ai waypoint della route corrente)
     * e ricostruisce i segmenti preservando la posizione raggiunta. Arriva in
     * ritardo rispetto a setRoute perché la classificazione OSM è asincrona.
     */
    suspend fun applySurfaces(surfaces: List<Surface>) = mutex.withLock {
        if (waypoints.isEmpty() || surfaces.size != waypoints.size) return@withLock
        waypointSurfaces = surfaces
        val savedM = traveledM
        segments = buildSegments(waypoints, surfaces)
        repositionTo(savedM)
    }

    /** Fondo del segmento su cui si trova ora il ciclista. */
    suspend fun currentSurface(): Surface = mutex.withLock {
        segments.getOrNull(currentIndex)?.surface ?: Surface.UNKNOWN
    }

    /**
     * Distanza rimanente (m) lungo il percorso fino al punto geograficamente
     * più lontano dalla partenza (tipico punto di svolta di un andata/ritorno).
     * 0 una volta superato; -1 se non c'è una route caricata.
     */
    suspend fun distanceToFarthestPointM(): Double = mutex.withLock {
        if (farthestPointCumDistM < 0.0) return@withLock -1.0
        (farthestPointCumDistM - traveledM).coerceAtLeast(0.0)
    }

    /** True se una route è caricata, indipendentemente dalla posizione su di essa. */
    suspend fun hasRoute(): Boolean = mutex.withLock {
        segments.isNotEmpty()
    }

    /** True se il GPS ha agganciato la route almeno una volta dal caricamento. */
    suspend fun isLocated(): Boolean = mutex.withLock {
        located
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

    /** Lower bound: primo indice i per cui arr[i] >= value. Può restituire arr.size se tutti gli elementi sono < value. */
    private fun lowerBound(arr: DoubleArray, value: Double): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Indice del waypoint con la massima distanza in linea d'aria dalla
     * partenza, restituito come distanza cumulativa lungo il percorso.
     * Usa la distanza geografica (non quella percorsa, che su un
     * andata/ritorno cresce sempre) — così funziona anche quando il punto
     * di svolta non è il punto di massima quota.
     */
    private fun findFarthestPointCumDist(pts: List<RouteWaypoint>, cumDist: DoubleArray): Double {
        if (pts.size < 2) return -1.0
        val start = pts[0]
        var bestIdx = 0
        var bestDist = 0.0
        for (i in pts.indices) {
            val d = haversineM(start.lat, start.lon, pts[i].lat, pts[i].lon)
            if (d > bestDist) { bestDist = d; bestIdx = i }
        }
        return cumDist[bestIdx]
    }

    private fun buildCumDist(pts: List<RouteWaypoint>): DoubleArray {
        val arr = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            arr[i] = arr[i - 1] + haversineM(pts[i-1].lat, pts[i-1].lon, pts[i].lat, pts[i].lon)
        }
        return arr
    }

    // ── costruzione segmenti ─────────────────────────────────────────────

    companion object {
        // Finestra di smoothing per le quote: il rumore tipico del polyline
        // (~1-2 m per waypoint) genera pendenze finte ±5-10% che vengono sommate
        // nel dislivello rimanente. Una media mobile su ~50 m abbatte il rumore
        // senza appiattire le salite reali (che durano centinaia di metri).
        private const val ELEVATION_SMOOTH_WINDOW_M = 50.0

        // Un waypoint è agganciabile dal GPS se dista dal fix meno di questa soglia
        private const val SNAP_MAX_DIST_M = 100.0
        // Finestra di ricerca intorno alla posizione stimata: dietro basta poco
        // (correzione di drift), davanti serve margine per il dead-reckoning lento
        private const val SNAP_BACK_WINDOW_M = 500.0
        private const val SNAP_FWD_WINDOW_M  = 2000.0
        // Tick consecutivi fuori finestra prima di tentare la ricerca globale
        // (con un tick al secondo = 30s; azzerato se located è ancora false)
        private const val RESCUE_AFTER_TICKS = 30
        // Conferme consecutive coerenti richieste per accettare un riaggancio globale
        private const val RESCUE_CONFIRM_TICKS = 3
        // Due proposte di riaggancio sono coerenti se distano meno di questa soglia
        private const val RESCUE_COHERENCE_M = 200.0
        // Guardia di plausibilità del riaggancio: velocità massima credibile
        // (ebike in discesa con margine) e tolleranza base sul salto
        private const val RESCUE_MAX_SPEED_MS  = 20.0
        private const val RESCUE_JUMP_MARGIN_M = 500.0
    }

    private fun buildSegments(
        rawWaypoints: List<RouteWaypoint>,
        surfaces: List<Surface>,
    ): List<RouteSegment> {
        if (rawWaypoints.size < 2) return emptyList()
        val waypoints = smoothElevations(rawWaypoints, ELEVATION_SMOOTH_WINDOW_M)
        val raw = mutableListOf<RouteSegment>()
        for (i in 0 until waypoints.size - 1) {
            val dist = haversineM(waypoints[i].lat, waypoints[i].lon, waypoints[i+1].lat, waypoints[i+1].lon)
            if (dist < 1.0) continue
            val elevDiff = waypoints[i+1].elevationM - waypoints[i].elevationM
            val grade    = (elevDiff / dist * 100.0).coerceIn(-30.0, 30.0)
            raw.add(RouteSegment(
                distanceM = dist,
                gradePct  = grade,
                surface   = surfaces.getOrElse(i) { Surface.UNKNOWN },
            ))
        }
        return simplify(raw)
    }

    /**
     * Media mobile delle quote su una finestra di [windowM] metri di percorso.
     * Sliding window O(n) con due puntatori.
     */
    private fun smoothElevations(waypoints: List<RouteWaypoint>, windowM: Double): List<RouteWaypoint> {
        if (waypoints.size < 3) return waypoints

        val cum = DoubleArray(waypoints.size)
        for (i in 1 until waypoints.size) {
            cum[i] = cum[i - 1] + haversineM(
                waypoints[i - 1].lat, waypoints[i - 1].lon,
                waypoints[i].lat,     waypoints[i].lon,
            )
        }

        val half = windowM / 2.0
        var lo   = 0
        var hi   = 0
        var sum  = 0.0
        return waypoints.mapIndexed { i, wp ->
            while (hi < waypoints.size && cum[hi] <= cum[i] + half) {
                sum += waypoints[hi].elevationM
                hi++
            }
            while (lo < hi && cum[lo] < cum[i] - half) {
                sum -= waypoints[lo].elevationM
                lo++
            }
            val count = hi - lo
            wp.copy(elevationM = if (count > 0) sum / count else wp.elevationM)
        }
    }

    private fun simplify(raw: List<RouteSegment>): List<RouteSegment> {
        if (raw.isEmpty()) return emptyList()
        val out     = mutableListOf<RouteSegment>()
        var current = raw[0]
        for (i in 1 until raw.size) {
            val next = raw[i]
            // Si fondono solo segmenti con pendenza simile E stesso fondo:
            // il confine asfalto/sterrato è un confine di velocità
            if (abs(next.gradePct - current.gradePct) <= 2.0 && next.surface == current.surface) {
                val total    = current.distanceM + next.distanceM
                val avgGrade = (current.gradePct * current.distanceM +
                                next.gradePct    * next.distanceM) / total
                current = RouteSegment(distanceM = total, gradePct = avgGrade, surface = current.surface)
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
