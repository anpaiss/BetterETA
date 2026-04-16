package com.anpaiss.karoobettereta

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
import kotlinx.coroutines.flow.StateFlow
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
) : DataTypeImpl(extension, "eta-remaining") {

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
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            
            // Aggiorniamo la UI solo quando il testo visualizzato cambia effettivamente
            etaState
                .map { formatRemaining(it) }
                .distinctUntilChanged()
                .collect { formattedText ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_remaining)
                    views.setTextViewText(R.id.tv_remaining_value, formattedText)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatRemaining(state: EtaState): String {
        if (!state.hasRoute || state.remainingSeconds < 0) return state.status

        val totalMin = state.remainingSeconds / 60L
        val hours    = totalMin / 60L
        val mins     = totalMin % 60L
        val time     = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        // "~" indica che il GPS non è disponibile: l'ETA è stima per dead reckoning
        return if (state.gpsStale) "~$time" else time
    }
}
