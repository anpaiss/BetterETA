package com.anpaiss.bettereta

import android.content.Context
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

/**
 * Campo grafico — distanza rimanente al punto geograficamente più lontano
 * dalla partenza (tipico punto di svolta di un andata/ritorno: non
 * necessariamente il più alto). Utile in salita per sapere "quanto manca
 * alla cima", indipendentemente dalla distanza totale che include il ritorno.
 * Mostra "--.-" se non c'è un percorso caricato; "0.0" una volta superato.
 */
class EtaFarthestDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-farthest") {

    companion object {
        // A distance in tenths of a km changes every ~100 m ridden, so this is
        // tighter than the time fields: the number must not look frozen.
        private const val REDRAW_THROTTLE_MS = 10_000L
    }

    // Fallback numerico: metri rimanenti al punto più lontano
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            var lastValidM = -1.0
            etaState
                .map { state ->
                    if (state.farthestPointDistanceM >= 0.0) lastValidM = state.farthestPointDistanceM
                    lastValidM   // -1 finché non c'è un valore valido, poi congelato
                }
                .distinctUntilChanged()
                .collect { metersRemaining ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to metersRemaining))
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

            combine(etaState, prefs.styleFlow()) { state, style ->
                formatDistance(state) to style.colorsFor(null)
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (formattedText, c) ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_farthest)
                    if (c.bgRes != null) views.setInt(R.id.root_farthest, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_farthest, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_farthest_label, c.label)
                    views.setTextColor(R.id.tv_farthest_value, c.value)
                    views.setTextViewText(R.id.tv_farthest_value, formattedText)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun formatDistance(state: EtaState): String {
        if (!state.hasRoute || state.farthestPointDistanceM < 0.0) return state.status
        val km = state.farthestPointDistanceM / 1000.0
        val text = "%.1f".format(km)
        // "~" indica che il GPS non è disponibile: la posizione è stima per dead reckoning
        return if (state.gpsStale || state.estimateApprox) "~$text" else text
    }
}
