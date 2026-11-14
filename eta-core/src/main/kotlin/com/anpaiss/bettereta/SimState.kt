package com.anpaiss.bettereta

/**
 * Stato "simulazione KarooSim attiva" (GPS finto per le prove in casa), in cache:
 * il provider di KarooSim si rilegge al massimo ogni [refreshMs], il broadcast lo
 * aggiorna subito. Nessuna lettura riuscita o un errore = non attiva.
 * Senza Android: la lettura vera sta in [KarooSimState].
 */
class SimState(private val refreshMs: Long = REFRESH_MS) {

    @Volatile var active = false
        private set
    private var readAtMs = 0L       // 0 = mai letto
    private var reading = false

    /** True se è ora di rileggere il provider: la lettura è prenotata, va chiusa con [onRead]. */
    @Synchronized
    fun claimRefresh(nowMs: Long): Boolean {
        if (reading || (readAtMs > 0L && nowMs - readAtMs < refreshMs)) return false
        reading = true
        return true
    }

    /** Esito della lettura del provider; null = KarooSim assente o errore. */
    @Synchronized
    fun onRead(value: Boolean?, nowMs: Long) {
        active = value == true
        readAtMs = nowMs
        reading = false
    }

    /** Broadcast com.anpaiss.karoosim.STATE. */
    @Synchronized
    fun onBroadcast(value: Boolean, nowMs: Long) {
        active = value
        readAtMs = nowMs
    }

    companion object {
        const val REFRESH_MS = 10_000L
        const val AUTHORITY = "com.anpaiss.karoosim.state"
        const val STATE_URI = "content://$AUTHORITY/state"
        const val ACTION_STATE = "com.anpaiss.karoosim.STATE"
        const val LOG_LINE = "Simulazione KarooSim attiva: niente apprendimento"
    }
}

/**
 * Un giro simulato non insegna niente: basta un momento di simulazione e il giro
 * intero resta fuori (modello, memoria tratti, storico), anche se poi torna il GPS vero.
 * Si azzera solo al giro nuovo.
 */
class SimRideGuard {

    @Volatile var simulated = false
        private set

    val learning: Boolean get() = !simulated

    /** Giro nuovo; true se parte già simulato (da scrivere nel log). */
    fun newRide(simActive: Boolean): Boolean {
        simulated = simActive
        return simActive
    }

    /** Esegue [block] (apprendimento o salvataggio) solo se il giro non è simulato; true se eseguito. */
    inline fun learn(block: () -> Unit): Boolean {
        if (!learning) return false
        block()
        return true
    }

    /** Stato visto durante il giro; true solo la prima volta che lo marca simulato. */
    fun observe(simActive: Boolean): Boolean {
        if (!simActive || simulated) return false
        simulated = true
        return true
    }
}
