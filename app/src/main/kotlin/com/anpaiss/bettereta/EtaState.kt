package com.anpaiss.bettereta

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Stato pubblicato da [BetterEtaExtension] verso i DataType.
 *
 * @param remainingSeconds  secondi al traguardo; -1 = dati non disponibili
 * @param hasRoute          true se un percorso con quota è stato caricato
 * @param status            messaggio descrittivo dello stato (es. "Looking for route", "No Route")
 * @param deltaSeconds      scarto del tempo di percorrenza (solo tempo in movimento)
 *                          rispetto alla prima stima valida del giro (positivo = in
 *                          ritardo sulla media); null finché non c'è baseline
 * @param farthestPointDistanceM  distanza rimanente al punto geograficamente più
 *                          lontano dalla partenza (svolta di un andata/ritorno);
 *                          0 dopo averlo superato, -1 se non disponibile
 * @param estimateApprox    true se l'ETA è una stima grossolana (fuori route: deviazione
 *                          di rientro del Karoo a velocità piana + route dal punto di rientro)
 * @param mtaEpochMs        mandatory time of arrival impostato dall'utente (epoch ms);
 *                          0 = nessuno
 * @param marginSeconds     MTA - (adesso + remainingSeconds): secondi di margine,
 *                          negativo = arrivo oltre l'MTA; null senza MTA o senza ETA
 * @param uncertaintySeconds larghezza del "±" della stima (EtaUncertainty); -1 = nessuna
 */
data class EtaState(
    val remainingSeconds: Long = -1L,
    val hasRoute: Boolean = false,
    val status: String = "Looking for route",
    val gpsStale: Boolean = false,
    val remainingDistanceM: Double = -1.0,
    val remainingElevationM: Double = -1.0,
    val deltaSeconds: Long? = null,
    val farthestPointDistanceM: Double = -1.0,
    val mtaEpochMs: Long = 0L,
    val marginSeconds: Long? = null,
    val estimateApprox: Boolean = false,
    /** Fattore di giornata del modello (1.0 = come la base) */
    val dayFactor: Double = 1.0,
    val uncertaintySeconds: Long = -1L,
) {
    companion object {
        /**
         * What the fields show in the Karoo profile editor: a ride in progress, 58 min
         * to go, a mandatory arrival time with 12 min of slack, 3 min ahead of plan.
         * Without this the editor shows "Paused" or "Looking for route" (25/10/2026).
         */
        fun sample(nowMs: Long = System.currentTimeMillis()): EtaState {
            val remaining = 58 * 60L + 20
            val margin = 12 * 60L
            return EtaState(
                remainingSeconds = remaining,
                hasRoute = true,
                status = "",
                remainingDistanceM = 14_300.0,
                remainingElevationM = 410.0,
                deltaSeconds = -3 * 60L,
                farthestPointDistanceM = 7_400.0,
                mtaEpochMs = nowMs + (remaining + margin) * 1000L,
                marginSeconds = margin,
                uncertaintySeconds = 4 * 60L,
            )
        }
    }
}

/** The live state, or the fixed [EtaState.sample] when the view is a profile editor preview. */
fun StateFlow<EtaState>.orSample(preview: Boolean): Flow<EtaState> =
    if (preview) flowOf(EtaState.sample()) else this
