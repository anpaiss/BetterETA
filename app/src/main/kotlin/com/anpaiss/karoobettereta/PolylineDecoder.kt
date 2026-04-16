package com.anpaiss.karoobettereta

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
