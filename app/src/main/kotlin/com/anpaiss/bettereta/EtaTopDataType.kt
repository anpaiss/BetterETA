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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The next climb's top (14/11/2026, Andrea): [AT] the time of day you get there, [TIME] how long it
 * takes, with the same model as the finish (way memory, speed by grade, the Bosch). The climbs are
 * the Karoo's when it sends them, else found on the route's elevation ([RouteClimbs]). "no climb"
 * when no top is left ahead; before the start ("Plan") the time from the start.
 *
 * The stream publishes the seconds to the top (-1 = none) and, under [TOP_M_FIELD], the metres to it,
 * so RouteProfile can show the time only when it speaks of the same climb.
 */
class EtaTopDataType(
    extension: String,
    private val mode: Mode,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, mode.typeId) {

    enum class Mode(val typeId: String, val title: String) {
        AT("eta-top-at", "AT THE TOP"),
        TIME("eta-time-to-top", "TIME TO TOP"),
    }

    companion object {
        private const val REDRAW_THROTTLE_MS = 15_000L
        const val TOP_M_FIELD = "top_m"
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            etaState
                .map { it.topSeconds to it.topDistanceM }
                .distinctUntilChanged()
                .collect { (sec, m) ->
                    emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId,
                        mapOf(DataType.Field.SINGLE to sec.toDouble(), TOP_M_FIELD to m))))
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        BetterEtaLogger.shared?.log("VIEW ", "startView $dataTypeId size=${config.viewSize} preview=${config.preview}")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = Prefs(context)
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            combine(etaState.orSample(config.preview), prefs.styleFlow()) { state, style ->
                Triple(label(state), value(context, state), style)
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (label, value, style) ->
                    if (style == EtaStyle.KAROO) { emitter.updateView(NativeField.views(context, config, label, value)); return@collect }
                    val c = style.colorsFor(null)
                    if (StackedField.narrow(config)) { emitter.updateView(StackedField.views(context, c, label, value)); return@collect }
                    val views = RemoteViews(context.packageName, R.layout.view_eta_next_poi)
                    if (c.bgRes != null) views.setInt(R.id.root_next_poi, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_next_poi, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_next_poi_label, c.label)
                    views.setTextColor(R.id.tv_next_poi_value, c.value)
                    views.setTextViewText(R.id.tv_next_poi_label, label)
                    views.setTextViewText(R.id.tv_next_poi_value, value)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun label(state: EtaState) = if (state.status == "Plan") "PLAN\n${mode.title}" else mode.title

    private fun value(context: Context, state: EtaState): String {
        if (!state.hasRoute) return state.status
        if (state.topSeconds < 0) return "no climb"
        val approx = state.gpsStale || state.estimateApprox
        if (mode == Mode.TIME || state.status == "Plan") {
            val totalMin = (state.topSeconds + 30) / 60L
            val text = "%d:%02d".format(totalMin / 60L, totalMin % 60L)
            return if (approx) "~$text" else text
        }
        val cal = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + state.topSeconds * 1000L }
        val time = DateFormat.getTimeFormat(context).format(cal.time)
        return if (approx) "~$time" else time
    }
}
