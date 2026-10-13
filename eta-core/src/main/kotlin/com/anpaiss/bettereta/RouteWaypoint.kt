package com.anpaiss.bettereta

/** Punto del percorso con coordinate e quota. */
data class RouteWaypoint(
    val lat: Double,
    val lon: Double,
    val elevationM: Double,
)

/**
 * Classe di fondo stradale, tre classi dal 05/09: nel vecchio "non asfalto"
 * la strada bianca battuta e il sentiero di radici avevano la stessa media.
 * Vedi SurfaceTagClassifier per la mappatura dai tag OSM.
 */
enum class Surface {
    PAVED,    // asfalto, cemento, autobloccanti
    GRAVEL,   // ghiaia, sterrato battuto, strada forestale
    TRAIL,    // sentiero, terra, erba, sabbia, roccia (mtb difficoltà 1-2)
    GNARLY,   // sentiero tecnico: mtb_difficulty 3-5 nelle mappe Karoo, mtb:scale >= 2 in OSM
    UNKNOWN,  // dato OSM assente o route non classificata
}

/**
 * Segmento semplificato del percorso.
 *
 * @param distanceM  lunghezza in metri
 * @param gradePct   pendenza media in % (positivo = salita, negativo = discesa)
 * @param surface    classe di fondo (da OSM; UNKNOWN se non classificato)
 * @param wayKey     (way OSM, verso) su cui corre il segmento — vedi WaySpeedMemory.wayKey;
 *                   0 = non abbinato a una way
 */
data class RouteSegment(
    val distanceM: Double,
    val gradePct: Double,
    val surface: Surface = Surface.UNKNOWN,
    val wayKey: Long = 0L,
)
