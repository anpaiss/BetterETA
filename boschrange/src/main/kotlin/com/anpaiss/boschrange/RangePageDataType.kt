package com.anpaiss.boschrange

import android.content.Context
import android.util.TypedValue
import android.widget.RemoteViews
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The boschRange page (step 3, 19/10/2026), a grid in the style of the
 * BetterETA dashboard: black cells, grey caption on top, big number.
 *
 *   NOW → ARRIVAL        "100 → 58"             caption carries the band and the calibration state;
 *                        red "OUT OF RANGE BY 29 %" when the route costs more than the battery holds
 *   MARGIN TO 10 % LIMIT | EXTRA CLIMB POSSIBLE  "+43 %" | "+762 m"  green when the route fits, red when it does not
 *   TO GO  | SPENT       "21.5 km" | "123 Wh"   (START "100 %" planned, before the ride)
 *   footer               last entry, s, rides, surface state
 *
 * A tap anywhere opens "Battery now?" ([BatteryTapReceiver]); not in the preview.
 */
class RangePageDataType(
    extension: String,
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?>,
) : DataTypeImpl(extension, "page") {

    private enum class Tone { NEUTRAL, OK, BAD }
    private data class Cell(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL)
    private data class Page(val a: Cell, val b1: Cell, val b2: Cell, val c1: Cell, val c2: Cell, val footer: String)

    companion object {
        private const val REDRAW_THROTTLE_MS = 5_000L
        private const val CHAR_EM = 0.62f     // bold digits, width per character
        private const val WEIGHT_A = 1.25f    // the arrival row is taller than the two below (same as the layout)
        private const val FOOTER_SP = 13f
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFF9E9E9E.toInt()
        private const val GREEN = 0xFF4CAF50.toInt()   // black text: 7.6:1
        private const val RED = 0xFFD32F2F.toInt()     // white text: 5.0:1
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
        val density = context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / density - 4f          // page padding
        val heightDp = config.viewSize.second / density - 16f       // 2 dp top, 14 dp bottom: the Karoo bar overlaps the last pixels
        val footerDp = FOOTER_SP * 1.3f * 2 + 2f                     // two lines
        val rowsDp = heightDp - footerDp - 2f * 2                    // minus the two row gaps
        val rowADp = rowsDp * WEIGHT_A / (WEIGHT_A + 2f)
        val rowBDp = rowsDp / (WEIGHT_A + 2f)
        val halfDp = (widthDp - 2f) / 2f
        val tap = if (config.preview) null else BatteryTapReceiver.pendingIntent(context)
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { p ->
                    val views = RemoteViews(context.packageName, R.layout.view_range_page)
                    cell(views, R.id.cell_a, R.id.lbl_a, R.id.val_a, p.a, widthDp, rowADp)
                    cell(views, R.id.cell_b1, R.id.lbl_b1, R.id.val_b1, p.b1, halfDp, rowBDp)
                    cell(views, R.id.cell_b2, R.id.lbl_b2, R.id.val_b2, p.b2, halfDp, rowBDp)
                    cell(views, R.id.cell_c1, R.id.lbl_c1, R.id.val_c1, p.c1, halfDp, rowBDp)
                    cell(views, R.id.cell_c2, R.id.lbl_c2, R.id.val_c2, p.c2, halfDp, rowBDp)
                    views.setTextViewText(R.id.tv_footer, p.footer)
                    views.setTextViewTextSize(R.id.tv_footer, TypedValue.COMPLEX_UNIT_SP, FOOTER_SP)
                    tap?.let { views.setOnClickPendingIntent(R.id.page_root, it) }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    /** Caption and value sized to the cell: value 0.46 of the height, never wider than the cell. */
    private fun cell(views: RemoteViews, cellId: Int, lblId: Int, valId: Int, c: Cell, wDp: Float, hDp: Float) {
        val byHeight = hDp * 0.46f
        val byWidth = (wDp - 8f) / (CHAR_EM * c.value.length.coerceAtLeast(3))
        val valueSp = minOf(byHeight, byWidth).coerceIn(16f, 96f)
        val labelByWidth = (wDp - 8f) / (0.55f * c.label.length.coerceAtLeast(6))
        val labelSp = minOf(valueSp * 0.30f, labelByWidth).coerceIn(10f, 18f)
        val (bg, fg, lbl) = when (c.tone) {
            Tone.NEUTRAL -> Triple(BLACK, WHITE, GREY)
            Tone.OK -> Triple(GREEN, BLACK, BLACK)
            Tone.BAD -> Triple(RED, WHITE, WHITE)
        }
        views.setInt(cellId, "setBackgroundColor", bg)
        views.setTextViewText(lblId, c.label)
        views.setTextViewText(valId, c.value)
        views.setTextColor(lblId, lbl)
        views.setTextColor(valId, fg)
        views.setTextViewTextSize(lblId, TypedValue.COMPLEX_UNIT_SP, labelSp)
        views.setTextViewTextSize(valId, TypedValue.COMPLEX_UNIT_SP, valueSp)
    }

    private fun render(s: DebugState): Page {
        if (!s.enabled && !s.rideOpen) {
            val off = Cell("", "--")
            return Page(Cell("BOSCHRANGE", "OFF"), off, off, off, off, "disabled in the app")
        }
        val now = if (s.rideOpen) s.estimatedPct else s.planningPct.toDouble()
        val arrival = s.arrivalPct
        val cal = if (s.historyValid < RangeForecast.CALIBRATION_RIDES) "CAL ${s.historyValid}/${RangeForecast.CALIBRATION_RIDES}" else "calibrated"
        val a = when {
            arrival == null -> Cell("BATTERY NOW  ·  no route", "%.0f %%".format(now))
            // The route costs more than the battery holds: say by how much instead of a negative % (Andrea, 19/10)
            arrival < -0.5 -> Cell("NOW %.0f %%  ·  OUT OF RANGE BY  ·  ±%.0f  ·  %s".format(now, s.halfBandPct, cal), "%.0f %%".format(-arrival), Tone.BAD)
            else -> Cell("NOW → ARRIVAL %%  ·  ±%.0f  ·  %s".format(s.halfBandPct, cal), "%.0f → %.0f".format(now, arrival))
        }
        val b1 = if (arrival == null) Cell("MARGIN", "--")
            else Cell("MARGIN TO ${s.thresholdPct} % LIMIT", "%+d %%".format(Math.round(s.marginPct)), if (s.marginPct >= 0.0) Tone.OK else Tone.BAD)
        val b2 = if (arrival == null) Cell("EXTRA CLIMB POSSIBLE", "--")
            else Cell("EXTRA CLIMB POSSIBLE", "%+d m".format(Math.round(s.extraClimbM)), if (s.extraClimbM >= 0.0) Tone.OK else Tone.BAD)
        val c1 = if (arrival == null) Cell("TO GO", "--")
            else Cell("TO GO  ·  D+%.0f m".format(s.remainingAscentM), "%.1f km".format(s.remainingKm))
        val c2 = if (s.rideOpen) Cell("SPENT  ·  %.0f %%".format(s.whEstimated / s.capacityWh * 100.0), "%.0f Wh".format(s.whEstimated))
            else Cell("START  ·  tap to set", "%d %%".format(s.planningPct))
        val parts = mutableListOf<String>()
        if (s.bikeConnected) parts += "bike " + if (s.liveSoc >= 0) "${s.liveSoc} %" else "linked"
        if (s.rideOpen) parts += when {
            s.bikeConnected && s.liveSoc >= 0 -> "live"
            s.manualUpdates > 0 -> "set %d %% @ %.1f km, %d min ago".format(s.lastUpdatePct, s.lastUpdateKm, s.lastUpdateAgeMin.coerceAtLeast(0))
            s.startAssumed -> "start %d %% assumed".format(s.startPct)
            else -> "start %d %% confirmed".format(s.startPct)
        }
        parts += "s %.2f".format(s.sUsed)
        parts += "%d/%d rides".format(s.historyValid, RangeForecast.CALIBRATION_RIDES)
        if (s.hasRoute) parts += "surface " + surfaceShort(s)
        return Page(a, b1, b2, c1, c2, parts.joinToString("  ·  "))
    }

    private fun surfaceShort(s: DebugState): String = when {
        s.routeUnclassifiedPct < 0.0 -> "classifying…"
        s.surfaceComplete == null -> "not classified"
        s.surfaceComplete == false -> "partial"
        s.routeUnclassifiedPct >= 1.0 -> "%.0f%% unknown".format(s.routeUnclassifiedPct)
        else -> "ok"
    }
}
