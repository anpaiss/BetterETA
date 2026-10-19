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

/** What the fields show; the extension publishes it every tick and on every event. */
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
    val lastUpdateAgeMin: Int = -1,
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
    // forecast (step 3): null = no route loaded
    val arrivalPct: Double? = null,
    val halfBandPct: Double = 0.0,
    val marginPct: Double = 0.0,
    val extraClimbM: Double = 0.0,
    val remainingKm: Double = 0.0,
    val remainingAscentM: Double = 0.0,
    val thresholdPct: Int = 10,
    /** Start % the forecast plans from before the ride (tap on a field to change it). */
    val planningPct: Int = 100,
    // Flow screenshots (FATTO on the popups)
    val flowShotStart: Boolean = false,
    val flowShotMid: Boolean = false,
    val flowShotEnd: Boolean = false,
    // history
    val historyRides: Int = 0,
    val historyValid: Int = 0,
    val historyComparable: Int = 0,
)

/** The big line and the label/value rows of a full-page field. */
data class PageContent(val big: String, val rows: List<Pair<String, String>>)

/**
 * Full-page text field: one big line and up to 13 label/value rows, text sizes
 * from the slot (a full page gets the large set). This class is the provisional
 * debug page of the test rides (13/10): estimated battery now, Wh spent, s,
 * ride and route figures, surface classification and composition, the Flow
 * screenshot checklist and the history counts. A tap opens "Battery now?".
 */
open class DebugFieldDataType(
    extension: String,
    typeId: String = "debug",
    private val header: String = "BOSCHRANGE DEBUG",
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, typeId) {

    companion object {
        private const val REDRAW_THROTTLE_MS = 5_000L
        private const val FULL_PAGE_MIN_PX = 500
        private const val HEADER_PX = 40f           // header line, 12 sp
        private const val ROW_PITCH_PER_SP = 1.45f  // measured: 19 sp rows are 53 px apart, 16 sp rows 43 px
        private const val CHAR_WIDTH_PER_SP = 0.5f  // measured: ~0.46 x density x sp per character, rounded up
        private const val ROWS = 13   // 12 used during a ride
        private val KEY_IDS = intArrayOf(R.id.tv_k1, R.id.tv_k2, R.id.tv_k3, R.id.tv_k4, R.id.tv_k5, R.id.tv_k6, R.id.tv_k7, R.id.tv_k8, R.id.tv_k9, R.id.tv_k10, R.id.tv_k11, R.id.tv_k12, R.id.tv_k13)
        private val VALUE_IDS = intArrayOf(R.id.tv_v1, R.id.tv_v2, R.id.tv_v3, R.id.tv_v4, R.id.tv_v5, R.id.tv_v6, R.id.tv_v7, R.id.tv_v8, R.id.tv_v9, R.id.tv_v10, R.id.tv_v11, R.id.tv_v12, R.id.tv_v13)
        private val SURFACE_SHORT = mapOf(Surface.PAVED to "pav", Surface.GRAVEL to "grv", Surface.TRAIL to "trl",
            Surface.GNARLY to "gnr", Surface.UNKNOWN to "unk")
    }

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
        val heightPx = config.viewSize.second            // full page on the Karoo 3: 478 x 642 px, density 1.875
        val widthPx = config.viewSize.first - 16 * context.resources.displayMetrics.density   // minus the 8 dp paddings
        val density = context.resources.displayMetrics.density
        val tap = if (config.preview) null else BatteryTapReceiver.pendingIntent(context)
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { page ->
                    // One size for all rows: the largest at which every row fits the width and all rows fit the
                    // height under a 44 sp number; the number then grows (up to 72 sp) into what is left,
                    // but never wider than the page ("62 → 31 %" is 9 characters).
                    val n = page.rows.size.coerceAtLeast(1)
                    val minBigSp = if (fullPage) 44f else 30f
                    val availPx = heightPx - HEADER_PX - minBigSp * density * 1.2f - 10f
                    val rowSpByHeight = (availPx / n / (density * ROW_PITCH_PER_SP)).toInt()
                    val rowSpByWidth = page.rows.minOf { (k, v) -> (widthPx / ((k.length + v.length + 2) * density * CHAR_WIDTH_PER_SP)).toInt() }
                    val rowSp = minOf(rowSpByHeight, rowSpByWidth).coerceIn(12, 30).toFloat()
                    val leftPx = heightPx - HEADER_PX - 10f - n * rowSp * density * ROW_PITCH_PER_SP
                    val bigByWidth = widthPx / (page.big.length.coerceAtLeast(3) * density * 0.62f)
                    val bigSp = minOf(leftPx / (density * 1.2f), bigByWidth).coerceIn(minBigSp, if (fullPage) 72f else 30f)
                    val views = RemoteViews(context.packageName, R.layout.view_debug)
                    views.setTextViewText(R.id.tv_header, header)
                    views.setTextViewText(R.id.tv_big, page.big)
                    views.setTextViewTextSize(R.id.tv_big, TypedValue.COMPLEX_UNIT_SP, bigSp)
                    for (i in 0 until ROWS) {
                        val (k, v) = page.rows.getOrNull(i) ?: ("" to "")
                        views.setTextViewText(KEY_IDS[i], k)
                        views.setTextViewText(VALUE_IDS[i], v)
                        views.setTextViewTextSize(KEY_IDS[i], TypedValue.COMPLEX_UNIT_SP, rowSp)
                        views.setTextViewTextSize(VALUE_IDS[i], TypedValue.COMPLEX_UNIT_SP, rowSp)
                    }
                    tap?.let { views.setOnClickPendingIntent(R.id.page_root, it) }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    protected open fun render(s: DebugState): PageContent {
        if (!s.enabled && !s.rideOpen) return PageContent("OFF", listOf("boschRange" to "disabled in the app"))
        val rows = mutableListOf<Pair<String, String>>()
        val big: String
        if (s.rideOpen) {
            big = "%.0f %%".format(s.estimatedPct)
            rows += "start" to "%d%% %s%s".format(s.startPct, if (s.startAssumed) "? unconfirmed" else "✓", s.condition?.let { " $it" } ?: "")
            rows += "spent" to "%.0f Wh (%.0f mech)".format(s.whEstimated, s.whMech)
            rows += "s / cap" to "%.2f / %.0f Wh".format(s.sUsed, s.capacityWh)
            rows += "last upd" to if (s.manualUpdates > 0) "%d%% @ %.1f km".format(s.lastUpdatePct, s.lastUpdateKm) else "-"
            rows += "ride" to "%.1f km D+%s %s".format(s.distanceKm, m(s.ascentKarooM), hm(s.movingS))
        } else {
            big = "no ride"
            rows += "s / cap" to "%.2f / %.0f Wh".format(s.sUsed, s.capacityWh)
        }
        if (s.hasRoute) {
            rows += "route" to "%.1f km D+%s m".format(s.routeKm, m(s.routeAscentM))
            rows += "left" to "%s km D+%s m".format(if (s.karooDistToDestKm >= 0) "%.1f".format(s.karooDistToDestKm) else "-", m(s.karooAscentRemM))
            s.arrivalPct?.let { arrival ->
                val now = if (s.rideOpen) s.estimatedPct else s.planningPct.toDouble()
                rows += "arrival" to "%.0f ±%.0f %% (%.0f Wh)".format(arrival, s.halfBandPct, (now - arrival) / 100.0 * s.capacityWh)
            }
            rows += "surface" to surfaceValue(s)
            s.routeSurfaceKm?.let { km ->
                val tot = km.sum().coerceAtLeast(0.001)
                rows += "mix %" to Surface.entries.sortedByDescending { km[it.ordinal] }
                    .filter { Math.round(km[it.ordinal] / tot * 100) >= 1 }.take(3)
                    .joinToString(" ") { "${SURFACE_SHORT[it]}${Math.round(km[it.ordinal] / tot * 100)}" }
            }
        } else {
            rows += "route" to "none"
        }
        if (s.rideOpen) rows += "Flow" to "${tick(s.flowShotStart)} start ${tick(s.flowShotMid)} mid ${tick(s.flowShotEnd)} end"
        rows += "history" to "%d / %d valid".format(s.historyRides, s.historyValid)
        rows += "vs Flow" to "%d/%d rides".format(s.historyComparable, FlowComparison.MIN_RIDES)
        return PageContent(big, rows)
    }

    protected fun surfaceValue(s: DebugState): String = when {
        s.routeUnclassifiedPct < 0.0   -> "classifying…"
        s.surfaceComplete == null      -> "NOT classified"
        else -> "%s %.0f%% unk".format(if (s.surfaceComplete) "ok" else "partial", s.routeUnclassifiedPct)
    }

    protected fun m(v: Double) = if (v >= 0) "%.0f".format(v) else "-"
    protected fun hm(sec: Double): String { val mnt = (sec / 60).toInt(); return "%d:%02d".format(mnt / 60, mnt % 60) }
    private fun tick(done: Boolean) = if (done) "✓" else "○"
}
