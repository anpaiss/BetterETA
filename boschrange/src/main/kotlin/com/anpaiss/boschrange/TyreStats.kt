package com.anpaiss.boschrange

import kotlin.math.sqrt

/**
 * What each tyre pair cost, over the rides of the history that carry it. The s of a
 * ride (battery Wh / mechanical Wh) already takes out gradient, surface, speed and mass:
 * what is left is mostly the tyres, the assistance mode, the cold and the wet. A few
 * points of difference are the size of the ride-to-ride spread, so [enough] stays false
 * until [MIN_RIDES] measured rides, and the spread is shown next to the mean.
 */
class TyreStats(
    val pairId: String,
    val rides: Int,
    val km: Double,
    /** Rides with both ends of the battery known and enough energy to measure s. */
    val measured: Int,
    /** Σ battery Wh / Σ mechanical Wh over the measured rides: the long rides weigh more. */
    val s: Double?,
    /** Standard deviation of the per-ride s. */
    val sSpread: Double?,
    /** Battery % per 1000 m of ascent, over the measured rides. */
    val pctPer1000m: Double?,
    val sDry: Double?,
    val sWet: Double?,
) {
    val enough: Boolean get() = measured >= MIN_RIDES

    companion object {
        const val MIN_RIDES = 3
        /** Below this the display's ±0.5 % swamps the ride: not a measurement of s. */
        const val MIN_WH_MECH = 50.0

        fun of(history: List<RideRecord>): List<TyreStats> =
            history.filter { it.tyres != null }.groupBy { it.tyres!!.id }.map { (id, rides) ->
                val m = rides.filter { it.whReal != null && it.whMech >= MIN_WH_MECH }
                fun sOf(rs: List<RideRecord>) = if (rs.isEmpty()) null else rs.sumOf { it.whReal!! } / rs.sumOf { it.whMech }
                val per = m.map { it.whReal!! / it.whMech }
                val spread = if (per.size < 2) null else per.average().let { mean -> sqrt(per.sumOf { (it - mean) * (it - mean) } / (per.size - 1)) }
                val climb = m.filter { it.ascentM > 0 }
                val pct = if (climb.isEmpty()) null else climb.sumOf { (it.startPct - it.arrivalPct!!).toDouble() } / climb.sumOf { it.ascentM } * 1000.0
                TyreStats(id, rides.size, rides.sumOf { it.distanceKm }, m.size, sOf(m), spread, pct,
                    sOf(m.filter { it.condition == Condition.DRY }), sOf(m.filter { it.condition == Condition.WET || it.condition == Condition.MUD }))
            }
    }
}
