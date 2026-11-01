package com.anpaiss.bettereta

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The way back home, built from the ride itself: the track ridden so far,
 * reduced to a spine of points every [stepM], that the rider would retrace to
 * get back to the start. No routing: the Karoo SDK cannot compute a route, so
 * the only return path we can be sure of is the one already ridden.
 *
 * Two states. *Exploring*: the rider is on new ground and every fix extends
 * the spine. *Following*: the rider is back on ground already ridden (a loop
 * closed, or the return leg of an out-and-back) and the head moves along the
 * spine instead; the part of the spine beyond the head is the loop just
 * closed, which is not part of the way home. Leaving the spine again
 * truncates it at the head and goes back to exploring.
 *
 * A rejoin is only accepted after [rejoinConfirm] consecutive fixes within
 * [rejoinM] of the spine, all near the same spine point, and only against
 * spine points more than [rejoinMinBehindM] behind the head along the track:
 * the two legs of a hairpin run close to each other for a few tens of metres,
 * and a rejoin there would cut the hairpin out of the way back.
 *
 * Return waypoints are the spine from the head back to the start, so the
 * segments built from them have the gradients inverted: the climb out is the
 * descent home. Surfaces come from the caller, [Surface.UNKNOWN] when it has
 * none; the segment pipeline treats them like any other route.
 */
class ReturnTrack(
    private val stepM: Double = 20.0,
    private val rejoinM: Double = 15.0,
    private val rejoinConfirm: Int = 3,
    private val rejoinMinBehindM: Double = 200.0,
) {
    private class Pt(val lat: Double, val lon: Double, val elevM: Double, val surface: Surface, val wayKey: Long, val cumM: Double)

    private val spine = ArrayList<Pt>()
    private var headIdx = -1          // -1 while exploring (the head is the last spine point)
    private var lastFixLat = Double.NaN
    private var lastFixLon = Double.NaN
    // Rejoin candidate while exploring: spine index and run of consecutive confirmations
    private var candIdx = -1
    private var candRun = 0
    // Fixes off the spine while following, appended to the spine once the departure is confirmed
    private val lost = ArrayList<Pt>()

    /** Number of rejoins accepted so far; a diagnostic for the replay. */
    var rejoins = 0
        private set

    /** Metres of spine dropped by rejoins (the loops closed); a diagnostic for the replay. */
    var droppedM = 0.0
        private set

    val isFollowing: Boolean get() = headIdx >= 0

    /** Points on the spine between the start and the head. */
    val size: Int get() = if (spine.isEmpty()) 0 else headIndex() + 1

    /** Length of the way back along the spine, metres. 0 before the first fix. */
    val returnDistanceM: Double get() = if (spine.isEmpty()) 0.0 else spine[headIndex()].cumM

    /**
     * Feed a fix. Fixes closer than [stepM] to the previous accepted fix are
     * ignored, so a rider standing still does not grow the spine with GPS
     * jitter. Returns true when the spine or the head changed.
     */
    fun add(lat: Double, lon: Double, elevM: Double, surface: Surface = Surface.UNKNOWN, wayKey: Long = 0L): Boolean {
        if (spine.isEmpty()) {
            spine += Pt(lat, lon, elevM, surface, wayKey, 0.0)
            lastFixLat = lat; lastFixLon = lon
            return true
        }
        if (distM(lastFixLat, lastFixLon, lat, lon) < stepM) return false
        lastFixLat = lat; lastFixLon = lon
        return if (headIdx < 0) explore(lat, lon, elevM, surface, wayKey) else follow(lat, lon, elevM, surface, wayKey)
    }

    /** Forget everything: a new ride. */
    fun reset() {
        spine.clear(); headIdx = -1; candIdx = -1; candRun = 0; lost.clear()
        lastFixLat = Double.NaN; lastFixLon = Double.NaN
        rejoins = 0; droppedM = 0.0
    }

    /** The way back: head first, start last. Empty before the first fix. */
    fun returnWaypoints(): List<RouteWaypoint> {
        if (spine.isEmpty()) return emptyList()
        val head = headIndex()
        return List(head + 1) { i -> spine[head - i].let { RouteWaypoint(it.lat, it.lon, it.elevM) } }
    }

    /** Surfaces parallel to [returnWaypoints]. */
    fun returnSurfaces(): List<Surface> {
        if (spine.isEmpty()) return emptyList()
        val head = headIndex()
        return List(head + 1) { i -> spine[head - i].surface }
    }

    /**
     * Way keys parallel to [returnWaypoints], with the direction flipped: the
     * way back runs each way the other way round, and the way memory keeps
     * the two directions apart. 0 stays 0.
     */
    fun returnWayKeys(): List<Long> {
        if (spine.isEmpty()) return emptyList()
        val head = headIndex()
        return List(head + 1) { i ->
            val k = spine[head - i].wayKey
            if (k == 0L) 0L else WaySpeedMemory.wayKey(WaySpeedMemory.wayIdOf(k), !WaySpeedMemory.isForward(k))
        }
    }

    /**
     * The way back as route segments, built by the same pipeline that serves
     * the loaded route, so the speed model sees them the same way. Empty when
     * the rider is still at the start.
     */
    suspend fun returnSegments(): List<RouteSegment> {
        val wps = returnWaypoints()
        if (wps.size < 2) return emptyList()
        val p = SegmentPipeline()
        p.setRoute(wps)
        p.applySurfaces(returnSurfaces(), returnWayKeys())
        return p.remainingSegments()
    }

    // ── private ──────────────────────────────────────────────────────────

    private fun headIndex() = if (headIdx >= 0) headIdx else spine.size - 1

    private fun explore(lat: Double, lon: Double, elevM: Double, surface: Surface, wayKey: Long): Boolean {
        val last = spine.last()
        spine += Pt(lat, lon, elevM, surface, wayKey, last.cumM + distM(last.lat, last.lon, lat, lon))
        // Back at the start: home is right here, no confirmation needed. A loop
        // that ends where it began from another direction never runs along the
        // spine, so the general rule below would never close it. Not in the
        // first metres, where every fix is still next to the start.
        if (spine.last().cumM > rejoinMinBehindM && distM(spine[0].lat, spine[0].lon, lat, lon) <= START_REJOIN_M) {
            droppedM += spine.last().cumM
            spine.subList(1, spine.size).clear()
            headIdx = 0
            rejoins++
            candIdx = -1; candRun = 0
            return true
        }
        val limitCum = spine.last().cumM - rejoinMinBehindM
        val j = nearestSpineIdx(lat, lon, 0, spine.size - 1) { it.cumM <= limitCum }
        if (j < 0) { candIdx = -1; candRun = 0; return true }
        candRun = if (candIdx >= 0 && abs(j - candIdx) <= 3) candRun + 1 else 1
        candIdx = j
        if (candRun < rejoinConfirm) return true
        // The last candRun points all sat on the spine near j: they are the
        // closing of a loop, not new ground. Drop them and follow from j.
        val cutFromM = spine[j].cumM
        repeat(candRun) { spine.removeAt(spine.size - 1) }
        droppedM += spine.last().cumM - cutFromM
        spine.subList(j + 1, spine.size).clear()
        headIdx = j
        rejoins++
        candIdx = -1; candRun = 0
        return true
    }

    private fun follow(lat: Double, lon: Double, elevM: Double, surface: Surface, wayKey: Long): Boolean {
        val headCum = spine[headIdx].cumM
        val lo = lowerBound(headCum - FOLLOW_WINDOW_M)
        val hi = minOf(spine.size - 1, lowerBound(headCum + FOLLOW_WINDOW_M))
        val j = nearestSpineIdx(lat, lon, lo, hi) { true }
        if (j >= 0) {
            lost.clear()
            if (j == headIdx) return false
            headIdx = j
            return true
        }
        val prev = lost.lastOrNull() ?: spine[headIdx]
        lost += Pt(lat, lon, elevM, surface, wayKey, prev.cumM + distM(prev.lat, prev.lon, lat, lon))
        if (lost.size < rejoinConfirm) return false
        // Off the spine for good: the way back now runs through these new points.
        spine.subList(headIdx + 1, spine.size).clear()
        spine += lost
        lost.clear()
        headIdx = -1
        return true
    }

    /** Index of the spine point within [rejoinM] of the fix among [lo]..[hi] that pass [accept], or -1. */
    private inline fun nearestSpineIdx(lat: Double, lon: Double, lo: Int, hi: Int, accept: (Pt) -> Boolean): Int {
        var best = -1
        var bestD = rejoinM
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
        for (i in lo..hi) {
            val p = spine[i]
            // Cheap bounding box before the distance
            val dy = (p.lat - lat) * 111_320.0
            if (dy > bestD || dy < -bestD) continue
            val dx = (p.lon - lon) * mPerDegLon
            if (dx > bestD || dx < -bestD) continue
            if (!accept(p)) continue
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** First spine index whose cumulative distance is >= [cumM]. */
    private fun lowerBound(cumM: Double): Int {
        var lo = 0; var hi = spine.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (spine[mid].cumM < cumM) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        // While following, the head is looked for within this distance along the spine
        private const val FOLLOW_WINDOW_M = 300.0
        // Within this distance of the first point the rider is home: rejoin at once
        private const val START_REJOIN_M = 30.0
    }
}
