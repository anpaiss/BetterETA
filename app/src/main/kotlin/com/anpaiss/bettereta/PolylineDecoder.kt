package com.anpaiss.bettereta

import kotlin.math.*

/**
 * Decodifica Google Encoded Polyline in formato standard.
 *
 * Karoo SDK (doc ufficiale karoo-ext 1.1.9, KarooEvent.kt) espone due polyline separate:
 *   - routePolyline / polyline → coppie (lat, lng), "Google encoded polyline, precision 5"
 *   - routeElevationPolyline / elevationPolyline → "Pair of distance, elevation,
 *     encoded as Google polyline, precision 1" → coppie 2D (distanza, quota) scala 10.
 *
 * Verificato su log ride reale (2026-07-02): decodificare l'elevazione come serie 1D
 * produce una sequenza monotona 0→22234 (la distanza cumulativa mischiata alle quote).
 */
object PolylineDecoder {

    /** Precisione 1 → valori codificati in decimi di metro */
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
     * Decodifica la polyline elevazioni come coppie (distanza dall'inizio, quota),
     * entrambe in metri. Restituisce lista vuota se la stringa è blank o malformata.
     */
    fun decodeDistanceElevation(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()
        val result = mutableListOf<Pair<Double, Double>>()
        val idx    = intArrayOf(0)
        var dist   = 0
        var elev   = 0
        try {
            while (idx[0] < encoded.length) {
                dist += nextDelta(encoded, idx)
                elev += nextDelta(encoded, idx)
                result.add(dist / ELEVATION_SCALE to elev / ELEVATION_SCALE)
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
 * I campioni quota portano ciascuno la propria distanza dall'inizio del percorso:
 * per ogni coordinata GPS si calcola la posizione frazionaria lungo il percorso
 * (normalizzata, così eventuali differenze di scala tra i due assi si annullano)
 * e si interpola linearmente sull'asse distanza dei campioni.
 *
 * @param coords   Waypoint GPS già decodificati come coppie (lat, lon).
 * @param samples  Campioni (distanza dall'inizio, quota) in ordine di distanza.
 * @return         Array di elevazioni interpolate, una per ogni punto di [coords].
 */
internal fun interpolateElevations(
    coords: List<Pair<Double, Double>>,
    samples: List<Pair<Double, Double>>,
): DoubleArray {
    if (samples.isEmpty()) return DoubleArray(coords.size) { 0.0 }
    if (coords.size <= 1 || samples.size == 1) {
        return DoubleArray(coords.size) { samples.first().second }
    }

    // Distanza cumulativa lungo le coordinate GPS
    val cumDist = DoubleArray(coords.size)
    for (i in 1 until coords.size) {
        cumDist[i] = cumDist[i - 1] + haversineForInterp(
            coords[i - 1].first, coords[i - 1].second,
            coords[i].first,     coords[i].second,
        )
    }
    val totalDist   = cumDist.last()
    val sampleStart = samples.first().first
    val sampleSpan  = samples.last().first - sampleStart

    return DoubleArray(coords.size) { i ->
        // Posizione frazionaria [0,1] del waypoint i lungo il percorso
        val t = if (totalDist > 0.0) cumDist[i] / totalDist
                else i.toDouble() / (coords.size - 1)
        val target = sampleStart + t * sampleSpan

        // Binary search: ultimo campione con distanza <= target
        var lo = 0
        var hi = samples.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].first <= target) lo = mid else hi = mid
        }
        val (d0, e0) = samples[lo]
        val (d1, e1) = samples[hi]
        if (d1 <= d0) e0
        else e0 + (e1 - e0) * ((target - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
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
