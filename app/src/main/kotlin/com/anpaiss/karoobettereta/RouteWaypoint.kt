package com.anpaiss.karoobettereta

/** Punto del percorso con coordinate e quota. */
data class RouteWaypoint(
    val lat: Double,
    val lon: Double,
    val elevationM: Double,
)

/**
 * Segmento semplificato del percorso.
 *
 * @param distanceM  lunghezza in metri
 * @param gradePct   pendenza media in % (positivo = salita, negativo = discesa)
 */
data class RouteSegment(
    val distanceM: Double,
    val gradePct: Double,
)
