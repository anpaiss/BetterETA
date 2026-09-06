package com.anpaiss.bettereta

import kotlin.math.roundToLong

/**
 * Larghezza dell'intervallo "±" mostrato accanto al tempo mancante: quanto
 * l'ETA può sbagliare, in secondi, secondo quello che il modello sa del
 * percorso che resta.
 *
 * È un errore relativo al tempo mancante (l'errore cresce con la strada da
 * fare) tarato sui giri veri con i log campione (`tools`, giro del 06/09/2026:
 * p68 dell'errore relativo 12 % con memoria tratti sul 60-90 % della route,
 * replay senza memoria 17 %). Tre correzioni:
 *  - la memoria per tratto stringe l'intervallo (tratti già percorsi, tempi noti);
 *  - il fallback fisico lo allarga (tratti su cui il modello non ha campioni);
 *  - una stima "~" (fuori route, non ancora agganciati) lo allarga ancora.
 * Sotto il minuto non ha senso: il pavimento è ±1 min. Taratura provvisoria,
 * da rivedere quando i giri con log saranno di più (vedi HANDOVER).
 */
object EtaUncertainty {
    const val BASE_REL         = 0.17
    const val MEMORY_GAIN      = 0.06   // con memoria sul 100 % della route: 0.11
    const val FALLBACK_PENALTY = 0.10   // tutto fallback: +0.10
    const val APPROX_PENALTY   = 0.10
    const val FLOOR_S          = 60L

    /** Secondi di incertezza; -1 se [remainingSec] non è una stima valida. */
    fun seconds(remainingSec: Long, memoryShare: Double, fallbackShare: Double, approx: Boolean): Long {
        if (remainingSec < 0) return -1L
        val rel = BASE_REL -
            MEMORY_GAIN * memoryShare.coerceIn(0.0, 1.0) +
            FALLBACK_PENALTY * fallbackShare.coerceIn(0.0, 1.0) +
            (if (approx) APPROX_PENALTY else 0.0)
        return (remainingSec * rel).roundToLong().coerceAtLeast(FLOOR_S)
    }
}
