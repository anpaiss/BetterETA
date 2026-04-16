package com.anpaiss.karoobettereta

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class BetterEtaExtension : KarooExtension("karoo-better-eta-v2", "2") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var logger: BetterEtaLogger

    internal val etaState = MutableStateFlow(EtaState())

    private val speedModel = AdaptiveSpeedModel()
    private val pipeline   = SegmentPipeline()
    private val etaEngine  = EtaEngine(speedModel, pipeline)

    private val speedMsFlow  = MutableStateFlow(0.0)
    private val gradePctFlow = MutableStateFlow(0.0)
    private val locationFlow = MutableStateFlow<Pair<Double, Double>?>(null)
    @Volatile private var rideState: RideState = RideState.Idle

    // Polyline dell'ultimo percorso caricato: se il Karoo riemette lo stesso percorso
    // (es. dopo GPS recovery) non resettiamo la posizione sulla pipeline.
    @Volatile private var lastRoutePolyline: String = ""

    // Timestamp dell'ultimo fix GPS ricevuto (0 = mai ricevuto)
    @Volatile private var lastGpsUpdateMs  = 0L
    // Stato GPS per logging (evita righe duplicate)
    @Volatile private var gpsStaleLogged  = false
    @Volatile private var gpsStaleStartMs = 0L

    // Timestamp di inizio della ride corrente/ultima (0 = mai avviata)
    @Volatile private var rideStartMs = 0L

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    // Log ETA ogni LOG_INTERVAL_TICKS secondi (non ogni tick)
    private var logTickCounter = 0

    companion object {
        private const val TICK_MS              = 1000L
        private const val GPS_TIMEOUT_MS       = 10_000L
        private const val PREFS_NAME           = "better_eta_model"
        private const val LOG_INTERVAL_TICKS   = 60       // 1 riga ETA al minuto
        private const val SNAP_LOG_THRESHOLD   = 50.0     // logga snap solo se > 50m
        private const val MIN_RIDE_FOR_RESET_MS = 60_000L // ride < 60s non resetta il modello
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState),
            EtaRemainingDataType(extension, etaState),
            EtaMasterDataType(extension, etaState, speedModel),
        )
    }

    override fun onCreate() {
        super.onCreate()
        logger = BetterEtaLogger(applicationContext)

        // Carica il modello addestrato dalla sessione precedente
        loadModel()

        karooSystem = KarooSystemService(applicationContext)

        karooSystem.connect { _ ->

            // ── Percorso attivo in tempo reale ────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnNavigationState.Params, { _ -> }, {}
            ) { event: OnNavigationState ->
                val nav = event.state
                logger.log("ROUTE", "Stato ricevuto: ${nav::class.simpleName}")

                val (polyline, elevPolyline, source) = when (nav) {
                    is OnNavigationState.NavigationState.NavigatingRoute ->
                        Triple(nav.routePolyline.orEmpty(), nav.routeElevationPolyline.orEmpty(), "NavigatingRoute")
                    is OnNavigationState.NavigationState.NavigatingToDestination ->
                        Triple(nav.polyline.orEmpty(), nav.elevationPolyline.orEmpty(), "NavigatingToDestination")
                    else -> {
                        logger.log("ROUTE", "Stato non gestito → nessun percorso caricato")
                        lastRoutePolyline = ""
                        extensionScope.launch { pipeline.setRoute(emptyList()) }
                        return@addConsumer
                    }
                }

                if (polyline == lastRoutePolyline) {
                    logger.log("ROUTE", "[$source] stesso percorso — posizione preservata")
                    return@addConsumer
                }

                lastRoutePolyline = polyline
                val waypoints = decodeRoute(polyline, elevPolyline, source)
                extensionScope.launch {
                    pipeline.setRoute(waypoints)
                    val segCount = pipeline.remainingSegments().size
                    if (waypoints.isNotEmpty()) {
                        logger.log("ROUTE", "Pipeline: ${waypoints.size} wp → $segCount segmenti")
                    }
                }
            }

            // ── Velocità ──────────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.SPEED), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let { speedMsFlow.value = it }
            }

            // ── Pendenza ──────────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let { gradePctFlow.value = it }
            }

            // ── Posizione GPS ─────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer({ _ -> }, {}) { event: OnLocationChanged ->
                if (lastGpsUpdateMs == 0L) {
                    logger.log("GPS  ", "Primo fix: %.5f, %.5f".format(event.lat, event.lng))
                }
                locationFlow.value = event.lat to event.lng
                lastGpsUpdateMs    = System.currentTimeMillis()
            }

            // ── Stato uscita ──────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                RideState.Params, { _ -> }, {}
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    val now = System.currentTimeMillis()
                    val prevDurationMs = if (rideStartMs > 0) now - rideStartMs else Long.MAX_VALUE
                    rideStartMs = now
                    if (prevDurationMs >= MIN_RIDE_FOR_RESET_MS) {
                        logger.log("RIDE ", "Avviata — model reset")
                        speedModel.reset()
                    } else {
                        logger.log("RIDE ", "Avviata — model mantenuto (ride precedente ${prevDurationMs / 1000}s < 60s)")
                    }
                    extensionScope.launch { pipeline.reset() }
                }
                if (rideState is RideState.Recording && state !is RideState.Recording) {
                    logger.log("RIDE ", "Terminata")
                    saveModel()
                }
                rideState = state
            }

            // ── Ticker principale ─────────────────────────────────────────────────
            tickerJob = extensionScope.launch {
                while (true) {
                    delay(TICK_MS)

                    val hasRoute = pipeline.hasSegments()
                    if (!hasRoute) {
                        etaState.value = EtaState(status = "No Route")
                        continue
                    }

                    if (rideState !is RideState.Recording) {
                        etaState.value = EtaState(hasRoute = true, status = "In pausa")
                        continue
                    }

                    // 1. Aggiorna modello
                    val currentSpeedMs  = speedMsFlow.value
                    val currentGradePct = gradePctFlow.value
                    speedModel.update(currentSpeedMs, currentGradePct)

                    // 2. Avanzamento stimato
                    pipeline.advanceByDistance(currentSpeedMs * (TICK_MS / 1000.0))

                    // 3. Snap GPS (solo se fix recente)
                    val now      = System.currentTimeMillis()
                    val gpsStale = lastGpsUpdateMs > 0 && now - lastGpsUpdateMs > GPS_TIMEOUT_MS

                    if (!gpsStale) {
                        locationFlow.value?.let { (lat, lon) ->
                            val correction = pipeline.snapToLocation(lat, lon)
                            if (correction > SNAP_LOG_THRESHOLD) {
                                logger.log("GPS  ", "Snap: correzione ${correction.toInt()}m")
                            }
                        }
                    }

                    // Logging GPS stale / recovery
                    if (gpsStale && !gpsStaleLogged) {
                        gpsStaleLogged  = true
                        gpsStaleStartMs = now
                        val staleSec = (now - lastGpsUpdateMs) / 1000
                        logger.log("GPS  ", "Stale da ${staleSec}s — dead-reckoning attivo")
                    } else if (!gpsStale && gpsStaleLogged) {
                        gpsStaleLogged = false
                        val staleDurSec = (now - gpsStaleStartMs) / 1000
                        logger.log("GPS  ", "Recuperato dopo ${staleDurSec}s")
                    }

                    // 4. Calcolo ETA + statistiche percorso (un solo lock sulla pipeline)
                    val remainingSegs = pipeline.remainingSegments()
                    val remainingSec  = etaEngine.computeFromSegments(remainingSegs)
                    val remainDistM   = remainingSegs.sumOf { it.distanceM }
                    val remainElevM   = remainingSegs
                        .filter { it.gradePct > 0 }
                        .sumOf { it.distanceM * it.gradePct / 100.0 }

                    etaState.value = EtaState(
                        remainingSeconds    = remainingSec,
                        hasRoute            = true,
                        gpsStale            = gpsStale,
                        remainingDistanceM  = remainDistM,
                        remainingElevationM = remainElevM,
                        status              = if (remainingSec > 0) "OK" else "Calcolo...",
                    )

                    // 5. Log periodico ETA
                    logTickCounter++
                    if (logTickCounter >= LOG_INTERVAL_TICKS) {
                        logTickCounter = 0
                        logEtaSnapshot(remainingSec, currentSpeedMs, currentGradePct, gpsStale)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        logger.log("SYS  ", "=== BetterETA fermato ===")
        saveModel()
        tickerJob?.cancel()
        tickerJob = null
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }

    // ── logging ──────────────────────────────────────────────────────────

    private fun logEtaSnapshot(remainingSec: Long, speedMs: Double, gradePct: Double, gpsStale: Boolean) {
        if (remainingSec <= 0L) return
        val h   = remainingSec / 3600
        val m   = (remainingSec % 3600) / 60
        val gps = if (gpsStale) " [GPS stale]" else ""
        logger.log("ETA  ", "${h}h ${m}m (${remainingSec}s) | " +
            "speed=%.1fm/s grade=%.1f%%%s".format(speedMs, gradePct, gps))
    }

    // ── persistenza modello ──────────────────────────────────────────────

    private fun saveModel() {
        val snap  = speedModel.snapshot()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("overall_ema",   snap.overallEma.toFloat())
            putInt("overall_samples", snap.overallSamples)
            snap.bucketEmas.forEachIndexed   { i, v -> putFloat("b${i}_ema", v.toFloat()) }
            snap.bucketCounts.forEachIndexed { i, c -> putInt("b${i}_cnt", c) }
        }.commit()  // sincrono: garantisce scrittura su disco prima che il processo venga ucciso

        val bucketStr = snap.bucketEmas.mapIndexed { i, v ->
            "b$i=%.1fm/s(n=${snap.bucketCounts[i]})".format(v)
        }.joinToString(" ")
        logger.log("MODEL", "Salvato: overall=%.2fm/s n=%d | %s"
            .format(snap.overallEma, snap.overallSamples, bucketStr))
    }

    private fun loadModel() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("overall_samples", 0) == 0) {
            logger.log("MODEL", "Nessun modello salvato — si parte da zero")
            return
        }
        val snap = AdaptiveSpeedModel.Snapshot(
            overallEma     = prefs.getFloat("overall_ema", 0f).toDouble(),
            overallSamples = prefs.getInt("overall_samples", 0),
            bucketEmas     = (0..4).map { prefs.getFloat("b${it}_ema", 0f).toDouble() },
            bucketCounts   = (0..4).map { prefs.getInt("b${it}_cnt", 0) },
        )
        speedModel.restore(snap)

        val bucketStr = snap.bucketEmas.mapIndexed { i, v ->
            "b$i=%.1fm/s(n=${snap.bucketCounts[i]})".format(v)
        }.joinToString(" ")
        logger.log("MODEL", "Ripristinato: overall=%.2fm/s n=%d | %s"
            .format(snap.overallEma, snap.overallSamples, bucketStr))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun decodeRoute(polyline: String, elevPolyline: String, source: String): List<RouteWaypoint> {
        if (polyline.isBlank()) {
            logger.log("ROUTE", "WARN [$source] polyline vuota — nessun waypoint")
            return emptyList()
        }

        val coords = PolylineDecoder.decodeLatLng(polyline)
        if (coords.isEmpty()) {
            logger.log("ROUTE", "WARN [$source] decodeLatLng ha restituito 0 punti " +
                "(polyline ${polyline.length} char, inizio: '${polyline.take(20)}')")
            return emptyList()
        }

        val elev = PolylineDecoder.decodeElevations(elevPolyline)
        if (elev.isEmpty()) {
            logger.log("ROUTE", "WARN [$source] elevazione vuota — pendenza sarà 0% ovunque")
        } else if (elev.size != coords.size) {
            logger.log("ROUTE", "INFO [$source] mismatch: ${coords.size} coord, ${elev.size} quote → interpolazione")
        }

        val elevInterp = interpolateElevations(coords, elev)
        return coords.mapIndexed { i, (lat, lng) ->
            RouteWaypoint(lat = lat, lon = lng, elevationM = elevInterp[i])
        }
    }
}
