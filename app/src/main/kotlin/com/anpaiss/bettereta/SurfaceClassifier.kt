package com.anpaiss.bettereta

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Classifica il fondo (PAVED/GRAVEL/TRAIL) di ogni waypoint della route
 * usando le way OSM nel corridoio del percorso: dalle mappe offline del
 * Karoo se leggibili (vedi MapsforgeSurfaceSource), altrimenti via Overpass.
 *
 * Flusso: prima si abbinano i waypoint alle way già in cache ([SurfaceWayStore],
 * geografica, indipendente dalla route); solo i tratti mai interrogati vengono
 * sottocampionati (~1 punto ogni 100 m) e chiesti a Overpass come catene
 * `around`. Le way tornate entrano in cache con i corridoi chiesti, così una
 * route ricalcolata o una route nuova che condivide tratti con una vecchia
 * riusa tutto quello che c'è e, senza rete, resta ignota solo la parte nuova.
 *
 * La cache è su file e sopravvive ai riavvii del servizio, che a metà giro
 * avvengono senza connettività.
 */
class SurfaceClassifier(
    context: Context,
    private val logger: BetterEtaLogger,
    private val offlineMaps: MapsforgeSurfaceSource? = null,
) {

    private val cacheFile = File(context.filesDir, "surface_ways.txt")
    // Formato precedente (fondo per indice, chiave = hash polyline): non riusabile
    private val legacyCacheFile = File(context.filesDir, "surface_cache.json")
    private var store: SurfaceWayStore? = null
    /** false durante l'import storico: un salvataggio solo alla fine invece di uno per giro. */
    @Volatile var autoSave = true
    // Ultimo fallimento di rete: ogni riemissione della route (decine per giro
    // fuori copertura) ritentava e scriveva tre righe di log
    private var lastFetchFailMs = 0L

    /**
     * @param wayKeys  (way, verso) per waypoint, 0 = nessuna way vicina — per la memoria dei tempi per tratto
     * @param complete false = una parte della route non è stata interrogata (offline): vale la pena ritentare.
     */
    class Result(val surfaces: List<Surface>, val wayKeys: List<Long>, val complete: Boolean)

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
        private const val RUN_MERGE_GAP     = 5       // tratti scoperti separati da <= N waypoint → una catena
        private const val MAX_RUNS          = 40      // oltre: una catena unica dal primo all'ultimo scoperto
        private const val RETRY_BACKOFF_MS  = 5 * 60_000L

    }

    /**
     * Restituisce la classe di fondo per ogni waypoint (lista parallela).
     * Chiamare fuori dal main thread. Restituisce null se nessuna parte della
     * route è classificabile (offline e niente in cache lì).
     */
    @Synchronized
    fun classify(waypoints: List<RouteWaypoint>): Result? {
        if (waypoints.size < 2) return null
        val store = loadStore()

        var match = store.match(waypoints)
        val missing = match.missing
        if (missing.isEmpty()) {
            logger.log("SURF ", "Fondo da cache: ${summary(match.surfaces, waypoints)}")
            return Result(match.surfaces, match.wayKeys, true)
        }

        // Corridoi = i waypoint reali dei tratti scoperti (per la copertura in
        // cache); a Overpass va la loro versione sottocampionata
        val sinceFailMs = System.currentTimeMillis() - lastFetchFailMs
        if (lastFetchFailMs > 0L && sinceFailMs < RETRY_BACKOFF_MS) {
            logger.log("SURF ", "Overpass fallito ${sinceFailMs / 1000}s fa — ritento tra ${(RETRY_BACKOFF_MS - sinceFailMs) / 1000}s; " +
                "${missing.size}/${waypoints.size} waypoint scoperti")
            return if (match.coveredCount == 0) null else Result(match.surfaces, match.wayKeys, false)
        }

        val runs   = missingRuns(waypoints, missing)
        val chains = runs.map { sampleChain(it) }

        // Prima le mappe offline del Karoo (nessuna rete), poi Overpass
        val offline = offlineMaps?.fetchWays(chains)
        if (offline != null) {
            store.add(SurfaceWayStore.Batch(System.currentTimeMillis(), runs.map { r -> r.map { it.lat to it.lon } }, offline.ways))
            saveStore(store)
            match = store.match(waypoints)
            logger.log("SURF ", "Fondo da mappe offline (${offline.ways.size} way [${offline.classCounts()}] da ${offline.tiles} tile in ${offline.files} file, " +
                "${offline.millis}ms, per ${missing.size}/${waypoints.size} waypoint; cache ${store.wayCount} way): ${summary(match.surfaces, waypoints)}")
            return Result(match.surfaces, match.wayKeys, true)
        }

        val ways = try {
            fetchWays(chains).also { lastFetchFailMs = 0L }
        } catch (e: Exception) {
            lastFetchFailMs = System.currentTimeMillis()
            if (match.coveredCount == 0) {
                logger.log("SURF ", "Overpass non raggiungibile (${e.message}) — fondo sconosciuto per questo giro")
                return null
            }
            logger.log("SURF ", "Overpass non raggiungibile (${e.message}) — fondo solo da cache, " +
                "${missing.size}/${waypoints.size} waypoint scoperti: ${summary(match.surfaces, waypoints)}")
            return Result(match.surfaces, match.wayKeys, false)
        }

        store.add(SurfaceWayStore.Batch(System.currentTimeMillis(), runs.map { r -> r.map { it.lat to it.lon } }, ways))
        saveStore(store)
        match = store.match(waypoints)
        logger.log("SURF ", "Fondo da OSM (${ways.size} way nuove per ${missing.size}/${waypoints.size} waypoint, " +
            "${runs.size} tratti; cache ${store.wayCount} way): ${summary(match.surfaces, waypoints)}")
        return Result(match.surfaces, match.wayKeys, true)
    }

    // ── Overpass ─────────────────────────────────────────────────────────

    /**
     * Tratti contigui di waypoint scoperti. Buchi piccoli tra tratti vengono
     * fusi; troppi tratti diventano un unico tratto dal primo all'ultimo
     * scoperto (query più grassa ma una sola).
     */
    private fun missingRuns(waypoints: List<RouteWaypoint>, missing: List<Int>): List<List<RouteWaypoint>> {
        val ranges = mutableListOf<IntRange>()
        var start = missing.first()
        var prev  = start
        for (i in missing.drop(1)) {
            if (i - prev > RUN_MERGE_GAP) { ranges.add(start..prev); start = i }
            prev = i
        }
        ranges.add(start..prev)
        val merged = if (ranges.size > MAX_RUNS) listOf(ranges.first().first..ranges.last().last) else ranges
        return merged.map { r -> waypoints.subList(r.first, r.last + 1) }
    }

    private fun fetchWays(runs: List<List<Pair<Double, Double>>>): List<OsmWay> {
        val clauses = runs.joinToString("") { run ->
            val chain = run.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.first, it.second) }
            "way[\"highway\"](around:${CORRIDOR_M.toInt()},$chain);"
        }
        val query = "[out:json][timeout:40];($clauses);out geom;"

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

    /** Sottocampiona un tratto a ~1 punto ogni SAMPLE_SPACING_M, come coppie (lat, lon). */
    private fun sampleChain(waypoints: List<RouteWaypoint>): List<Pair<Double, Double>> {
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
        return picked.filterIndexed { i, _ -> i % step == 0 || i == picked.lastIndex }
            .map { it.lat to it.lon }
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
            if (pts.size >= 2) result.add(OsmWay(el.optLong("id"), surface, pts))
        }
        return result
    }

    /** Classifica una way dai tag (stessa regola delle mappe offline); null se non classificabile. */
    private fun classifyTags(tags: JSONObject): Surface? {
        val map = HashMap<String, String>()
        for (k in tags.keys()) map[k] = tags.optString(k)
        return SurfaceTagClassifier.classify(map)
    }

    private fun approxDistM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }

    // ── cache ────────────────────────────────────────────────────────────

    private fun loadStore(): SurfaceWayStore {
        store?.let { return it }
        if (legacyCacheFile.exists()) legacyCacheFile.delete()
        val loaded = runCatching {
            if (cacheFile.exists()) SurfaceWayStore.parse(cacheFile.readText()) else null
        }.getOrNull() ?: SurfaceWayStore()
        if (loaded.batchCount > 0) {
            logger.log("SURF ", "Cache way caricata: ${loaded.batchCount} batch, ${loaded.wayCount} way, ${loaded.wayPointCount} punti")
        }
        store = loaded
        return loaded
    }

    private fun saveStore(store: SurfaceWayStore) {
        if (!autoSave) return
        runCatching { cacheFile.writeText(store.serialize()) }
            .onFailure { logger.logError("SURF ", "Salvataggio cache fondo fallito", it) }
    }

    /** Salvataggio esplicito (fine import storico). */
    @Synchronized
    fun saveNow() {
        val s = store ?: return
        val t0 = System.currentTimeMillis()
        runCatching { cacheFile.writeText(s.serialize()) }
            .onFailure { logger.logError("SURF ", "Salvataggio cache fondo fallito", it) }
        logger.log("SURF ", "Cache way salvata: ${s.wayCount} way, ${s.wayPointCount} punti, ${System.currentTimeMillis() - t0} ms")
    }

    private fun summary(surfaces: List<Surface>, waypoints: List<RouteWaypoint>): String {
        val km = DoubleArray(Surface.entries.size)
        for (i in 0 until waypoints.size - 1) {
            val d = approxDistM(waypoints[i].lat, waypoints[i].lon, waypoints[i+1].lat, waypoints[i+1].lon)
            km[surfaces.getOrElse(i) { Surface.UNKNOWN }.ordinal] += d
        }
        val tot = km.sum().coerceAtLeast(1.0)
        return Surface.entries.joinToString(", ") { s ->
            "%.1fkm %s (%d%%)".format(km[s.ordinal] / 1000, s.name.lowercase(), (km[s.ordinal] / tot * 100).toInt())
        }
    }
}
