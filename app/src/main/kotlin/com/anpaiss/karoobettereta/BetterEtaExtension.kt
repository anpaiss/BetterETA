package com.anpaiss.karoobettereta

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
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
 *   - Velocità e pendenza: stream Karoo SDK
 *   - Percorso con quota: file GPX Komoot su storage Karoo
 *
 * Il modello adattivo impara la velocità per fascia di pendenza
 * durante l'uscita e aggiorna la stima ogni secondo.
 *
 * TODO: quando disponibile, sostituire RouteReader.waypointFlow()
 *       con consumerFlow<OnNavigationState>() per percorso in tempo reale.
 */
class BetterEtaExtension : KarooExtension("karoo-better-eta", "1") {

    private lateinit var karooSystem: KarooSystemService

    internal val etaState = MutableStateFlow(EtaState())

    private val speedModel  = AdaptiveSpeedModel()
    private val pipeline    = SegmentPipeline()
    private val etaEngine   = EtaEngine(speedModel, pipeline)
    private lateinit var routeReader: RouteReader

    // Valori correnti letti dai stream Karoo (thread-safe via StateFlow)
    private val speedMsFlow  = MutableStateFlow(0.0)
    private val gradePctFlow = MutableStateFlow(0.0)
    @Volatile private var rideState: RideState = RideState.Idle

    private var serviceJob: Job? = null

    companion object {
        private const val TICK_MS = 1000L  // intervallo aggiornamento modello
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState),
            EtaRemainingDataType(extension, etaState),
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem  = KarooSystemService(applicationContext)
        routeReader  = RouteReader(applicationContext)

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { }

            // Carica il percorso dal file GPX
            launch {
                routeReader.waypointFlow().collect { waypoints ->
                    pipeline.setRoute(waypoints)
                }
            }

            // Velocità corrente (m/s)
            launch {
                karooSystem.streamDataFlow(DataType.Type.SPEED).collect { state ->
                    (state as? StreamState.Streaming)?.dataPoint?.singleValue
                        ?.let { speedMsFlow.value = it }
                }
            }

            // Pendenza corrente (%)
            // TODO: verificare il nome esatto della costante su Karoo 3
            launch {
                karooSystem.streamDataFlow(DataType.Type.GRADE).collect { state ->
                    (state as? StreamState.Streaming)?.dataPoint?.singleValue
                        ?.let { gradePctFlow.value = it }
                }
            }

            // Stato uscita — reset del modello a ogni nuova uscita
            launch {
                var prev: RideState = RideState.Idle
                karooSystem.consumerFlow<RideState>().collect { state ->
                    if (prev !is RideState.Recording && state is RideState.Recording) {
                        speedModel.reset()
                        pipeline.reset()
                    }
                    prev      = state
                    rideState = state
                }
            }

            // Ticker principale: aggiorna modello e ricalcola ETA ogni secondo
            launch {
                while (true) {
                    delay(TICK_MS)
                    val currentState = rideState
                    if (currentState !is RideState.Recording) continue

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
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }
}
