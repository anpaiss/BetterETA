package com.anpaiss.karoobettereta

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Sorgente dati:
 *   - Velocità e pendenza: addConsumer() su KarooSystemService
 *   - Percorso con quota: file GPX Komoot su storage Karoo
 */
class BetterEtaExtension : KarooExtension("karoo-better-eta", "1") {

    private lateinit var karooSystem: KarooSystemService

    internal val etaState = MutableStateFlow(EtaState())

    private val speedModel = AdaptiveSpeedModel()
    private val pipeline   = SegmentPipeline()
    private val etaEngine  = EtaEngine(speedModel, pipeline)

    // Valori correnti aggiornati dai consumer callback
    private val speedMsFlow  = MutableStateFlow(0.0)
    private val gradePctFlow = MutableStateFlow(0.0)
    @Volatile private var rideState: RideState = RideState.Idle

    private var tickerJob: Job? = null
    private var routeJob: Job? = null
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

        // Carica il percorso GPX in background (indipendente dalla connessione)
        routeJob = CoroutineScope(Dispatchers.IO).launch {
            RouteReader().waypointFlow().collect { waypoints ->
                pipeline.setRoute(waypoints)
            }
        }

        karooSystem.connect { _ ->
            // Velocità corrente (m/s)
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.SPEED),
                { _ -> }, {},
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?.let { speedMsFlow.value = it }
            }

            // Pendenza corrente (%) — ELEVATION_GRADE è il tipo corretto in SDK 1.1.x
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE),
                { _ -> }, {},
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?.let { gradePctFlow.value = it }
            }

            // Stato uscita — reset del modello a ogni nuova uscita
            consumerIds += karooSystem.addConsumer(
                RideState.Params,
                { _ -> }, {},
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    speedModel.reset()
                    pipeline.reset()
                }
                rideState = state
            }

            // Ticker principale: aggiorna modello e ricalcola ETA ogni secondo
            tickerJob = CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    delay(TICK_MS)
                    if (rideState !is RideState.Recording) continue

                    val speedMs  = speedMsFlow.value
                    val gradePct = gradePctFlow.value

                    speedModel.update(speedMs = speedMs, gradePct = gradePct)
                    pipeline.advanceByDistance(speedMs * (TICK_MS / 1000.0))

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
        routeJob?.cancel()
        routeJob = null
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
