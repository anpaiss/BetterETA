package com.anpaiss.boschrange

import com.anpaiss.bettereta.MapsforgeSurfaceSource
import com.anpaiss.bettereta.PolylineDecoder
import com.anpaiss.bettereta.RouteWaypoint
import com.anpaiss.bettereta.SegmentPipeline
import com.anpaiss.bettereta.Surface
import com.anpaiss.bettereta.SurfaceClassifier
import com.anpaiss.bettereta.interpolateElevations
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
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
import kotlinx.coroutines.launch

/**
 * boschRange, step 2 (13/10/2026): collects the data the battery model will be
 * calibrated on. No data field yet.
 *
 *  - RideState of the SDK gives ride start (Idle → Recording), pauses and the
 *    end (→ Idle): no detector of our own, BetterETA does the same.
 *  - The route is decoded with eta-core, its surface classified ONCE at load
 *    (offline maps, Overpass as fallback); every 5 s the surface under the
 *    bike is read from the segment at the Karoo's own position along the
 *    route. No GPS snap of our own.
 *  - Each tick integrates the mechanical energy (EnergyModel) and the ride
 *    statistics into the open RideRecord, saved every minute.
 *  - Popups: start (100 % by default: the timeout confirms it, only SKIP or a
 *    different value change that, ride 18/10), after a stop of 5 min or more
 *    (prefilled with the estimate), end (arrival %). SKIP or timeout at the end
 *    leaves arrival null: the ride stays in the history but out of the regression.
 */
class BoschRangeExtension : KarooExtension("bosch-range", BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var logger: BoschRangeLogger
    private lateinit var prefs: Prefs
    private lateinit var history: RideHistory
    private lateinit var sampleLog: SampleLog
    private lateinit var popup: BatteryPopupController
    private lateinit var surfaceClassifier: SurfaceClassifier
    private lateinit var energy: EnergyModel
    private val pipeline = SegmentPipeline()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val consumerIds = mutableListOf<String>()
    private var tickerJob: Job? = null
    private var startPopupJob: Job? = null

    private val debugState = MutableStateFlow(DebugState())
    private val visibleDataTypeIds = MutableStateFlow<Set<String>?>(null)
    private val lock = Any()
    private var ride: RideRecord? = null              // guarded by lock
    @Volatile private var rideState: RideState = RideState.Idle
    @Volatile private var pausedSinceMs = 0L
    @Volatile private var overlayAlertShown = false

    // Karoo streams, last value
    @Volatile private var speedMs = 0.0
    @Volatile private var gradePct = 0.0
    @Volatile private var distanceM = -1.0
    @Volatile private var ascentM = -1.0
    @Volatile private var descentM = -1.0
    @Volatile private var temperatureC: Double? = null
    @Volatile private var karooDistToDestM = -1.0
    @Volatile private var karooAscentRemM = -1.0

    // Route
    @Volatile private var routeTotalDistM = -1.0
    @Volatile private var routeAscentM = -1.0
    @Volatile private var routeName = ""
    @Volatile private var hasRoute = false
    @Volatile private var routeUnclassifiedPct = -1.0
    @Volatile private var surfaceComplete: Boolean? = null
    @Volatile private var routeSurfaceKm: List<Double>? = null    // per Surface.ordinal, from the classified segments
    @Volatile private var historyCounts = Triple(0, 0, 0)          // rides, valid, comparable with Flow
    private var lastRoutePolyline = ""
    // Stops seen from the speed stream, independent of the Karoo auto-pause
    @Volatile private var stoppedSinceMs = 0L
    @Volatile private var lastStopPopupMs = 0L

    companion object {
        private const val TICK_MS              = 5_000L
        // Popups never at the same time as BetterETA's MTA popup (same overlay, same
        // place on the screen): the MTA comes 10-210 s after the start (it waits for a
        // located ETA) and stays 45 s, so the start popup comes at 4.5 min; after a
        // stop of 5+ min the MTA is re-proposed at once, so ours waits 60 s.
        private const val START_POPUP_DELAY_MS = 270_000L
        private const val STOP_POPUP_DELAY_MS  = 60_000L
        private const val LONG_STOP_MS         = 5 * 60_000L
        private const val STOP_POPUP_DEDUP_MS  = 2 * 60_000L
        private const val SAVE_EVERY_MS        = 60_000L
        private const val RESUME_OPEN_RIDE_MS  = 6 * 3_600_000L   // an open ride older than this is a leftover
        @Volatile var instance: BoschRangeExtension? = null
    }

    override val types: List<DataTypeImpl> by lazy { listOf(DebugFieldDataType(extension, debugState, visibleDataTypeIds)) }

    override fun onCreate() {
        super.onCreate()
        logger  = BoschRangeLogger(applicationContext)
        prefs   = Prefs(applicationContext)
        history = RideHistory(applicationContext)
        sampleLog = SampleLog(applicationContext)
        popup   = BatteryPopupController(applicationContext, logger)
        energy  = EnergyModel(prefs.riderKg)
        surfaceClassifier = SurfaceClassifier(applicationContext, logger, MapsforgeSurfaceSource(applicationContext, logger))
        instance = this

        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            logger.log("SYS  ", "Versione: ${pi.versionName} (${pi.longVersionCode}); capacità ${prefs.capacityWh} Wh, s=${prefs.sCoefficient}, " +
                "massa ${energy.totalMassKg} kg; storico ${history.historyFile.absolutePath}")
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            logger.logError("CRASH", "Eccezione non gestita su ${thread.name}", t)
            prevHandler?.uncaughtException(thread, t)
        }

        // A ride left open by a restart of the extension goes on; an old one is a leftover
        history.loadCurrent()?.let { r ->
            if (System.currentTimeMillis() - r.startMs < RESUME_OPEN_RIDE_MS) {
                synchronized(lock) { ride = r }
                logger.log("RIDE ", "Giro aperto ripreso dal file: iniziato ${r.startMs}, ${r.distanceKm} km, stima ${"%.1f".format(r.estimatedPct())} %")
            } else {
                logger.log("RIDE ", "Giro aperto vecchio (${(System.currentTimeMillis() - r.startMs) / 3_600_000} h) chiuso senza arrivo")
                r.endMs = r.startMs
                history.append(r)
            }
        }

        writeFlowReport()   // the JSON may have been filled in on the PC and pushed back since the last start
        // The debug field starts from DebugState() defaults (1000 Wh, empty history) and
        // nothing published it before the first ride/route event: after a restart it
        // showed "1.25 / 1000 Wh" and "history 0 / 0" for 750 Wh and one ride (18/10)
        publishDebug()

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { _ ->
            consumerIds += karooSystem.addConsumer(RideState.Params, { _ -> }, {}) { state: RideState -> onRideState(state) }
            consumerIds += karooSystem.addConsumer(ActiveRidePage.Params, { _ -> }, {}) { ev: ActiveRidePage ->
                visibleDataTypeIds.value = ev.page.elements.map { it.dataTypeId }.toSet()
            }
            consumerIds += karooSystem.addConsumer(OnNavigationState.Params, { _ -> }, {}) { ev: OnNavigationState -> onNavigation(ev) }
            consumerIds += karooSystem.addConsumer(UserProfile.Params, { _ -> }, {}) { p: UserProfile ->
                val kg = p.weight.toDouble()
                if (kg > 20.0 && kg != prefs.riderKg) { prefs.riderKg = kg; energy.riderKg = kg
                    logger.log("MODEL", "UserProfile: peso $kg kg → massa totale ${energy.totalMassKg} kg") }
            }
            stream(DataType.Type.SPEED, DataType.Field.SPEED) { speedMs = it }
            stream(DataType.Type.ELEVATION_GRADE, DataType.Field.ELEVATION_GRADE) { gradePct = it }
            stream(DataType.Type.DISTANCE, DataType.Field.DISTANCE) { distanceM = it }
            stream(DataType.Type.ELEVATION_GAIN, DataType.Field.ELEVATION_GAIN) { ascentM = it }
            stream(DataType.Type.ELEVATION_LOSS, DataType.Field.ELEVATION_LOSS) { descentM = it }
            stream(DataType.Type.TEMPERATURE, DataType.Field.TEMPERATURE) { temperatureC = it }
            stream(DataType.Type.DISTANCE_TO_DESTINATION, DataType.Field.DISTANCE_TO_DESTINATION) { karooDistToDestM = it }
            stream(DataType.Type.ELEVATION_REMAINING, DataType.Field.ASCENT_REMAINING) { karooAscentRemM = it }

            tickerJob = scope.launch { ticker() }
            logger.log("SYS  ", "Consumer registrati, ticker ${TICK_MS / 1000} s")
        }
    }

    private fun stream(type: String, field: String, onValue: (Double) -> Unit) {
        consumerIds += karooSystem.addConsumer(OnStreamState.StartStreaming(type), { _ -> }, {}) { ev: OnStreamState ->
            (ev.state as? StreamState.Streaming)?.dataPoint?.values?.get(field)?.let(onValue)
        }
    }

    // ── Ride state ────────────────────────────────────────────────────────

    private fun onRideState(state: RideState) {
        val prev = rideState
        rideState = state
        if (prev::class == state::class) return
        logger.log("RIDE ", "${prev::class.simpleName} → ${state::class.simpleName}")
        if (!prefs.enabled && synchronized(lock) { ride } == null) {
            logger.log("RIDE ", "boschRange OFF nelle impostazioni: nessun giro aperto, nessun popup")
            publishDebug()
            return
        }
        when {
            prev !is RideState.Recording && state is RideState.Recording ->
                if (prev is RideState.Idle) onRideStart() else onRideResume()
            prev is RideState.Recording && state !is RideState.Recording -> {
                pausedSinceMs = System.currentTimeMillis()
                saveCurrent()
                if (state is RideState.Idle) onRideEnd()
            }
            prev is RideState.Paused && state is RideState.Idle -> onRideEnd()
        }
    }

    private fun onRideStart() {
        val now = System.currentTimeMillis()
        val r = synchronized(lock) {
            ride?.let { open ->
                // The extension restarted during this ride: go on with the open record
                logger.log("RIDE ", "Recording con giro già aperto: continuo (${open.distanceKm} km)")
                return
            }
            RideRecord(now, prefs.capacityWh, prefs.sCoefficient).also { ride = it }
        }
        r.routeName = routeName; r.routeDistanceM = routeTotalDistM; r.routeAscentM = routeAscentM
        r.karooAscentRemainingAtStartM = karooAscentRemM
        r.routeUnclassifiedPct = routeUnclassifiedPct; r.surfaceComplete = surfaceComplete
        r.setStart(100, assumed = true)   // stays "assumed" until SET or timeout on the popup
        sampleLog.ride(r)
        saveCurrent()
        publishDebug()
        logger.log("RIDE ", "Nuovo giro: capacità ${r.capacityWh} Wh, s=${r.sUsed}, route '${routeName}' D+ ${routeAscentM.toInt()} m")
        startPopupJob?.cancel()
        startPopupJob = scope.launch {
            delay(START_POPUP_DELAY_MS)
            if (rideState !is RideState.Recording) return@launch
            askBattery(getString(R.string.popup_title_start), 100, withCondition = true,
                reminder = getString(R.string.reminder_flow_start),
                onReminderDone = { flowShot(r) { it.flowShotStartMs = System.currentTimeMillis(); it.flowShotStartKm = it.distanceKm } },
                onTimeout = {
                    logger.log("RIDE ", "Partenza 100 % confermata per timeout")
                    synchronized(lock) { ride?.setStart(100, assumed = false) }
                    saveCurrent()
                    publishDebug()
                }) { pct, cond ->
                synchronized(lock) { ride?.let { it.setStart(pct, assumed = false); if (cond != null) it.condition = cond } }
                saveCurrent()
                publishDebug()
            }
        }
    }

    private fun onRideResume() {
        val pauseMs = if (pausedSinceMs > 0L) System.currentTimeMillis() - pausedSinceMs else 0L
        pausedSinceMs = 0L
        stoppedSinceMs = 0L
        if (pauseMs >= LONG_STOP_MS) askAfterStop("pausa Karoo di ${pauseMs / 60_000} min")
    }

    /**
     * "Battery now?" after a long stop, whether the Karoo paused (auto-pause or
     * button) or only the speed stayed at zero (auto-pause off): the ticker
     * calls this too. One popup per stop.
     */
    private fun askAfterStop(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastStopPopupMs < STOP_POPUP_DEDUP_MS) return
        val r = synchronized(lock) { ride } ?: return
        lastStopPopupMs = now
        logger.log("RIDE ", "Ripresa ($reason): chiedo la % attuale fra ${STOP_POPUP_DELAY_MS / 1000} s")
        scope.launch {
            delay(STOP_POPUP_DELAY_MS)
            if (rideState !is RideState.Recording || synchronized(lock) { ride } !== r) return@launch
            askBattery(getString(R.string.popup_title_now), Math.round(r.estimatedPct()).toInt().coerceIn(0, 100), withCondition = false,
            reminder = getString(R.string.reminder_flow_mid),
            onReminderDone = { flowShot(r) { it.flowShotMidMs = System.currentTimeMillis(); it.flowShotMidKm = it.distanceKm } }) { pct, _ ->
                manualUpdate(pct)
            }
        }
    }

    private fun onRideEnd() {
        startPopupJob?.cancel()
        val r = synchronized(lock) { ride } ?: return
        r.endMs = System.currentTimeMillis()
        captureStreams(r)
        saveCurrent()
        val est = Math.round(r.estimatedPct()).toInt().coerceIn(0, 100)
        logger.log("RIDE ", "Fine giro: ${"%.1f".format(r.distanceKm)} km, D+ Karoo ${r.ascentM.toInt()} m (integrato ${r.ascentIntegratedM.toInt()}), " +
            "Wh meccanici ${"%.0f".format(r.whMech)} → stimati ${"%.0f".format(r.whEstimated)}, stima arrivo $est %")
        val missing = listOfNotNull(
            if (r.flowShotStartMs == 0L) getString(R.string.reminder_flow_start) else null,
            if (r.flowShotMidMs == 0L) getString(R.string.reminder_flow_mid) else null)
        val reminder = getString(R.string.reminder_flow_end) +
            if (missing.isEmpty()) "" else "\n" + getString(R.string.reminder_missing, missing.joinToString("; "))
        askBattery(getString(R.string.popup_title_end), est, withCondition = false,
            reminder = reminder,
            onReminderDone = { flowShot(r) { it.flowShotEndMs = System.currentTimeMillis() } },
            onSkip = { finish(r, null) }) { pct, _ -> finish(r, pct) }
    }

    /** FATTO on a popup: marks when/where a Flow screenshot was taken. */
    private fun flowShot(r: RideRecord, mark: (RideRecord) -> Unit) {
        mark(r)
        logger.log("FLOW ", "Screenshot Flow segnato a ${"%.1f".format(r.distanceKm)} km")
        if (synchronized(lock) { ride === r }) saveCurrent()
        publishDebug()
    }

    private fun finish(r: RideRecord, arrivalPct: Int?) {
        synchronized(lock) { if (ride !== r) return; ride = null }
        r.arrivalPct = arrivalPct
        history.append(r)
        publishDebug()
        val real = r.whReal
        logger.log("RIDE ", "Giro salvato nello storico: partenza ${r.startPct} %${if (r.startAssumed) " (NON confermata)" else ""}, " +
            "arrivo ${arrivalPct?.toString() ?: "non inserito"}, ${if (r.validForRegression) "valido" else "ESCLUSO"} per la regressione" +
            (real?.let { ", Wh reali ${"%.0f".format(it)} vs stimati ${"%.0f".format(r.whEstimated)}: s reale ${"%.2f".format(it / r.whMech.coerceAtLeast(1.0))}" } ?: ""))
        writeFlowReport()
    }

    /** files/flow_comparison_report.txt, rewritten whenever the history holds enough rides compared with Flow. */
    private fun writeFlowReport() {
        runCatching {
            val rides = history.load()
            historyCounts = Triple(rides.size, rides.count { it.validForRegression }, FlowComparison.comparable(rides).size)
            val text = FlowComparison.report(rides)
            val file = java.io.File(history.historyFile.parentFile, "flow_comparison_report.txt")
            if (text == null) {
                logger.log("FLOW ", "Confronto con Flow: ${FlowComparison.comparable(rides).size}/${FlowComparison.MIN_RIDES} giri confrontabili, report non ancora generato")
            } else {
                file.writeText(text)
                logger.log("FLOW ", "Report confronto con Flow scritto in ${file.absolutePath}:\n$text")
            }
        }.onFailure { logger.logError("FLOW ", "Report confronto con Flow fallito", it) }
    }

    /** Called from the popups and from the app (TEST / manual entry). */
    fun manualUpdate(pct: Int) {
        val r = synchronized(lock) { ride } ?: return
        val before = r.estimatedPct()
        r.anchor(pct, System.currentTimeMillis())
        saveCurrent()
        publishDebug()
        logger.log("RIDE ", "Aggiornamento manuale a ${"%.1f".format(r.distanceKm)} km: inserito $pct %, stima era ${"%.1f".format(before)} %")
    }

    private fun askBattery(title: String, initial: Int, withCondition: Boolean, reminder: String? = null, onReminderDone: () -> Unit = {},
                           onSkip: () -> Unit = {}, onTimeout: (() -> Unit)? = null, onSet: (Int, Condition?) -> Unit) {
        if (!popup.canShow()) {
            logger.log("POPUP", "Permesso overlay assente: '$title' non chiesto")
            if (!overlayAlertShown) {
                overlayAlertShown = true
                karooSystem.dispatch(InRideAlert("bosch-range-overlay", R.drawable.ic_launcher,
                    getString(R.string.alert_overlay_title), getString(R.string.alert_overlay_text), 8_000L,
                    R.color.alert_bg, R.color.alert_text))
            }
            onSkip(); return
        }
        val cond = if (withCondition) (synchronized(lock) { ride?.condition } ?: Condition.DRY) else null
        playPopupSound()
        popup.show(BatteryPopupController.Request(title, initial, cond, onSet, onSkip, onTimeout ?: onSkip, reminder, onReminderDone))
    }

    /**
     * boschRange's own sound: two falling pairs (1200→900, twice). BetterETA's MTA
     * popup is three rising notes (600/800/1000), PoiAhead two equal 800 Hz beeps.
     */
    fun playPopupSound() {
        runCatching {
            karooSystem.dispatch(PlayBeepPattern(listOf(
                PlayBeepPattern.Tone(1200, 120), PlayBeepPattern.Tone(null, 40),
                PlayBeepPattern.Tone(900, 160), PlayBeepPattern.Tone(null, 160),
                PlayBeepPattern.Tone(1200, 120), PlayBeepPattern.Tone(null, 40),
                PlayBeepPattern.Tone(900, 160),
            )))
        }.onFailure { logger.logError("POPUP", "Suono popup fallito", it) }
    }

    // ── Route ─────────────────────────────────────────────────────────────

    private fun onNavigation(ev: OnNavigationState) {
        val nav = ev.state
        val (poly, elev, name, dist) = when (nav) {
            is OnNavigationState.NavigationState.NavigatingRoute ->
                Quad(nav.routePolyline.orEmpty(), nav.routeElevationPolyline.orEmpty(), nav.name, nav.routeDistance)
            is OnNavigationState.NavigationState.NavigatingToDestination ->
                Quad(nav.polyline.orEmpty(), nav.elevationPolyline.orEmpty(), nav.destination.name ?: "destination", -1.0)
            else -> { if (hasRoute) logger.log("ROUTE", "Navigazione chiusa: route in memoria"); return }
        }
        if (poly.isBlank() || poly == lastRoutePolyline) return
        lastRoutePolyline = poly
        val coords  = PolylineDecoder.decodeLatLng(poly)
        val samples = PolylineDecoder.decodeDistanceElevation(elev)
        val elevs   = interpolateElevations(coords, samples)
        val wps     = coords.mapIndexed { i, c -> RouteWaypoint(c.first, c.second, elevs[i]) }
        val ascent  = samples.zipWithNext().sumOf { (a, b) -> (b.second - a.second).coerceAtLeast(0.0) }
        routeName = name; routeTotalDistM = dist; routeAscentM = ascent
        synchronized(lock) { ride?.let { if (it.routeAscentM < 0) { it.routeName = name; it.routeDistanceM = dist; it.routeAscentM = ascent } } }
        logger.log("ROUTE", "'$name': ${wps.size} waypoint, ${(dist / 1000).let { "%.1f".format(it) }} km, D+ profilo ${ascent.toInt()} m")
        scope.launch {
            pipeline.setRoute(wps)
            hasRoute = wps.size >= 2
            val r = surfaceClassifier.classify(wps)
            val unclassified: Double
            if (r != null) {
                pipeline.applySurfaces(r.surfaces, r.wayKeys)
                unclassified = r.surfaces.count { it == Surface.UNKNOWN } * 100.0 / r.surfaces.size.coerceAtLeast(1)
                surfaceComplete = r.complete
                logger.log("SURF ", "Fondo applicato ai segmenti: ${if (r.complete) "completo" else "PARZIALE (offline)"}, " +
                    "${"%.1f".format(unclassified)} % dei waypoint senza classe")
            } else {
                unclassified = 100.0
                surfaceComplete = null
                logger.log("SURF ", "Fondo NON classificabile (niente mappe, niente rete, niente cache): tutta la route UNKNOWN, Crr 0,012")
            }
            routeUnclassifiedPct = unclassified
            routeSurfaceKm = pipeline.remainingSegmentsFrom(0.0).let { segs ->
                val km = DoubleArray(Surface.entries.size)
                segs.forEach { km[it.surface.ordinal] += it.distanceM / 1000.0 }
                km.toList()
            }
            synchronized(lock) { ride?.let { it.routeUnclassifiedPct = unclassified; it.surfaceComplete = surfaceComplete } }
            publishDebug()
        }
    }

    private data class Quad(val poly: String, val elev: String, val name: String, val dist: Double)

    /** Surface of the segment under the bike, from the Karoo's position along the route. */
    private suspend fun currentSurface(): Surface {
        if (!hasRoute || routeTotalDistM <= 0.0 || karooDistToDestM < 0.0) return Surface.UNKNOWN
        val pos = (routeTotalDistM - karooDistToDestM).coerceIn(0.0, routeTotalDistM)
        pipeline.locateAt(pos)
        return pipeline.currentSurface()
    }

    // ── Ticker ────────────────────────────────────────────────────────────

    private suspend fun ticker() {
        var lastMs = System.currentTimeMillis()
        var lastSaveMs = lastMs
        while (true) {
            delay(TICK_MS)
            val now = System.currentTimeMillis()
            val dt = ((now - lastMs) / 1000.0).coerceIn(1.0, 15.0)
            lastMs = now
            if (rideState !is RideState.Recording) continue
            val r = synchronized(lock) { ride } ?: continue
            val v = speedMs; val g = gradePct
            // Long standstill seen from the speed, for riders with the auto-pause off
            if (v < RideRecord.MOVING_MS) {
                if (stoppedSinceMs == 0L) stoppedSinceMs = now
            } else if (stoppedSinceMs > 0L) {
                val stopMs = now - stoppedSinceMs
                stoppedSinceMs = 0L
                if (stopMs >= LONG_STOP_MS) askAfterStop("fermo ${stopMs / 60_000} min dal GPS")
            }
            val surface = currentSurface()
            synchronized(lock) {
                r.tick(v, g, surface, dt, energy, temperatureC)
                captureStreams(r)
            }
            sampleLog.record(now, v, g, surface, distanceM, karooDistToDestM, karooAscentRemM, temperatureC, r)
            publishDebug()
            if (now - lastSaveMs >= SAVE_EVERY_MS) { lastSaveMs = now; saveCurrent() }
        }
    }

    private fun captureStreams(r: RideRecord) {
        if (distanceM >= 0.0) r.distanceM = distanceM
        if (ascentM >= 0.0) r.ascentM = ascentM
        if (descentM >= 0.0) r.descentM = descentM
    }

    private fun saveCurrent() {
        val r = synchronized(lock) { ride } ?: return
        runCatching { history.saveCurrent(r) }.onFailure { logger.logError("RIDE ", "Salvataggio giro aperto fallito", it) }
    }

    /** The debug field reads this; cheap enough to call on every tick and event. */
    private fun publishDebug() {
        val r = synchronized(lock) { ride }
        val (nRides, nValid, nComparable) = historyCounts
        val base = DebugState(enabled = prefs.enabled, sUsed = prefs.sCoefficient, capacityWh = prefs.capacityWh,
            hasRoute = hasRoute, routeKm = routeTotalDistM / 1000.0, routeAscentM = routeAscentM,
            karooDistToDestKm = if (karooDistToDestM >= 0) karooDistToDestM / 1000.0 else -1.0, karooAscentRemM = karooAscentRemM,
            routeUnclassifiedPct = routeUnclassifiedPct, surfaceComplete = surfaceComplete, routeSurfaceKm = routeSurfaceKm,
            historyRides = nRides, historyValid = nValid, historyComparable = nComparable)
        debugState.value = if (r == null) base else base.copy(
            rideOpen = true, startPct = r.startPct, startAssumed = r.startAssumed, condition = r.condition,
            estimatedPct = r.estimatedPct(), whMech = r.whMech, whEstimated = r.whEstimated, sUsed = r.sUsed, capacityWh = r.capacityWh,
            manualUpdates = r.manualUpdates.size,
            lastUpdateKm = r.manualUpdates.lastOrNull()?.distanceKm ?: -1.0, lastUpdatePct = r.manualUpdates.lastOrNull()?.enteredPct ?: -1,
            distanceKm = r.distanceKm, ascentKarooM = r.ascentM, movingS = r.movingS, temperatureC = r.temperatureC,
            routeUnclassifiedPct = r.routeUnclassifiedPct, surfaceComplete = r.surfaceComplete,
            flowShotStart = r.flowShotStartMs != 0L, flowShotMid = r.flowShotMidMs != 0L, flowShotEnd = r.flowShotEndMs != 0L)
    }

    /** Snapshot for the app screen. */
    fun openRideSummary(): String? {
        val r = synchronized(lock) { ride } ?: return null
        return "Open ride: ${"%.1f".format(r.distanceKm)} km, D+ ${r.ascentIntegratedM.toInt()} m, " +
            "${"%.0f".format(r.whEstimated)} Wh estimated, now ${"%.0f".format(r.estimatedPct())} % (start ${r.startPct} %)"
    }

    override fun onDestroy() {
        startPopupJob?.cancel()
        tickerJob?.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        popup.hide()
        saveCurrent()
        scope.cancel()
        karooSystem.disconnect()
        instance = null
        logger.log("SYS  ", "Estensione fermata")
        super.onDestroy()
    }
}
