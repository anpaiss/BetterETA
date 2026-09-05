package com.anpaiss.bettereta

import android.content.Context
import android.widget.RemoteViews
import android.text.format.DateFormat
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
 * Campo grafico — mostra l'orario di arrivo previsto nel formato HH:mm.
 * Mostra "--:--" se non c'è un percorso caricato.
 */
class EtaAbsoluteDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-absolute") {

    companion object {
        // An arrival time has minute resolution, so this throttle almost never
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

            // Aggiorniamo la UI solo quando il testo visualizzato cambia effettivamente (es. cambio minuto)
            combine(etaState, prefs.styleFlow()) { state, style ->
                formatEta(context, state) to style.colorsFor(null)
            }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { (formattedText, c) ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_absolute)
                    if (c.bgRes != null) views.setInt(R.id.root_eta, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_eta, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_eta_label, c.label)
                    views.setTextColor(R.id.tv_eta_value, c.value)
                    views.setTextViewText(R.id.tv_eta_value, formattedText)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun formatEta(context: Context, state: EtaState): String {
        if (!state.hasRoute || state.remainingSeconds < 0) return state.status

        val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
        val cal  = Calendar.getInstance().apply { timeInMillis = arrivalMs }
        val time = DateFormat.getTimeFormat(context).format(cal.time)
        // "~" indica che il GPS non è disponibile: l'orario è stima per dead reckoning
        return if (state.gpsStale) "~$time" else time
    }
}
