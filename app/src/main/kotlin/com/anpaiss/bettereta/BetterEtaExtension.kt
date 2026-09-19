package com.anpaiss.bettereta

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class BetterEtaExtension : KarooExtension("better-eta", "1") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var logger: BetterEtaLogger
    private lateinit var sampleLog: SampleLog
    private lateinit var surfaceClassifier: SurfaceClassifier
    private lateinit var prefs: Prefs
    private lateinit var mtaPopup: MtaPopupController

    internal val etaState = MutableStateFlow(EtaState())

    // Full data type ids on the ride page the user is currently looking at.
    // null = not known yet, in which case every field keeps drawing (fail-open).
    internal val visibleDataTypeIds = MutableStateFlow<Set<String>?>(null)

    private val speedModel = AdaptiveSpeedModel()
    private val pipeline   = SegmentPipeline()
    // Memoria dei tempi per (way, verso): caricata in onCreate, salvata con il modello
    private val wayMemory  = WaySpeedMemory()
    private val etaEngine  = EtaEngine(speedModel, pipeline, wayMemory = wayMemory)

    // Shadow ETA: the physics model answers every segment it can (climbs and
    // flats), the learned buckets only where it cannot (descents). Until 12/09
    // it sat at rung 3 of the cascade, which a model rebuilt from the FIT
    // history never reaches: "phys +0" on every ride, no verdict. Never
    // displayed — it only goes to the sample log and to the end-of-ride
    // comparison, which is what decides whether it gets promoted. See
    // PhysicsSpeedModel for why this holds on an e-bike.
    private val physicsModel = PhysicsSpeedModel()
    private val shadowEngine = EtaEngine(speedModel, pipeline, wayMemory = wayMemory, physicsFirst = physicsModel)

    // Passaggio in corso su una way: chiave, metri e secondi accumulati in movimento
    private var passKey   = 0L
    private var passDistM = 0.0
    private var passSec   = 0

    private val speedMsFlow  = MutableStateFlow(0.0)
    private val gradePctFlow = MutableStateFlow(0.0)
    private val locationFlow = MutableStateFlow<Pair<Double, Double>?>(null)
    @Volatile private var rideState: RideState = RideState.Idle
    @Volatile private var pausedSinceMs = 0L   // inizio dell'ultima pausa (Recording → non Recording)

    // Polyline dell'ultimo percorso caricato: se il Karoo riemette lo stesso percorso
    // (es. dopo GPS recovery) non resettiamo la posizione sulla pipeline.
    @Volatile private var lastRoutePolyline: String = ""
    // Da che stato di navigazione viene la route in memoria: lo stub di rientro
    // esiste solo durante un NavigatingRoute
    @Volatile private var lastRouteSource: String = ""
    // Popup MTA in arrivo o a schermo (orario di inizio, 0 = no): l'avviso "margine
    // perso" aspetta che si chiuda. A tempo, così un popup mai comparso (overlay
    // fallito) non zittisce gli avvisi per tutto il giro.
    @Volatile private var mtaAskPendingSinceMs = 0L

    // Fine dell'ultima route caricata: se la nuova route finisce nello stesso
    // punto (ricalcolo / back-to-start), campioni e baseline restano validi.
    @Volatile private var lastRouteEnd: Pair<Double, Double>? = null

    // Stato della classificazione fondo per la route corrente (per il retry
    // quando il primo tentativo fallisce offline)
    @Volatile private var surfacesApplied = false
    @Volatile private var lastWaypointsForSurfaces: List<RouteWaypoint>? = null

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

    // ── Karoo native references — recorded only, never fed into the ETA ──
    // These are the Karoo's own answers to questions we compute ourselves.
    // Phase 1 logs them side by side with ours; only once the logs from real
    // rides say they are trustworthy do they get promoted into the ETA path.
    @Volatile private var karooDistToDestM = -1.0
    @Volatile private var karooOnRoute: Double? = null
    // Fuori route secondo il Karoo (ON_ROUTE=0): l'ETA viene sospeso e il dead
    // reckoning congelato — avanzare sulla route mentre si è altrove ha dato
    // 32 min di ETA sbagliato il 05/09 (partenza fuori route, aggancio al km 7,6)
    @Volatile private var offRoute = false
    // Il suggerimento di posizione del Karoo arriva ~1 min dopo la partenza: al
    // primo valore si verifica l'aggancio iniziale fatto senza di lui
    @Volatile private var hintChecked = false
    @Volatile private var karooAscentRemM = -1.0

    // Route metadata published by NavigatingRoute that used to be discarded.
    @Volatile private var routeName = ""
    @Volatile private var routeTotalDistM = -1.0
    @Volatile private var routeClimbs: List<OnNavigationState.NavigationState.Climb> = emptyList()
    @Volatile private var rejoinActive = false
    @Volatile private var karooRejoinDistM: Double? = null   // deviazione di rientro proposta dal Karoo

    // Mandatory time of arrival (epoch ms, 0 = nessuno) e hash della polyline
    // per cui il popup è già stato mostrato (risposto o chiuso): una domanda
    // sola per route, mai sulle transizioni RideState successive né dopo un
    // restart dell'estensione (entrambi persistiti in Prefs).
    @Volatile private var mtaEpochMs = 0L
    @Volatile private var mtaSetAtMs = 0L
    @Volatile private var mtaAskedRouteHash = 0
    private val marginGate = MarginAlertGate()

    // Last set of our own data types seen on the active page, for transition logging.
    @Volatile private var lastVisibleOurs: Set<String>? = null

    // Timestamp dell'ultimo fix GPS ricevuto (0 = mai ricevuto)
    @Volatile private var lastGpsUpdateMs  = 0L
    @Volatile private var lastFixSavedMs   = 0L
    // Stato GPS per logging (evita righe duplicate)
    @Volatile private var gpsStaleLogged  = false
    @Volatile private var gpsStaleStartMs = 0L

    private val extensionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickerJob: Job? = null
    private val consumerIds = mutableListOf<String>()

    // Log ETA ogni LOG_INTERVAL_TICKS secondi (non ogni tick)
    private var logTickCounter = 0
    // Campioni CSV: 1 Hz in movimento, 1/min da fermo (un giro finto lasciato
    // acceso una notte ha riempito il file di 40k righe a velocità zero)
    private var stationarySampleTicks = 0

    // "In movimento" = velocità sopra soglia E avanzamento reale sulla route
    // nell'ultimo minuto (vedi ProgressGate). Governa apprendimento del
    // modello, tempo in movimento del pace e cadenza dei campioni.
    private val progressGate = ProgressGate()
    @Volatile private var learningPaused = false

    // Giro libero (registrazione senza route): il modello impara lo stesso, con la
    // guardia GPS (spostamento netto plausibile nell'ultimo minuto) al posto di
    // quella sulla route. Niente CSV e niente memoria tratti senza route.
    private val gpsGate = GpsProgressGate()
    private var freeRideSamples = 0

    // Inizio del giro in corso: finisce in Prefs.rideStartsMs al primo salvataggio
    // del modello, così il FIT del giro non viene reimportato. Se il processo muore
    // prima di aver mai salvato, l'import dal FIT recupera il giro.
    private var liveRideStartMs = 0L

    // Import dello storico FIT del Karoo: una volta per file, in background,
    // mai durante una registrazione. Elenco dei file già importati su disco.
    @Volatile private var historyImportRunning = false

    // ── Confronto ETA BetterEta vs ETA nativa Karoo ──────────────────────
    // Un campione al minuto (movingAt = tempo in movimento dalla baseline,
    // previsioni di entrambi); a fine giro ogni campione viene giudicato
    // contro il tempo in movimento realmente servito da quel punto in poi.
    private data class EtaSample(
        val movingAtSec: Long,
        val beRemainingSec: Long,
        val karooRemainingSec: Double,
        val physicsRemainingSec: Long,
    )
    private val etaSamples = mutableListOf<EtaSample>()  // accesso via synchronized(etaSamples)
    @Volatile private var karooEtaSec    = -1.0          // ultimo TIME_TO_DESTINATION ricevuto
    @Volatile private var resultRecorded = false         // già registrato per questa route
    private var sampleTickCounter = 0

    companion object {
        /** Istanza viva del servizio, per la schermata dell'app (stesso processo). */
        @Volatile var instance: BetterEtaExtension? = null

        private const val TICK_MS              = 1000L
        private const val GPS_TIMEOUT_MS       = 10_000L
        private const val PREFS_NAME           = "better_eta_model"
        private const val LOG_INTERVAL_TICKS   = 60       // 1 riga ETA al minuto
        private const val SNAP_LOG_THRESHOLD   = 50.0     // logga snap solo se > 50m
        // Popup MTA: attesa massima di un ETA con posizione agganciata dal GPS,
        // così le rotelle partono dall'arrivo stimato da dove sei (giro 05/09:
        // aperto prima dell'aggancio → ETA della route intera, 10:45 per un
        // arrivo reale verso le 10:15). Oltre il limite, l'ETA che c'è.
        private const val MTA_LOCATED_WAIT_MS = 90_000L
        // 120 s (era 30, prima 10): la stima Rejoin di una partenza fuori route
        // arriva dopo la prima auto-pausa e il primo DISTANCE_TO_DESTINATION,
        // che il Karoo pubblica ~3 min dopo l'avvio (giri 12/09 e 17/09: con
        // 30 s il default era "adesso + 1 h")
        private const val MTA_ANY_ETA_WAIT_MS = 120_000L
        // Nessun avviso "margine perso" subito dopo aver impostato l'MTA
        private const val MTA_ALERT_GRACE_MS  = 60_000L
        // Sosta almeno così lunga → alla ripresa il popup ripropone l'MTA attuale
        // (giro 12/09: soste di 8 e 9,5 min, arrivo cambiato, nessuna domanda).
        // Le auto-pause ai semafori (secondi) non contano.
        private const val MTA_REASK_PAUSE_MS  = 5 * 60_000L
        // Attese del default (90 + 120 s) + popup a schermo (45 s) + margine
        private const val MTA_ASK_PENDING_MAX_MS = 5 * 60_000L
        // Su una stima "~" l'avviso scatta solo con un ritardo che la stima grossolana non spiega
        private const val MTA_APPROX_ALERT_SLACK_S = 600L
        // Import storico automatico: parte poco dopo l'avvio, se non si registra
        private const val HISTORY_AUTO_IMPORT_DELAY_MS = 20_000L
        private const val HISTORY_MIN_KM = 10.0   // sotto: prove, test in casa, giri da niente
        // Posizione Karoo negativa (prima dello start) fino a questa distanza = start
        private const val BEFORE_START_TOLERANCE_M = 1000.0

        // Confronto ETA: cadenza campioni, minimo percorso reale davanti perché
        // un campione sia giudicabile (vicino all'arrivo l'errore relativo
        // esplode), campioni minimi per registrare un risultato, cap di memoria.
        private const val SAMPLE_INTERVAL_TICKS      = 60
        private const val MIN_ACTUAL_REMAINING_SEC   = 300L
        private const val MIN_SAMPLES                = 5
        private const val SAME_DEST_THRESHOLD_M     = 200.0
        private const val ARRIVAL_NET_MAX_REMAINING_SEC = 300L
        // Arrivo fuori route (parcheggio a lato della route, giro 12/09): la
        // navigazione si chiude con la posizione congelata a km dalla fine e il
        // residuo stimato sopra i 300 s. Conta come arrivo se siamo negli ultimi
        // km lungo la route E in aria vicino al punto finale.
        private const val ARRIVAL_NET_MAX_ALONG_M = 3_000.0
        private const val ARRIVAL_NET_MAX_AIR_M   = 1_000.0
        // Polyline di NavigatingToDestination più corte di così, con una route in
        // memoria, sono istruzioni di rientro del Karoo, non route
        private const val REJOIN_STUB_MAX_M = 3_000.0
        private const val MAX_SAMPLES                = 600
        // Memoria tratti: un passaggio conta solo se lungo/durato almeno tanto
        private const val WAY_PASS_MIN_M             = 50.0
        private const val WAY_PASS_MIN_SEC           = 10
        internal const val RESULTS_PREFS             = "better_eta_results"
        internal const val RESULTS_KEY               = "history"
        internal const val RESULTS_MAX               = 20
    }

    override val types by lazy {
        listOf(
            EtaAbsoluteDataType(extension, etaState, visibleDataTypeIds),
            EtaRemainingDataType(extension, etaState, visibleDataTypeIds),
            EtaMasterDataType(extension, etaState, visibleDataTypeIds),
            EtaFarthestDataType(extension, etaState, visibleDataTypeIds),
            EtaMarginDataType(extension, etaState, visibleDataTypeIds),
            EtaPaceDataType(extension, etaState, visibleDataTypeIds),
        )
    }

    override fun onCreate() {
        super.onCreate()
        logger = BetterEtaLogger(applicationContext)
        BetterEtaLogger.shared = logger
        instance = this
        sampleLog = SampleLog(applicationContext)
        logger.log("SYS  ", "Sample log: ${sampleLog.path}")
        surfaceClassifier = SurfaceClassifier(applicationContext, logger,
            MapsforgeSurfaceSource(applicationContext, logger))
        prefs = Prefs(applicationContext)
        loadWayMemory()
        mtaPopup = MtaPopupController(applicationContext, logger,
            onSet = { h, m -> mtaAskPendingSinceMs = 0L; setMta(h, m) },
            onDismiss = {
                mtaAskPendingSinceMs = 0L
                logger.log("MTA  ", if (mtaEpochMs > 0L) "Popup chiuso: MTA invariato" else "Nessun MTA impostato")
            })

        // Versione installata: correla il log al build effettivamente sul dispositivo
        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            logger.log("SYS  ", "Versione: ${pi.versionName} (${pi.longVersionCode})")
        }

        // Un crash non gestito finora non lasciava traccia nel file: registralo
        // prima di delegare all'handler di sistema (che uccide il processo)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            logger.logError("CRASH", "Eccezione non gestita su ${thread.name}", t)
            prevHandler?.uncaughtException(thread, t)
        }

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

                // Route metadata the Karoo already publishes. Not used by the ETA
                // yet — they go into the sample log as independent references for
                // the values we compute ourselves (position, remaining ascent).
                when (nav) {
                    is OnNavigationState.NavigationState.NavigatingRoute -> {
                        routeName       = nav.name
                        routeTotalDistM = nav.routeDistance
                        routeClimbs     = nav.climbs
                        rejoinActive    = nav.rejoinDistance != null || nav.rejoinPolyline != null
                        karooRejoinDistM = nav.rejoinDistance
                    }
                    is OnNavigationState.NavigationState.NavigatingToDestination -> {
                        routeName       = nav.destination.name ?: "destination"
                        routeTotalDistM = -1.0   // not published for a free destination
                        routeClimbs     = nav.climbs
                        rejoinActive    = false
                        karooRejoinDistM = null
                    }
                    else -> Unit
                }

                val (polyline, elevPolyline, source) = when (nav) {
                    is OnNavigationState.NavigationState.NavigatingRoute ->
                        Triple(nav.routePolyline.orEmpty(), nav.routeElevationPolyline.orEmpty(), "NavigatingRoute")
                    is OnNavigationState.NavigationState.NavigatingToDestination ->
                        Triple(nav.polyline.orEmpty(), nav.elevationPolyline.orEmpty(), "NavigatingToDestination")
                    else -> {
                        logger.log("ROUTE", "Stato non gestito → ETA sospeso, route in memoria")
                        navActive = false
                        maybeRecordAtEnd("Navigazione chiusa")
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

                // Stub di rientro: durante un giro con route il Karoo emette
                // NavigatingToDestination con polyline di pochi waypoint (l'istruzione
                // per tornare sulla route dopo una deviazione). Non è una route: presa
                // per tale azzerava posizione, baseline e campioni del confronto
                // (giro 06/09, 18:13: 16 waypoint, ETA "1 min", 67 campioni persi).
                // Solo se la route in memoria è una route pianificata: navigando verso
                // una destinazione ("Home", giro 19/09) il ricalcolo degli ultimi 3 km
                // è la route vera e veniva scartato come stub.
                if (source == "NavigatingToDestination" && lastRouteSource == "NavigatingRoute" &&
                    lastRoutePolyline.isNotBlank() &&
                    polyline != lastRoutePolyline && routeStubM(polyline) < REJOIN_STUB_MAX_M) {
                    logger.log("ROUTE", "[$source] stub di rientro (%.0fm) ignorato — route e stato preservati".format(routeStubM(polyline)))
                    return@addConsumer
                }

                if (polyline == lastRoutePolyline) {
                    logger.log("ROUTE", "[$source] stesso percorso — posizione preservata")
                    // Classificazione fondo fallita in precedenza (es. offline al
                    // caricamento): la ri-emissione della route è l'occasione per ritentare
                    if (!surfacesApplied) {
                        val wps = lastWaypointsForSurfaces
                        if (wps != null) extensionScope.launch {
                            surfaceClassifier.classify(wps)?.let { r ->
                                pipeline.applySurfaces(r.surfaces, r.wayKeys)
                                surfacesApplied = r.complete
                                logger.log("SURF ", "Classificazione fondo al retry: " +
                                    if (r.complete) "completa" else "parziale, si ritenta alla prossima riemissione")
                            }
                        }
                    }
                    return@addConsumer
                }

                val prevRouteHash = lastRoutePolyline.hashCode()
                val routeHash     = polyline.hashCode()
                lastRoutePolyline = polyline
                lastRouteSource   = source
                karooOnRoute      = null      // riferito alla route precedente
                offRoute          = false
                hintChecked       = false
                hasElevationData  = elevPolyline.isNotBlank()
                val waypoints = decodeRoute(polyline, elevPolyline, source)

                logger.log("ROUTE", "[$source] '$routeName' dist=${routeTotalDistM.toInt()}m " +
                    "climbs=${routeClimbs.size} (asc=${routeClimbs.sumOf { it.totalElevation }.toInt()}m)")
                sampleLog.ride(routeName, routeTotalDistM, polyline.hashCode())

                // Route diversa ma stessa destinazione (ricalcolo, "back to start"
                // verso il punto di partenza, ...): campioni e baseline restano
                // validi — misurano l'arrivo nello stesso posto. Si azzera solo
                // quando cambia davvero la meta (giro 30/08: 88 campioni persi
                // per il passaggio route → back-to-start sulla stessa meta).
                val newEnd  = waypoints.lastOrNull()
                val endDist = if (newEnd != null && lastRouteEnd != null)
                    haversineM(lastRouteEnd!!.first, lastRouteEnd!!.second, newEnd.lat, newEnd.lon)
                    else Double.MAX_VALUE
                lastRouteEnd = newEnd?.let { it.lat to it.lon }

                // MTA: è una decisione del ciclista, non della route. Mai azzerato
                // da un cambio di percorso (giro 06/09: cercare una fontana o una
                // deviazione lo azzeravano e lo richiedevano da zero). Ripristinato
                // se salvato per questa polyline (restart a metà giro); portato sulla
                // nuova polyline se la meta è la stessa; se la meta cambia resta
                // com'è e il popup lo RIPROPONE con l'orario attuale già impostato.
                // Si azzera solo all'inizio di un giro nuovo.
                val askedRecently = System.currentTimeMillis() - prefs.mtaAskedAtMs < ArrivalMargin.STALE_AFTER_MS
                when {
                    prefs.mtaRouteHash == routeHash && askedRecently -> restoreMta(routeHash)
                    endDist <= SAME_DEST_THRESHOLD_M && mtaAskedRouteHash == prevRouteHash -> {
                        mtaAskedRouteHash = routeHash
                        prefs.mtaRouteHash = routeHash
                    }
                    else -> {
                        mtaAskedRouteHash = 0   // meta diversa: riproponi, senza toccare l'MTA
                        if (mtaEpochMs > 0L) logger.log("MTA  ", "Meta cambiata: MTA mantenuto (%s), verrà riproposto".format(
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(mtaEpochMs))))
                    }
                }

                if (endDist <= SAME_DEST_THRESHOLD_M) {
                    logger.log("STATS", "Route cambiata, stessa destinazione (Δ${endDist.toInt()}m) — " +
                        "campioni (${synchronized(etaSamples) { etaSamples.size }}) e baseline preservati")
                } else {
                    // Meta cambiata a ridosso dell'arrivo: il confronto va valutato ora,
                    // non buttato (stessa rete di sicurezza della navigazione chiusa)
                    val remainingNow = etaState.value.remainingSeconds
                    if (!resultRecorded && initialRemainingSec >= 0 && remainingNow in 0..ARRIVAL_NET_MAX_REMAINING_SEC) {
                        resultRecorded = true
                        logger.log("STATS", "Meta cambiata a ${remainingNow}s dall'arrivo — confronto valutato ora")
                        recordEtaComparison(movingElapsedSec)
                    }
                    initialRemainingSec = -1L  // nuova meta → nuova baseline per il delta
                    movingElapsedSec    = 0L
                    resultRecorded      = false
                    synchronized(etaSamples) {
                        if (etaSamples.isNotEmpty()) {
                            logger.log("STATS", "${etaSamples.size} campioni confronto scartati — destinazione cambiata prima dell'arrivo")
                        }
                        etaSamples.clear()
                    }
                }
                progressGate.reset()
                closeWayPass()
                extensionScope.launch {
                    pipeline.setRoute(waypoints)
                    val segCount = pipeline.remainingSegments().size
                    if (waypoints.isNotEmpty()) {
                        logger.log("ROUTE", "Pipeline: ${waypoints.size} wp → $segCount segmenti")
                    }
                    // Route caricata a registrazione già avviata: la domanda MTA
                    // non è partita alla transizione a Recording, parte ora
                    maybeAskMta()
                    // Classificazione fondo (OSM/cache) e riclassificazione segmenti:
                    // asincrona, l'ETA gira intanto coi bucket globali
                    surfacesApplied = false
                    lastWaypointsForSurfaces = waypoints
                    surfaceClassifier.classify(waypoints)?.let { r ->
                        pipeline.applySurfaces(r.surfaces, r.wayKeys)
                        surfacesApplied = r.complete
                        logger.log("SURF ", "Segmenti riclassificati col fondo: " +
                            "${pipeline.remainingSegments().size} rimanenti" +
                            if (r.complete) "" else " (parziale, si ritenta alla prossima riemissione)")
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
                (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                    // Il Karoo pubblica TIME_TO_DESTINATION in millisecondi (come ELAPSED_TIME):
                    // trattarlo come secondi gonfiava l'errore nativo di ~1000x (uscita 29/08).
                    if (karooEtaSec < 0) logger.log("STATS", "TIME_TO_DESTINATION: primo valore raw=$it → ${it / 1000.0}s")
                    karooEtaSec = it / 1000.0
                }
            }

            // ── Distanza a destinazione + on-route nativi (solo log, fase 1) ──────
            // routeDistance - DISTANCE_TO_DESTINATION è la posizione lungo la route
            // secondo il Karoo: il termine di paragone della nostra dead reckoning.
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.values?.let { v ->
                    v[DataType.Field.DISTANCE_TO_DESTINATION]?.let {
                        // Scale is asserted by the docs, not verified: TIME_TO_DESTINATION
                        // turned out to be milliseconds. Log the first raw value so the
                        // unit can be confirmed from a real ride before anyone relies on it.
                        if (karooDistToDestM < 0) logger.log("STATS", "DISTANCE_TO_DESTINATION: primo valore raw=$it")
                        karooDistToDestM = it
                    }
                    karooOnRoute = v[DataType.Field.ON_ROUTE]
                }
            }

            // ── Dislivello rimanente nativo (solo log, fase 1) ────────────────────
            consumerIds += karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.ELEVATION_REMAINING), { _ -> }, {}
            ) { event: OnStreamState ->
                (event.state as? StreamState.Streaming)?.dataPoint?.values
                    ?.get(DataType.Field.ASCENT_REMAINING)?.let {
                        if (karooAscentRemM < 0) logger.log("STATS", "ASCENT_REMAINING: primo valore raw=$it")
                        karooAscentRemM = it
                    }
            }

            // ── Pagina attiva: gate di visibilità per i redraw ────────────────────
            consumerIds += karooSystem.addConsumer(
                ActiveRidePage.Params, { _ -> }, {}
            ) { event: ActiveRidePage ->
                val onPage = event.page.elements.map { it.dataTypeId }.toSet()
                val ours   = types.map { it.dataTypeId }.filter { it in onPage }.toSet()
                if (ours != lastVisibleOurs) {
                    lastVisibleOurs = ours
                    logger.log("VIEW ", "Campi BetterETA sulla pagina attiva: " +
                        if (ours.isEmpty()) "nessuno — redraw sospesi"
                        else ours.joinToString { it.substringAfterLast(':') })
                }
                visibleDataTypeIds.value = onPage
            }

            // ── Posizione GPS ─────────────────────────────────────────────────────
            consumerIds += karooSystem.addConsumer({ _ -> }, {}) { event: OnLocationChanged ->
                if (lastGpsUpdateMs == 0L) {
                    logger.log("GPS  ", "Primo fix: %.5f, %.5f".format(event.lat, event.lng))
                }
                locationFlow.value = event.lat to event.lng
                val nowFix = System.currentTimeMillis()
                // Ultimo fix nelle prefs una volta al minuto (prova mappe offline nell'app)
                if (nowFix - lastFixSavedMs > 60_000L) {
                    lastFixSavedMs = nowFix
                    prefs.lastFixLat = event.lat; prefs.lastFixLon = event.lng
                }
                lastGpsUpdateMs    = nowFix
            }

            // ── Profilo utente: massa per il modello fisico ───────────────────────
            // The learned power is only meaningful with the mass it was inferred
            // with, so a changed weight resets it (setRiderMassKg says when).
            consumerIds += karooSystem.addConsumer(
                UserProfile.Params, { _ -> }, {}
            ) { profile: UserProfile ->
                val reset = physicsModel.setRiderMassKg(profile.weight.toDouble())
                logger.log("MODEL", "UserProfile: peso=${profile.weight}kg → massa totale=%.1fkg%s"
                    .format(physicsModel.massKg, if (reset) " (potenza appresa azzerata)" else ""))
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
                    // Idle → Recording = giro nuovo: il fattore di giornata riparte da 1.
                    // Paused → Recording è una ripresa: resta com'è.
                    val newRide = rideState is RideState.Idle
                    if (newRide) {
                        speedModel.newRide()
                        gpsGate.reset()
                        freeRideSamples = 0
                        liveRideStartMs = System.currentTimeMillis()
                        clearMta()   // l'MTA vale per un giro: il prossimo lo chiede da capo
                        logger.log("RIDE ", "Avviata (nuovo giro) — base preservata, fattore di giornata a 1.00")
                    } else {
                        logger.log("RIDE ", "Ripresa — model e posizione preservati")
                        // Sosta lunga con un MTA impostato: l'arrivo è cambiato, il
                        // popup lo ripropone con l'orario attuale sulle rotelle
                        // (SET cambia, DISMISS o timeout lasciano com'è)
                        val pauseMs = if (pausedSinceMs > 0L) System.currentTimeMillis() - pausedSinceMs else 0L
                        if (pauseMs >= MTA_REASK_PAUSE_MS && mtaEpochMs > 0L && navActive) {
                            logger.log("MTA  ", "Sosta di ${pauseMs / 60_000} min — MTA riproposto")
                            mtaAskedRouteHash = 0
                        }
                    }
                    pausedSinceMs = 0L
                    rideState = state
                    maybeAskMta()
                }
                if (rideState is RideState.Recording && state !is RideState.Recording) {
                    logger.log("RIDE ", "Sospesa — model salvato")
                    pausedSinceMs = System.currentTimeMillis()
                    closeWayPass()
                    saveModel()
                }
                // Giro chiuso con la navigazione ancora aperta: stessa rete di
                // sicurezza della chiusura navigazione
                if (state is RideState.Idle && rideState !is RideState.Idle && navActive) {
                    maybeRecordAtEnd("Giro chiuso")
                }
                rideState = state
            }

            // ── Storico FIT: import automatico dei giri mai importati ─────────────
            extensionScope.launch {
                delay(HISTORY_AUTO_IMPORT_DELAY_MS)
                if (rideState !is RideState.Recording) importRideHistory(auto = true)
            }

            // ── Ticker principale ─────────────────────────────────────────────────
            tickerJob = extensionScope.launch {
                while (true) {
                    delay(TICK_MS)

                    val hasRoute = pipeline.hasRoute()
                    if (!hasRoute || !navActive) {
                        etaState.value = EtaState(status = "No Route")
                        if (rideState is RideState.Recording) freeRideLearn()
                        continue
                    }

                    if (rideState !is RideState.Recording) {
                        // Preserva gli ultimi valori calcolati (ETA, distanza, dislivello):
                        // il Karoo va in auto-pausa a ogni fermata e azzerarli fa comparire
                        // i segnaposto "--" per tutta la sosta.
                        val prev = etaState.value
                        etaState.value = prev.copy(hasRoute = true, status = "Paused",
                            mtaEpochMs = mtaEpochMs, marginSeconds = marginFor(prev.remainingSeconds, prev.estimateApprox))
                        continue
                    }

                    // 0. Fuori route secondo il Karoo: transizioni e riaggancio al rientro
                    val karooPosM = karooAlongRouteM()
                    if (karooPosM != null && !hintChecked && pipeline.isLocated()) {
                        hintChecked = true
                        val loc = locationFlow.value
                        val ours = pipeline.distanceAlongRouteM()
                        if (loc != null) {
                            val corr = pipeline.relocateWithHint(loc.first, loc.second, karooPosM)
                            if (corr > 0.0) {
                                logger.log("GPS  ", "Primo suggerimento Karoo (%.0fm) contro aggancio iniziale (%.0fm): riagganciato, correzione ${corr.toInt()}m — baseline pace azzerata".format(karooPosM, ours))
                                initialRemainingSec = -1L
                                movingElapsedSec    = 0L
                                progressGate.reset()
                            } else {
                                logger.log("GPS  ", "Primo suggerimento Karoo (%.0fm) coerente con l'aggancio iniziale (%.0fm)".format(karooPosM, ours))
                            }
                        }
                    }
                    val nowOff    = karooOnRoute == 0.0
                    if (nowOff != offRoute) {
                        offRoute = nowOff
                        if (nowOff) {
                            logger.log("ROUTE", "Off route (Karoo ON_ROUTE=0) — ETA sospeso, posizione congelata a %.0fm".format(pipeline.distanceAlongRouteM()))
                        } else {
                            logger.log("ROUTE", "Rientro in route" + (karooPosM?.let { " — posizione Karoo %.0fm, nostra %.0fm".format(it, pipeline.distanceAlongRouteM()) } ?: ""))
                            val loc = locationFlow.value
                            if (karooPosM != null && loc != null) {
                                val corr = pipeline.relocateWithHint(loc.first, loc.second, karooPosM)
                                if (corr > 0.0) {
                                    logger.log("GPS  ", "Riaggancio al rientro: correzione ${corr.toInt()}m verso la posizione Karoo — baseline pace azzerata")
                                    initialRemainingSec = -1L
                                    movingElapsedSec    = 0L
                                    progressGate.reset()
                                }
                            }
                        }
                    }

                    // 1. Aggiorna modello (col fondo su cui ci troviamo, se noto),
                    // solo se si sta davvero avanzando sulla route: il GPS che
                    // balla da fermo dà velocità plausibili ma nessun progresso.
                    val currentSpeedMs  = speedMsFlow.value
                    val currentGradePct = gradePctFlow.value
                    val currentSurface  = pipeline.currentSurface()
                    val progressing     = progressGate.onTick(pipeline.distanceAlongRouteM())
                    val moving          = currentSpeedMs >= 0.5 && progressing && !offRoute
                    if (moving) {
                        speedModel.update(currentSpeedMs, currentGradePct, currentSurface)
                        physicsModel.update(currentSpeedMs, currentGradePct, currentSurface)
                    }
                    if (currentSpeedMs >= 0.5 && !progressing && !learningPaused && !offRoute) {
                        learningPaused = true
                        logger.log("MODEL", "Apprendimento sospeso: speed=%.1fkm/h ma nessun avanzamento sulla route nell'ultimo minuto (GPS fermo?)".format(currentSpeedMs * 3.6))
                    } else if (moving && learningPaused) {
                        learningPaused = false
                        logger.log("MODEL", "Apprendimento ripreso: avanzamento sulla route rilevato")
                    }

                    // 2. Avanzamento stimato (congelato fuori route)
                    if (!offRoute) pipeline.advanceByDistance(currentSpeedMs * (TICK_MS / 1000.0))

                    // 2b. Memoria dei tempi per tratto: accumula il passaggio sulla way corrente
                    trackWayPass(if (offRoute) 0L else pipeline.currentWayKey(), moving, currentSpeedMs)

                    // 3. Snap GPS (solo se fix recente)
                    val now      = System.currentTimeMillis()
                    val gpsStale = lastGpsUpdateMs > 0 && now - lastGpsUpdateMs > GPS_TIMEOUT_MS

                    if (!gpsStale) {
                        locationFlow.value?.let { (lat, lon) ->
                            val correction = pipeline.snapToLocation(lat, lon, karooPosM)
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

                    if (offRoute) {
                        // Anche fuori route si sta andando verso la meta: il tempo in
                        // movimento conta per il pace e per il giudizio dei campioni
                        // (giro 12/09: ultimi 8 min fuori route non contati → "reale"
                        // troppo corto nel confronto di fine giro)
                        if (initialRemainingSec >= 0 && currentSpeedMs >= 0.5) movingElapsedSec += TICK_MS / 1000

                        // Fuori route ma il Karoo sta facendo rientrare: ETA = deviazione
                        // di rientro (rejoinDistance, senza quote → velocità piana del
                        // modello) + route dal punto di rientro, che è la posizione lungo
                        // la route secondo il Karoo. Stima grossolana, marcata "~".
                        val rejoinM = karooRejoinDistM
                        if (rejoinM != null && karooPosM != null) {
                            val segs      = pipeline.remainingSegmentsFrom(karooPosM)
                            val routeBd   = etaEngine.computeBreakdown(segs)
                            val routeSec  = routeBd.seconds
                            val flatMs    = speedModel.predict(0.0).speedMs.coerceAtLeast(0.5)
                            val detourSec = (rejoinM / flatMs).toLong()
                            val totalSec  = detourSec + routeSec
                            etaState.value = EtaState(
                                remainingSeconds   = totalSec,
                                hasRoute           = true,
                                status             = "Rejoin",
                                gpsStale           = gpsStale,
                                remainingDistanceM = rejoinM + segs.sumOf { it.distanceM },
                                mtaEpochMs         = mtaEpochMs,
                                marginSeconds      = marginFor(totalSec, approx = true),
                                estimateApprox     = true,
                                dayFactor          = speedModel.currentDayFactor,
                                uncertaintySeconds = EtaUncertainty.seconds(totalSec, routeBd.memoryDistanceShare, routeBd.fallbackDistanceShare, approx = true),
                            )
                            logTickCounter++
                            if (logTickCounter >= LOG_INTERVAL_TICKS) {
                                logTickCounter = 0
                                logger.log("ETA  ", "Rejoin: deviazione %.0fm a %.1fkm/h (%ds) + route da %.0fm (%ds) = %ds".format(
                                    rejoinM, flatMs * 3.6, detourSec, karooPosM, routeSec, totalSec))
                            }
                        } else {
                            etaState.value = EtaState(
                                hasRoute      = true,
                                status        = "Off route",
                                gpsStale      = gpsStale,
                                mtaEpochMs    = mtaEpochMs,
                            )
                        }
                        continue
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
                            hasRoute               = true,
                            status                 = "Arrived",
                            gpsStale               = gpsStale,
                            remainingDistanceM     = 0.0,
                            remainingElevationM    = if (hasElevationData) 0.0 else -1.0,
                            farthestPointDistanceM = 0.0,
                            mtaEpochMs             = mtaEpochMs,
                            marginSeconds          = marginFor(0L, approx = false),
                        )
                        continue
                    }

                    val primary       = etaEngine.computeBreakdown(remainingSegs)
                    val shadow        = shadowEngine.computeBreakdown(remainingSegs)
                    val remainingSec  = primary.seconds
                    val remainDistM   = remainingSegs.sumOf { it.distanceM }

                    // Remaining ascent, computed two ways so the change can be judged
                    // from real rides instead of asserted:
                    //  - "thresh" is the historical rule, counting only segments at
                    //    >= 1%. It drops every false flat and so under-reports, the
                    //    error growing with the length of the route.
                    //  - "full" sums every positive delta. The 50 m elevation smoothing
                    //    in SegmentPipeline already removes the polyline noise the
                    //    threshold was there to guard against, so this is the one shown.
                    // Both go to the sample log alongside the Karoo's ASCENT_REMAINING
                    // and its climb list, which are two independent references.
                    val remainElevThreshM = if (hasElevationData) {
                        remainingSegs.filter { it.gradePct >= 1.0 }
                                     .sumOf { it.distanceM * it.gradePct / 100.0 }
                    } else -1.0
                    val remainElevM = if (hasElevationData) {
                        remainingSegs.filter { it.gradePct > 0.0 }
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
                        } else if (initialRemainingSec >= 0 && moving) {
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
                                        etaSamples.add(EtaSample(movingElapsedSec, remainingSec, k, shadow.seconds))
                                    }
                                }
                            }
                        }
                    }

                    val locatedNow = pipeline.isLocated()
                    val marginSec = marginFor(remainingSec, approx = !locatedNow)
                    val uncertaintySec = EtaUncertainty.seconds(remainingSec,
                        primary.memoryDistanceShare, primary.fallbackDistanceShare, approx = !locatedNow)
                    etaState.value = EtaState(
                        remainingSeconds       = remainingSec,
                        hasRoute               = true,
                        gpsStale               = gpsStale,
                        remainingDistanceM     = remainDistM,
                        remainingElevationM    = remainElevM,
                        status                 = if (remainingSec > 0) "OK" else "Calculating...",
                        deltaSeconds           = deltaSec,
                        farthestPointDistanceM = pipeline.distanceToFarthestPointM(),
                        mtaEpochMs             = mtaEpochMs,
                        marginSeconds          = marginSec,
                        // Non ancora agganciati (aspettiamo il GPS o il suggerimento del
                        // Karoo): l'ETA vale dallo start, mostrato come stima "~"
                        estimateApprox         = !locatedNow,
                        dayFactor              = speedModel.currentDayFactor,
                        uncertaintySeconds     = uncertaintySec,
                    )

                    // 5. Riga di campionamento per l'analisi post-uscita
                    stationarySampleTicks = if (moving) 0 else stationarySampleTicks + 1
                    if (moving || stationarySampleTicks >= 60) {
                        if (!moving) stationarySampleTicks = 0
                    recordSample(
                        now             = now,
                        speedMs         = currentSpeedMs,
                        gradePct        = currentGradePct,
                        surface         = currentSurface,
                        posOursM        = pipeline.distanceAlongRouteM(),
                        remainDistM     = remainDistM,
                        remainAscThresh = remainElevThreshM,
                        remainAscFull   = remainElevM,
                        remainingSec    = remainingSec,
                        shadowSec       = shadow.seconds,
                        fallbackShare   = primary.fallbackDistanceShare,
                        mtaMarginS      = marginSec,
                    )
                    }

                    // 6. Log periodico ETA
                    logTickCounter++
                    if (logTickCounter >= LOG_INTERVAL_TICKS) {
                        logTickCounter = 0
                        logEtaSnapshot(remainingSec, currentSpeedMs, currentGradePct, gpsStale,
                            remainDistM, deltaSec, karooEtaSec, marginSec, primary.memoryDistanceShare, uncertaintySec,
                            shadow.seconds, shadow.physicsDistanceShare)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        logger.log("SYS  ", "=== BetterETA fermato ===")
        instance = null
        mtaPopup.hide()
        closeWayPass()
        saveModel()
        sampleLog.close()
        tickerJob?.cancel()
        tickerJob = null
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }

    // ── logging ──────────────────────────────────────────────────────────

    /**
     * Writes one row of the structured sample log. Everything the Karoo
     * publishes natively sits next to our own answer for the same quantity,
     * which is what makes the phase 2/3/4 decisions checkable after a ride
     * rather than a matter of opinion.
     */
    private fun recordSample(
        now: Long,
        speedMs: Double,
        gradePct: Double,
        surface: Surface,
        posOursM: Double,
        remainDistM: Double,
        remainAscThresh: Double,
        remainAscFull: Double,
        remainingSec: Long,
        shadowSec: Long,
        fallbackShare: Double,
        mtaMarginS: Long?,
    ) {
        val karooDist = karooDistToDestM.takeIf { it >= 0.0 }
        sampleLog.record(
            SampleLog.Row(
                tMs           = now,
                rideState     = rideState::class.simpleName.orEmpty(),
                gpsAgeMs      = if (lastGpsUpdateMs > 0) now - lastGpsUpdateMs else -1L,
                speedMs       = speedMs,
                gradePct      = gradePct,
                surface       = surface,
                posOursM      = posOursM,
                posKarooM     = if (routeTotalDistM > 0.0 && karooDist != null) routeTotalDistM - karooDist else null,
                onRoute       = karooOnRoute,
                rejoinActive  = rejoinActive,
                remDistOursM  = remainDistM,
                remDistKarooM = karooDist,
                remAscThreshM = remainAscThresh,
                remAscFullM   = remainAscFull,
                remAscKarooM  = karooAscentRemM.takeIf { it >= 0.0 },
                remAscClimbsM = remainingClimbsAscentM(routeClimbs, posOursM),
                etaLearnedS   = remainingSec,
                etaKarooS     = karooEtaSec.takeIf { it > 0.0 },
                etaPhysicsS   = shadowSec.takeIf { it >= 0L },
                pLowW         = physicsModel.powerLowW,
                pHighW        = physicsModel.powerHighW,
                // Share of the remaining distance served by rung 3 (the fallback)
                // rather than a learned bucket. If this is small, no fallback —
                // however good — can move the ETA much.
                modelRung     = String.format(Locale.US, "%.2f", fallbackShare),
                mtaMarginS    = mtaMarginS,
            )
        )
    }

    private fun logEtaSnapshot(
        remainingSec: Long, speedMs: Double, gradePct: Double, gpsStale: Boolean,
        remainDistM: Double, deltaSec: Long?, karooSec: Double, marginSec: Long?,
        memoryShare: Double = 0.0,
        uncertaintySec: Long = -1L,
        shadowSec: Long = -1L,
        physicsShare: Double = 0.0,
    ) {
        if (remainingSec <= 0L) return
        val h     = remainingSec / 3600
        val m     = (remainingSec % 3600) / 60
        val gps   = if (gpsStale) " [GPS stale]" else ""
        val delta = deltaSec?.let { " delta=%+ds".format(it) } ?: ""
        val karoo = if (karooSec > 0) " karoo=${karooSec.roundToInt()}s" else ""
        // Shadow fisico: secondi e quota di route che ha risposto lui
        val phys  = if (shadowSec > 0 && physicsShare > 0.0) " phys=${shadowSec}s(%d%%)".format((physicsShare * 100).toInt()) else ""
        val fact  = " f=%.2f".format(speedModel.currentDayFactor) +
            (if (memoryShare > 0.0) " mem=%d%%".format((memoryShare * 100).toInt()) else "") +
            (if (uncertaintySec >= 0) " ±%dm".format((uncertaintySec + 30) / 60) else "")
        val mta   = if (mtaEpochMs > 0L) " mta=%s margin=%s".format(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(mtaEpochMs)),
            marginSec?.let { "%+ds".format(it) } ?: "-") else ""
        logger.log("ETA  ", "${h}h ${m}m (${remainingSec}s) | %.1fkm | ".format(remainDistM / 1000.0) +
            "speed=%.1fkm/h grade=%.1f%%%s%s%s%s%s%s".format(speedMs * 3.6, gradePct, fact, delta, karoo, phys, mta, gps))
    }

    /** Lunghezza approssimata (m) di una polyline, per riconoscere gli stub di rientro. */
    private fun routeStubM(polyline: String): Double {
        val pts = PolylineDecoder.decodeLatLng(polyline)
        var d = 0.0
        for (i in 1 until pts.size) d += haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        return d
    }

    /** Posizione lungo la route secondo il Karoo (routeDistance − DISTANCE_TO_DESTINATION), se pubblicata e dentro la route. */
    private fun karooAlongRouteM(): Double? {
        if (routeTotalDistM <= 0.0 || karooDistToDestM < 0.0) return null
        val pos = routeTotalDistM - karooDistToDestM
        return when {
            // Poco prima dello start (il Karoo conta anche l'avvicinamento): vale lo start
            pos < 0.0 && pos > -BEFORE_START_TOLERANCE_M -> 0.0
            pos >= 0.0 && pos <= routeTotalDistM         -> pos
            else                                          -> null
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLat = kotlin.math.sin(dLat / 2)
        val sinLon = kotlin.math.sin(dLon / 2)
        val a = sinLat * sinLat +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * sinLon * sinLon
        return 2.0 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a))
    }

    // ── confronto ETA di fine giro ───────────────────────────────────────

    /**
     * Rete di sicurezza per il confronto quando la pipeline non ha esaurito i
     * segmenti: navigazione chiusa o giro chiuso (a) a ridosso dell'arrivo
     * (giro 30/08) oppure (b) fuori route ma negli ultimi km della route e in
     * aria vicino al punto finale (giro 12/09: parcheggio a 900 m dalla route,
     * posizione congelata al km 20,1 di 21,2, residuo Rejoin 427 s → perso).
     * Il tetto lungo-route evita che un anello (start = fine) sia "arrivato"
     * già alla partenza.
     */
    private fun maybeRecordAtEnd(context: String) {
        if (resultRecorded || initialRemainingSec < 0) return
        val remaining = etaState.value.remainingSeconds
        val moved     = movingElapsedSec
        extensionScope.launch {
            val why = when {
                remaining in 0..ARRIVAL_NET_MAX_REMAINING_SEC -> "a ${remaining}s dall'arrivo"
                nearRouteEnd() -> "fuori route a ridosso della fine (residuo stimato ${remaining}s)"
                else -> null
            }
            if (why == null || resultRecorded) return@launch
            resultRecorded = true
            logger.log("STATS", "$context $why — confronto valutato ora")
            recordEtaComparison(moved)
        }
    }

    /** Ultimi [ARRIVAL_NET_MAX_ALONG_M] lungo la route (posizione nostra o Karoo) e ultimo fix a meno di [ARRIVAL_NET_MAX_AIR_M] dal punto finale. */
    private suspend fun nearRouteEnd(): Boolean {
        val total = routeTotalDistM
        val end   = lastRouteEnd ?: return false
        val fix   = locationFlow.value ?: return false
        if (total <= 0.0 || !pipeline.hasRoute()) return false
        val along = maxOf(pipeline.distanceAlongRouteM(), karooAlongRouteM() ?: 0.0)
        val airM  = haversineM(fix.first, fix.second, end.first, end.second)
        logger.log("STATS", "Fine route? lungo-route %.0f/%.0fm, in aria %.0fm dalla fine".format(along, total, airM))
        return total - along <= ARRIVAL_NET_MAX_ALONG_M && airM <= ARRIVAL_NET_MAX_AIR_M
    }

    /**
     * Giudica ogni campione contro il tempo in movimento realmente servito da
     * quel punto all'arrivo e salva nello storico (max [RESULTS_MAX] uscite)
     * di quanto BetterEta è stata migliore/peggiore dell'ETA nativa, in punti
     * percentuali di errore medio. Formato entry: vedi [EtaResult].
     *
     * The shadow ETA (physics fallback) is judged on the same samples as a
     * third series. It is never displayed in-ride; this is where it gets its
     * verdict. The promotion criterion is fixed in advance: at least 5 rides,
     * mean error at least 2 pp lower than the factors, never more than 3 pp
     * worse on any single ride.
     */
    private fun recordEtaComparison(totalMovingSec: Long) {
        data class Judged(val actual: Long, val be: Long, val karoo: Double, val physics: Long)
        val samples = synchronized(etaSamples) { etaSamples.toList() }
        val valid = samples
            .map { s -> Judged(totalMovingSec - s.movingAtSec, s.beRemainingSec, s.karooRemainingSec, s.physicsRemainingSec) }
            .filter { it.actual >= MIN_ACTUAL_REMAINING_SEC }
        if (valid.size < MIN_SAMPLES) {
            logger.log("STATS", "Confronto ETA non registrato: ${valid.size} campioni validi su ${samples.size} (min $MIN_SAMPLES)")
            return
        }

        // Dump dei campioni: rende ogni statistica finale verificabile a posteriori
        // (il bug ms/s del 29/08 era diagnosticabile solo per deduzione)
        logger.log("STATS", "Campioni validi ${valid.size}/${samples.size} (act=reale, be/phys/karoo=stime, in s):")
        valid.forEach { j ->
            logger.log("STATS", "    act=${j.actual} be=${j.be} (%+d) phys=${j.physics} (%+d) karoo=${j.karoo.roundToInt()} (%+d)"
                .format(j.be - j.actual, j.physics - j.actual, j.karoo.roundToInt() - j.actual))
        }

        val errBe = valid.sumOf { kotlin.math.abs(it.be - it.actual).toDouble() / it.actual } / valid.size
        val errK  = valid.sumOf { kotlin.math.abs(it.karoo - it.actual) / it.actual } / valid.size
        // A shadow sample without an answer (-1) cannot be blamed on the fallback.
        val physValid = valid.filter { it.physics >= 0L }
        val errPhys = if (physValid.size >= MIN_SAMPLES)
            physValid.sumOf { kotlin.math.abs(it.physics - it.actual).toDouble() / it.actual } / physValid.size
            else null

        val result = EtaResult(
            date          = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
            diffPct       = ((errK - errBe) * 100).roundToInt(),
            errBePct      = (errBe * 100).roundToInt(),
            errKarooPct   = (errK * 100).roundToInt(),
            errPhysicsPct = errPhys?.let { (it * 100).roundToInt() },
        )
        val prefs   = getSharedPreferences(RESULTS_PREFS, Context.MODE_PRIVATE)
        val history = EtaResult.parseHistory(prefs.getString(RESULTS_KEY, "").orEmpty())
        val updated = (listOf(result) + history).take(RESULTS_MAX)
        prefs.edit().putString(RESULTS_KEY, EtaResult.encodeHistory(updated)).commit()

        logger.log("STATS", "ETA vs Karoo: diff=${result.diffPct}% (BE ${result.errBePct}%, Karoo ${result.errKarooPct}%, " +
            "physics-shadow ${result.errPhysicsPct?.let { "$it%" } ?: "n/a"}) su ${valid.size} campioni")
    }

    // ── storico FIT ──────────────────────────────────────────────────────

    private fun importedListFile() = java.io.File(filesDir, "imported_fits.txt")

    /** Nomi dei FIT già importati (uno per riga). */
    fun importedFits(): Set<String> =
        runCatching { importedListFile().readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())

    /** FIT del Karoo dentro la finestra di mesi scelta (0 = tutti), dal più vecchio. */
    fun fitFiles(): List<java.io.File> {
        val months = prefs.historyMonths
        val since = if (months <= 0) 0L else System.currentTimeMillis() - months * 30L * 86_400_000L
        return java.io.File(android.os.Environment.getExternalStorageDirectory(), "FitFiles")
            .listFiles { f -> f.isFile && f.name.endsWith(".fit") && f.lastModified() >= since }
            ?.sortedBy { it.lastModified() }.orEmpty()
    }

    /** Lanciato dalla schermata dell'app: import in background dei FIT mai importati. */
    fun requestHistoryImport() {
        extensionScope.launch { importRideHistory(auto = false) }
    }

    /**
     * Ricostruzione da zero: azzera bucket, memoria tratti ed elenco dei FIT
     * esaminati, poi importa la finestra scelta. Serve quando cambiano i filtri
     * (mesi, 10 km) o dopo dati sporchi: la base torna a essere solo storia vera.
     */
    fun requestHistoryRebuild() {
        extensionScope.launch {
            if (rideState is RideState.Recording) { logger.log("HIST ", "Ricostruzione rimandata: registrazione in corso"); return@launch }
            logger.log("HIST ", "Ricostruzione dal storico: modello, memoria tratti ed elenco FIT azzerati")
            speedModel.reset()
            wayMemory.clear()
            closeWayPass()
            runCatching { importedListFile().delete() }
            saveModel()
            importRideHistory(auto = false)
        }
    }

    /**
     * Ogni FIT non ancora importato viene rigiocato nel modello come se
     * l'estensione fosse stata in sella quel giorno (RideHistoryImporter):
     * bucket pendenza e fondo, fisica in ombra, memoria per tratto. Una riga
     * HIST per giro; il modello viene salvato alla fine. Non parte mentre si
     * registra: il modello in memoria è quello del giro in corso.
     */
    private suspend fun importRideHistory(auto: Boolean) {
        if (historyImportRunning) return
        if (rideState is RideState.Recording) { logger.log("HIST ", "Import storico rimandato: registrazione in corso"); return }
        val storage = applicationContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            applicationContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!storage) { if (!auto) logger.log("HIST ", "Import storico: permesso storage assente"); return }
        val done = importedFits()
        val todo = fitFiles().filter { it.name !in done }
        if (todo.isEmpty()) { if (!auto) logger.log("HIST ", "Import storico: nessun FIT nuovo (${done.size} già importati)"); return }
        historyImportRunning = true
        val tStart = System.currentTimeMillis()
        surfaceClassifier.autoSave = false
        try {
            logger.log("HIST ", "Import storico: ${todo.size} FIT da esaminare (${done.size} già importati, finestra ${prefs.historyMonths.let { if (it <= 0) "tutta" else "$it mesi" }}, minimo ${HISTORY_MIN_KM.toInt()} km)")
            val importer = RideHistoryImporter(speedModel, physicsModel, wayMemory) { pts ->
                surfaceClassifier.classify(pts)?.let { it.surfaces to it.wayKeys }
            }
            var rides = 0; var accepted = 0; var km = 0.0
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val liveRideStarts = prefs.rideStartsMs
            for (f in todo) {
                if (rideState is RideState.Recording) { logger.log("HIST ", "Import storico interrotto: registrazione avviata"); break }
                val t0 = System.currentTimeMillis()
                val records = runCatching { FitRecordReader.read(f.readBytes()) }
                    .onFailure { logger.logError("HIST ", "FIT ${f.name} non leggibile", it) }
                    .getOrNull()
                val fitStartMs = records?.firstNotNullOfOrNull { it.timeMs }
                if (RideHistoryImporter.learnedLive(fitStartMs, liveRideStarts)) {
                    // Giri 06/09, 12/09 e 13/09: appresi in sella e poi di nuovo dal FIT
                    // all'avvio successivo (campioni doppi, passaggi way a fattore 1)
                    markImported(f.name)
                    logger.log("HIST ", "${fmt.format(Date(fitStartMs!!))} ${f.name.take(8)}: saltato, giro già appreso dal vivo")
                    continue
                }
                val summary = records?.let { recs ->
                    runCatching { importer.importRecords(recs) }
                        .onFailure { logger.logError("HIST ", "FIT ${f.name} non importabile", it) }
                        .getOrNull()
                }
                markImported(f.name)
                if (summary == null || summary.accepted < RideHistoryImporter.MIN_ACCEPTED_SAMPLES || summary.distanceM < HISTORY_MIN_KM * 1000) {
                    logger.log("HIST ", "${f.name.take(8)}: scartato (${summary?.records ?: 0} record, ${summary?.accepted ?: 0} accettati, %.1f km)".format((summary?.distanceM ?: 0.0) / 1000))
                    continue
                }
                rides++; accepted += summary.accepted; km += summary.distanceM / 1000
                val classes = Surface.entries.joinToString(", ") { s -> "%.1f %s".format((summary.kmByClass[s] ?: 0.0) / 1000, s.name.lowercase()) }
                logger.log("HIST ", "${fmt.format(Date(summary.startMs))} ${f.name.take(8)}: %.1f km, ${summary.records} record, ${summary.accepted} accettati, ${summary.passes} passaggi way, %d ms | $classes".format(
                    summary.distanceM / 1000, System.currentTimeMillis() - t0))
            }
            surfaceClassifier.autoSave = true
            surfaceClassifier.saveNow()
            saveModel()
            val totalS = (System.currentTimeMillis() - tStart) / 1000.0
            logger.log("HIST ", "Import storico finito: $rides giri, %.0f km, $accepted campioni in %.0f s (%.1f s/giro)".format(km, totalS, if (rides > 0) totalS / rides else 0.0))
        } finally {
            surfaceClassifier.autoSave = true
            historyImportRunning = false
        }
    }

    private fun markImported(name: String) {
        runCatching { importedListFile().appendText(name + "\n") }
            .onFailure { logger.logError("HIST ", "Elenco FIT importati non aggiornabile", it) }
    }

    // ── apprendimento nei giri liberi ────────────────────────────────────

    /**
     * Senza route velocità e pendenza arrivano lo stesso dal Karoo: i bucket
     * pendenza (fondo ignoto) e il fattore di giornata imparano, purché il GPS
     * dica che ci si sta davvero spostando (vedi GpsProgressGate: né il fix che
     * balla da fermo né un salto del fix passano).
     */
    private fun freeRideLearn() {
        val speed = speedMsFlow.value
        val grade = gradePctFlow.value
        val now   = System.currentTimeMillis()
        val fresh = lastGpsUpdateMs > 0 && now - lastGpsUpdateMs <= GPS_TIMEOUT_MS
        val loc   = locationFlow.value
        val progressing = fresh && loc != null && gpsGate.onTick(loc.first, loc.second, speed)
        if (!progressing || speed < 0.5) return
        speedModel.update(speed, grade, Surface.UNKNOWN)
        physicsModel.update(speed, grade, Surface.UNKNOWN)
        freeRideSamples++
        if (freeRideSamples == 1) {
            logger.log("MODEL", "Apprendimento libero (senza route) avviato: guardia GPS superata")
        } else if (freeRideSamples % 300 == 0) {
            logger.log("MODEL", "Apprendimento libero: $freeRideSamples campioni, f=%.2f".format(speedModel.currentDayFactor))
        }
    }

    // ── memoria dei tempi per tratto ─────────────────────────────────────

    /**
     * Un passaggio = tratto continuo sulla stessa (way, verso). Alla chiusura,
     * se abbastanza lungo, la velocità media divisa per il fattore di giornata
     * finisce nella memoria: è la velocità "di base" su quel tratto.
     */
    private fun trackWayPass(key: Long, moving: Boolean, speedMs: Double) {
        if (key != passKey) {
            closeWayPass()
            passKey = key
        }
        if (moving && key != 0L) {
            passDistM += speedMs * (TICK_MS / 1000.0)
            passSec++
        }
    }

    private fun closeWayPass() {
        val key = passKey
        if (key != 0L && passDistM >= WAY_PASS_MIN_M && passSec >= WAY_PASS_MIN_SEC) {
            val speed  = passDistM / passSec
            val factor = speedModel.currentDayFactor.coerceIn(0.3, 3.0)
            val base   = speed / factor
            val merged = wayMemory.record(key, base, System.currentTimeMillis(), passDistM)
            val est = wayMemory.estimate(key)
            logger.log("WAY  ", "way %d %s: %.1fkm/h su %.0fm in %ds → base %.1fkm/h (f=%.2f), passaggi %d%s, media %.1fkm/h".format(
                WaySpeedMemory.wayIdOf(key), if (WaySpeedMemory.isForward(key)) "→" else "←",
                speed * 3.6, passDistM, passSec, base * 3.6, factor, est?.passes ?: 0,
                if (merged) " (fuso col precedente)" else "", (est?.speedMs ?: 0.0) * 3.6))
        }
        passKey   = 0L
        passDistM = 0.0
        passSec   = 0
    }

    private fun wayMemoryFile() = java.io.File(filesDir, "way_speeds.txt")

    private fun loadWayMemory() {
        runCatching {
            val f = wayMemoryFile()
            if (f.exists()) {
                wayMemory.loadFrom(f.readText())
                logger.log("WAY  ", "Memoria tratti caricata: ${wayMemory.size} (way, verso)")
            }
        }.onFailure { logger.logError("WAY  ", "Caricamento memoria tratti fallito", it) }
    }

    private fun saveWayMemory() {
        runCatching { wayMemoryFile().writeText(wayMemory.serialize()) }
            .onFailure { logger.logError("WAY  ", "Salvataggio memoria tratti fallito", it) }
    }

    // ── persistenza modello ──────────────────────────────────────────────

    private fun saveModel() {
        if (liveRideStartMs > 0L && liveRideStartMs !in this.prefs.rideStartsMs) {
            this.prefs.rideStartsMs = this.prefs.rideStartsMs + liveRideStartMs
        }
        saveWayMemory()
        val snap  = speedModel.snapshot()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("overall_ema",   snap.overallEma.toFloat())
            putInt("overall_samples", snap.overallSamples)
            snap.bucketEmas.forEachIndexed    { i, v -> putFloat("b${i}_ema", v.toFloat()) }
            snap.bucketCounts.forEachIndexed  { i, c -> putInt("b${i}_cnt", c) }
            snap.pavedEmas.forEachIndexed     { i, v -> putFloat("p${i}_ema", v.toFloat()) }
            snap.pavedCounts.forEachIndexed   { i, c -> putInt("p${i}_cnt", c) }
            snap.unpavedEmas.forEachIndexed   { i, v -> putFloat("u${i}_ema", v.toFloat()) }
            snap.unpavedCounts.forEachIndexed { i, c -> putInt("u${i}_cnt", c) }
            snap.trailEmas.forEachIndexed     { i, v -> putFloat("t${i}_ema", v.toFloat()) }
            snap.trailCounts.forEachIndexed   { i, c -> putInt("t${i}_cnt", c) }
            snap.gnarlyEmas.forEachIndexed    { i, v -> putFloat("g${i}_ema", v.toFloat()) }
            snap.gnarlyCounts.forEachIndexed  { i, c -> putInt("g${i}_cnt", c) }
            putInt("bucket_layout", AdaptiveSpeedModel.BUCKETS)   // assente = 5 bucket (fino al 05/09)
            putFloat("day_f", snap.dayFactor.toFloat())
            putInt("day_n", snap.dayCount)
            snap.bucketFactors.forEachIndexed      { i, v -> putFloat("f${i}_ema", v.toFloat()) }
            snap.bucketFactorCounts.forEachIndexed { i, c -> putInt("f${i}_cnt", c) }
            // Physics model (shadow). Persisted for the same reason as the
            // learned buckets: a shadow that restarted cold every ride would be
            // judged on a handicap and the comparison would be biased.
            val ps = physicsModel.snapshot()
            putFloat("phys_low_ema",  ps.lowEma.toFloat())
            putInt("phys_low_cnt",    ps.lowCount)
            putFloat("phys_high_ema", ps.highEma.toFloat())
            putInt("phys_high_cnt",   ps.highCount)
            putFloat("phys_mass",     ps.totalMassKg.toFloat())
        }.commit()  // sincrono: garantisce scrittura su disco prima che il processo venga ucciso

        // Nel log le velocità sono in km/h (i m/s restano dentro il modello)
        val bucketStr = snap.bucketEmas.mapIndexed { i, v ->
            "b$i=%.1f(n=${snap.bucketCounts[i]})".format(v * 3.6)
        }.joinToString(" ")
        fun surf(name: String, emas: List<Double>, counts: List<Int>) =
            "$name[" + emas.mapIndexed { i, v -> "%.1f(n=${counts.getOrElse(i){0}})".format(v * 3.6) }.joinToString(",") + "]"
        val surfStr = surf("pav", snap.pavedEmas, snap.pavedCounts) + " " +
            surf("gravel", snap.unpavedEmas, snap.unpavedCounts) + " " +
            surf("trail", snap.trailEmas, snap.trailCounts) + " " +
            surf("gnarly", snap.gnarlyEmas, snap.gnarlyCounts)
        val factStr = "f=%.2f [".format(snap.dayFactor) + speedModel.effectiveFactors().joinToString(",") { "%.2f".format(it) } + "]"
        logger.log("MODEL", "Salvato (km/h): overall=%.1f n=%d | %s | %s | $factStr"
            .format(snap.overallEma * 3.6, snap.overallSamples, bucketStr, surfStr))
        logger.log("MODEL", "Physics (shadow): pLow=%s W (n=%d) pHigh=%s W (n=%d) massa=%.1fkg".format(
            physicsModel.powerLowW?.let { "%.0f".format(it) } ?: "-", physicsModel.lowSamples,
            physicsModel.powerHighW?.let { "%.0f".format(it) } ?: "-", physicsModel.highSamples,
            physicsModel.massKg))
    }

    private fun loadModel() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains("phys_mass")) {
            physicsModel.restore(PhysicsSpeedModel.Snapshot(
                lowEma      = prefs.getFloat("phys_low_ema", 0f).toDouble(),
                lowCount    = prefs.getInt("phys_low_cnt", 0),
                highEma     = prefs.getFloat("phys_high_ema", 0f).toDouble(),
                highCount   = prefs.getInt("phys_high_cnt", 0),
                totalMassKg = prefs.getFloat("phys_mass", 0f).toDouble(),
            ))
        }
        if (prefs.getInt("overall_samples", 0) == 0) {
            logger.log("MODEL", "Nessun modello salvato — si parte da zero")
            return
        }
        // Layout salvato: 5 bucket fino al 05/09, 6 fino al 19/09, 7 dopo. Le liste
        // più corte vengono migrate da AdaptiveSpeedModel.restore.
        val savedBuckets = prefs.getInt("bucket_layout", 5)
        val idx = 0 until savedBuckets
        val snap = AdaptiveSpeedModel.Snapshot(
            overallEma     = prefs.getFloat("overall_ema", 0f).toDouble(),
            overallSamples = prefs.getInt("overall_samples", 0),
            bucketEmas     = idx.map { prefs.getFloat("b${it}_ema", 0f).toDouble() },
            bucketCounts   = idx.map { prefs.getInt("b${it}_cnt", 0) },
            pavedEmas      = idx.map { prefs.getFloat("p${it}_ema", 0f).toDouble() },
            pavedCounts    = idx.map { prefs.getInt("p${it}_cnt", 0) },
            unpavedEmas    = idx.map { prefs.getFloat("u${it}_ema", 0f).toDouble() },
            unpavedCounts  = idx.map { prefs.getInt("u${it}_cnt", 0) },
            trailEmas      = idx.map { prefs.getFloat("t${it}_ema", 0f).toDouble() },
            trailCounts    = idx.map { prefs.getInt("t${it}_cnt", 0) },
            gnarlyEmas     = idx.map { prefs.getFloat("g${it}_ema", 0f).toDouble() },
            gnarlyCounts   = idx.map { prefs.getInt("g${it}_cnt", 0) },
            dayFactor      = prefs.getFloat("day_f", 1f).toDouble(),
            dayCount       = prefs.getInt("day_n", 0),
            bucketFactors      = idx.map { prefs.getFloat("f${it}_ema", 1f).toDouble() },
            bucketFactorCounts = idx.map { prefs.getInt("f${it}_cnt", 0) },
        )
        speedModel.restore(snap)
        if (savedBuckets != AdaptiveSpeedModel.BUCKETS) {
            logger.log("MODEL", "Migrato da $savedBuckets a ${AdaptiveSpeedModel.BUCKETS} bucket di pendenza (i nuovi partono dal valore del bucket da cui nascono)")
            saveModel()
        }

        // Log dello stato effettivo (dopo l'eventuale migrazione), non delle prefs lette
        val restored  = speedModel.snapshot()
        val bucketStr = restored.bucketEmas.mapIndexed { i, v ->
            "b$i=%.1f(n=${restored.bucketCounts[i]})".format(v * 3.6)
        }.joinToString(" ")
        logger.log("MODEL", "Ripristinato (km/h): overall=%.1f n=%d | %s"
            .format(snap.overallEma * 3.6, snap.overallSamples, bucketStr))
    }

    // ── Mandatory time of arrival ────────────────────────────────────────

    /**
     * Mostra il popup MTA se c'è una route in navigazione, la registrazione è
     * attiva e per questa route non è ancora stato chiesto. Senza permesso
     * overlay non chiede nulla (l'MTA resta impostabile solo col popup).
     */
    private fun maybeAskMta() {
        if (rideState !is RideState.Recording || !navActive) return
        extensionScope.launch {
            if (!pipeline.hasRoute()) return@launch
            val routeHash = lastRoutePolyline.hashCode()
            if (routeHash == 0 || mtaAskedRouteHash == routeHash) return@launch
            mtaAskedRouteHash  = routeHash
            prefs.mtaRouteHash = routeHash
            prefs.mtaAskedAtMs = System.currentTimeMillis()
            if (!mtaPopup.canShow()) {
                logger.log("MTA  ", "Popup non mostrato: permesso overlay assente")
                return@launch
            }
            mtaAskPendingSinceMs = System.currentTimeMillis()
            val t0 = System.currentTimeMillis()
            val located = withTimeoutOrNull(MTA_LOCATED_WAIT_MS) {
                etaState.first { it.remainingSeconds > 0 && !offRoute && pipeline.isLocated() }.remainingSeconds
            }
            val anyEta = located
                ?: withTimeoutOrNull(MTA_ANY_ETA_WAIT_MS) { etaState.first { it.remainingSeconds > 0 }.remainingSeconds }
            // Senza una stima nostra (partenza fuori route, giro 12/09) l'ETA nativa
            // del Karoo è un default migliore di "adesso + 1 h"
            val karoo = if (anyEta == null) karooEtaSec.takeIf { it > 0.0 }?.toLong() else null
            val remaining = anyEta ?: karoo ?: etaState.value.remainingSeconds
            val waitedS = (System.currentTimeMillis() - t0) / 1000
            when {
                located != null  -> logger.log("MTA  ", "ETA con posizione agganciata dopo ${waitedS}s: ${remaining}s")
                anyEta != null   -> logger.log("MTA  ", "Nessun aggancio entro ${MTA_LOCATED_WAIT_MS / 1000}s — default sull'ETA disponibile (${remaining}s)")
                karoo != null    -> logger.log("MTA  ", "Nessun ETA nostro entro ${waitedS}s — default sull'ETA nativa Karoo (${karoo}s)")
                else             -> logger.log("MTA  ", "Nessun ETA entro ${waitedS}s — default adesso + 1 h")
            }
            // Con un MTA già impostato le rotelle partono da quello: allungare o
            // accorciare di un quarto d'ora è un tocco; DISMISS o timeout = invariato
            val (h, m) = if (mtaEpochMs > 0L) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = mtaEpochMs }
                logger.log("MTA  ", "Popup con MTA attuale preimpostato")
                cal.get(java.util.Calendar.HOUR_OF_DAY) to cal.get(java.util.Calendar.MINUTE)
            } else ArrivalMargin.defaultPickerTime(System.currentTimeMillis(), remaining)
            mtaPopup.show(h, m)
        }
    }

    private fun setMta(hour: Int, minute: Int) {
        mtaEpochMs = ArrivalMargin.epochMsFor(hour, minute, System.currentTimeMillis())
        prefs.mtaEpochMs = mtaEpochMs
        mtaSetAtMs = System.currentTimeMillis()
        marginGate.reset()
        logger.log("MTA  ", "Impostato %02d:%02d (%s)".format(hour, minute,
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(mtaEpochMs))))
    }

    /** Popup già mostrato oggi per la polyline [routeHash] (restart a metà giro): non richiedere, ripristina la risposta. */
    private fun restoreMta(routeHash: Int) {
        mtaAskedRouteHash = routeHash
        val saved = prefs.mtaEpochMs
        mtaEpochMs = saved
        if (saved > 0L) {
            marginGate.reset()
            logger.log("MTA  ", "Ripristinato: " +
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(saved)))
        }
    }

    private fun clearMta() {
        if (mtaEpochMs > 0L) logger.log("MTA  ", "Azzerato: nuovo giro")
        mtaEpochMs         = 0L
        mtaAskedRouteHash  = 0
        prefs.mtaEpochMs   = 0L
        prefs.mtaRouteHash = 0
        prefs.mtaAskedAtMs = 0L
        marginGate.reset()
    }

    /**
     * Margine attuale (null senza MTA o senza ETA) e avviso al primo passaggio
     * in negativo. Va chiamato anche in pausa: l'orologio corre, il margine cala.
     * Con una stima approssimata ("~": fuori route, Rejoin, non agganciati) il
     * margine si mostra ma fa scattare l'avviso solo se è sotto di oltre 10 min:
     * giro 17/09, "Margine perso" su una stima Rejoin sforata di 47 s, sei minuti
     * dopo il margine vero era +8 min; giro 19/09, un'ora fuori route, margine a
     * −23 min alle 17:20 e avviso solo alle 17:34 al primo ETA agganciato.
     */
    private fun marginFor(remainingSec: Long, approx: Boolean): Long? {
        val mta = mtaEpochMs
        if (mta <= 0L || remainingSec < 0) return null
        val now    = System.currentTimeMillis()
        val margin = ArrivalMargin.marginSeconds(mta, now, remainingSec)
        if (approx && margin > -MTA_APPROX_ALERT_SLACK_S) return margin
        // Popup MTA a schermo (riproposto dopo una sosta): l'avviso finirebbe sotto
        // l'overlay (giro 19/09, 17:34:21). La gate resta armata: scatta alla chiusura.
        if (now - mtaAskPendingSinceMs < MTA_ASK_PENDING_MAX_MS) return margin
        // Appena impostato: niente avviso (giro 12/09: "Margine perso" un secondo
        // dopo il SET). Se resta negativo, la gate lo dà a fine grazia.
        if (now - mtaSetAtMs < MTA_ALERT_GRACE_MS) return margin
        if (marginGate.onMargin(margin)) {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val eta = fmt.format(Date(now + remainingSec * 1000L))
            logger.log("MTA  ", "Margine perso: ETA $eta vs MTA ${fmt.format(Date(mta))}")
            karooSystem.dispatch(
                InRideAlert(
                    id              = "better-eta-mta-$mta",
                    icon            = R.drawable.ic_launcher_foreground,
                    title           = getString(R.string.mta_alert_title),
                    detail          = "ETA $eta, MTA ${fmt.format(Date(mta))}",
                    autoDismissMs   = 30_000L,   // 15 s: intravisto appena (17/09)
                    backgroundColor = R.color.alert_bg,
                    textColor       = R.color.alert_text,
                )
            )
        }
        return margin
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
