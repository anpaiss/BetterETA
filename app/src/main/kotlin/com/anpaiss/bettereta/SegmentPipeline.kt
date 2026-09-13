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
    private var waypointWayKeys    = listOf<Long>()      // parallelo a waypoints; vuoto = way ignote
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
    private var unlocatedWaitTicks = 0      // tick passati ad aspettare il suggerimento del Karoo

    /** Carica un nuovo percorso e riparte dall'inizio. */
    suspend fun setRoute(newWaypoints: List<RouteWaypoint>) = mutex.withLock {
        waypoints          = newWaypoints
        waypointSurfaces   = emptyList()
        waypointWayKeys    = emptyList()
        segments           = buildSegments(newWaypoints, emptyList(), emptyList())
        waypointCumDist    = buildCumDist(newWaypoints)
        currentIndex       = 0
        consumedInCurrentM = 0.0
        traveledM          = 0.0
        located            = false
        offWindowTicks     = 0
        rescueCandidateM   = -1.0
        rescueConfirmCount = 0
        unlocatedWaitTicks = 0
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
    suspend fun snapToLocation(lat: Double, lon: Double, hintM: Double? = null): Double = mutex.withLock {
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
            // Il GPS conferma la stima: il candidato più coerente è sempre entro
            // mezzo passo di waypoint dalla posizione vera. Riposizionare qui ogni
            // tick annullava il dead-reckoning (giro 13/09: posizione ferma 20-60 s
            // e poi scatti di ~100 m, sempre ~100 m dietro il Karoo). Si corregge
            // solo quando stima e GPS divergono più del passo locale.
            if (abs(waypointCumDist[bestIdx] - traveledM) < holdToleranceM(bestIdx)) return@withLock 0.0
            val prevTraveledM = traveledM
            repositionTo(waypointCumDist[bestIdx])
            return@withLock abs(traveledM - prevTraveledM)
        }

        // Nessun aggancio nella finestra locale
        offWindowTicks++
        if (located && offWindowTicks < RESCUE_AFTER_TICKS) return@withLock 0.0

        if (!located && hintM == null) {
            // Aggancio iniziale senza il suggerimento del Karoo (arriva ~1 min dopo
            // la partenza). Giro 05/09: parcheggio a 310 m dallo start ma a pochi
            // metri dal km 7,6 dell'anello → agganciati al km 7,6.
            // 1) Vicino allo start: lo start, anche se il waypoint più vicino è altrove.
            val startDistM = haversineM(lat, lon, waypoints[0].lat, waypoints[0].lon)
            if (startDistM <= START_PREFER_M) {
                located            = true
                offWindowTicks     = 0
                rescueCandidateM   = -1.0
                rescueConfirmCount = 0
                lastGoodSnapMs     = nowMs()
                val prevTraveledM = traveledM
                repositionTo(0.0)
                return@withLock abs(traveledM - prevTraveledM)
            }
            // 2) Altrimenti aspetta il suggerimento per un po' prima di agganciare
            //    un punto interno alla route
            if (unlocatedWaitTicks < HINT_WAIT_TICKS) {
                unlocatedWaitTicks++
                return@withLock 0.0
            }
        }

        // Ricerca globale: tra i waypoint di tutta la route vicini al fix sceglie
        // quello più coerente con la posizione stimata (stesso criterio dello snap
        // locale). Il waypoint in assoluto più vicino al fix può stare sul ramo
        // sbagliato — anelli con partenza=arrivo, andata/ritorno — e teleportare
        // la posizione a inizio o fine route.
        // Riferimento per la coerenza: la posizione stimata; finché non siamo
        // mai stati agganciati, la posizione lungo la route secondo il Karoo
        // ([hintM]) se c'è — a inizio giro la stima vale 0 e non dice nulla.
        val refM = if (!located && hintM != null) hintM else traveledM
        var globalIdx    = -1
        var globalOffset = Double.MAX_VALUE
        for (i in waypoints.indices) {
            if (haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon) >= SNAP_MAX_DIST_M) continue
            val offset = abs(waypointCumDist[i] - refM)
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
     * Riaggancio forzato al rientro in route (ON_ROUTE del Karoo torna 1): tra
     * i waypoint entro SNAP_MAX_DIST_M dal fix sceglie il più coerente con
     * [hintM], la posizione lungo la route secondo il Karoo, e riposiziona
     * saltando le guardie di plausibilità pensate per i fix spuri — qui è il
     * Karoo stesso a dire che siamo sulla route. Giro 05/09: partenza a 310 m
     * dallo start ma a pochi metri dal km 7,6 dell'anello, aggancio al km 7,6,
     * poi 32 min fuori route con la posizione che avanzava sul ramo sbagliato.
     *
     * @return correzione applicata in metri; 0 se nessun waypoint vicino al fix
     *   o se la posizione era già coerente (sotto RELOCATE_MIN_JUMP_M)
     */
    suspend fun relocateWithHint(lat: Double, lon: Double, hintM: Double): Double = mutex.withLock {
        if (waypoints.isEmpty()) return@withLock 0.0
        var bestIdx    = -1
        var bestOffset = Double.MAX_VALUE
        for (i in waypoints.indices) {
            if (haversineM(lat, lon, waypoints[i].lat, waypoints[i].lon) >= SNAP_MAX_DIST_M) continue
            val offset = abs(waypointCumDist[i] - hintM)
            if (offset < bestOffset) { bestOffset = offset; bestIdx = i }
        }
        if (bestIdx == -1) return@withLock 0.0
        val prevTraveledM = traveledM
        val candM = waypointCumDist[bestIdx]
        // Solo se il candidato è più coerente col suggerimento di dove siamo già:
        // se l'unico waypoint vicino al fix è quello sbagliato, restiamo dove siamo
        if (abs(candM - hintM) >= abs(prevTraveledM - hintM)) return@withLock 0.0
        if (abs(candM - prevTraveledM) < RELOCATE_MIN_JUMP_M) return@withLock 0.0
        located            = true
        offWindowTicks     = 0
        rescueCandidateM   = -1.0
        rescueConfirmCount = 0
        lastGoodSnapMs     = nowMs()
        repositionTo(waypointCumDist[bestIdx])
        abs(traveledM - prevTraveledM)
    }

    /**
     * Applica la classificazione fondo (parallela ai waypoint della route corrente)
     * e ricostruisce i segmenti preservando la posizione raggiunta. Arriva in
     * ritardo rispetto a setRoute perché la classificazione OSM è asincrona.
     */
    suspend fun applySurfaces(surfaces: List<Surface>, wayKeys: List<Long> = emptyList()) = mutex.withLock {
        if (waypoints.isEmpty() || surfaces.size != waypoints.size) return@withLock
        waypointSurfaces = surfaces
        waypointWayKeys  = if (wayKeys.size == waypoints.size) wayKeys else emptyList()
        val savedM = traveledM
        segments = buildSegments(waypoints, surfaces, waypointWayKeys)
        repositionTo(savedM)
    }

    /** Fondo del segmento su cui si trova ora il ciclista. */
    suspend fun currentSurface(): Surface = mutex.withLock {
        segments.getOrNull(currentIndex)?.surface ?: Surface.UNKNOWN
    }

    /** (way, verso) del segmento su cui si trova ora il ciclista; 0 se ignota. */
    suspend fun currentWayKey(): Long = mutex.withLock {
        segments.getOrNull(currentIndex)?.wayKey ?: 0L
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

    /**
     * Distance covered along the route so far (m), i.e. our own estimate of
     * where the rider is. Exposed so it can be logged side by side with the
     * Karoo's own `routeDistance - DISTANCE_TO_DESTINATION`.
     */
    suspend fun distanceAlongRouteM(): Double = mutex.withLock {
        traveledM
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

    /**
     * Segmenti dal punto [alongM] alla fine, senza toccare la posizione: serve
     * all'ETA fuori route, calcolato dal punto in cui il Karoo farà rientrare.
     */
    suspend fun remainingSegmentsFrom(alongM: Double): List<RouteSegment> = mutex.withLock {
        var rem = alongM.coerceAtLeast(0.0)
        var idx = 0
        while (idx < segments.size && rem >= segments[idx].distanceM) {
            rem -= segments[idx].distanceM
            idx++
        }
        if (idx >= segments.size) return@withLock emptyList()
        val result = mutableListOf<RouteSegment>()
        val first  = segments[idx]
        if (first.distanceM - rem > 0.0) result.add(first.copy(distanceM = first.distanceM - rem))
        for (i in idx + 1 until segments.size) result.add(segments[i])
        result
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

    /**
     * Divergenza stima/GPS tollerata prima di riposizionare sul waypoint [idx]:
     * poco più di mezzo passo tra i waypoint vicini (con la stima giusta il
     * candidato coerente dista al massimo mezzo passo), mai sotto SNAP_HOLD_MIN_M.
     */
    private fun holdToleranceM(idx: Int): Double {
        val before = if (idx > 0) waypointCumDist[idx] - waypointCumDist[idx - 1] else 0.0
        val after  = if (idx + 1 < waypointCumDist.size) waypointCumDist[idx + 1] - waypointCumDist[idx] else 0.0
        return maxOf(SNAP_HOLD_MIN_M, SNAP_HOLD_STEP_FRACTION * maxOf(before, after))
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
        // Snap locale: la stima viene lasciata al dead-reckoning finché il candidato
        // coerente dista meno di 0,6 passi di waypoint (minimo 30 m)
        private const val SNAP_HOLD_MIN_M = 30.0
        private const val SNAP_HOLD_STEP_FRACTION = 0.6
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
        // Sotto questo scarto il riaggancio al rientro in route non tocca la posizione
        private const val RELOCATE_MIN_JUMP_M  = 300.0
        // Aggancio iniziale: entro questa distanza dallo start si parte dallo start;
        // altrimenti si aspettano fino a tanti tick il suggerimento del Karoo
        private const val START_PREFER_M       = 400.0
        private const val HINT_WAIT_TICKS      = 60
    }

    private fun buildSegments(
        rawWaypoints: List<RouteWaypoint>,
        surfaces: List<Surface>,
        wayKeys: List<Long>,
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
                wayKey    = wayKeys.getOrElse(i) { 0L },
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
            // Si fondono solo segmenti con pendenza simile, stesso fondo e stessa
            // way: il confine asfalto/sterrato è un confine di velocità, e la
            // memoria dei tempi per tratto lavora per way
            if (abs(next.gradePct - current.gradePct) <= 2.0 && next.surface == current.surface &&
                next.wayKey == current.wayKey) {
                val total    = current.distanceM + next.distanceM
                val avgGrade = (current.gradePct * current.distanceM +
                                next.gradePct    * next.distanceM) / total
                current = RouteSegment(distanceM = total, gradePct = avgGrade, surface = current.surface, wayKey = current.wayKey)
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
