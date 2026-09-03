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
import android.util.Log
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
 * Dashboard "Da qui al traguardo" — mostra in un'unica pagina:
 *   - Orario di arrivo stimato (GRANDE)
 *   - Tempo rimanente
 *   - Km mancanti (GRANDE)
 *   - Dislivello positivo rimanente (GRANDE)
 *   - Velocità media prevista sul tratto rimanente
 *   - Delta vs prima stima del giro (verde = in anticipo, rosso = in ritardo)
 */
class EtaMasterDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "eta-master") {

    companion object {
        // The dashboard carries fast-moving numbers (distance, predicted average),
        // so it redraws more often than the single-value fields — but still far
        // less often than the 1 Hz at which the engine publishes state.
        private const val REDRAW_THROTTLE_MS = 5_000L
    }

    /**
     * Exactly what ends up on screen. Rendering goes through this so redraws can
     * be gated on the displayed text changing rather than on the engine ticking.
     */
    private data class MasterView(
        val eta: String,
        val remaining: String,
        val km: String,
        val ascent: String,
        val avgSpeed: String,
        val delta: String,
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
        Log.i("BetterETA", "MASTER startView called: gridSize=${config.gridSize}, viewSize=${config.viewSize}, preview=${config.preview}")
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
                    views.setTextViewText(R.id.tv_master_remaining, view.remaining)
                    views.setTextViewText(R.id.tv_master_km, view.km)
                    views.setTextViewText(R.id.tv_master_ascent, view.ascent)
                    views.setTextViewText(R.id.tv_master_avgspeed, view.avgSpeed)
                    views.setTextViewText(R.id.tv_master_delta, view.delta)
                    for (id in intArrayOf(R.id.tv_master_lbl_eta, R.id.tv_master_lbl_km,
                            R.id.tv_master_lbl_ascent, R.id.tv_master_lbl_avgspeed, R.id.tv_master_lbl_delta))
                        views.setTextColor(id, c.label)
                    for (id in intArrayOf(R.id.tv_master_eta, R.id.tv_master_remaining,
                            R.id.tv_master_km, R.id.tv_master_ascent, R.id.tv_master_avgspeed))
                        views.setTextColor(id, c.value)
                    views.setTextColor(R.id.tv_master_delta, c.delta)
                    views.setInt(R.id.tv_master_div1, "setBackgroundColor", c.label)
                    views.setInt(R.id.tv_master_div2, "setBackgroundColor", c.label)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(context: Context, state: EtaState, style: EtaStyle): MasterView {
        // 1. ETA e tempo rimanente (header)
        val eta: String
        val remaining: String
        if (state.hasRoute && state.remainingSeconds >= 0) {
            val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
            val cal = Calendar.getInstance().apply { timeInMillis = arrivalMs }
            eta       = DateFormat.getTimeFormat(context).format(cal.time)
            remaining = formatRemaining(state.remainingSeconds)
        } else {
            eta       = "--:--"
            remaining = state.status
        }

        // 2. Dati al traguardo (grandi)
        val distStr = if (state.remainingDistanceM >= 0)
            "%.1f".format(state.remainingDistanceM / 1000.0) else "--.-"
        val elevStr = if (state.remainingElevationM >= 0)
            "${state.remainingElevationM.toInt()}" else "--"

        // 3. Velocità media prevista = distanza rimanente / tempo rimanente.
        // È la media implicata dall'ETA sul tratto davanti (non l'EMA del modello).
        val avgStr = if (state.remainingSeconds > 0 && state.remainingDistanceM >= 0)
            "%.1f".format(state.remainingDistanceM / state.remainingSeconds * 3.6)
            else "--"

        // 4. Delta vs prima stima del giro: positivo = arrivo più tardi del previsto
        val delta = state.deltaSeconds
        val pace = when {
            delta == null -> Pace.UNKNOWN
            delta > 60L   -> Pace.LATE
            delta < -60L  -> Pace.EARLY
            else          -> Pace.ON_PACE
        }

        return MasterView(
            eta        = eta,
            remaining  = remaining,
            km         = "$distStr km",
            ascent     = "$elevStr m",
            avgSpeed   = avgStr,
            delta      = formatDelta(delta),
            colors     = style.colorsFor(pace),
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
        return if (h > 0) "$sign${h}h ${m}m" else "$sign$m min"
    }
}
