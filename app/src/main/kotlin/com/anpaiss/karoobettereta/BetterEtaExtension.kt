package com.anpaiss.karoobettereta

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
import kotlin.math.abs

/**
 * Extension principale Karoo BetterETA.
 *
 * Espone due campi dati:
 *   - eta-absolute  → orario di arrivo (HH:mm)
 *   - eta-remaining → tempo rimanente (Xh Ym)
 *
 * Sorgenti dati:
 *   - Percorso attivo: OnNavigationState (aggiornamento in tempo reale)
 *     Gestisce sia percorsi Komoot (NavigatingRoute) sia navigazione
 *     ad-hoc come "Portami a casa" (NavigatingToDestination).
 *   - Velocità e pendenza: stream OnStreamState via KarooSystemService
 *   - Posizione sul percorso: integrazione speed×dt + correzione drift
 *     via DISTANCE_TO_DESTINATION quando il delta supera SYNC_THRESHOLD_M
 */
class BetterEtaExtension : KarooExtension("karoo-better-eta", "1") {

    private lateinit var karooSystem: KarooSystemService

    internal val etaState = MutableStateFlow(EtaState())

    private val speedModel = AdaptiveSpeedModel()
    private val pipeline   = SegmentPipeline()
    private val etaEngine  = EtaEngine(speedModel, pipeline)

    private val speedMsFlow      = MutableStateFlow(0.0)
    private val gradePctFlow     = MutableStateFlow(0.0)
    private val locationFlow     = MutableStateFlow<Pair<Double, Double>?>(null)
    private val distToDestFlow   = MutableStateFlow(-1.0)
    @Volatile private var rideState: RideState = RideState.Idle

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    companion object {
        private const val TICK_MS = 1000L
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState),
            EtaRemainingDataType(extension, etaState),
        )
    }

    override fun onCreate() {
        super.onCreate()
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
                locationFlow.value = event.lat to event.lng
            }

            // ── Stato uscita ──────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                RideState.Params, { _ -> }, {}
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    speedModel.reset()
                    extensionScope.launch { pipeline.reset() }
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

                    // 2. Avanzamento stimato e Sync GPS
                    pipeline.advanceByDistance(speedMsFlow.value * (TICK_MS / 1000.0))
                    locationFlow.value?.let { (lat, lon) ->
                        pipeline.snapToLocation(lat, lon)
                    }

                    // 3. Calcolo ETA
                    val remainingSec = etaEngine.computeRemainingSeconds()
                    etaState.value = EtaState(
                        remainingSeconds = remainingSec,
                        hasRoute         = true,
                        status           = if (remainingSec > 0) "OK" else "Calcolo..."
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        tickerJob = null
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }

    private fun decodeRoute(polyline: String, elevPolyline: String): List<RouteWaypoint> {
        val coords = PolylineDecoder.decodeLatLng(polyline)
        val elev   = PolylineDecoder.decodeElevations(elevPolyline)
        return coords.mapIndexed { i, (lat, lng) ->
            RouteWaypoint(lat = lat, lon = lng, elevationM = elev.getOrElse(i) { 0.0 })
        }
    }
}
