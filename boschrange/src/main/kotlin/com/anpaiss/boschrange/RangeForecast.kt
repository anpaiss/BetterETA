package com.anpaiss.boschrange

import com.anpaiss.bettereta.RouteSegment
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin

/**
 * Battery at arrival, from the route still to ride (step 3, 19/10/2026).
 *
 * The energy of a segment is the same physics as [EnergyModel.tickWh], with the
 * speed we do not know yet taken from a table per gradient band: the average
 * speed and the share of the distance ridden with assistance (below the 25 km/h
 * cutoff) measured on the ride of 18/10 (5 s samples, 21.9 km, D+ 712 m).
 * Below the cutoff the aero term is small next to gravity and rolling, so a
 * rough speed does little harm; what matters is the cutoff share on the
 * descents and the flat, where the battery pays nothing.
 *
 *   arrival % = now % − remaining mechanical Wh × s / capacity × 100
 */
object RangeForecast {

    /** Representative assisted speed per [RideRecord.GRADE_BANDS] band, m/s (18/10: 22.7, 22.6, 16.6, 14.1, 12.7, 9.9, 9.6 km/h). */
    val SPEED_MS = doubleArrayOf(5.0, 5.0, 4.6, 3.9, 3.5, 2.75, 2.65)
    /** Share of the distance ridden with assistance in each band (18/10: 63, 70, 85 % and 100 % uphill). */
    val ASSIST_SHARE = doubleArrayOf(0.63, 0.70, 0.85, 1.0, 1.0, 1.0, 1.0)

    /** Relative error assumed on the remaining consumption until the regression has [CALIBRATION_RIDES] rides. */
    const val UNCALIBRATED_REL_ERROR = 0.20
    const val CALIBRATION_RIDES = 5
    /** Smallest half band shown, in % points. */
    const val MIN_HALF_BAND_PCT = 2.0
    /** Wh of battery per metre of ascent when the ride has not climbed enough to measure it: 109 kg, gravity + rolling at 8 %, s = 1.25. */
    const val DEFAULT_WH_PER_CLIMB_M = 0.42

    class Result(
        val nowPct: Double,
        val arrivalPct: Double,
        val halfBandPct: Double,
        val remainingWhMech: Double,
        val remainingKm: Double,
        val remainingAscentM: Double,
        val marginPct: Double,
        val extraClimbM: Double,
    )

    /** Mechanical Wh the battery will pay for on [segments]. */
    fun remainingWhMech(segments: List<RouteSegment>, model: EnergyModel): Double {
        var wh = 0.0
        for (seg in segments) {
            val band = RideRecord.gradeBand(seg.gradePct)
            val v = SPEED_MS[band]
            val p = model.powerW(v, seg.gradePct, seg.surface)
            if (p <= 0.0) continue
            wh += p * (seg.distanceM / v) / 3600.0 * ASSIST_SHARE[band]
        }
        return wh
    }

    fun remainingAscentM(segments: List<RouteSegment>): Double =
        segments.sumOf { s -> (s.distanceM * sin(atan(s.gradePct / 100.0))).coerceAtLeast(0.0) }

    /**
     * @param nowPct       battery % the model believes now (or the planned start % before the ride)
     * @param sUsed        battery Wh per mechanical Wh
     * @param relError     relative error assumed on the remaining consumption ([relativeError])
     * @param whPerClimbM  battery Wh per metre of ascent ([whPerClimbM]) for the "climb still possible"
     */
    fun forecast(
        segments: List<RouteSegment>,
        model: EnergyModel,
        nowPct: Double,
        capacityWh: Double,
        sUsed: Double,
        thresholdPct: Int,
        relError: Double,
        whPerClimbM: Double,
    ): Result {
        val whMech = remainingWhMech(segments, model)
        val spentPct = whMech * sUsed / capacityWh * 100.0
        val arrival = nowPct - spentPct
        val halfBand = (spentPct * relError).coerceAtLeast(MIN_HALF_BAND_PCT)
        val margin = arrival - thresholdPct
        val extraClimb = margin / 100.0 * capacityWh / whPerClimbM
        return Result(nowPct, arrival, halfBand, whMech, segments.sumOf { it.distanceM } / 1000.0, remainingAscentM(segments),
            margin, extraClimb)
    }

    /**
     * Relative error on the consumption: fixed until [CALIBRATION_RIDES] valid
     * rides, then the mean relative miss of s on those rides (what the model
     * used against what the battery really spent).
     */
    fun relativeError(rides: List<RideRecord>): Double {
        val valid = rides.filter { it.validForRegression && it.whMech > 1.0 }
        if (valid.size < CALIBRATION_RIDES) return UNCALIBRATED_REL_ERROR
        return valid.map { r -> abs(r.whReal!! / r.whMech - r.sUsed) / r.sUsed }.average().coerceAtLeast(0.05)
    }

    /** Battery Wh per metre of ascent: measured on this ride once it has climbed 100 m, the default before. */
    fun whPerClimbM(ride: RideRecord?, sUsed: Double): Double {
        if (ride == null || ride.ascentIntegratedM < 100.0) return DEFAULT_WH_PER_CLIMB_M
        return (ride.whMech * sUsed / ride.ascentIntegratedM).coerceIn(0.2, 1.0)
    }
}
