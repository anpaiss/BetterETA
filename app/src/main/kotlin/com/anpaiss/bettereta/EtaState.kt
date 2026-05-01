package com.anpaiss.bettereta

/**
 * Stato pubblicato da [BetterEtaExtension] verso i DataType.
 *
 * @param remainingSeconds  secondi al traguardo; -1 = dati non disponibili
 * @param hasRoute          true se un percorso con quota è stato caricato
 * @param status            messaggio descrittivo dello stato (es. "Looking for route", "No Route")
 */
data class EtaState(
    val remainingSeconds: Long = -1L,
    val hasRoute: Boolean = false,
    val status: String = "Looking for route",
    val gpsStale: Boolean = false,
    val remainingDistanceM: Double = -1.0,
    val remainingElevationM: Double = -1.0,
)
