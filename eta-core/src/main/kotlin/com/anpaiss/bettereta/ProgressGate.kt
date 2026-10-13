package com.anpaiss.bettereta

import kotlin.math.abs

/**
 * Dice se il ciclista sta davvero avanzando lungo la route, guardando la
 * posizione stimata di [windowTicks] tick fa.
 *
 * La velocità da sola non basta: la notte del 04/09 il Karoo, fermo in casa
 * con il GPS che ballava, ha riportato 0,5–4,8 m/s per otto ore restando in
 * Recording, e il bucket "piano" del modello è sceso da 6,0 a 1,1 m/s. Da
 * fermo lo snap GPS riporta la posizione indietro a ogni tick, quindi
 * l'avanzamento netto sulla route in un minuto resta sotto pochi metri;
 * pedalando anche a passo d'uomo si superano i 20 m.
 *
 * Finché la finestra non è piena (inizio giro, cambio route) risponde true:
 * un minuto di campioni eventualmente cattivi pesa nulla contro la finestra
 * del modello.
 */
class ProgressGate(
    private val windowTicks: Int = 60,
    private val minProgressM: Double = 20.0,
) {
    private val ring = DoubleArray(windowTicks + 1) { Double.NaN }
    private var idx = 0

    /** Registra la posizione lungo la route di questo tick e dice se si sta avanzando. */
    fun onTick(alongRouteM: Double): Boolean {
        val oldest = ring[idx]          // posizione di windowTicks tick fa
        ring[idx] = alongRouteM
        idx = (idx + 1) % ring.size
        return oldest.isNaN() || abs(alongRouteM - oldest) >= minProgressM
    }

    fun reset() {
        ring.fill(Double.NaN)
        idx = 0
    }
}
