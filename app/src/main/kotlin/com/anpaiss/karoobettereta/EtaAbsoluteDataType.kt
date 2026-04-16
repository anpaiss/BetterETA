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
            etaState.collect { state ->
                val value = if (state.remainingSeconds >= 0) state.remainingSeconds / 60.0 else -1.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value))
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
            etaState.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.view_eta_absolute)
                views.setTextViewText(R.id.tv_eta_value, formatEta(state))
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatEta(state: EtaState): String {
        if (!state.hasRoute || state.remainingSeconds < 0) return "--:--"
        val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
        val cal = Calendar.getInstance().apply { timeInMillis = arrivalMs }
        return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
