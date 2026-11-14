package com.anpaiss.bettereta

/** A climb of the route: from [startM] to [endM] along it, [gainM] of ascent. */
data class RouteClimb(val startM: Double, val endM: Double, val gainM: Double)

/**
 * The route's climbs, for "Time to top" and "At the top" (14/11/2026). The Karoo sends none for
 * imported routes (our logs, September to November 2026: always 0), so they are found on the route's
 * own elevation, with the same detector as RouteProfile (ClimbDetector there, checked on the seven
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
