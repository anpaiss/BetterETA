package com.anpaiss.bettereta

/** A climb of the route: from [startM] to [endM] along it, [gainM] of ascent. */
data class RouteClimb(val startM: Double, val endM: Double, val gainM: Double)

/**
 * The route's climbs found on its own elevation, for "Time to top" and "At the top" (14/11/2026) until
 * the Karoo sends its own: it does during the ride, not before the start nor indoors without GPS.
 * Same detector as RouteProfile (ClimbDetector there, checked on the seven
 * lagorai.bike routes): from the lowest point to the top, ended by a real drop, split at long false
 * flats, flat ends trimmed. Here the elevation is rebuilt from the segments' grades, every [STEP_M].
 */
object RouteClimbs {
    const val STEP_M = 50.0
    private const val SMOOTH_HALF = 2
    /** Climbs from Cat 4 up (80 m of ascent), as RouteProfile counts them by default. */
    const val MIN_TOP_GAIN_M = 80.0
    /** A top counts as passed this far before it, as a POI does. */
    const val PASSED_MARGIN_M = PoiEta.PASSED_MARGIN_M

    private const val DROP_M = 25.0
    private const val DROP_SHARE = 0.2
    private const val FLAT_GRADE = 2.0
    private const val TRIM_M = 150.0
    private const val COARSE_TRIM_M = 500.0
    private const val FLAT_SPLIT_M = 1_500.0
    private const val MIN_GAIN_M = 40.0
    private const val MIN_LENGTH_M = 300.0
    private const val MIN_GRADE = 3.0

    private const val ALIGN_TOLERANCE = 0.15
    private const val ALIGN_TOLERANCE_MIN_M = 15.0

    /**
     * The Karoo's climbs placed on the route, or null when they cannot be trusted (same rule as RouteProfile):
     * when navigation starts part-way the Karoo counts from there (KarooSim, 14/11/2026). Try no shift, then the
     * shifts that line a start or top up with one of [own]; keep the smallest under which every climb rises on
     * the route's elevation [ele] (every [stepM]) as much as the Karoo says.
     */
    fun align(karoo: List<RouteClimb>, own: List<RouteClimb>, ele: DoubleArray, stepM: Double): List<RouteClimb>? {
        if (karoo.isEmpty() || ele.size < 2) return null
        val totalM = (ele.size - 1) * stepM
        fun at(m: Double): Double {
            val x = (m / stepM).coerceIn(0.0, (ele.size - 1).toDouble())
            val i = x.toInt().coerceAtMost(ele.size - 2)
            return ele[i] + (ele[i + 1] - ele[i]) * (x - i)
        }
        val shifts = (listOf(0.0) + own.flatMap { o -> karoo.flatMap { k -> listOf(o.startM - k.startM, o.endM - k.endM) } })
            .distinct().sortedBy { kotlin.math.abs(it) }
        for (d in shifts) {
            val moved = karoo.map { RouteClimb(it.startM + d, it.endM + d, it.gainM) }
            if (moved.any { it.startM < -50.0 || it.endM > totalM + 50.0 }) continue
            if (moved.all { c -> kotlin.math.abs(at(c.endM) - at(c.startM) - c.gainM) <= maxOf(ALIGN_TOLERANCE_MIN_M, ALIGN_TOLERANCE * c.gainM) })
                return moved
        }
        return null
    }

    /** The climbs of the whole route given as its [segments] from the start. */
    fun find(segments: List<RouteSegment>): List<RouteClimb> {
        val ele = elevation(segments)
        if (ele.size < 3) return emptyList()
        return find(ele, STEP_M)
    }

    /** The climb whose top is the next one ahead of [traveledM] (the one under the rider, or the next). */
    fun nextTop(climbs: List<RouteClimb>, traveledM: Double): RouteClimb? =
        climbs.filter { it.gainM >= MIN_TOP_GAIN_M && it.endM > traveledM + PASSED_MARGIN_M }.minByOrNull { it.endM }

    /** Smoothed elevation every [STEP_M] metres, from 0 at the start. */
    fun elevation(segments: List<RouteSegment>): DoubleArray {
        val total = segments.sumOf { it.distanceM }
        val n = (total / STEP_M).toInt() + 1
        if (n < 2) return DoubleArray(0)
        val raw = DoubleArray(n)
        var segIdx = 0
        var segStart = 0.0
        var eleAtSegStart = 0.0
        for (i in 0 until n) {
            val m = i * STEP_M
            while (segIdx < segments.size - 1 && m > segStart + segments[segIdx].distanceM) {
                eleAtSegStart += segments[segIdx].distanceM * segments[segIdx].gradePct / 100.0
                segStart += segments[segIdx].distanceM
                segIdx++
            }
            raw[i] = eleAtSegStart + (m - segStart).coerceAtMost(segments[segIdx].distanceM) * segments[segIdx].gradePct / 100.0
        }
        return DoubleArray(n) { i ->
            val a = maxOf(0, i - SMOOTH_HALF); val b = minOf(n - 1, i + SMOOTH_HALF)
            (a..b).sumOf { raw[it] } / (b - a + 1)
        }
    }

    /** [ele]: smoothed elevation every [stepM] metres. */
    fun find(ele: DoubleArray, stepM: Double): List<RouteClimb> {
        val out = ArrayList<RouteClimb>()
        for ((a, b) in raw(ele)) for ((x, y) in refine(ele, stepM, a, b)) {
            val gain = ele[y] - ele[x]
            val len = (y - x) * stepM
            if (gain >= MIN_GAIN_M && len >= MIN_LENGTH_M && gain / len * 100.0 >= MIN_GRADE)
                out.add(RouteClimb(x * stepM, y * stepM, gain))
        }
        return out
    }

    private fun raw(e: DoubleArray): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var lo = 0
        var top = 0
        for (i in 1 until e.size) {
            if (e[i] > e[top]) top = i
            val gain = e[top] - e[lo]
            if (top > lo && e[top] - e[i] > maxOf(DROP_M, DROP_SHARE * gain)) {
                res.add(lo to top); lo = i; top = i
            } else if (e[i] < e[lo] && (top <= lo || gain < MIN_GAIN_M)) {
                lo = i; top = i
            }
        }
        if (top > lo) res.add(lo to top)
        return res
    }

    private fun refine(e: DoubleArray, stepM: Double, a: Int, b: Int): List<Pair<Int, Int>> {
        val w = (TRIM_M / stepM).toInt()
        val fs = (FLAT_SPLIT_M / stepM).toInt()
        fun grade(i: Int, j: Int) = (e[j] - e[i]) / ((j - i) * stepM) * 100.0
        val pieces = ArrayList<Pair<Int, Int>>()
        var i = a
        var start = a
        while (i + fs <= b) {
            if (grade(i, i + fs) < FLAT_GRADE) {
                var j = i + fs
                while (j < b && grade(j - fs, j) < FLAT_GRADE) j++
                pieces.add(start to i); start = j - fs; i = j
            } else i++
        }
        pieces.add(start to b)
        return pieces.mapNotNull { (x0, y0) ->
            var x = x0; var y = y0
            // first a coarse trim (a false flat), then a fine one (where the climb really starts and ends)
            for (ww in intArrayOf((COARSE_TRIM_M / stepM).toInt(), w)) {
                while (x + ww < y && grade(x, x + ww) < FLAT_GRADE) x++
                while (y - ww > x && grade(y - ww, y) < FLAT_GRADE) y--
            }
            if (y > x) x to y else null
        }
    }
}
