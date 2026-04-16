package com.anpaiss.karoobettereta

/**
 * Stato pubblicato da [BetterEtaExtension] verso i DataType.
 *
 * @param remainingSeconds  secondi al traguardo; -1 = dati non disponibili
 * @param hasRoute          true se un percorso con quota è stato caricato
 */
data class EtaState(
    val remainingSeconds: Long = -1L,
    val hasRoute: Boolean = false,
)
