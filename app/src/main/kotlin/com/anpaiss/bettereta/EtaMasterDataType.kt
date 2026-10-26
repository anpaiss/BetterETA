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
 * Dashboard "Da qui al traguardo", nello stile di BigFields (25/09/2026): due
 * righe uguali, didascalia fissa in alto e numero grande disegnato da BigNumber.
 *   1. ETA     orario di arrivo stimato ("~" se stima grossolana); con un MTA
 *              la didascalia porta anche l'MTA e il margine
 *   2. TO GO   tempo mancante (h:mm); nella didascalia "±xm", l'incertezza
 *              della stima (EtaUncertainty)
 * Il pace vs plan non è più sulla dashboard: resta nei log e nel campo singolo
 * "PACE VS PLAN". Il colore della pagina segue il margine quando c'è un MTA,
 * altrimenti è quello neutro dello stile.
 */
class EtaMasterDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-master") {

    companion object {
        // The dashboard carries the remaining time and the margin, which move by
        // the minute — still far less often than the 1 Hz at which the engine
        // publishes state.
        private const val REDRAW_THROTTLE_MS = 5_000L

        // Larghezze tipiche dei due numeri: fissano il corpo delle cifre
        private const val ETA_TEMPLATE = "88:88"
        private const val TOGO_TEMPLATE = "8:88"
    }

    /**
     * Exactly what ends up on screen. Rendering goes through this so redraws can
     * be gated on the displayed text changing rather than on the engine ticking.
     */
    private data class MasterView(
        val etaLabel: String,
        val eta: String,
        val togoLabel: String,
        val togo: String,
        val colors: FieldColors,
        /** Fascia libera in fondo per il banner "next cue": solo con una route in navigazione */
        val band: Boolean,
    )

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            var lastValidSecs = -1.0
            etaState
                .map { state ->
                    if (state.remainingSeconds >= 0) lastValidSecs = state.remainingSeconds.toDouble()
                    lastValidSecs   // -1.0 finché non c'è un valore valido, poi congelato
                }
                .distinctUntilChanged()
                .collect { secs ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to secs))
                        )
                    )
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        BetterEtaLogger.shared?.log("VIEW ", "startView $dataTypeId size=${config.viewSize} preview=${config.preview}")
        BigNumber.init(context)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = Prefs(context)
        val res = context.resources
        val padH = res.getDimensionPixelSize(R.dimen.master_pad_h)
        val padV = res.getDimensionPixelSize(R.dimen.master_pad_top) + res.getDimensionPixelSize(R.dimen.master_pad_bottom)
        val labelRow = res.getDimensionPixelSize(R.dimen.master_label_row)
        val gap = res.getDimensionPixelSize(R.dimen.master_row_gap)
        val bandPx = res.getDimensionPixelSize(R.dimen.master_band)
        val (cellW, cellH) = config.viewSize
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            combine(etaState.orSample(config.preview), prefs.styleFlow()) { state, style -> render(context, state, style) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, preview = config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    // Area del numero di ogni riga e cifre alte uguali nelle due righe
                    val rowH = (cellH - (if (view.band) bandPx else 0) - gap) / 2
                    val w = cellW - 2 * padH
                    val h = rowH - padV - labelRow
                    val digitH = minOf(BigNumber.maxDigitHeight(ETA_TEMPLATE, w, h),
                        BigNumber.maxDigitHeight(TOGO_TEMPLATE, w, h))
                    val c = view.colors
                    val views = RemoteViews(context.packageName,
                        if (view.band) R.layout.view_eta_master_band else R.layout.view_eta_master)
                    for (row in intArrayOf(R.id.row_eta, R.id.row_togo)) {
                        if (c.bgRes != null) views.setInt(row, "setBackgroundResource", c.bgRes)
                        else views.setInt(row, "setBackgroundColor", c.bgColor ?: 0)
                    }
                    views.setTextViewText(R.id.tv_lbl_eta, view.etaLabel)
                    views.setTextViewText(R.id.tv_lbl_togo, view.togoLabel)
                    views.setTextColor(R.id.tv_lbl_eta, c.label)
                    views.setTextColor(R.id.tv_lbl_togo, c.label)
                    views.setImageViewBitmap(R.id.iv_eta, BigNumber.render(view.eta, w, h, digitH))
                    views.setImageViewBitmap(R.id.iv_togo, BigNumber.render(view.togo, w, h, digitH))
                    views.setInt(R.id.iv_eta, "setColorFilter", c.value)
                    views.setInt(R.id.iv_togo, "setColorFilter", c.value)
                    try {
                        emitter.updateView(views)
                    } catch (e: Exception) {
                        BetterEtaLogger.shared?.logError("VIEW ", "Disegno della dashboard fallito", e)
                    }
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun render(context: Context, state: EtaState, style: EtaStyle): MasterView {
        val timeFmt = DateFormat.getTimeFormat(context)
        val hasEstimate = state.hasRoute && state.remainingSeconds >= 0

        val eta: String
        val togo: String
        val togoLabel: String
        if (hasEstimate) {
            val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
            eta       = (if (state.estimateApprox) "~" else "") +
                timeFmt.format(Calendar.getInstance().apply { timeInMillis = arrivalMs }.time)
            togo      = formatRemaining(state.remainingSeconds)
            // "PLAN": route aperta e giro non partito, stima dell'intera route
            val what  = if (state.status == "Plan") "PLAN" else "TO GO"
            togoLabel = if (state.uncertaintySeconds >= 0)
                "$what  ±${(state.uncertaintySeconds + 30) / 60}m" else what
        } else {
            eta       = "--:--"
            togo      = state.status
            togoLabel = "TO GO"
        }

        // Con un MTA la didascalia dell'ETA porta ora e margine, e il colore della
        // pagina segue il margine (verde in anticipo, rosso in ritardo)
        val etaLabel: String
        val colors: FieldColors
        if (state.mtaEpochMs > 0L) {
            val mta = timeFmt.format(Calendar.getInstance().apply { timeInMillis = state.mtaEpochMs }.time)
            // Senza "ETA ·": con un margine di un'ora la riga superava i 462 px (review 27/09)
            etaLabel = "MTA $mta  ${formatSignedMinutes(state.marginSeconds)}"
            colors   = style.colorsFor(state.marginSeconds?.let(::marginPace) ?: Pace.UNKNOWN)
        } else {
            etaLabel = "ETA"
            colors   = style.colorsFor(null)
        }

        return MasterView(etaLabel, eta, togoLabel, togo, colors, band = state.hasRoute)
    }

    /** h:mm, come i campi di BigFields: "0:58", "2:05". */
    private fun formatRemaining(seconds: Long): String {
        val min = (seconds + 30) / 60
        return "%d:%02d".format(min / 60, min % 60)
    }
}
