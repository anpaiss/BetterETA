package com.anpaiss.bettereta

import android.content.Context
import android.graphics.Color
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
        val deltaColor: Int,
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
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            etaState
                .map { render(context, it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    val views = RemoteViews(context.packageName, R.layout.view_eta_master)
                    views.setTextViewText(R.id.tv_master_eta, view.eta)
                    views.setTextViewText(R.id.tv_master_remaining, view.remaining)
                    views.setTextViewText(R.id.tv_master_km, view.km)
                    views.setTextViewText(R.id.tv_master_ascent, view.ascent)
                    views.setTextViewText(R.id.tv_master_avgspeed, view.avgSpeed)
                    views.setTextViewText(R.id.tv_master_delta, view.delta)
                    views.setTextColor(R.id.tv_master_delta, view.deltaColor)
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(context: Context, state: EtaState): MasterView {
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
        val deltaColor = when {
            delta == null -> Color.parseColor("#888888")
            delta > 60L   -> Color.RED
            delta < -60L  -> Color.parseColor("#4CAF50")
            else          -> Color.parseColor("#FF5823")
        }

        return MasterView(
            eta        = eta,
            remaining  = remaining,
            km         = "$distStr km",
            ascent     = "$elevStr m",
            avgSpeed   = avgStr,
            delta      = formatDelta(delta),
            deltaColor = deltaColor,
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
