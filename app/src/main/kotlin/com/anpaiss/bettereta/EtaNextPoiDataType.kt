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
 * Campo grafico — ora prevista al prossimo POI della route (il punto d'incontro,
 * la fontana, il rifugio messi sulla route in Komoot o nella dashboard
 * Hammerhead), con lo stesso modello dell'arrivo. A sinistra il nome del POI,
 * a destra l'ora; superato un POI passa al successivo. "no POI" se la route
 * non ne ha davanti; prima della partenza ("Plan") il tempo dalla partenza.
 */
class EtaNextPoiDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-next-poi") {

    companion object {
        private const val REDRAW_THROTTLE_MS = 15_000L
        private const val NO_POI_LABEL = "NEXT\nPOINT"
    }

    // Fallback numerico: secondi al prossimo POI (-1 senza POI)
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            etaState
                .map { it.nextPoiSeconds }
                .distinctUntilChanged()
                .collect { seconds ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to seconds.toDouble()))
                        )
                    )
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
                Triple(formatLabel(state), formatValue(context, state), style.colorsFor(null))
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (label, value, c) ->
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

    private fun formatLabel(state: EtaState): String {
        val name = state.nextPoiName ?: return NO_POI_LABEL
        return if (state.status == "Plan") "PLAN\n$name" else name
    }

    private fun formatValue(context: Context, state: EtaState): String {
        if (!state.hasRoute) return state.status
        if (state.nextPoiName == null || state.nextPoiSeconds < 0) return "no POI"
        if (state.status == "Plan") {
            // Giro non partito: tempo dalla partenza, non un'ora
            val totalMin = state.nextPoiSeconds / 60L
            val hours    = totalMin / 60L
            val mins     = totalMin % 60L
            return "~" + if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        }
        val arrivalMs = System.currentTimeMillis() + state.nextPoiSeconds * 1000L
        val cal  = Calendar.getInstance().apply { timeInMillis = arrivalMs }
        val time = DateFormat.getTimeFormat(context).format(cal.time)
        // "~" indica che il GPS non è disponibile: l'orario è stima per dead reckoning
        return if (state.gpsStale || state.estimateApprox) "~$time" else time
    }
}
