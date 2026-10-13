package com.anpaiss.bettereta

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Aritmetica dei tile "slippy map" (Web Mercator), quella dei file Mapsforge. */
object SlippyTiles {

    data class Tile(val x: Int, val y: Int, val zoom: Int)

    fun tileFor(lat: Double, lon: Double, zoom: Int): Tile {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / Math.cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Tile(x, y, zoom)
    }

    /** Angolo nord-ovest del tile. */
    fun tileOrigin(tile: Tile): Pair<Double, Double> {
        val n = 1 shl tile.zoom
        val lon = tile.x / n.toDouble() * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * tile.y / n.toDouble()))))
        return lat to lon
    }

    /** Centro del tile (lat, lon). */
    fun tileCenter(tile: Tile): Pair<Double, Double> {
        val (lat0, lon0) = tileOrigin(tile)
        val (lat1, lon1) = tileOrigin(Tile(tile.x + 1, tile.y + 1, tile.zoom))
        return (lat0 + lat1) / 2.0 to (lon0 + lon1) / 2.0
    }
}
