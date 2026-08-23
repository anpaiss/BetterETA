package com.anpaiss.bettereta

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BetterEtaExtension : KarooExtension("better-eta", "1") {

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

    // False quando il Karoo emette uno stato di navigazione non gestito (es. Idle).
    // Il Karoo emette Idle transitori anche a metà navigazione: route e posizione
    // restano in memoria, si sospende solo la pubblicazione dell'ETA — un reset
    // qui azzerava la posizione e mostrava distanza/dislivello dell'intera route.
    @Volatile private var navActive = false

    // True se il percorso corrente ha dati di elevazione (NavigatingToDestination
    // emette polyline elevazioni vuota → dislivello sconosciuto, non zero)
    @Volatile private var hasElevationData = false

    // Baseline del campo delta: tempo di percorrenza previsto alla prima stima
    // valida del giro (-1 = non catturata) e secondi effettivi in movimento da
    // allora. Il confronto usa solo il tempo in movimento: le soste non spostano
    // il delta, che misura se la parte pedalata sta rispettando le medie.
    // Si azzera solo al cambio route, mai su pause/riprese o Idle transitori.
    @Volatile private var initialRemainingSec = -1L
    @Volatile private var movingElapsedSec    = 0L

    // Timestamp dell'ultimo fix GPS ricevuto (0 = mai ricevuto)
    @Volatile private var lastGpsUpdateMs  = 0L
    // Stato GPS per logging (evita righe duplicate)
    @Volatile private var gpsStaleLogged  = false
    @Volatile private var gpsStaleStartMs = 0L

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    // Log ETA ogni LOG_INTERVAL_TICKS secondi (non ogni tick)
    private var logTickCounter = 0

    // ── Confronto ETA BetterEta vs ETA nativa Karoo ──────────────────────
    // Un campione al minuto (movingAt = tempo in movimento dalla baseline,
    // previsioni di entrambi); a fine giro ogni campione viene giudicato
    // contro il tempo in movimento realmente servito da quel punto in poi.
    private data class EtaSample(
        val movingAtSec: Long,
        val beRemainingSec: Long,
        val karooRemainingSec: Double,
    )
    private val etaSamples = mutableListOf<EtaSample>()  // accesso via synchronized(etaSamples)
    @Volatile private var karooEtaSec    = -1.0          // ultimo TIME_TO_DESTINATION ricevuto
    @Volatile private var resultRecorded = false         // già registrato per questa route
    private var sampleTickCounter = 0

    companion object {
        private const val TICK_MS              = 1000L
        private const val GPS_TIMEOUT_MS       = 10_000L
        private const val PREFS_NAME           = "better_eta_model"
        private const val LOG_INTERVAL_TICKS   = 60       // 1 riga ETA al minuto
        private const val SNAP_LOG_THRESHOLD   = 50.0     // logga snap solo se > 50m

        // Confronto ETA: cadenza campioni, minimo percorso reale davanti perché
        // un campione sia giudicabile (vicino all'arrivo l'errore relativo
        // esplode), campioni minimi per registrare un risultato, cap di memoria.
        private const val SAMPLE_INTERVAL_TICKS      = 60
        private const val MIN_ACTUAL_REMAINING_SEC   = 300L
        private const val MIN_SAMPLES                = 5
        private const val MAX_SAMPLES                = 600
        internal const val RESULTS_PREFS             = "better_eta_results"
        internal const val RESULTS_KEY               = "history"
        internal const val RESULTS_MAX               = 20
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState),
            EtaRemainingDataType(extension, etaState),
            EtaMasterDataType(extension, etaState),
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
                        logger.log("ROUTE", "Stato non gestito → ETA sospeso, route in memoria")
                        navActive = false
                        return@addConsumer
                    }
                }

                navActive = true

                // Polyline vuota su stato di navigazione attivo: evento transitorio del
                // Karoo — decodificarla azzerava la route a metà giro.
                if (polyline.isBlank()) {
                    logger.log("ROUTE", "WARN [$source] polyline vuota — route corrente mantenuta")
                    return@addConsumer
                }

                if (polyline == lastRoutePolyline) {
                    logger.log("ROUTE", "[$source] stesso percorso — posizione preservata")
                    return@addConsumer
                }

                lastRoutePolyline = polyline
                hasElevationData  = elevPolyline.isNotBlank()
                initialRemainingSec = -1L  // nuova route → nuova baseline per il delta
                movingElapsedSec    = 0L
                resultRecorded      = false
                synchronized(etaSamples) { etaSamples.clear() }
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

            // ── ETA nativa Karoo (per il confronto di fine giro) ──────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.TIME_TO_DESTINATION), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let { karooEtaSec = it }
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
            // Modello e posizione vengono preservati tra le transizioni di stato:
            // il Karoo emette spesso Recording↔non-Recording per auto-pausa o cambi
            // pagina, e azzerare ogni volta vanificava l'apprendimento.
            // Il reset della posizione avviene solo quando cambia la route (setRoute).
            consumerIds += karooSystem.addConsumer(
                RideState.Params, { _ -> }, {}
            ) { state: RideState ->
                if (rideState !is RideState.Recording && state is RideState.Recording) {
                    logger.log("RIDE ", "Avviata — model e posizione preservati")
                }
                if (rideState is RideState.Recording && state !is RideState.Recording) {
                    logger.log("RIDE ", "Sospesa — model salvato")
                    saveModel()
                }
                rideState = state
            }

            // ── Ticker principale ─────────────────────────────────────────────────
            tickerJob = extensionScope.launch {
                while (true) {
                    delay(TICK_MS)

                    val hasRoute = pipeline.hasRoute()
                    if (!hasRoute || !navActive) {
                        etaState.value = EtaState(status = "No Route")
                        continue
                    }

                    if (rideState !is RideState.Recording) {
                        // Preserva gli ultimi valori calcolati (ETA, distanza, dislivello):
                        // il Karoo va in auto-pausa a ogni fermata e azzerarli fa comparire
                        // i segnaposto "--" per tutta la sosta.
                        etaState.value = etaState.value.copy(hasRoute = true, status = "Paused")
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

                    // Segmenti esauriti ma route ancora attiva: fine percorso raggiunta
                    // (o posizione agganciata male). Lo snap sopra continua a girare
                    // e il riaggancio globale può rianimare la pipeline se il GPS
                    // mostra che siamo ancora sulla route.
                    if (remainingSegs.isEmpty()) {
                        // Fine giro: valuta i campioni raccolti e registra il
                        // confronto con l'ETA nativa (una sola volta per route)
                        if (!resultRecorded && initialRemainingSec >= 0) {
                            resultRecorded = true
                            recordEtaComparison(movingElapsedSec)
                        }
                        etaState.value = EtaState(
                            hasRoute            = true,
                            status              = "Arrived",
                            gpsStale            = gpsStale,
                            remainingDistanceM  = 0.0,
                            remainingElevationM = if (hasElevationData) 0.0 else -1.0,
                        )
                        continue
                    }

                    val remainingSec  = etaEngine.computeFromSegments(remainingSegs)
                    val remainDistM   = remainingSegs.sumOf { it.distanceM }
                    // Solo segmenti con pendenza ≥ 1% contribuiscono al dislivello:
                    // sotto questa soglia è quasi sempre rumore residuo dello smoothing.
                    val remainElevM   = if (hasElevationData) {
                        remainingSegs.filter { it.gradePct >= 1.0 }
                                     .sumOf { it.distanceM * it.gradePct / 100.0 }
                    } else -1.0

                    // Baseline per il delta: primo ETA valido con posizione agganciata
                    // dal GPS (evita di congelare la stima dell'intera route quando
                    // il percorso viene caricato a metà giro). Il tempo in movimento
                    // avanza solo sopra la soglia di fermo (stessa del modello):
                    // le soste non spostano il delta.
                    var deltaSec: Long? = null
                    if (remainingSec > 0) {
                        if (initialRemainingSec < 0 && !gpsStale && pipeline.isLocated()) {
                            initialRemainingSec = remainingSec
                            movingElapsedSec    = 0L
                            sampleTickCounter   = 0
                            synchronized(etaSamples) { etaSamples.clear() }
                            logger.log("ETA  ", "Baseline pace catturata: ${remainingSec}s previsti")
                        } else if (initialRemainingSec >= 0 && currentSpeedMs >= 0.5) {
                            movingElapsedSec += TICK_MS / 1000
                        }
                        if (initialRemainingSec >= 0) {
                            deltaSec = movingElapsedSec + remainingSec - initialRemainingSec

                            // Campione per il confronto di fine giro (1/min),
                            // solo se anche l'ETA nativa del Karoo è disponibile
                            sampleTickCounter++
                            if (sampleTickCounter >= SAMPLE_INTERVAL_TICKS) {
                                sampleTickCounter = 0
                                val k = karooEtaSec
                                if (k > 0) synchronized(etaSamples) {
                                    if (etaSamples.size < MAX_SAMPLES) {
                                        etaSamples.add(EtaSample(movingElapsedSec, remainingSec, k))
                                    }
                                }
                            }
                        }
                    }

                    etaState.value = EtaState(
                        remainingSeconds    = remainingSec,
                        hasRoute            = true,
                        gpsStale            = gpsStale,
                        remainingDistanceM  = remainDistM,
                        remainingElevationM = remainElevM,
                        status              = if (remainingSec > 0) "OK" else "Calculating...",
                        deltaSeconds        = deltaSec,
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

    // ── confronto ETA di fine giro ───────────────────────────────────────

    /**
     * Giudica ogni campione contro il tempo in movimento realmente servito da
     * quel punto all'arrivo e salva nello storico (max [RESULTS_MAX] uscite)
     * di quanto BetterEta è stata migliore/peggiore dell'ETA nativa, in punti
     * percentuali di errore medio. Formato entry: "data|diffPct|errBePct|errKarooPct".
     */
    private fun recordEtaComparison(totalMovingSec: Long) {
        val samples = synchronized(etaSamples) { etaSamples.toList() }
        val valid = samples
            .map { s -> Triple(totalMovingSec - s.movingAtSec, s.beRemainingSec, s.karooRemainingSec) }
            .filter { it.first >= MIN_ACTUAL_REMAINING_SEC }
        if (valid.size < MIN_SAMPLES) {
            logger.log("STATS", "Confronto ETA non registrato: ${valid.size} campioni validi (min $MIN_SAMPLES)")
            return
        }

        val errBe = valid.sumOf { (act, be, _) -> kotlin.math.abs(be - act).toDouble() / act } / valid.size
        val errK  = valid.sumOf { (act, _, k)  -> kotlin.math.abs(k - act) / act } / valid.size
        val diffPct   = ((errK - errBe) * 100).roundToInt()
        val errBePct  = (errBe * 100).roundToInt()
        val errKPct   = (errK * 100).roundToInt()

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val entry   = "$dateStr|$diffPct|$errBePct|$errKPct"
        val prefs   = getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
        val history = prefs.getString(RESULTS_KEY, "").orEmpty()
        val entries = (listOf(entry) + history.split(';').filter { it.isNotBlank() }).take(RESULTS_MAX)
        prefs.edit().putString(RESULTS_KEY, entries.joinToString(";")).commit()

        logger.log("STATS", "ETA vs Karoo: diff=$diffPct% (BE $errBePct%, Karoo $errKPct%) su ${valid.size} campioni")
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

        val elevSamples = PolylineDecoder.decodeDistanceElevation(elevPolyline)
        if (elevSamples.isEmpty()) {
            logger.log("ROUTE", "WARN [$source] elevazione vuota — dislivello non disponibile")
        } else {
            // Diagnostica: distanza totale e range quote per validare la decodifica
            // (quote plausibili in Italia = 0-3000 m, distanza ≈ lunghezza route).
            val quote = elevSamples.map { it.second }
            logger.log("ROUTE", "Elev: n=${elevSamples.size} dist=%.0fm quota min=%.1f max=%.1f first=%.1f last=%.1f"
                .format(elevSamples.last().first, quote.min(), quote.max(), quote.first(), quote.last()))
            if (elevSamples.size != coords.size) {
                logger.log("ROUTE", "INFO [$source] ${coords.size} coord, ${elevSamples.size} campioni quota → interpolazione")
            }
        }

        val elevInterp = interpolateElevations(coords, elevSamples)
        return coords.mapIndexed { i, (lat, lng) ->
            RouteWaypoint(lat = lat, lon = lng, elevationM = elevInterp[i])
        }
    }
}
