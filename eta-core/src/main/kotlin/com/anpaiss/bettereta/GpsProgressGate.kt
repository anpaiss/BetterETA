package com.anpaiss.bettereta

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * "Sto davvero pedalando?" senza una route, dal solo GPS. Apre l'apprendimento
 * nei giri liberi, dove [ProgressGate] non può lavorare (misura l'avanzamento
 * sulla route).
 *
 * Due controlli sull'ultimo minuto, entrambi necessari:
 *  1. spostamento netto tra il fix di [windowTicks] tick fa e quello attuale
 *     di almeno [minNetM]: il GPS che balla in casa (notte del 04/09: speed
 *     0,5–4,8 m/s per otto ore) resta entro pochi metri;
 *  2. spostamento netto non oltre [maxRatio] × la distanza integrata dalla
 *     velocità nello stesso minuto (più un margine): un salto del fix di
 *     centinaia di metri con velocità quasi nulla è un teletrasporto, non
 *     una pedalata. Pedalando, il netto è sempre ≤ dell'integrato (curve).
 *
 * Finché la finestra non è piena risponde false: nei giri liberi non c'è
 * fretta, e un minuto di campioni persi non pesa.
 */
class GpsProgressGate(
    private val windowTicks: Int = 60,
    private val minNetM: Double = 20.0,
    private val maxRatio: Double = 2.0,
    private val ratioMarginM: Double = 30.0,
) {
    private val lat  = DoubleArray(windowTicks + 1) { Double.NaN }
    private val lon  = DoubleArray(windowTicks + 1)
    private val dist = DoubleArray(windowTicks + 1)   // distanza integrata cumulativa
    private var idx = 0
    private var cumDistM = 0.0

    /** Registra il fix e la velocità di questo tick; true se si sta davvero avanzando. */
    fun onTick(latDeg: Double, lonDeg: Double, speedMs: Double, dtSec: Double = 1.0): Boolean {
        cumDistM += speedMs.coerceAtLeast(0.0) * dtSec
        val oldLat = lat[idx]; val oldLon = lon[idx]; val oldDist = dist[idx]
        lat[idx] = latDeg; lon[idx] = lonDeg; dist[idx] = cumDistM
        idx = (idx + 1) % lat.size
        if (oldLat.isNaN()) return false
        val net = approxDistM(oldLat, oldLon, latDeg, lonDeg)
        val integrated = cumDistM - oldDist
        return net >= minNetM && net <= maxRatio * integrated + ratioMarginM
    }

    fun reset() {
        lat.fill(Double.NaN)
        idx = 0
        cumDistM = 0.0
    }

    private fun approxDistM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }
}
