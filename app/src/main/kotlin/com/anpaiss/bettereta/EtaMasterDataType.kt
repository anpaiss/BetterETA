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
 * Dashboard "Da qui al traguardo": tre righe grandi, didascalia a sinistra e
 * valore a destra (stesso disegno dei campi singoli):
 *   1. ETA        orario di arrivo stimato ("~" se stima grossolana)
 *   2. TO GO      tempo mancante secondo l'ETA; nella didascalia "±xm",
 *                 l'incertezza della stima (EtaUncertainty)
 *   3. senza MTA  PACE VS PLAN: scarto dalla prima stima del giro
 *      con MTA    "MTA hh:mm" + AHEAD/LATE e il margine in minuti
 * Il colore della terza riga segue lo stato (verde/rosso) secondo lo stile.
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
    }

    /**
     * Exactly what ends up on screen. Rendering goes through this so redraws can
     * be gated on the displayed text changing rather than on the engine ticking.
     */
    private data class MasterView(
        val eta: String,
        val remainingLabel: String,
        val remaining: String,
        /** Terza riga: didascalia piccola sopra, grande sotto ("PACE"/"VS PLAN" o "MTA"/ora) */
        val thirdTop: String,
        val thirdBottom: String,
        val third: String,
        /** Parola sotto il valore (AHEAD/LATE/MARGIN), vuota senza MTA */
        val thirdTag: String,
        val thirdColor: Int,
        /** Fondo della cella del valore; 0 = trasparente (senza MTA) */
        val thirdBg: Int,
        val colors: FieldColors,
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
                    val views = RemoteViews(context.packageName, R.layout.view_eta_master)
                    if (c.bgRes != null) views.setInt(R.id.root_master, "setBackgroundResource", c.bgRes)
                    else views.setInt(R.id.root_master, "setBackgroundColor", c.bgColor ?: 0)
                    views.setTextViewText(R.id.tv_master_eta, view.eta)
                    views.setTextViewText(R.id.tv_master_lbl_remaining, view.remainingLabel)
                    views.setTextViewText(R.id.tv_master_remaining, view.remaining)
                    views.setTextViewText(R.id.tv_master_lbl_third, view.thirdTop)
                    views.setTextViewText(R.id.tv_master_third_sub, view.thirdBottom)
                    views.setTextViewText(R.id.tv_master_third, view.third)
                    views.setTextViewText(R.id.tv_master_third_tag, view.thirdTag)
                    for (id in intArrayOf(R.id.tv_master_lbl_eta, R.id.tv_master_lbl_remaining, R.id.tv_master_lbl_third))
                        views.setTextColor(id, c.label)
                    views.setTextColor(R.id.tv_master_third_sub, c.value)
                    views.setTextColor(R.id.tv_master_eta, c.value)
                    views.setTextColor(R.id.tv_master_remaining, c.value)
                    views.setTextColor(R.id.tv_master_third, view.thirdColor)
                    views.setTextColor(R.id.tv_master_third_tag, view.thirdColor)
                    views.setInt(R.id.cell_master_third, "setBackgroundColor", view.thirdBg)
                    views.setInt(R.id.tv_master_div1, "setBackgroundColor", c.label)
                    views.setInt(R.id.tv_master_div2, "setBackgroundColor", c.label)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable {
            BetterEtaLogger.shared?.log("VIEW ", "stop $dataTypeId")
            scope.cancel()
        }
    }

    private fun render(context: Context, state: EtaState, style: EtaStyle): MasterView {
        val timeFmt = DateFormat.getTimeFormat(context)

        // 1-2. ETA e tempo mancante, con l'incertezza nella didascalia
        val eta: String
        val remaining: String
        val remainingLabel: String
        if (state.hasRoute && state.remainingSeconds >= 0) {
            val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
            val cal = Calendar.getInstance().apply { timeInMillis = arrivalMs }
            eta            = (if (state.estimateApprox) "~" else "") + timeFmt.format(cal.time)
            remaining      = formatRemaining(state.remainingSeconds)
            remainingLabel = if (state.uncertaintySeconds >= 0)
                "TO GO\n±${(state.uncertaintySeconds + 30) / 60}m" else "TO GO"
        } else {
            eta            = "--:--"
            remaining      = state.status
            remainingLabel = "TO GO"
        }

        // 3. Delta vs prima stima del giro: positivo = arrivo più tardi del previsto
        val delta = state.deltaSeconds
        val pace = when {
            delta == null -> Pace.UNKNOWN
            delta > 60L   -> Pace.LATE
            delta < -60L  -> Pace.EARLY
            else          -> Pace.ON_PACE
        }
        val colors = style.colorsFor(pace)

        // Con un MTA la terza riga diventa MTA + margine: a sinistra "MTA" e l'ora,
        // a destra i minuti su una cella verde/rossa con la parola AHEAD/LATE, che
        // dice il segno anche dove il colore non basta (pieno sole, stili chiari).
        val thirdTop: String
        val thirdBottom: String
        val third: String
        val thirdTag: String
        val thirdColor: Int
        val thirdBg: Int
        if (state.mtaEpochMs > 0L) {
            val margin     = state.marginSeconds
            val marginPace = margin?.let(::marginPace) ?: Pace.UNKNOWN
            val cell       = style.marginCell(marginPace)
            thirdTop    = "MTA"
            thirdBottom = timeFmt.format(Calendar.getInstance().apply { timeInMillis = state.mtaEpochMs }.time)
            third       = formatDelta(margin)
            thirdTag    = when (marginPace) {
                Pace.EARLY -> "AHEAD"
                Pace.LATE  -> "LATE"
                else       -> "MARGIN"
            }
            thirdBg     = cell.first
            thirdColor  = cell.second
        } else {
            thirdTop    = "PACE"
            thirdBottom = "VS PLAN"
            third       = formatDelta(delta)
            thirdTag    = ""
            thirdBg     = 0
            thirdColor  = colors.delta
        }

        return MasterView(
            eta            = eta,
            remainingLabel = remainingLabel,
            remaining      = remaining,
            thirdTop       = thirdTop,
            thirdBottom    = thirdBottom,
            third          = third,
            thirdTag       = thirdTag,
            thirdColor     = thirdColor,
            thirdBg        = thirdBg,
            colors         = colors,
        )
    }

    private fun formatRemaining(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun formatDelta(seconds: Long?): String {
        if (seconds == null) return "--"
        val sign = if (seconds < 0) "-" else "+"
        val abs  = kotlin.math.abs(seconds)
        val h = abs / 3600
        val m = (abs % 3600) / 60
        return if (h > 0) "$sign${h}h ${m}m" else "$sign${m}m"
    }
}
