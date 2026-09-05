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
 * Campo grafico — margine in minuti rispetto al mandatory time of arrival
 * ("+12 min" verde / "-5 min" rosso). "--" senza MTA impostato.
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

    private data class MarginView(val text: String, val label: String, val colors: FieldColors, val valueColor: Int)

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
            combine(etaState, prefs.styleFlow()) { state, style -> render(state, style) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    val c = view.colors
                    val views = RemoteViews(context.packageName, R.layout.view_eta_margin)
                    if (c.bgRes != null) views.setInt(R.id.root_margin, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_margin, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextColor(R.id.tv_margin_label, c.label)
                    views.setTextColor(R.id.tv_margin_value, view.valueColor)
                    views.setTextViewText(R.id.tv_margin_value, view.text)
                    views.setTextViewText(R.id.tv_margin_label, view.label)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun render(state: EtaState, style: EtaStyle): MarginView {
        val margin = state.marginSeconds
            ?: return MarginView("--", "MTA MARGIN", style.colorsFor(Pace.UNKNOWN), style.marginColor(Pace.UNKNOWN))
        val pace  = marginPace(margin)
        val label = if (pace == Pace.LATE) "LATE VS MTA" else "AHEAD OF MTA"
        return MarginView(ArrivalMargin.format(margin), label, style.colorsFor(pace), style.marginColor(pace))
    }
}

/** Colore del margine: verde finché non negativo, rosso oltre l'MTA. */
fun marginPace(marginSeconds: Long): Pace = if (marginSeconds < 0) Pace.LATE else Pace.EARLY
