package com.anpaiss.bettereta

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** Way OSM classificata: fondo e geometria come coppie (lat, lon). */
class OsmWay(val id: Long, val surface: Surface, val points: List<Pair<Double, Double>>)

/**
 * Cache geografica del fondo: le way OSM scaricate (geometria + fondo) e i
 * corridoi lungo cui sono state chieste a Overpass.
 *
 * È indipendente dalla route. La versione precedente salvava "fondo del
 * waypoint N" per hash esatto della polyline: una route ricalcolata di due
 * metri, o una route nuova che condivide la salita con una vecchia, non
 * riusava niente (giro test del 04/09). Qui invece ogni waypoint di qualsiasi
 * route viene abbinato alla way più vicina tra quelle in cache, e solo i
 * tratti mai interrogati vanno a Overpass. Più giri in zona, meno rete serve.
 *
 * Un [Batch] = una chiamata Overpass: i tratti di route campionati (i "run")
 * e le way tornate. La copertura di un waypoint si giudica sui run: se sta a
 * meno di [COVER_M] da un corridoio già chiesto, la sua risposta è nota anche
 * quando è "nessuna way qui vicino". I batch più vecchi vengono scartati oltre
 * [maxWayPoints] punti di geometria.
 *
 * Nessuna dipendenza Android: serializzazione testuale propria, testabile su JVM.
 */
class SurfaceWayStore(private val maxWayPoints: Int = MAX_WAY_POINTS) {

    class Batch(
        val timeMs: Long,
        /**
         * Tratti di route (lat, lon) per cui è stata fatta la chiamata: i
         * waypoint reali, non la catena sottocampionata mandata a Overpass —
         * in curva la route si scosta dalla corda tra due campioni più di
         * [COVER_M] e la stessa route risultava scoperta (giro test 04/09).
         */
        val runs: List<List<Pair<Double, Double>>>,
        val ways: List<OsmWay>,
    ) {
        val wayPointCount: Int = ways.sumOf { it.points.size }
    }

    /**
     * Esito dell'abbinamento di una route: fondo per waypoint, copertura e
     * chiave (way, verso) per waypoint (0 = nessuna way entro MATCH_MAX_DIST_M).
     * Il verso è quello in cui la route percorre la way: ordine crescente o
     * decrescente della posizione lungo la way tra waypoint consecutivi.
     */
    class Match(val surfaces: List<Surface>, val covered: BooleanArray, val wayKeys: List<Long> = emptyList()) {
        val missing: List<Int> get() = covered.indices.filter { !covered[it] }
        val coveredCount: Int get() = covered.count { it }
    }

    private val batches = ArrayDeque<Batch>()

    // Indice spaziale costruito una volta e aggiornato a ogni add(): ricostruirlo
    // a ogni match costava ~10 s a giro con 200k punti (import storico 06/09).
    // Proiezione con latitudine di riferimento fissa (prima geometria vista):
    // entro qualche grado l'errore di scala è di pochi percento.
    private var proj: LocalProjection? = null
    private var wayIndex: SegmentIndex? = null
    private var runIndex: SegmentIndex? = null

    val batchCount: Int get() = batches.size
    val wayCount: Int get() = batches.sumOf { it.ways.size }
    val wayPointCount: Int get() = batches.sumOf { it.wayPointCount }

    fun add(batch: Batch) {
        batches.addLast(batch)
        var evicted = false
        while (batches.size > 1 && wayPointCount > maxWayPoints) { batches.removeFirst(); evicted = true }
        if (evicted) invalidateIndex() else if (wayIndex != null) indexBatch(batch)
    }

    private fun invalidateIndex() { wayIndex = null; runIndex = null; proj = null }

    private fun indexBatch(b: Batch) {
        for (w in b.ways) wayIndex!!.addPolyline(w.points, w.surface, w.id)
        for (r in b.runs) runIndex!!.addPolyline(r, Surface.UNKNOWN, 0L)
    }

    private fun ensureIndex(refLatIfEmpty: Double) {
        if (wayIndex != null) return
        val refLat = batches.firstOrNull()?.let { b ->
            b.runs.firstOrNull()?.firstOrNull()?.first ?: b.ways.firstOrNull()?.points?.firstOrNull()?.first
        } ?: refLatIfEmpty
        val p = LocalProjection(refLat)
        proj = p
        wayIndex = SegmentIndex(p)
        runIndex = SegmentIndex(p)
        for (b in batches) indexBatch(b)
    }

    fun match(waypoints: List<RouteWaypoint>): Match {
        if (waypoints.isEmpty()) return Match(emptyList(), BooleanArray(0))
        ensureIndex(waypoints.first().lat)
        val proj = proj!!
        val wayIndex = wayIndex!!
        val runIndex = runIndex!!

        val surfaces = ArrayList<Surface>(waypoints.size)
        val covered  = BooleanArray(waypoints.size)
        val wayIds   = LongArray(waypoints.size)
        val along    = DoubleArray(waypoints.size)
        waypoints.forEachIndexed { i, wp ->
            val px = proj.x(wp.lon)
            val py = proj.y(wp.lat)
            val hit = wayIndex.nearest(px, py)
            val onWay = hit.distM <= MATCH_MAX_DIST_M
            surfaces.add(if (onWay) hit.surface else Surface.UNKNOWN)
            if (onWay) { wayIds[i] = hit.wayId; along[i] = hit.alongM }
            covered[i] = runIndex.nearest(px, py).distM <= COVER_M
        }
        // Verso: dal vicino sulla stessa way (avanti, altrimenti indietro); senza
        // vicino sulla stessa way, o fermi, si assume in avanti
        val keys = List(waypoints.size) { i ->
            val id = wayIds[i]
            if (id == 0L) 0L else {
                val delta = when {
                    i + 1 < waypoints.size && wayIds[i + 1] == id -> along[i + 1] - along[i]
                    i > 0 && wayIds[i - 1] == id                  -> along[i] - along[i - 1]
                    else                                          -> 0.0
                }
                WaySpeedMemory.wayKey(id, forward = delta >= 0.0)
            }
        }
        return Match(surfaces, covered, keys)
    }

    // ── serializzazione ──────────────────────────────────────────────────
    //  SURF2
    //  B <timeMs>
    //  R lat,lon;lat,lon;...
    //  W <id> <surface name> lat,lon;lat,lon;...   (SURF2: ordinale a due classi 0=PAVED 1=UNPAVED→GRAVEL)

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        for (b in batches) {
            append("B ").append(b.timeMs).append('\n')
            for (r in b.runs) append("R ").append(encodePoints(r)).append('\n')
            for (w in b.ways) {
                append("W ").append(w.id).append(' ').append(w.surface.name).append(' ')
                    .append(encodePoints(w.points)).append('\n')
            }
        }
    }

    companion object {
        const val MATCH_MAX_DIST_M = 30.0     // waypoint→way oltre questa = UNKNOWN
        const val COVER_M          = 40.0     // waypoint→corridoio già chiesto oltre questa = da chiedere
        const val MAX_WAY_POINTS   = 400_000  // ~10 MB di file: due anni di giri in zona
        private const val HEADER   = "SURF3"
        private const val LEGACY_HEADER = "SURF2"
        private const val GRID_CELL_M = 330.0

        /** Store vuoto se il testo non è nel formato atteso (incluso il file legacy JSON). */
        fun parse(text: String, maxWayPoints: Int = MAX_WAY_POINTS): SurfaceWayStore {
            val store = SurfaceWayStore(maxWayPoints)
            val lines = text.lineSequence().iterator()
            val header = if (lines.hasNext()) lines.next().trim() else ""
            if (header != HEADER && header != LEGACY_HEADER) return store
            fun surfaceOf(token: String): Surface = when (token) {
                "0" -> Surface.PAVED
                "1" -> Surface.GRAVEL
                "2" -> Surface.UNKNOWN
                else -> runCatching { Surface.valueOf(token) }.getOrDefault(Surface.UNKNOWN)
            }

            var time = 0L
            var runs = mutableListOf<List<Pair<Double, Double>>>()
            var ways = mutableListOf<OsmWay>()
            var open = false
            fun flush() {
                if (open) store.batches.addLast(Batch(time, runs, ways))
                runs = mutableListOf(); ways = mutableListOf(); open = false
            }
            runCatching {
                for (raw in lines) {
                    val line = raw.trim()
                    when {
                        line.startsWith("B ") -> { flush(); time = line.substring(2).toLong(); open = true }
                        line.startsWith("R ") -> runs.add(decodePoints(line.substring(2)))
                        line.startsWith("W ") -> {
                            val p = line.split(' ', limit = 4)
                            val pts = decodePoints(p[3])
                            if (pts.isNotEmpty()) ways.add(OsmWay(p[1].toLong(), surfaceOf(p[2]), pts))
                        }
                    }
                }
                flush()
            }.onFailure { store.batches.clear() }
            return store
        }

        // Formattazione a mano a 6 decimali: String.format su 200k punti costava
        // decine di secondi sul Karoo a ogni salvataggio (import storico 06/09)
        private fun encodePoints(pts: List<Pair<Double, Double>>): String {
            val sb = StringBuilder(pts.size * 22)
            for ((i, p) in pts.withIndex()) {
                if (i > 0) sb.append(';')
                appendFixed6(sb, p.first); sb.append(','); appendFixed6(sb, p.second)
            }
            return sb.toString()
        }

        private fun appendFixed6(sb: StringBuilder, v: Double) {
            var micro = Math.round(v * 1_000_000.0)
            if (micro < 0) { sb.append('-'); micro = -micro }
            val whole = micro / 1_000_000
            val frac = micro % 1_000_000
            sb.append(whole).append('.')
            val f = frac.toString()
            repeat(6 - f.length) { sb.append('0') }
            sb.append(f)
        }

        private fun decodePoints(s: String): List<Pair<Double, Double>> =
            s.split(';').filter { it.isNotBlank() }.map {
                val c = it.split(',')
                c[0].toDouble() to c[1].toDouble()
            }
    }

    // ── geometria ────────────────────────────────────────────────────────

    /** Proiezione equirettangolare locale in metri. */
    private class LocalProjection(refLat: Double) {
        private val mPerDegLat = 111_320.0
        private val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat))
        fun x(lon: Double) = lon * mPerDegLon
        fun y(lat: Double) = lat * mPerDegLat
    }

    private class Seg(
        val ax: Double, val ay: Double, val bx: Double, val by: Double,
        val surface: Surface, val wayId: Long, val cumStartM: Double, val lenM: Double,
    )

    /** Segmento più vicino a un punto: distanza, fondo, way e posizione lungo la way. */
    private class Hit(val distM: Double, val surface: Surface, val wayId: Long, val alongM: Double)

    /** Indice a griglia di segmenti: risponde con il segmento più vicino a un punto. */
    private class SegmentIndex(private val proj: LocalProjection) {
        private val grid = HashMap<Long, MutableList<Seg>>()

        private fun key(cx: Long, cy: Long) = cx * 1_000_003L + cy

        fun addPolyline(points: List<Pair<Double, Double>>, surface: Surface, wayId: Long) {
            if (points.isEmpty()) return
            // Un punto solo (run degenere): segmento di lunghezza zero
            val n = if (points.size == 1) 1 else points.size - 1
            var cum = 0.0
            for (j in 0 until n) {
                val a = points[j]
                val b = points[minOf(j + 1, points.size - 1)]
                val ax = proj.x(a.second); val ay = proj.y(a.first)
                val bx = proj.x(b.second); val by = proj.y(b.first)
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                val seg = Seg(ax, ay, bx, by, surface, wayId, cum, len)
                cum += len
                val cx0 = floor(minOf(seg.ax, seg.bx) / GRID_CELL_M).toLong()
                val cx1 = floor(maxOf(seg.ax, seg.bx) / GRID_CELL_M).toLong()
                val cy0 = floor(minOf(seg.ay, seg.by) / GRID_CELL_M).toLong()
                val cy1 = floor(maxOf(seg.ay, seg.by) / GRID_CELL_M).toLong()
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(seg)
                }
            }
        }

        /** Segmento più vicino nelle celle attorno; distM = MAX_VALUE se nessuno. */
        fun nearest(px: Double, py: Double): Hit {
            var best = Hit(Double.MAX_VALUE, Surface.UNKNOWN, 0L, 0.0)
            val cx = floor(px / GRID_CELL_M).toLong()
            val cy = floor(py / GRID_CELL_M).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                grid[key(cx + dx, cy + dy)]?.forEach { seg ->
                    val (d, t) = project(px, py, seg)
                    if (d < best.distM) best = Hit(d, seg.surface, seg.wayId, seg.cumStartM + t * seg.lenM)
                }
            }
            return best
        }

        /** (distanza dal segmento, parametro 0..1 della proiezione lungo il segmento). */
        private fun project(px: Double, py: Double, s: Seg): Pair<Double, Double> {
            val dx = s.bx - s.ax
            val dy = s.by - s.ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0
                else (((px - s.ax) * dx + (py - s.ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = s.ax + t * dx
            val cy = s.ay + t * dy
            return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) to t
        }
    }
}
