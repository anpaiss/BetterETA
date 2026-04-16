package com.anpaiss.karoobettereta

import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Simula un'uscita in bici sul percorso GPX campione (assets/sample_route.gpx).
 *
 * Utile per verificare EtaEngine e AdaptiveSpeedModel senza un Karoo fisico.
 * Si avvia su qualsiasi emulatore Android o smartphone.
 *
 * Uso:
 *   1. Clicca "Avvia Simulazione"
 *   2. Regola la velocità con lo slider durante la simulazione
 *   3. Osserva ORA ARRIVO e RIMANENTE aggiornarsi ogni secondo
 */
class SimulatorActivity : AppCompatActivity() {

    private val pipeline   = SegmentPipeline()
    private val speedModel = AdaptiveSpeedModel()
    private val etaEngine  = EtaEngine(speedModel, pipeline)

    private var simJob: Job? = null
    private var totalDistanceM = 0.0
    private var traveledM = 0.0

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simulator)

        val tvRouteInfo    = findViewById<TextView>(R.id.tvRouteInfo)
        val sbSpeed        = findViewById<SeekBar>(R.id.sbSpeed)
        val tvSpeedValue   = findViewById<TextView>(R.id.tvSpeedValue)
        val btnStartStop   = findViewById<Button>(R.id.btnStartStop)
        val pbRoute        = findViewById<ProgressBar>(R.id.pbRoute)
        val tvProgress     = findViewById<TextView>(R.id.tvProgress)
        val tvGrade        = findViewById<TextView>(R.id.tvGrade)
        val tvEtaAbsolute  = findViewById<TextView>(R.id.tvEtaAbsolute)
        val tvEtaRemaining = findViewById<TextView>(R.id.tvEtaRemaining)

        // Carica percorso da assets in background
        lifecycleScope.launch {
            val waypoints = try {
                assets.open("sample_route.gpx").use { RouteReader().parseFromStream(it) }
            } catch (e: Exception) {
                emptyList()
            }
            if (waypoints.isEmpty()) {
                tvRouteInfo.text = "Errore: percorso non trovato in assets/"
                return@launch
            }
            pipeline.setRoute(waypoints)
            totalDistanceM = pipeline.remainingSegments().sumOf { it.distanceM }
            tvRouteInfo.text = "${waypoints.size} waypoint  ·  %.1f km  ·  pronto".format(totalDistanceM / 1000.0)
        }

        // Slider velocità: 0-100 → 0-50 km/h (step 0.5)
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvSpeedValue.text = "${progress / 2} km/h"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        tvSpeedValue.text = "${sbSpeed.progress / 2} km/h"

        btnStartStop.setOnClickListener {
            if (simJob?.isActive == true) {
                // Pausa
                simJob?.cancel()
                btnStartStop.text = "Riprendi"
            } else {
                if (totalDistanceM == 0.0) {
                    Toast.makeText(this, "Percorso non ancora caricato", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val isFirstRun = btnStartStop.text == "Avvia Simulazione" || btnStartStop.text == "Ricomincia"
                btnStartStop.text = "Pausa"

                simJob = lifecycleScope.launch {
                    // Prima esecuzione o riavvio dopo completamento
                    if (isFirstRun) {
                        speedModel.reset()
                        pipeline.reset()
                        traveledM = 0.0
                    }
                    while (pipeline.hasSegments()) {
                        val speedKmh = (sbSpeed.progress / 2.0).coerceAtLeast(1.0)
                        val speedMs  = speedKmh / 3.6
                        val gradePct = pipeline.remainingSegments().firstOrNull()?.gradePct ?: 0.0

                        speedModel.update(speedMs = speedMs, gradePct = gradePct)
                        pipeline.advanceByDistance(speedMs)   // 1 secondo × speedMs m/s
                        traveledM += speedMs

                        val remainingSec = etaEngine.computeRemainingSeconds()
                        val progress     = if (totalDistanceM > 0)
                            (traveledM / totalDistanceM * 1000).toInt().coerceIn(0, 1000)
                        else 0

                        pbRoute.progress = progress
                        tvProgress.text  = "%.1f / %.1f km".format(traveledM / 1000.0, totalDistanceM / 1000.0)
                        tvGrade.text     = "Pendenza: %+.1f%%".format(gradePct)

                        if (remainingSec > 0) {
                            val cal = Calendar.getInstance().apply {
                                add(Calendar.SECOND, remainingSec.toInt())
                            }
                            tvEtaAbsolute.text  = timeFmt.format(cal.time)
                            tvEtaRemaining.text = formatRemaining(remainingSec)
                        } else {
                            tvEtaAbsolute.text  = "--:--"
                            tvEtaRemaining.text = "--"
                        }

                        delay(1000L)
                    }

                    // Percorso completato
                    tvEtaAbsolute.text  = "Arrivato!"
                    tvEtaRemaining.text = "0m"
                    btnStartStop.text   = "Ricomincia"
                }
            }
        }
    }

    override fun onDestroy() {
        simJob?.cancel()
        super.onDestroy()
    }

    private fun formatRemaining(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
