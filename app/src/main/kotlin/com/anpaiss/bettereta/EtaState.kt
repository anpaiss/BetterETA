package com.anpaiss.bettereta

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
)
