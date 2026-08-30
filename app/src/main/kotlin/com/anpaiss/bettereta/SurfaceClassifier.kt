package com.anpaiss.bettereta

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Classifica il fondo (PAVED/UNPAVED) di ogni waypoint della route usando le
 * way OSM nel corridoio del percorso, via Overpass API.
 *
 * Flusso: la route viene sottocampionata (~1 punto ogni 100 m) e usata come
 * catena `around` nella query; le way restituite (con geometria) vengono
 * indicizzate su una griglia spaziale; ogni waypoint prende il fondo della
 * way più vicina entro MATCH_MAX_DIST_M.
 *
 * La classificazione di una route viene cachata su file (chiave: hash della
 * polyline): sopravvive ai riavvii del servizio, che a metà giro avvengono
 * senza connettività.
 */
class SurfaceClassifier(context: Context, private val logger: BetterEtaLogger) {

    private val cacheFile = File(context.filesDir, "surface_cache.json")

    companion object {
        // Il primario ha finestre di congestione ("server too busy", visto il
        // 30/08): si prova in ordine finché uno risponde
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        private const val TIMEOUT_MS        = 45_000
        private const val CORRIDOR_M        = 25.0    // raggio della catena around
        private const val SAMPLE_SPACING_M  = 100.0   // spaziatura punti nella query
        private const val MAX_QUERY_POINTS  = 400     // cap sicurezza dimensione query
        private const val MATCH_MAX_DIST_M  = 30.0    // waypoint→way oltre questa = UNKNOWN
        private const val GRID_CELL_DEG     = 0.003   // ~330 m, per l'indice spaziale
        private const val CACHE_MAX_ROUTES  = 8

        private val PAVED_SURFACES = setOf(
            "asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes",
            "paving_stones", "sett", "chipseal", "metal", "wood",
        )
        private val UNPAVED_SURFACES = setOf(
            "gravel", "fine_gravel", "compacted", "ground", "dirt", "earth",
            "unpaved", "sand", "grass", "mud", "rock", "stone", "pebblestone",
            "cobblestone", "woodchips", "grass_paver",
        )
        private val UNPAVED_HIGHWAYS = setOf("path", "track", "bridleway")
        private val PAVED_HIGHWAYS = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "service", "living_street", "cycleway", "footway",
            "pedestrian", "motorway_link", "trunk_link", "primary_link",
            "secondary_link", "tertiary_link",
        )
    }

    /**
     * Restituisce la classe di fondo per ogni waypoint (lista parallela), da
     * cache se disponibile, altrimenti da Overpass. Chiamare fuori dal main
     * thread. Restituisce null se la classificazione non è possibile (offline
     * e nessuna cache).
     */
    fun classify(routeKey: String, waypoints: List<RouteWaypoint>): List<Surface>? {
        if (waypoints.size < 2) return null

        loadFromCache(routeKey, waypoints.size)?.let {
            logger.log("SURF ", "Fondo da cache: ${summary(it, waypoints)}")
            return it
        }

        val ways = try {
            fetchWays(waypoints)
        } catch (e: Exception) {
            logger.log("SURF ", "Overpass non raggiungibile (${e.message}) — fondo sconosciuto per questo giro")
            return null
        }

        val surfaces = matchWaypoints(waypoints, ways)
        saveToCache(routeKey, surfaces)
        logger.log("SURF ", "Fondo da OSM (${ways.size} way): ${summary(surfaces, waypoints)}")
        return surfaces
    }

    // ── Overpass ─────────────────────────────────────────────────────────

    private class OsmWay(val surface: Surface, val points: List<Pair<Double, Double>>)

    private fun fetchWays(waypoints: List<RouteWaypoint>): List<OsmWay> {
        val chain = sampleChain(waypoints)
        val query = "[out:json][timeout:40];" +
            "way[\"highway\"](around:${CORRIDOR_M.toInt()},$chain);out geom;"

        var lastError: Exception? = null
        for (endpoint in ENDPOINTS) {
            try {
                return fetchFrom(endpoint, query)
            } catch (e: Exception) {
                lastError = e
                logger.log("SURF ", "Endpoint $endpoint fallito (${e.message}) — provo il prossimo")
            }
        }
        throw lastError ?: IOException("nessun endpoint Overpass disponibile")
    }

    private fun fetchFrom(endpoint: String, query: String): List<OsmWay> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout    = TIMEOUT_MS
            doOutput       = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "BetterEta/1.0 (Karoo extension)")
        }
        try {
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return parseWays(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    /** Sottocampiona la route a ~1 punto ogni SAMPLE_SPACING_M, formato "lat,lon,lat,lon,...". */
    private fun sampleChain(waypoints: List<RouteWaypoint>): String {
        val picked = mutableListOf(waypoints.first())
        var accum = 0.0
        for (i in 1 until waypoints.size) {
            accum += approxDistM(
                waypoints[i - 1].lat, waypoints[i - 1].lon,
                waypoints[i].lat, waypoints[i].lon,
            )
            if (accum >= SAMPLE_SPACING_M) {
                picked.add(waypoints[i])
                accum = 0.0
            }
        }
        if (picked.last() !== waypoints.last()) picked.add(waypoints.last())
        val step = if (picked.size > MAX_QUERY_POINTS) picked.size / MAX_QUERY_POINTS + 1 else 1
        return picked.filterIndexed { i, _ -> i % step == 0 }
            .joinToString(",") { "%.5f,%.5f".format(it.lat, it.lon) }
    }

    private fun parseWays(json: String): List<OsmWay> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<OsmWay>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "way") continue
            val tags = el.optJSONObject("tags") ?: continue
            val surface = classifyTags(tags) ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            val pts = ArrayList<Pair<Double, Double>>(geom.length())
            for (j in 0 until geom.length()) {
                val p = geom.getJSONObject(j)
                pts.add(p.getDouble("lat") to p.getDouble("lon"))
            }
            if (pts.size >= 2) result.add(OsmWay(surface, pts))
        }
        return result
    }

    /** Classifica una way dai tag; null se non classificabile (esclusa dal match). */
    private fun classifyTags(tags: JSONObject): Surface? {
        val surface = tags.optString("surface")
        if (surface.isNotBlank()) {
            return when {
                surface in PAVED_SURFACES   -> Surface.PAVED
                surface in UNPAVED_SURFACES -> Surface.UNPAVED
                else                        -> null
            }
        }
        // Senza tag surface: il tipo di strada come proxy
        return when (tags.optString("highway")) {
            in UNPAVED_HIGHWAYS -> Surface.UNPAVED
            in PAVED_HIGHWAYS   -> Surface.PAVED
            else                -> null
        }
    }

    // ── matching spaziale ────────────────────────────────────────────────

    private class WaySeg(
        val ax: Double, val ay: Double,
        val bx: Double, val by: Double,
        val surface: Surface,
    )

    private fun matchWaypoints(waypoints: List<RouteWaypoint>, ways: List<OsmWay>): List<Surface> {
        if (ways.isEmpty()) return List(waypoints.size) { Surface.UNKNOWN }

        // Proiezione equirettangolare locale (metri) + griglia per cella
        val refLat = waypoints.first().lat
        val cosRef = cos(Math.toRadians(refLat))
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cosRef
        fun x(lon: Double) = lon * mPerDegLon
        fun y(lat: Double) = lat * mPerDegLat
        val cellM = GRID_CELL_DEG * mPerDegLat
        fun cellKey(px: Double, py: Double) =
            floor(px / cellM).toLong() * 1_000_003L + floor(py / cellM).toLong()

        val grid = HashMap<Long, MutableList<WaySeg>>()
        for (way in ways) {
            for (j in 0 until way.points.size - 1) {
                val seg = WaySeg(
                    x(way.points[j].second),     y(way.points[j].first),
                    x(way.points[j + 1].second), y(way.points[j + 1].first),
                    way.surface,
                )
                // Registra il segmento in tutte le celle toccate dal suo bbox
                val cx0 = floor(minOf(seg.ax, seg.bx) / cellM).toLong()
                val cx1 = floor(maxOf(seg.ax, seg.bx) / cellM).toLong()
                val cy0 = floor(minOf(seg.ay, seg.by) / cellM).toLong()
                val cy1 = floor(maxOf(seg.ay, seg.by) / cellM).toLong()
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    grid.getOrPut(cx * 1_000_003L + cy) { mutableListOf() }.add(seg)
                }
            }
        }

        return waypoints.map { wp ->
            val px = x(wp.lon)
            val py = y(wp.lat)
            var best = Double.MAX_VALUE
            var bestSurface = Surface.UNKNOWN
            val cx = floor(px / cellM).toLong()
            val cy = floor(py / cellM).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                grid[(cx + dx) * 1_000_003L + (cy + dy)]?.forEach { seg ->
                    val d = pointToSegM(px, py, seg)
                    if (d < best) { best = d; bestSurface = seg.surface }
                }
            }
            if (best <= MATCH_MAX_DIST_M) bestSurface else Surface.UNKNOWN
        }
    }

    private fun pointToSegM(px: Double, py: Double, s: WaySeg): Double {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0
            else (((px - s.ax) * dx + (py - s.ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = s.ax + t * dx
        val cy = s.ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    private fun approxDistM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }

    // ── cache ────────────────────────────────────────────────────────────

    private fun loadFromCache(routeKey: String, expectedSize: Int): List<Surface>? {
        return runCatching {
            if (!cacheFile.exists()) return null
            val root = JSONObject(cacheFile.readText())
            val entry = root.optJSONArray(routeKey) ?: return null
            if (entry.length() != expectedSize) return null
            List(entry.length()) { Surface.entries[entry.getInt(it)] }
        }.getOrNull()
    }

    @Synchronized
    private fun saveToCache(routeKey: String, surfaces: List<Surface>) {
        runCatching {
            val root = if (cacheFile.exists()) JSONObject(cacheFile.readText()) else JSONObject()
            root.put(routeKey, JSONArray().apply { surfaces.forEach { put(it.ordinal) } })
            // Cap: elimina le entry più vecchie oltre il limite (ordine chiavi ~ inserimento)
            val keys = root.keys().asSequence().toMutableList()
            while (keys.size > CACHE_MAX_ROUTES) root.remove(keys.removeAt(0))
            cacheFile.writeText(root.toString())
        }.onFailure { logger.logError("SURF ", "Salvataggio cache fondo fallito", it) }
    }

    private fun summary(surfaces: List<Surface>, waypoints: List<RouteWaypoint>): String {
        var paved = 0.0; var unpaved = 0.0; var unknown = 0.0
        for (i in 0 until waypoints.size - 1) {
            val d = approxDistM(waypoints[i].lat, waypoints[i].lon, waypoints[i+1].lat, waypoints[i+1].lon)
            when (surfaces.getOrElse(i) { Surface.UNKNOWN }) {
                Surface.PAVED   -> paved += d
                Surface.UNPAVED -> unpaved += d
                Surface.UNKNOWN -> unknown += d
            }
        }
        val tot = (paved + unpaved + unknown).coerceAtLeast(1.0)
        return "%.1fkm paved (%d%%), %.1fkm unpaved (%d%%), %.1fkm ? (%d%%)".format(
            paved / 1000, (paved / tot * 100).toInt(),
            unpaved / 1000, (unpaved / tot * 100).toInt(),
            unknown / 1000, (unknown / tot * 100).toInt(),
        )
    }
}
