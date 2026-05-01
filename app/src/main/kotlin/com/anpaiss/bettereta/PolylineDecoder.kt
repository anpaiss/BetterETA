package com.anpaiss.bettereta

import kotlin.math.*

/**
 * Decodifica Google Encoded Polyline in formato standard.
 *
 * Karoo SDK espone due polyline separate:
 *   - routePolyline / polyline         → coppie (lat, lng) con precisione 1e-5 gradi
 *   - routeElevationPolyline / elevationPolyline → elevazioni in decimetri (precisione 0.1 m)
 *
 * NOTA: la scala delle elevazioni (ELEVATION_SCALE = 10) è inferita dal formato SDK.
 * Se su hardware reale i valori risultano fuori range (>9000 m o negativi),
 * modificare ELEVATION_SCALE: provare 1.0 (metri interi) o 1e5 (standard Google).
 */
object PolylineDecoder {

    /** Scala di decodifica per le elevazioni: 10 = decimetri → metri */
    private const val ELEVATION_SCALE = 10.0

    /**
     * Decodifica una polyline 2D in coppie (lat, lon).
     * Restituisce lista vuota se la stringa è blank o malformata.
     */
    fun decodeLatLng(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()
        val result = mutableListOf<Pair<Double, Double>>()
        val idx    = intArrayOf(0)
        var lat    = 0
        var lng    = 0
        try {
            while (idx[0] < encoded.length) {
                lat += nextDelta(encoded, idx)
                lng += nextDelta(encoded, idx)
                result.add(lat / 1e5 to lng / 1e5)
            }
        } catch (_: Exception) { /* stringa troncata: restituisce quello decodificato finora */ }
        return result
    }

    /**
     * Decodifica una polyline 1D di elevazioni.
     * Restituisce lista vuota se la stringa è blank o malformata.
     */
    fun decodeElevations(encoded: String): List<Double> {
        if (encoded.isBlank()) return emptyList()
        val result = mutableListOf<Double>()
        val idx    = intArrayOf(0)
        var acc    = 0
        try {
            while (idx[0] < encoded.length) {
                acc += nextDelta(encoded, idx)
                result.add(acc / ELEVATION_SCALE)
            }
        } catch (_: Exception) { /* stringa troncata */ }
        return result
    }

    /**
     * Legge il prossimo delta intero dalla polyline codificata.
     * Aggiorna idx[0] al carattere successivo dopo la lettura.
     *
     * Algoritmo standard:
     *   - Ogni carattere: sottrai 63, isola i 5 bit bassi
     *   - Se bit 6 (0x20) è settato, ci sono altri caratteri da leggere (continuation bit)
     *   - Il delta risultante è signed: se bit LSB è 1, inverti tutti i bit
     */
    private fun nextDelta(encoded: String, idx: IntArray): Int {
        var result = 0
        var shift  = 0
        var b: Int
        do {
            b       = encoded[idx[0]++].code - 63
            result  = result or ((b and 0x1f) shl shift)
            shift  += 5
        } while (b >= 0x20 && idx[0] < encoded.length)
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
}

/**
 * Interpola le elevazioni sui punti delle coordinate GPS.
 *
 * Il Karoo SDK fornisce due polyline con campionamenti diversi: la polyline GPS
 * ha tipicamente meno punti di quella delle elevazioni. Questa funzione assume
 * che entrambe coprano lo stesso percorso totale e che i punti di elevazione
 * siano distribuiti uniformemente sulla distanza totale — e ricampiona le quote
 * sulle coordinate GPS tramite interpolazione lineare.
 *
 * @param coords  Waypoint GPS già decodificati come coppie (lat, lon).
 * @param elev    Elevazioni già decodificate (campionamento uniforme per distanza).
 * @return        Array di elevazioni interpolate, una per ogni punto di [coords].
 */
internal fun interpolateElevations(
    coords: List<Pair<Double, Double>>,
    elev: List<Double>,
): DoubleArray {
    if (elev.isEmpty()) return DoubleArray(coords.size) { 0.0 }
    if (coords.size <= 1) return DoubleArray(coords.size) { elev.first() }
    if (elev.size == coords.size) return elev.toDoubleArray()

    // Distanza cumulativa lungo le coordinate GPS
    val cumDist = DoubleArray(coords.size)
    for (i in 1 until coords.size) {
        cumDist[i] = cumDist[i - 1] + haversineForInterp(
            coords[i - 1].first, coords[i - 1].second,
            coords[i].first,     coords[i].second,
        )
    }
    val totalDist = cumDist.last()

    return DoubleArray(coords.size) { i ->
        // Posizione frazionaria [0,1] del waypoint i lungo il percorso
        val t = if (totalDist > 0.0) cumDist[i] / totalDist
                else i.toDouble() / (coords.size - 1)
        // Posizione corrispondente nell'array elevazioni
        val pos = t * (elev.size - 1)
        val lo  = pos.toInt().coerceIn(0, elev.size - 2)
        val frac = pos - lo
        elev[lo] * (1.0 - frac) + elev[lo + 1] * frac
    }
}

private fun haversineForInterp(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R    = 6_371_000.0
    val dLat = (lat2 - lat1).toRadians()
    val dLon = (lon2 - lon1).toRadians()
    val a    = sin(dLat / 2).pow(2) +
               cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2)
    return 2.0 * R * asin(sqrt(a))
}

private fun Double.toRadians() = Math.toRadians(this)
