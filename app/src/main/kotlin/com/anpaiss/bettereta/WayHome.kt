package com.anpaiss.bettereta

/**
 * What the Way Home field shows.
 *
 * @param returnSec       moving time to retrace the track to the start; -1 until a track exists
 * @param returnDistM     length of the way back along the track
 * @param following       true while the rider is already on the way back (on ground already ridden)
 * @param arrivalEpochMs  now + returnSec; 0 without an estimate
 * @param limitEpochMs    the daylight limit (dusk or sunset, minus the margin); 0 when the Karoo
 *                        has not published the sun times yet
 * @param allowanceSec    minutes of further outbound riding the limit still allows, in seconds;
 *                        null without a limit or while following. Negative = already too late.
 * @param growth          how fast the way back grows per second of outbound riding (the k of the
 *                        design note): 1.0 until measured
 */
data class WayHomeState(
    val returnSec: Long = -1L,
    val returnDistM: Double = 0.0,
    val following: Boolean = false,
    val arrivalEpochMs: Long = 0L,
    val limitEpochMs: Long = 0L,
    val allowanceSec: Long? = null,
    val growth: Double = 1.0,
) {
    companion object {
        /** The profile editor preview: 38 minutes of exploring left, home at 16:50. */
        fun sample(nowMs: Long = System.currentTimeMillis()) = WayHomeState(
            returnSec = 48 * 60L, returnDistM = 11_300.0, following = false,
            arrivalEpochMs = nowMs + 48 * 60_000L, limitEpochMs = nowMs + 125 * 60_000L,
            allowanceSec = 38 * 60L, growth = 1.0,
        )
    }
}

/**
 * The way-home estimate: feeds the [ReturnTrack] with the ride, asks the
 * [EtaEngine] for the time to retrace it, and turns the daylight limit into
 * minutes of further outbound riding.
 *
 * The allowance is `(limit - now - returnSec) / (1 + growth)`: every second
 * ridden outbound costs itself plus the extra way back it adds, and the extra
 * is measured, not assumed: the change of the return estimate over the last
 * ten minutes of moving time, divided by that moving time. Climbing a road at
 * 8 km/h that will be descended at 25 gives a growth of about 0.3; a flat trail
 * ridden at the same pace both ways gives 1.
 *
 * Elevation is integrated from the Karoo's gradient and the distance covered
 * each tick: only differences matter for the gradients of the way back, and
 * that is exactly what the gradient stream measures.
 *
 * Single-threaded by design: everything is called from the extension's ticker.
 */
class WayHome(
    private val track: ReturnTrack = ReturnTrack(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val RECOMPUTE_EVERY_MS = 30_000L
        private const val GROWTH_WINDOW_SEC = 600.0
        private const val GROWTH_MIN_SPAN_SEC = 120.0
        private const val GROWTH_MIN = 0.2
        private const val GROWTH_MAX = 3.0
    }

    private class Mark(val movingSec: Double, val returnSec: Long)

    @Volatile var state = WayHomeState()
        private set

    private var elevM = 0.0
    private var movingSec = 0.0
    private var lastRecomputeMs = 0L
    private var rejoinsSeen = 0
    private var growth = 1.0
    private val marks = ArrayDeque<Mark>()

    val returnDistanceM: Double get() = track.returnDistanceM
    val isFollowing: Boolean get() = track.isFollowing

    /** A new ride: forget the track. */
    fun reset() {
        track.reset()
        elevM = 0.0; movingSec = 0.0; lastRecomputeMs = 0L; rejoinsSeen = 0; growth = 1.0
        marks.clear()
        state = WayHomeState()
    }

    /**
     * One ticker step. Returns true when the estimate is due for a [recompute].
     *
     * @param fix     the current GPS position, null when stale
     * @param moving  speed above the standstill threshold on this tick
     */
    fun onTick(fix: Pair<Double, Double>?, speedMs: Double, gradePct: Double, tickSec: Double,
               moving: Boolean, surface: Surface, wayKey: Long): Boolean {
        if (moving) {
            elevM += gradePct / 100.0 * speedMs * tickSec
            movingSec += tickSec
        }
        if (fix != null) track.add(fix.first, fix.second, elevM, surface, wayKey)
        return nowMs() - lastRecomputeMs >= RECOMPUTE_EVERY_MS
    }

    /**
     * Rebuild the estimate. [limitEpochMs] is the daylight limit already
     * reduced by the margin, 0 when unknown.
     */
    suspend fun recompute(engine: EtaEngine, limitEpochMs: Long): WayHomeState {
        val now = nowMs()
        lastRecomputeMs = now
        val segs = track.returnSegments()
        if (segs.isEmpty()) {
            state = WayHomeState(limitEpochMs = limitEpochMs)
            return state
        }
        val returnSec = engine.computeBreakdown(segs).seconds
        val following = track.isFollowing
        if (track.rejoins != rejoinsSeen) {
            // A loop just closed: the way back shrank by the loop, which says
            // nothing about how fast it grows while exploring
            rejoinsSeen = track.rejoins
            marks.clear()
        }
        if (!following) {
            marks.addLast(Mark(movingSec, returnSec))
            while (marks.size > 1 && movingSec - marks.first().movingSec > GROWTH_WINDOW_SEC) marks.removeFirst()
            val oldest = marks.first()
            val span = movingSec - oldest.movingSec
            if (span >= GROWTH_MIN_SPAN_SEC) {
                growth = ((returnSec - oldest.returnSec) / span).coerceIn(GROWTH_MIN, GROWTH_MAX)
            }
        } else {
            marks.clear()
        }
        val allowance = if (limitEpochMs > 0L && !following)
            ((limitEpochMs - now) / 1000L - returnSec).let { (it / (1.0 + growth)).toLong() }
            else null
        state = WayHomeState(
            returnSec = returnSec,
            returnDistM = segs.sumOf { it.distanceM },
            following = following,
            arrivalEpochMs = now + returnSec * 1000L,
            limitEpochMs = limitEpochMs,
            allowanceSec = allowance,
            growth = growth,
        )
        return state
    }
}

/**
 * When the Way Home alerts fire. Each one fires once on crossing its line and
 * re-arms only after the margin climbs back by five minutes, so GPS noise and
 * the estimate's own wobble do not repeat it. Turn-back alerts make no sense
 * on the way back; "home after dusk" is for both legs.
 */
class WayHomeAlertGate(private val leadSec: Long) {
    enum class Alert { TURN_SOON, TURN_NOW, AFTER_DUSK }

    private val fired = HashSet<Alert>()

    companion object {
        private const val REARM_SEC = 300L
    }

    /** @param duskEpochMs the bare dusk time, without the margin: "after dusk" means in the dark. */
    fun onState(s: WayHomeState, duskEpochMs: Long): List<Alert> {
        val out = ArrayList<Alert>()
        val a = s.allowanceSec
        if (a != null && !s.following) {
            if (a <= 0) fire(Alert.TURN_NOW, out) else if (a >= REARM_SEC) fired.remove(Alert.TURN_NOW)
            if (a <= leadSec) fire(Alert.TURN_SOON, out) else if (a >= leadSec + REARM_SEC) fired.remove(Alert.TURN_SOON)
        }
        if (duskEpochMs > 0L && s.arrivalEpochMs > 0L) {
            val slackSec = (duskEpochMs - s.arrivalEpochMs) / 1000L
            if (slackSec < 0) fire(Alert.AFTER_DUSK, out) else if (slackSec >= REARM_SEC) fired.remove(Alert.AFTER_DUSK)
        }
        return out
    }

    private fun fire(a: WayHomeAlertGate.Alert, out: MutableList<Alert>) {
        if (fired.add(a)) out += a
    }
}
