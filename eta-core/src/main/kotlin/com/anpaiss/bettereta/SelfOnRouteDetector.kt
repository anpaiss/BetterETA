package com.anpaiss.bettereta

import kotlin.math.abs

/**
 * "In route" deciso dal nostro GPS, per quando il Karoo dice fuori route a
 * torto: difetto noto del Karoo ("riders stuck in an off-route state",
 * dichiarato corretto nella 1.656.2526 del 17/09). Nei giri del 27/09 il
 * Karoo aveva invece ragione (replay sul FIT: mai entro 30 m dalla traccia),
 * e questo rilevatore non si sarebbe attivato.
 *
 * Conferma dopo [confirmMs] continui con la traccia entro la distanza usata
 * dal chiamante (una posizione lungo la route a ogni tick) e un avanzamento
 * di almeno [minProgressM] nel verso della route: esclude gli incroci e i
 * passaggi paralleli brevi, il senso contrario e il fermo. Una volta
 * confermato resta in route anche da fermo; lo perde dopo [loseMs] senza
 * traccia vicina. Un salto oltre [maxStepM] tra due tick (cambio di ramo,
 * GPS che salta) fa ripartire la conferma.
 */
class SelfOnRouteDetector(
    private val confirmMs: Long = 30_000L,
    private val loseMs: Long = 20_000L,
    private val minProgressM: Double = 50.0,
    private val maxStepM: Double = 100.0,
) {
    var onRoute = false
        private set

    /** Ultima posizione lungo la route agganciata dal GPS; null senza aggancio recente. */
    var lastAlongM: Double? = null
        private set

    private var runStartMs = -1L
    private var runStartAlongM = 0.0
    private var lastMatchMs = -1L

    @Synchronized
    fun reset() {
        onRoute = false
        lastAlongM = null
        runStartMs = -1L
        lastMatchMs = -1L
    }

    /** Un tick: [alongM] = posizione agganciata, null se la traccia è lontana o il GPS manca. */
    @Synchronized
    fun onTick(nowMs: Long, alongM: Double?): Boolean {
        if (alongM == null) {
            if (onRoute) {
                if (nowMs - lastMatchMs >= loseMs) reset()
            } else {
                // La conferma vuole una serie continua
                runStartMs = -1L
                lastAlongM = null
            }
            return onRoute
        }
        val prev = lastAlongM
        // Salto (cambio di ramo) o avanzamento all'indietro sul ramo scelto
        // (andata e ritorno sovrapposti): la conferma riparte da qui
        val backwards = runStartMs >= 0 && alongM - runStartAlongM < -minProgressM
        if (runStartMs < 0 || backwards || (prev != null && abs(alongM - prev) > maxStepM)) {
            runStartMs = nowMs
            runStartAlongM = alongM
        }
        lastAlongM = alongM
        lastMatchMs = nowMs
        if (!onRoute && nowMs - runStartMs >= confirmMs && alongM - runStartAlongM >= minProgressM) {
            onRoute = true
        }
        return onRoute
    }
}
