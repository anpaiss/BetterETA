package com.anpaiss.karoobettereta

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
import kotlinx.coroutines.flow.StateFlow
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
) : DataTypeImpl(extension, "eta-absolute") {

    // Fallback numerico: minuti rimanenti
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            etaState
                .map { if (it.remainingSeconds >= 0) it.remainingSeconds / 60L else -1L }
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
            
            // Aggiorniamo la UI solo quando il testo visualizzato cambia effettivamente (es. cambio minuto)
            etaState
                .map { formatEta(context, it) }
                .distinctUntilChanged()
                .collect { formattedText ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_absolute)
                    views.setTextViewText(R.id.tv_eta_value, formattedText)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatEta(context: Context, state: EtaState): String {
        if (!state.hasRoute || state.remainingSeconds < 0) return state.status
        
        val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
        val cal = Calendar.getInstance().apply { timeInMillis = arrivalMs }
        
        return DateFormat.getTimeFormat(context).format(cal.time)
    }
}
