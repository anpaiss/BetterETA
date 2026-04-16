package com.anpaiss.karoobettereta

import android.content.Context
import android.graphics.Color
import android.text.format.DateFormat
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
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
 * Pagina Dashboard (Master) - Versione 2.
 * Mostra tutti i dati dell'ETA, lo stato del modello adattivo e la qualità GPS.
 */
class EtaMasterDataType(
    extension: String,
    private val etaState: StateFlow<EtaState>,
    private val speedModel: AdaptiveSpeedModel
) : DataTypeImpl(extension, "eta-master") {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Comunichiamo al Karoo di non mostrare l'header standard per avere più spazio
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        scope.launch {
            etaState.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.view_eta_master)

                // 1. Orario di arrivo e Tempo Rimanente
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

                // 2. Statistiche Modello Adattivo (Bucket)
                val snap = speedModel.snapshot()
                val labels = listOf("Discesa", "Piano", "Salita", "Ripida", "Muro")
                val ids = listOf(R.id.tv_b0, R.id.tv_b1, R.id.tv_b2, R.id.tv_b3, R.id.tv_b4)
                
                snap.bucketEmas.forEachIndexed { i, speedMs ->
                    val speedKmh = speedMs * 3.6
                    val count = snap.bucketCounts[i]
                    val isCurrent = i == bucketIndex(smoothedGradeFallback(speedMs)) // Logica semplificata per highlight
                    
                    val text = "${labels[i]}: %.1f km/h (%d pts)".format(speedKmh, count)
                    views.setTextViewText(ids[i], text)
                    
                    // Colore verde se abbiamo dati, grigio se vuoto
                    val color = if (count > 0) Color.GREEN else Color.LTGRAY
                    views.setTextColor(ids[i], color)
                }

                // 3. Footer: Status e GPS Snap
                views.setTextViewText(R.id.tv_status_msg, state.status)
                
                val gpsText = if (state.gpsStale) "STALE / DR" else "ACTIVE"
                val gpsColor = if (state.gpsStale) Color.RED else Color.CYAN
                views.setTextViewText(R.id.tv_gps_status, gpsText)
                views.setTextColor(R.id.tv_gps_status, gpsColor)

                emitter.updateView(views)
            }
        }

        emitter.setCancellable {
            scope.cancel()
        }
    }

    private fun formatRemaining(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    // Helper per determinare il bucket (solo per visualizzazione)
    private fun bucketIndex(grade: Double): Int = when {
        grade < -5.0 -> 0
        grade < 2.0  -> 1
        grade < 6.0  -> 2
        grade < 10.0 -> 3
        else         -> 4
    }

    private fun smoothedGradeFallback(speedMs: Double): Double = 0.0 // Semplificato per ora
}
