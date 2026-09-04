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

    /** Esito dell'abbinamento di una route: fondo per waypoint e copertura. */
    class Match(val surfaces: List<Surface>, val covered: BooleanArray) {
        val missing: List<Int> get() = covered.indices.filter { !covered[it] }
        val coveredCount: Int get() = covered.count { it }
    }

    private val batches = ArrayDeque<Batch>()

    val batchCount: Int get() = batches.size
    val wayCount: Int get() = batches.sumOf { it.ways.size }
    val wayPointCount: Int get() = batches.sumOf { it.wayPointCount }

    fun add(batch: Batch) {
        batches.addLast(batch)
        while (batches.size > 1 && wayPointCount > maxWayPoints) batches.removeFirst()
    }

    fun match(waypoints: List<RouteWaypoint>): Match {
        if (waypoints.isEmpty()) return Match(emptyList(), BooleanArray(0))
        val proj = LocalProjection(waypoints.first().lat)

        val wayIndex = SegmentIndex(proj)
        val runIndex = SegmentIndex(proj)
        for (b in batches) {
            for (w in b.ways) wayIndex.addPolyline(w.points, w.surface)
            for (r in b.runs) runIndex.addPolyline(r, Surface.UNKNOWN)
        }

        val surfaces = ArrayList<Surface>(waypoints.size)
        val covered  = BooleanArray(waypoints.size)
        waypoints.forEachIndexed { i, wp ->
            val px = proj.x(wp.lon)
            val py = proj.y(wp.lat)
            val (wayDist, surface) = wayIndex.nearest(px, py)
            surfaces.add(if (wayDist <= MATCH_MAX_DIST_M) surface else Surface.UNKNOWN)
            covered[i] = runIndex.nearest(px, py).first <= COVER_M
        }
        return Match(surfaces, covered)
    }

    // ── serializzazione ──────────────────────────────────────────────────
    //  SURF2
    //  B <timeMs>
    //  R lat,lon;lat,lon;...
    //  W <id> <surface ordinal> lat,lon;lat,lon;...

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        for (b in batches) {
            append("B ").append(b.timeMs).append('\n')
            for (r in b.runs) append("R ").append(encodePoints(r)).append('\n')
            for (w in b.ways) {
                append("W ").append(w.id).append(' ').append(w.surface.ordinal).append(' ')
                    .append(encodePoints(w.points)).append('\n')
            }
        }
    }

    companion object {
        const val MATCH_MAX_DIST_M = 30.0     // waypoint→way oltre questa = UNKNOWN
        const val COVER_M          = 40.0     // waypoint→corridoio già chiesto oltre questa = da chiedere
        const val MAX_WAY_POINTS   = 200_000  // ~5 MB di file, decine di route
        private const val HEADER   = "SURF2"
        private const val GRID_CELL_M = 330.0

        /** Store vuoto se il testo non è nel formato atteso (incluso il file legacy JSON). */
        fun parse(text: String, maxWayPoints: Int = MAX_WAY_POINTS): SurfaceWayStore {
            val store = SurfaceWayStore(maxWayPoints)
            val lines = text.lineSequence().iterator()
            if (!lines.hasNext() || lines.next().trim() != HEADER) return store

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
                            if (pts.isNotEmpty()) ways.add(OsmWay(p[1].toLong(), Surface.entries[p[2].toInt()], pts))
                        }
                    }
                }
                flush()
            }.onFailure { store.batches.clear() }
            return store
        }

        private fun encodePoints(pts: List<Pair<Double, Double>>): String =
            pts.joinToString(";") { "%.6f,%.6f".format(java.util.Locale.ROOT, it.first, it.second) }

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

    private class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val surface: Surface)

    /** Indice a griglia di segmenti: risponde con il segmento più vicino a un punto. */
    private class SegmentIndex(private val proj: LocalProjection) {
        private val grid = HashMap<Long, MutableList<Seg>>()

        private fun key(cx: Long, cy: Long) = cx * 1_000_003L + cy

        fun addPolyline(points: List<Pair<Double, Double>>, surface: Surface) {
            if (points.isEmpty()) return
            // Un punto solo (run degenere): segmento di lunghezza zero
            val n = if (points.size == 1) 1 else points.size - 1
            for (j in 0 until n) {
                val a = points[j]
                val b = points[minOf(j + 1, points.size - 1)]
                val seg = Seg(proj.x(a.second), proj.y(a.first), proj.x(b.second), proj.y(b.first), surface)
                val cx0 = floor(minOf(seg.ax, seg.bx) / GRID_CELL_M).toLong()
                val cx1 = floor(maxOf(seg.ax, seg.bx) / GRID_CELL_M).toLong()
                val cy0 = floor(minOf(seg.ay, seg.by) / GRID_CELL_M).toLong()
                val cy1 = floor(maxOf(seg.ay, seg.by) / GRID_CELL_M).toLong()
                for (cx in cx0..cx1) for (cy in cy0..cy1) {
                    grid.getOrPut(key(cx, cy)) { mutableListOf() }.add(seg)
                }
            }
        }

        /** (distanza in metri, fondo) del segmento più vicino nelle celle attorno; MAX_VALUE se nessuno. */
        fun nearest(px: Double, py: Double): Pair<Double, Surface> {
            var best = Double.MAX_VALUE
            var bestSurface = Surface.UNKNOWN
            val cx = floor(px / GRID_CELL_M).toLong()
            val cy = floor(py / GRID_CELL_M).toLong()
            for (dx in -1..1) for (dy in -1..1) {
                grid[key(cx + dx, cy + dy)]?.forEach { seg ->
                    val d = pointToSegM(px, py, seg)
                    if (d < best) { best = d; bestSurface = seg.surface }
                }
            }
            return best to bestSurface
        }

        private fun pointToSegM(px: Double, py: Double, s: Seg): Double {
            val dx = s.bx - s.ax
            val dy = s.by - s.ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0
                else (((px - s.ax) * dx + (py - s.ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = s.ax + t * dx
            val cy = s.ay + t * dy
            return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
        }
    }
}
