package com.anpaiss.bettereta

/** Punto del percorso con coordinate e quota. */
data class RouteWaypoint(
    val lat: Double,
    val lon: Double,
    val elevationM: Double,
)

/**
 * Classe di fondo stradale. Due sole classi: di più frammenterebbe
 * l'apprendimento (i bucket per-fondo imparano da zero).
 */
enum class Surface {
    PAVED,    // asfalto, cemento, autobloccanti
    UNPAVED,  // ghiaia, sterrato, sentiero, fondo smosso
    UNKNOWN,  // dato OSM assente o route non classificata
}

/**
 * Segmento semplificato del percorso.
 *
 * @param distanceM  lunghezza in metri
 * @param gradePct   pendenza media in % (positivo = salita, negativo = discesa)
 * @param surface    classe di fondo (da OSM; UNKNOWN se non classificato)
 */
data class RouteSegment(
    val distanceM: Double,
    val gradePct: Double,
    val surface: Surface = Surface.UNKNOWN,
)
