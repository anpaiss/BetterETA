package com.anpaiss.boschrange

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
import com.anpaiss.bettereta.Surface
import com.anpaiss.bettereta.throttle
import com.anpaiss.bettereta.whileVisible
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the debug field shows; the extension publishes it every tick and on every event. */
data class DebugState(
    val enabled: Boolean = true,
    val rideOpen: Boolean = false,
    val startPct: Int = 100,
    val startAssumed: Boolean = true,
    val condition: Condition? = null,
    val estimatedPct: Double = 100.0,
    val whMech: Double = 0.0,
    val whEstimated: Double = 0.0,
    val sUsed: Double = Prefs.S_INITIAL,
    val capacityWh: Double = 1000.0,
    val manualUpdates: Int = 0,
    val lastUpdateKm: Double = -1.0,
    val lastUpdatePct: Int = -1,
    // ride so far (Karoo streams)
    val distanceKm: Double = 0.0,
    val ascentKarooM: Double = -1.0,
    val movingS: Double = 0.0,
    val temperatureC: Double? = null,
    // route
    val hasRoute: Boolean = false,
    val routeKm: Double = -1.0,
    val routeAscentM: Double = -1.0,
    val karooDistToDestKm: Double = -1.0,
    val karooAscentRemM: Double = -1.0,
    val routeUnclassifiedPct: Double = -1.0,   // -1 = not classified yet / no route
    val surfaceComplete: Boolean? = null,
    val routeSurfaceKm: List<Double>? = null,  // per Surface.ordinal, after the classification
    // Flow screenshots (FATTO on the popups)
    val flowShotStart: Boolean = false,
    val flowShotMid: Boolean = false,
    val flowShotEnd: Boolean = false,
    // history
    val historyRides: Int = 0,
    val historyValid: Int = 0,
    val historyComparable: Int = 0,
)

/**
 * Provisional, text-only data field for the test rides (13/10): estimated
 * battery now (start % minus the integrated energy), Wh spent, s, ride and
 * route figures, surface classification and composition, the Flow screenshot
 * checklist and the history counts. No arrival prediction: that is step 3.
 * "boschRange Debug" in the BOSCHRANGE group of the Karoo field picker. Text
 * sizes follow the slot: a full page gets the large set.
 */
class DebugFieldDataType(
    extension: String,
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "debug") {

    companion object {
        private const val REDRAW_THROTTLE_MS = 5_000L
        private const val FULL_PAGE_MIN_PX = 500
        private val LINE_IDS = intArrayOf(R.id.tv_l1, R.id.tv_l2, R.id.tv_l3, R.id.tv_l4, R.id.tv_l5,
            R.id.tv_l6, R.id.tv_l7, R.id.tv_l8, R.id.tv_l9, R.id.tv_l10)
        private val SURFACE_SHORT = mapOf(Surface.PAVED to "pav", Surface.GRAVEL to "grv", Surface.TRAIL to "trl",
            Surface.GNARLY to "gnr", Surface.UNKNOWN to "unk")
    }

    private data class Lines(val big: String, val lines: List<String>)

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            state.map { Math.round(it.estimatedPct).toDouble() }.distinctUntilChanged().collect { pct ->
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to pct))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val fullPage = config.viewSize.second >= FULL_PAGE_MIN_PX
        val bigSp = if (fullPage) 64f else 34f
        val lineSp = if (fullPage) 19f else 13f
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { l ->
                    val views = RemoteViews(context.packageName, R.layout.view_debug)
                    views.setTextViewText(R.id.tv_big, l.big)
                    views.setTextViewTextSize(R.id.tv_big, TypedValue.COMPLEX_UNIT_SP, bigSp)
                    LINE_IDS.forEachIndexed { i, id ->
                        views.setTextViewText(id, l.lines.getOrNull(i) ?: "")
                        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, lineSp)
                    }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(s: DebugState): Lines {
        if (!s.enabled && !s.rideOpen) return Lines("OFF", listOf("boschRange disabled in the app"))
        val lines = mutableListOf<String>()
        val big: String
        if (s.rideOpen) {
            big = "%.0f %%".format(s.estimatedPct)
            // every line at most ~27 characters: that is what fits a full page at 19 sp
            lines += "start %d%% %s%s".format(s.startPct, if (s.startAssumed) "?" else "✓",
                s.condition?.let { "  $it" } ?: "")
            lines += "%.0f Wh (%.0f mech) s %.2f".format(s.whEstimated, s.whMech, s.sUsed)
            lines += if (s.manualUpdates > 0) "upd %d: %d%% at %.1f km".format(s.manualUpdates, s.lastUpdatePct, s.lastUpdateKm)
                     else "no upd  cap %.0f Wh".format(s.capacityWh)
            lines += "%.1f km D+%s %s%s".format(s.distanceKm, m(s.ascentKarooM), hm(s.movingS),
                s.temperatureC?.let { " %.0f°".format(it) } ?: "")
        } else {
            big = "no ride"
            lines += "s %.2f  cap %.0f Wh".format(s.sUsed, s.capacityWh)
        }
        if (s.hasRoute) {
            lines += "route %.1f km D+ %s m".format(s.routeKm, m(s.routeAscentM))
            lines += "left %s km D+ %s m".format(if (s.karooDistToDestKm >= 0) "%.1f".format(s.karooDistToDestKm) else "-", m(s.karooAscentRemM))
            lines += surfaceLine(s)
            s.routeSurfaceKm?.let { km ->
                val tot = km.sum().coerceAtLeast(0.001)
                lines += Surface.entries.filter { km[it.ordinal] > 0 }
                    .joinToString(" ") { "${SURFACE_SHORT[it]}${Math.round(km[it.ordinal] / tot * 100)}" } + " %"
            }
        } else {
            lines += "no route loaded"
        }
        if (s.rideOpen) lines += "Flow ${tick(s.flowShotStart)} start ${tick(s.flowShotMid)} mid ${tick(s.flowShotEnd)} end"
        lines += "hist %d  valid %d  Flow %d/%d".format(s.historyRides, s.historyValid, s.historyComparable, FlowComparison.MIN_RIDES)
        return Lines(big, lines)
    }

    private fun surfaceLine(s: DebugState): String = when {
        s.routeUnclassifiedPct < 0.0   -> "surf classifying…"
        s.surfaceComplete == null      -> "surf NOT classified"
        else -> "surf %s %.0f%% unk".format(if (s.surfaceComplete) "classified" else "partial", s.routeUnclassifiedPct)
    }

    private fun m(v: Double) = if (v >= 0) "%.0f".format(v) else "-"
    private fun hm(sec: Double): String { val mnt = (sec / 60).toInt(); return "%d:%02d".format(mnt / 60, mnt % 60) }
    private fun tick(done: Boolean) = if (done) "✓" else "○"
}
