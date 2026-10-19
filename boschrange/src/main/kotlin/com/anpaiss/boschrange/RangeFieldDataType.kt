package com.anpaiss.boschrange

import android.content.Context
import android.util.TypedValue
import android.view.View
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
 * The three single-cell fields of step 3 (19/10/2026), all drawn from the
 * same [DebugState] the extension publishes every tick:
 *
 *  - COMPACT      "62 → 31 %"  battery now and predicted at arrival, the half band below
 *  - MARGIN       "+16 %"      arrival minus the alert threshold ("to the 10 % limit"), green or red
 *  - EXTRA_CLIMB  "+420 m"     extra ascent the margin would still pay for, green or red
 *
 * Caption, value and sub-caption scale with the cell. A tap opens "Battery
 * now?" ([BatteryTapReceiver]); not in the profile editor preview.
 */
class RangeFieldDataType(
    extension: String,
    typeId: String,
    private val kind: Kind,
    private val state: StateFlow<DebugState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?>,
) : DataTypeImpl(extension, typeId) {

    enum class Kind { COMPACT, MARGIN, EXTRA_CLIMB }
    private enum class Tone { NEUTRAL, OK, BAD }
    private data class Face(val caption: String, val value: String, val sub: String, val tone: Tone)

    companion object {
        private const val REDRAW_THROTTLE_MS = 2_000L
        /** Bold digits are about 0.62 em wide; the value must fit the cell on one line. */
        private const val CHAR_EM = 0.62f
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            state.map { s ->
                when (kind) {
                    Kind.COMPACT     -> s.arrivalPct
                    Kind.MARGIN      -> s.arrivalPct?.let { s.marginPct }
                    Kind.EXTRA_CLIMB -> s.arrivalPct?.let { s.extraClimbM }
                }?.let { Math.round(it).toDouble() }
            }.distinctUntilChanged().collect { v ->
                emitter.onNext(if (v == null) StreamState.NotAvailable
                    else StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val density = context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / density - 16f
        val heightDp = config.viewSize.second / density
        val tap = if (config.preview) null else BatteryTapReceiver.pendingIntent(context)
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { face ->
                    val byHeight = heightDp * 0.40f
                    val byWidth = widthDp / (CHAR_EM * face.value.length.coerceAtLeast(3))
                    val valueSp = minOf(byHeight, byWidth).coerceIn(16f, 80f)
                    val captionSp = (valueSp * 0.32f).coerceIn(10f, 16f)
                    val subSp = (valueSp * 0.40f).coerceIn(10f, 20f)
                    val small = heightDp < 60f
                    val views = RemoteViews(context.packageName, R.layout.view_field)
                    views.setTextViewText(R.id.tv_caption, face.caption)
                    views.setTextViewText(R.id.tv_value, face.value)
                    views.setTextViewText(R.id.tv_sub, face.sub)
                    views.setTextViewTextSize(R.id.tv_caption, TypedValue.COMPLEX_UNIT_SP, captionSp)
                    views.setTextViewTextSize(R.id.tv_value, TypedValue.COMPLEX_UNIT_SP, valueSp)
                    views.setTextViewTextSize(R.id.tv_sub, TypedValue.COMPLEX_UNIT_SP, subSp)
                    views.setViewVisibility(R.id.tv_caption, if (small) View.GONE else View.VISIBLE)
                    views.setViewVisibility(R.id.tv_sub, if (small || face.sub.isEmpty()) View.GONE else View.VISIBLE)
                    views.setTextColor(R.id.tv_value, context.getColor(when (face.tone) {
                        Tone.NEUTRAL -> R.color.page_text; Tone.OK -> R.color.field_ok; Tone.BAD -> R.color.field_bad
                    }))
                    tap?.let { views.setOnClickPendingIntent(R.id.field_root, it) }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun band(s: DebugState): String = "±%.0f".format(s.halfBandPct) +
        if (s.historyValid < RangeForecast.CALIBRATION_RIDES) " · CAL ${s.historyValid}/${RangeForecast.CALIBRATION_RIDES}" else ""

    private fun render(s: DebugState): Face {
        val caption = when (kind) { Kind.COMPACT -> "NOW → ARRIVAL"; Kind.MARGIN -> "MARGIN"; Kind.EXTRA_CLIMB -> "EXTRA CLIMB POSSIBLE" }
        if (!s.enabled && !s.rideOpen) return Face(caption, "OFF", "boschRange off", Tone.NEUTRAL)
        val now = if (s.rideOpen) s.estimatedPct else s.planningPct.toDouble()
        val arrival = s.arrivalPct
        return when (kind) {
            Kind.COMPACT -> when {
                arrival == null -> Face(caption, "%.0f %%".format(now), "no route", Tone.NEUTRAL)
                // The route costs more than the battery holds: say by how much instead of a negative % (Andrea, 19/10)
                arrival < -0.5 -> Face("OUT OF RANGE BY", "%.0f %%".format(-arrival), "now %.0f %%  ·  ".format(now) + band(s), Tone.BAD)
                else -> Face(caption, "%.0f → %.0f %%".format(now, arrival), band(s), Tone.NEUTRAL)
            }
            Kind.MARGIN -> when {
                arrival == null -> Face(caption, "--", "no route", Tone.NEUTRAL)
                else -> Face(caption, "%+d %%".format(Math.round(s.marginPct)), "to the ${s.thresholdPct} % limit",
                    if (s.marginPct >= 0.0) Tone.OK else Tone.BAD)
            }
            Kind.EXTRA_CLIMB -> when {
                arrival == null -> Face(caption, "--", "no route", Tone.NEUTRAL)
                else -> Face(caption, "%+d m".format(Math.round(s.extraClimbM)), "%+d %% margin".format(Math.round(s.marginPct)),
                    if (s.extraClimbM >= 0.0) Tone.OK else Tone.BAD)
            }
        }
    }
}
