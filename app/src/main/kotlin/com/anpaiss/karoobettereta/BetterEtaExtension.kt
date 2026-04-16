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

/**
 * Extension principale Karoo BetterETA.
 *
 * Espone due campi dati:
 *   - eta-absolute  → orario di arrivo (HH:mm)
 *   - eta-remaining → tempo rimanente (Xh Ym)
 *
 * Sorgenti dati:
 *   - Percorso attivo: OnNavigationState
 *   - Velocità e pendenza: OnStreamState
 *   - Posizione GPS: OnLocationChanged
 */
class BetterEtaExtension : KarooExtension("karoo-better-eta", "1") {

    private lateinit var karooSystem: KarooSystemService

    internal val etaState = MutableStateFlow(EtaState())

    private val speedModel = AdaptiveSpeedModel()
    private val pipeline   = SegmentPipeline()
    private val etaEngine  = EtaEngine(speedModel, pipeline)

    private val speedMsFlow  = MutableStateFlow(0.0)
    private val gradePctFlow = MutableStateFlow(0.0)
    private val locationFlow = MutableStateFlow<Pair<Double, Double>?>(null)
    @Volatile private var rideState: RideState = RideState.Idle

    // Timestamp dell'ultimo fix GPS ricevuto (0 = mai ricevuto)
    @Volatile private var lastGpsUpdateMs = 0L

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    companion object {
        private const val TICK_MS        = 1000L
        private const val GPS_TIMEOUT_MS = 10_000L   // 10s senza fix → stale
        private const val PREFS_NAME     = "better_eta_model"
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState),
            EtaRemainingDataType(extension, etaState),
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Carica il modello addestrato dalla sessione precedente
        loadModel()

        karooSystem = KarooSystemService(applicationContext)

        karooSystem.connect { _ ->

            // ── Percorso attivo in tempo reale ────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnNavigationState.Params, { _ -> }, {}
            ) { event: OnNavigationState ->
                val waypoints = when (val nav = event.state) {
                    is OnNavigationState.NavigationState.NavigatingRoute ->
                        decodeRoute(nav.routePolyline.orEmpty(), nav.routeElevationPolyline.orEmpty())
                    is OnNavigationState.NavigationState.NavigatingToDestination ->
                        decodeRoute(nav.polyline.orEmpty(), nav.elevationPolyline.orEmpty())
                    else -> emptyList()
                }
                extensionScope.launch {
                    pipeline.setRoute(waypoints)
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

            // ── Posizione GPS (Lat/Lon) per Snap-to-Route ────────────────────────
            consumerIds += karooSystem.addConsumer({ _ -> }, {}) { event: OnLocationChanged ->
                locationFlow.value  = event.lat to event.lng
                lastGpsUpdateMs     = System.currentTimeMillis()
            }

            // ── Stato uscita ──────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                RideState.Params, { _ -> }, {}
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    speedModel.reset()
                    extensionScope.launch { pipeline.reset() }
                }
                // Fine uscita → salva il modello addestrato
                if (rideState is RideState.Recording && state !is RideState.Recording) {
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

                    // 1. Update Modello
                    speedModel.update(speedMsFlow.value, gradePctFlow.value)

                    // 2. Avanzamento stimato + Snap GPS (solo se fix recente)
                    pipeline.advanceByDistance(speedMsFlow.value * (TICK_MS / 1000.0))
                    val gpsStale = lastGpsUpdateMs > 0 &&
                        System.currentTimeMillis() - lastGpsUpdateMs > GPS_TIMEOUT_MS
                    if (!gpsStale) {
                        locationFlow.value?.let { (lat, lon) ->
                            pipeline.snapToLocation(lat, lon)
                        }
                    }

                    // 3. Calcolo ETA
                    val remainingSec = etaEngine.computeRemainingSeconds()
                    etaState.value = EtaState(
                        remainingSeconds = remainingSec,
                        hasRoute         = true,
                        gpsStale         = gpsStale,
                        status           = if (remainingSec > 0) "OK" else "Calcolo...",
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        saveModel()
        tickerJob?.cancel()
        tickerJob = null
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }

    // ── persistenza modello ──────────────────────────────────────────────

    private fun saveModel() {
        val snap  = speedModel.snapshot()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("overall_ema",     snap.overallEma.toFloat())
            putInt("overall_samples",   snap.overallSamples)
            snap.bucketEmas.forEachIndexed   { i, v -> putFloat("b${i}_ema", v.toFloat()) }
            snap.bucketCounts.forEachIndexed { i, c -> putInt("b${i}_cnt", c) }
        }.apply()
    }

    private fun loadModel() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("overall_samples", 0) == 0) return  // nessun dato salvato
        val snap = AdaptiveSpeedModel.Snapshot(
            overallEma     = prefs.getFloat("overall_ema", 0f).toDouble(),
            overallSamples = prefs.getInt("overall_samples", 0),
            bucketEmas     = (0..4).map { prefs.getFloat("b${it}_ema", 0f).toDouble() },
            bucketCounts   = (0..4).map { prefs.getInt("b${it}_cnt", 0) },
        )
        speedModel.restore(snap)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun decodeRoute(polyline: String, elevPolyline: String): List<RouteWaypoint> {
        val coords = PolylineDecoder.decodeLatLng(polyline)
        val elev   = PolylineDecoder.decodeElevations(elevPolyline)
        return coords.mapIndexed { i, (lat, lng) ->
            RouteWaypoint(lat = lat, lon = lng, elevationM = elev.getOrElse(i) { 0.0 })
        }
    }
}
