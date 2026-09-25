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
 * Campo grafico — PACE VS PLAN da solo: scarto del tempo di percorrenza
 * rispetto alla prima stima del giro ("+4m" = arrivo più tardi del previsto,
 * "-4m" = prima). Stesso dato e stessi colori della terza riga della dashboard.
 */
class EtaPaceDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-pace") {

    companion object {
        // Minute resolution: the throttle is a ceiling on redraws, not a sampling rate.
        private const val REDRAW_THROTTLE_MS = 20_000L
    }

    private data class PaceView(val text: String, val colors: FieldColors)

    // Fallback numerico: minuti di scarto (verso zero); 0 senza baseline
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            etaState
                .map { state -> state.deltaSeconds?.let { it / 60L } ?: 0L }
                .distinctUntilChanged()
                .collect { mins ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to mins.toDouble()))
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
            combine(etaState, prefs.styleFlow()) { state, style -> render(state, style) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    val c = view.colors
                    val views = RemoteViews(context.packageName, R.layout.view_eta_pace)
                    if (c.bgRes != null) views.setInt(R.id.root_pace, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_pace, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_pace_label, c.label)
                    views.setTextColor(R.id.tv_pace_value, c.delta)
                    views.setTextViewText(R.id.tv_pace_value, view.text)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun render(state: EtaState, style: EtaStyle): PaceView =
        PaceView(formatSignedMinutes(state.deltaSeconds), style.colorsFor(paceOf(state.deltaSeconds)))
}

/** Stato pace dal delta vs prima stima: oltre un minuto in più = LATE, in meno = EARLY. */
fun paceOf(deltaSeconds: Long?): Pace = when {
    deltaSeconds == null -> Pace.UNKNOWN
    deltaSeconds > 60L   -> Pace.LATE
    deltaSeconds < -60L  -> Pace.EARLY
    else                 -> Pace.ON_PACE
}

/** "+4m", "-1h 5m", "--" se null: minuti con segno, come li mostra la dashboard. */
fun formatSignedMinutes(seconds: Long?): String {
    if (seconds == null) return "--"
    val sign = if (seconds < 0) "-" else "+"
    val abs  = kotlin.math.abs(seconds)
    val h = abs / 3600
    val m = (abs % 3600) / 60
    return if (h > 0) "$sign${h}h ${m}m" else "$sign${m}m"
}
