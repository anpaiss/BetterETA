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
 * checklist and the history counts, one item per row over the whole page. No
 * arrival prediction: that is step 3. "boschRange Debug" in the BOSCHRANGE
 * group of the Karoo field picker. Text sizes follow the slot: a full page
 * gets the large set.
 */
class DebugFieldDataType(
    extension: String,
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "debug") {

    companion object {
        private const val REDRAW_THROTTLE_MS = 5_000L
        private const val FULL_PAGE_MIN_PX = 500
        private const val ROWS = 13
        private val KEY_IDS = intArrayOf(R.id.tv_k1, R.id.tv_k2, R.id.tv_k3, R.id.tv_k4, R.id.tv_k5, R.id.tv_k6, R.id.tv_k7, R.id.tv_k8, R.id.tv_k9, R.id.tv_k10, R.id.tv_k11, R.id.tv_k12, R.id.tv_k13)
        private val VALUE_IDS = intArrayOf(R.id.tv_v1, R.id.tv_v2, R.id.tv_v3, R.id.tv_v4, R.id.tv_v5, R.id.tv_v6, R.id.tv_v7, R.id.tv_v8, R.id.tv_v9, R.id.tv_v10, R.id.tv_v11, R.id.tv_v12, R.id.tv_v13)
        private val SURFACE_SHORT = mapOf(Surface.PAVED to "pav", Surface.GRAVEL to "grv", Surface.TRAIL to "trl",
            Surface.GNARLY to "gnr", Surface.UNKNOWN to "unk")
    }

    /** The big number and the label/value rows, one item per row. */
    private data class Page(val big: String, val rows: List<Pair<String, String>>)

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
        // full page: 11 rows of 16 sp (43 px each) plus the 44 sp number fit the ~610 px below the status bar
        val bigSp = if (fullPage) 44f else 30f
        val rowSp = if (fullPage) 16f else 12f
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { page ->
                    val views = RemoteViews(context.packageName, R.layout.view_debug)
                    views.setTextViewText(R.id.tv_big, page.big)
                    views.setTextViewTextSize(R.id.tv_big, TypedValue.COMPLEX_UNIT_SP, bigSp)
                    for (i in 0 until ROWS) {
                        val (k, v) = page.rows.getOrNull(i) ?: ("" to "")
                        views.setTextViewText(KEY_IDS[i], k)
                        views.setTextViewText(VALUE_IDS[i], v)
                        views.setTextViewTextSize(KEY_IDS[i], TypedValue.COMPLEX_UNIT_SP, rowSp)
                        views.setTextViewTextSize(VALUE_IDS[i], TypedValue.COMPLEX_UNIT_SP, rowSp)
                    }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(s: DebugState): Page {
        if (!s.enabled && !s.rideOpen) return Page("OFF", listOf("boschRange" to "disabled in the app"))
        val rows = mutableListOf<Pair<String, String>>()
        val big: String
        if (s.rideOpen) {
            big = "%.0f %%".format(s.estimatedPct)
            rows += "start" to "%d%% %s%s".format(s.startPct, if (s.startAssumed) "? unconfirmed" else "\u2713", s.condition?.let { " $it" } ?: "")
            rows += "spent" to "%.0f Wh (%.0f mech)".format(s.whEstimated, s.whMech)
            rows += "s / cap" to "%.2f / %.0f Wh".format(s.sUsed, s.capacityWh)
            rows += "last upd" to if (s.manualUpdates > 0) "%d%% at %.1f km (%d)".format(s.lastUpdatePct, s.lastUpdateKm, s.manualUpdates) else "-"
            rows += "ride" to "%.1f km  D+ %s m  %s".format(s.distanceKm, m(s.ascentKarooM), hm(s.movingS))
        } else {
            big = "no ride"
            rows += "s / cap" to "%.2f / %.0f Wh".format(s.sUsed, s.capacityWh)
        }
        if (s.hasRoute) {
            rows += "route" to "%.1f km  D+ %s m".format(s.routeKm, m(s.routeAscentM))
            rows += "left" to "%s km  D+ %s m".format(if (s.karooDistToDestKm >= 0) "%.1f".format(s.karooDistToDestKm) else "-", m(s.karooAscentRemM))
            rows += "surface" to surfaceValue(s)
            s.routeSurfaceKm?.let { km ->
                val tot = km.sum().coerceAtLeast(0.001)
                rows += "mix %" to Surface.entries.filter { km[it.ordinal] > 0 }
                    .joinToString(" ") { "${SURFACE_SHORT[it]}${Math.round(km[it.ordinal] / tot * 100)}" }
            }
        } else {
            rows += "route" to "none"
        }
        if (s.rideOpen) rows += "Flow shots" to "${tick(s.flowShotStart)} start ${tick(s.flowShotMid)} mid ${tick(s.flowShotEnd)} end"
        rows += "history" to "%d rides, %d valid, Flow %d/%d".format(s.historyRides, s.historyValid, s.historyComparable, FlowComparison.MIN_RIDES)
        return Page(big, rows)
    }

    private fun surfaceValue(s: DebugState): String = when {
        s.routeUnclassifiedPct < 0.0   -> "classifying\u2026"
        s.surfaceComplete == null      -> "NOT classified"
        else -> "%s, %.0f%% unk".format(if (s.surfaceComplete) "classified" else "partial", s.routeUnclassifiedPct)
    }

    private fun m(v: Double) = if (v >= 0) "%.0f".format(v) else "-"
    private fun hm(sec: Double): String { val mnt = (sec / 60).toInt(); return "%d:%02d".format(mnt / 60, mnt % 60) }
    private fun tick(done: Boolean) = if (done) "✓" else "○"
}
