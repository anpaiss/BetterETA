package com.anpaiss.karoobettereta

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val distToDestFlow   = MutableStateFlow(-1.0)  // -1 = non disponibile
    @Volatile private var rideState: RideState = RideState.Idle

    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    companion object {
        private const val TICK_MS          = 1000L
        /** Soglia oltre la quale la posizione stimata viene corretta da DISTANCE_TO_DESTINATION. */
        private const val SYNC_THRESHOLD_M = 200.0
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
            // Si aggiorna automaticamente a ogni cambio di navigazione:
            // nuovo percorso, rerouting, navigazione ad-hoc, fine navigazione.
            consumerIds += karooSystem.addConsumer(
                OnNavigationState.Params, { _ -> }, {}
            ) { event: OnNavigationState ->
                val waypoints = when (val nav = event.state) {
                    is OnNavigationState.NavigationState.NavigatingRoute ->
                        decodeRoute(
                            nav.routePolyline.orEmpty(),
                            nav.routeElevationPolyline.orEmpty(),
                        )

                    is OnNavigationState.NavigationState.NavigatingToDestination ->
                        decodeRoute(
                            nav.polyline.orEmpty(),
                            nav.elevationPolyline.orEmpty(),
                        )

                    else -> emptyList()
                }
                pipeline.setRoute(waypoints)
                // Se il percorso viene aggiornato mentre siamo già in giro
                // (es. rerouting), riallinea subito la posizione.
                val dist = distToDestFlow.value
                if (waypoints.isNotEmpty() && dist > 0) {
                    pipeline.syncToRemainingDistance(dist)
                }
            }

            // ── Velocità corrente (m/s) ───────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.SPEED), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?.let { speedMsFlow.value = it }
            }

            // ── Pendenza corrente (%) ─────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?.let { gradePctFlow.value = it }
            }

            // ── Distanza rimanente a destinazione (m) ────────────────────────────
            // Fonte di verità per correggere il drift dell'integrazione speed×dt.
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION),
                { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?.let { if (it > 0) distToDestFlow.value = it }
            }

            // ── Stato uscita ──────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer(
                RideState.Params, { _ -> }, {}
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    speedModel.reset()
                    pipeline.reset()
                    distToDestFlow.value = -1.0
                }
                rideState = state
            }

            // ── Ticker principale: aggiorna modello e ricalcola ETA ogni secondo ─
            tickerJob = CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    delay(TICK_MS)
                    if (rideState !is RideState.Recording) continue

                    val speedMs  = speedMsFlow.value
                    val gradePct = gradePctFlow.value

                    speedModel.update(speedMs = speedMs, gradePct = gradePct)
                    pipeline.advanceByDistance(speedMs * (TICK_MS / 1000.0))

                    // Corregge il drift di posizione se la stima si discosta
                    // dalla distanza reale riportata dal Karoo di più di SYNC_THRESHOLD_M.
                    val trueDist = distToDestFlow.value
                    if (trueDist > 0) {
                        val estDist = pipeline.remainingSegments().sumOf { it.distanceM }
                        if (abs(trueDist - estDist) > SYNC_THRESHOLD_M) {
                            pipeline.syncToRemainingDistance(trueDist)
                        }
                    }

                    val remainingSec = etaEngine.computeRemainingSeconds()
                    etaState.value = EtaState(
                        remainingSeconds = remainingSec,
                        hasRoute         = pipeline.hasSegments(),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        tickerJob = null
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
