package com.anpaiss.bettereta

import android.content.Context
import android.text.format.DateFormat
import android.widget.RemoteViews
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Way Home field: how much longer you can keep going before the way back,
 * retraced along the track ridden so far, lands after the daylight limit.
 *
 * Outbound, with the sun times from the Karoo: the big number is the minutes
 * of further riding the limit allows; the label gives the arrival home if you
 * turned now and the time by which to turn. On the way back the big number is
 * the slack against the limit. Without sun times the big number is the time
 * of the way back itself.
 */
class WayHomeDataType(
    extension: String,
    private val wayHomeState: StateFlow<WayHomeState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-way-home") {

    companion object {
        private const val REDRAW_THROTTLE_MS = 15_000L
        private const val IDLE_LABEL = "WAY\nHOME"
        /** Key of the allowance in the published data point; BigFields reads it under this name. */
        const val ALLOWANCE_FIELD = "allowance"
    }

    // Numeric values: SINGLE = seconds of the way back (-1 without a track); "allowance" = seconds of
    // further outbound riding the daylight limit allows (absent without a limit or on the way back),
    // read by BigFields to show Way Home in large digits (12/11/2026)
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            wayHomeState.map { it.returnSec to it.allowanceSec }.distinctUntilChanged().collect { (sec, allowance) ->
                val values = mutableMapOf(DataType.Field.SINGLE to sec.toDouble())
                if (allowance != null) values[ALLOWANCE_FIELD] = allowance.toDouble()
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, values)))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        BetterEtaLogger.shared?.log("VIEW ", "startView $dataTypeId size=${config.viewSize} preview=${config.preview}")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = Prefs(context)
        val source: Flow<WayHomeState> = if (config.preview) flowOf(WayHomeState.sample()) else wayHomeState
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            combine(source, prefs.styleFlow()) { state, style ->
                Triple(formatLabel(context, state), formatValue(state), style)
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (label, value, style) ->
                    if (style == EtaStyle.KAROO) { emitter.updateView(NativeField.views(context, config, label, value)); return@collect }
                    val c = style.colorsFor(null)
                    if (StackedField.narrow(config)) { emitter.updateView(StackedField.views(context, c, label, value)); return@collect }
                    val views = RemoteViews(context.packageName, R.layout.view_way_home)
                    if (c.bgRes != null) views.setInt(R.id.root_way_home, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_way_home, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_way_home_label, c.label)
                    views.setTextColor(R.id.tv_way_home_value, c.value)
                    views.setTextViewText(R.id.tv_way_home_label, label)
                    views.setTextViewText(R.id.tv_way_home_value, value)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun time(context: Context, epochMs: Long): String = DateFormat.getTimeFormat(context).format(Date(epochMs))

    private fun formatLabel(context: Context, s: WayHomeState): String {
        if (s.returnSec < 0) return IDLE_LABEL
        val home = "HOME " + time(context, s.arrivalEpochMs)
        return when {
            s.following -> "BACK\n$home"
            s.allowanceSec != null -> "$home\nturn by " + time(context, System.currentTimeMillis() + s.allowanceSec * 1000L)
            else -> "$home\n%.1f km".format(s.returnDistM / 1000.0)
        }
    }

    private fun formatValue(s: WayHomeState): String {
        if (s.returnSec < 0) return "--"
        val a = s.allowanceSec
        return when {
            s.following && s.limitEpochMs > 0L -> signedMinutes((s.limitEpochMs - s.arrivalEpochMs) / 1000L)
            a != null -> signedMinutes(a)
            else -> hoursMinutes(s.returnSec)
        }
    }

    private fun signedMinutes(sec: Long): String {
        val m = sec / 60L
        return if (m < 0) "${m}m" else "${m}m"
    }

    private fun hoursMinutes(sec: Long): String {
        val m = sec / 60L
        return if (m >= 60) "%d:%02d".format(m / 60, m % 60) else "${m}m"
    }
}
