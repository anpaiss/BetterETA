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
 *   - Stato dei bucket di velocità (per feedback modello)
 */
class EtaMasterDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
) : DataTypeImpl(extension, "eta-master") {

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
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            var emitCount = 0
            etaState.collect { state ->
                emitCount++
                if (emitCount <= 3 || emitCount % 30 == 0) {
                    Log.i("BetterETA", "MASTER updateView #$emitCount: hasRoute=${state.hasRoute}, status=${state.status}, secs=${state.remainingSeconds}")
                }
                val views = RemoteViews(context.packageName, R.layout.view_eta_master)
                
                // 1. ETA e Tempo Rimanente (Header)
                if (state.hasRoute && state.remainingSeconds >= 0) {
                    val arrivalMs = System.currentTimeMillis() + state.remainingSeconds * 1000L
                    val cal = Calendar.getInstance().apply { timeInMillis = arrivalMs }
                    val timeFmt = DateFormat.getTimeFormat(context)
                    
                    views.setTextViewText(R.id.tv_master_eta, timeFmt.format(cal.time))
                    views.setTextViewText(R.id.tv_master_remaining, formatRemaining(state.remainingSeconds))
                } else {
                    views.setTextViewText(R.id.tv_master_eta, "--:--")
                    views.setTextViewText(R.id.tv_master_remaining, state.status)
                }

                // 2. Dati al traguardo (Grandi)
                val distStr = if (state.remainingDistanceM >= 0) 
                    "%.1f".format(state.remainingDistanceM / 1000.0) else "--.-"
                val elevStr = if (state.remainingElevationM >= 0) 
                    "${state.remainingElevationM.toInt()}" else "--"
                
                views.setTextViewText(R.id.tv_master_km, "$distStr km")
                views.setTextViewText(R.id.tv_master_ascent, "$elevStr m")

                // 3. Velocità media prevista = distanza rimanente / tempo rimanente.
                // È la media implicata dall'ETA sul tratto davanti (non l'EMA del modello).
                val avgStr = if (state.remainingSeconds > 0 && state.remainingDistanceM >= 0)
                    "%.1f km/h".format(state.remainingDistanceM / state.remainingSeconds * 3.6)
                    else "-- km/h"
                views.setTextViewText(R.id.tv_master_avgspeed, avgStr)

                // 4. Footer: Status e GPS
                views.setTextViewText(R.id.tv_status_msg, state.status)
                val gpsText = if (state.gpsStale) "GPS STALE / DR" else "GPS ACTIVE"
                val gpsColor = if (state.gpsStale) Color.RED else Color.parseColor("#FF5823")
                views.setTextViewText(R.id.tv_gps_status, gpsText)
                views.setTextColor(R.id.tv_gps_status, gpsColor)

                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatRemaining(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
