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
 * Campo grafico — mostra il tempo rimanente nel formato Xh Ym.
 * Mostra "--h--m" se non c'è un percorso caricato.
 */
class EtaRemainingDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-remaining") {

    companion object {
        // The displayed text has minute resolution, so this throttle almost never
        // delays anything real — it is a ceiling on redraws, not a sampling rate.
        private const val REDRAW_THROTTLE_MS = 20_000L
    }

    // Fallback numerico: minuti rimanenti
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            var lastValidMins = -1L
            etaState
                .map { state ->
                    if (state.remainingSeconds >= 0) lastValidMins = state.remainingSeconds / 60L
                    lastValidMins   // -1 finché non c'è un valore valido, poi congelato
                }
                .distinctUntilChanged()
                .collect { minsRemaining ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to minsRemaining.toDouble()))
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

            // Aggiorniamo la UI solo quando il testo visualizzato cambia effettivamente
            combine(etaState, prefs.styleFlow()) { state, style ->
                formatRemaining(state) to style.colorsFor(null)
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (formattedText, c) ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_remaining)
                    if (c.bgRes != null) views.setInt(R.id.root_remaining, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_remaining, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_remaining_label, c.label)
                    views.setTextColor(R.id.tv_remaining_value, c.value)
                    views.setTextViewText(R.id.tv_remaining_value, formattedText)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun formatRemaining(state: EtaState): String {
        if (!state.hasRoute || state.remainingSeconds < 0) return state.status

        val totalMin = state.remainingSeconds / 60L
        val hours    = totalMin / 60L
        val mins     = totalMin % 60L
        val time     = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        // "~" indica che il GPS non è disponibile: l'ETA è stima per dead reckoning
        return if (state.gpsStale || state.estimateApprox) "~$time" else time
    }
}
