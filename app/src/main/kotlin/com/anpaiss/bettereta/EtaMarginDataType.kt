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
 * Campo grafico — MTA e margine, come la terza riga della dashboard: "MTA" e
 * l'ora a sinistra, a destra il margine ("+12m" / "-5m") su una cella verde o
 * rossa con AHEAD/LATE sotto. "--:--" e "--" senza MTA impostato.
 */
class EtaMarginDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-margin") {

    companion object {
        // Minute resolution: the throttle is a ceiling on redraws, not a sampling rate.
        private const val REDRAW_THROTTLE_MS = 20_000L
    }

    private data class MarginView(
        val time: String,
        val value: String,
        val tag: String,
        val cellBg: Int,
        val cellText: Int,
        val colors: FieldColors,
    )

    // Fallback numerico: minuti di margine (verso zero); -1 senza MTA
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            etaState
                .map { state -> state.marginSeconds?.let { it / 60L } ?: -1L }
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
            combine(etaState, prefs.styleFlow()) { state, style -> render(context, state, style) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    val c = view.colors
                    val views = RemoteViews(context.packageName, R.layout.view_eta_margin)
                    if (c.bgRes != null) views.setInt(R.id.root_margin, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_margin, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_margin_label, c.label)
                    views.setTextColor(R.id.tv_margin_time, c.value)
                    views.setTextViewText(R.id.tv_margin_time, view.time)
                    views.setTextViewText(R.id.tv_margin_value, view.value)
                    views.setTextViewText(R.id.tv_margin_tag, view.tag)
                    views.setTextColor(R.id.tv_margin_value, view.cellText)
                    views.setTextColor(R.id.tv_margin_tag, view.cellText)
                    views.setInt(R.id.cell_margin, "setBackgroundColor", view.cellBg)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun render(context: Context, state: EtaState, style: EtaStyle): MarginView {
        if (state.mtaEpochMs <= 0L) {
            val c = style.colorsFor(Pace.UNKNOWN)
            return MarginView("--:--", "--", "", 0, c.delta, c)
        }
        val time   = DateFormat.getTimeFormat(context)
            .format(Calendar.getInstance().apply { timeInMillis = state.mtaEpochMs }.time)
        val margin = state.marginSeconds
        val pace   = margin?.let(::marginPace) ?: Pace.UNKNOWN
        val cell   = style.marginCell(pace)
        val tag    = when (pace) {
            Pace.EARLY -> "AHEAD"
            Pace.LATE  -> "LATE"
            else       -> "MARGIN"
        }
        return MarginView(time, formatSignedMinutes(margin), tag, cell.first, cell.second, style.colorsFor(pace))
    }
}

/** Colore del margine: verde finché non negativo, rosso oltre l'MTA. */
fun marginPace(marginSeconds: Long): Pace = if (marginSeconds < 0) Pace.LATE else Pace.EARLY
