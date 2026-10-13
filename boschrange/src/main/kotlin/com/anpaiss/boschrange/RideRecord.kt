package com.anpaiss.boschrange

import com.anpaiss.bettereta.Surface
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan
import kotlin.math.sin

/** Road condition entered by the rider. Recorded only, never an input of the model ("condizione", not "fondo"). */
enum class Condition { DRY, WET, MUD }

/** A battery percentage the rider typed during the ride, with what the model thought at that moment. */
class ManualUpdate(val timeMs: Long, val distanceKm: Double, val estimatePct: Double, val enteredPct: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("time_ms", timeMs).put("km", r1(distanceKm)).put("estimate_pct", r1(estimatePct)).put("entered_pct", enteredPct)

    companion object {
        fun fromJson(o: JSONObject) = ManualUpdate(o.getLong("time_ms"), o.getDouble("km"), o.getDouble("estimate_pct"), o.getInt("entered_pct"))
    }
}

/**
 * One ride of the history: what the rider entered (start, arrival, manual
 * updates, condition), what the Karoo measured (distance, ascent, descent,
 * temperature), what we integrated (time per gradient band, km and ascent per
 * surface class, mechanical Wh) and what the model used (capacity, s). The
 * fields "to fill by hand from the Flow app" start null.
 *
 * Mutable while the ride is open; serialized to JSON on every save.
 */
class RideRecord(
    val startMs: Long,
    val capacityWh: Double,
    val sUsed: Double,
) {
    var endMs: Long = 0L
    var routeName: String = ""
    var routeDistanceM: Double = -1.0
    /** Positive elevation of the route profile at load, from routeElevationPolyline. */
    var routeAscentM: Double = -1.0
    /** The Karoo's own ASCENT_REMAINING at ride start. */
    var karooAscentRemainingAtStartM: Double = -1.0

    var startPct: Int = 100
    var startAssumed: Boolean = true         // true = popup skipped, 100 % by default
    var arrivalPct: Int? = null
    var condition: Condition? = null
    val manualUpdates = mutableListOf<ManualUpdate>()

    // Karoo streams at the last tick
    var distanceM: Double = 0.0
    var ascentM: Double = -1.0
    var descentM: Double = -1.0
    var temperatureSum: Double = 0.0
    var temperatureN: Int = 0

    // Integrated per tick while moving
    var movingS: Double = 0.0
    var ascentIntegratedM: Double = 0.0
    val secondsByGrade = DoubleArray(GRADE_BANDS.size)
    val kmBySurface = DoubleArray(Surface.entries.size)
    val ascentBySurface = DoubleArray(Surface.entries.size)
    var whMech: Double = 0.0

    // Anchor of the estimate: the last percentage the rider entered and the Wh at that moment
    var anchorPct: Double = 100.0
    var anchorWhMech: Double = 0.0

    // To fill by hand after the ride, from the Bosch Flow app
    var flowRiderSharePct: Double? = null
    var flowKmByMode: Map<String, Double>? = null
    var notes: String = ""

    val temperatureC: Double? get() = if (temperatureN > 0) temperatureSum / temperatureN else null
    val whEstimated: Double get() = whMech * sUsed
    val whReal: Double? get() = arrivalPct?.let { (startPct - it) / 100.0 * capacityWh }
    val distanceKm: Double get() = distanceM / 1000.0

    /** Battery % the model believes now: anchor minus what was spent since the anchor. */
    fun estimatedPct(): Double = anchorPct - (whMech - anchorWhMech) * sUsed / capacityWh * 100.0

    fun setStart(pct: Int, assumed: Boolean) {
        startPct = pct; startAssumed = assumed
        anchorPct = pct.toDouble(); anchorWhMech = whMech
    }

    fun anchor(pct: Int, nowMs: Long) {
        manualUpdates += ManualUpdate(nowMs, distanceKm, estimatedPct(), pct)
        anchorPct = pct.toDouble(); anchorWhMech = whMech
    }

    /** One tick of the ride while recording. [dtSec] real elapsed time since the previous tick. */
    fun tick(speedMs: Double, gradePct: Double, surface: Surface, dtSec: Double, model: EnergyModel, temperatureC: Double?) {
        if (temperatureC != null) { temperatureSum += temperatureC; temperatureN++ }
        if (speedMs < MOVING_MS) return
        movingS += dtSec
        val d = speedMs * dtSec
        secondsByGrade[gradeBand(gradePct)] += dtSec
        kmBySurface[surface.ordinal] += d / 1000.0
        val dh = d * sin(atan(gradePct / 100.0))
        if (dh > 0.0) { ascentIntegratedM += dh; ascentBySurface[surface.ordinal] += dh }
        whMech += model.tickWh(speedMs, gradePct, surface, dtSec)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("model", MODEL_VERSION)
        put("start_ms", startMs)
        put("end_ms", endMs)
        put("route_name", routeName)
        put("route_distance_m", r0(routeDistanceM))
        put("route_ascent_m", r0(routeAscentM))
        put("karoo_ascent_remaining_at_start_m", r0(karooAscentRemainingAtStartM))
        put("capacity_wh", capacityWh)
        put("s_used", sUsed)
        put("start_pct", startPct)
        put("start_assumed", startAssumed)
        put("arrival_pct", arrivalPct ?: JSONObject.NULL)
        put("condition", condition?.name ?: JSONObject.NULL)
        put("manual_updates", JSONArray().apply { manualUpdates.forEach { put(it.toJson()) } })
        put("distance_km", r1(distanceKm))
        put("moving_s", r0(movingS))
        put("ascent_karoo_m", r0(ascentM))
        put("descent_karoo_m", r0(descentM))
        put("ascent_integrated_m", r0(ascentIntegratedM))
        put("temperature_c", temperatureC?.let { r1(it) } ?: JSONObject.NULL)
        put("seconds_by_grade", JSONObject().apply { GRADE_BANDS.forEachIndexed { i, b -> put(b, r0(secondsByGrade[i])) } })
        put("km_by_surface", JSONObject().apply { Surface.entries.forEach { put(it.name, r1(kmBySurface[it.ordinal])) } })
        put("ascent_by_surface_m", JSONObject().apply { Surface.entries.forEach { put(it.name, r0(ascentBySurface[it.ordinal])) } })
        put("wh_mech", r1(whMech))
        put("wh_estimated", r1(whEstimated))
        put("wh_real", whReal?.let { r1(it) } ?: JSONObject.NULL)
        put("anchor_pct", r1(anchorPct))
        put("anchor_wh_mech", r1(anchorWhMech))
        put("flow_rider_share_pct", flowRiderSharePct ?: JSONObject.NULL)
        put("flow_km_by_mode", flowKmByMode?.let { m -> JSONObject().apply { m.forEach { (k, v) -> put(k, v) } } } ?: JSONObject.NULL)
        put("notes", notes)
    }

    companion object {
        const val FORMAT_VERSION = 1
        /** Bumped whenever the energy model changes: the regression must not mix versions. */
        const val MODEL_VERSION = "2026-10-13 physics-s"
        const val MOVING_MS = 0.5
        /** Gradient bands in %, lower edge inclusive. */
        val GRADE_BANDS = listOf("lt-6", "-6..-3", "-3..3", "3..6", "6..10", "10..15", "ge15")
        private val GRADE_EDGES = doubleArrayOf(-6.0, -3.0, 3.0, 6.0, 10.0, 15.0)

        fun gradeBand(gradePct: Double): Int {
            var i = 0
            while (i < GRADE_EDGES.size && gradePct >= GRADE_EDGES[i]) i++
            return i
        }

        fun fromJson(o: JSONObject): RideRecord {
            val r = RideRecord(o.getLong("start_ms"), o.getDouble("capacity_wh"), o.getDouble("s_used"))
            r.endMs = o.optLong("end_ms", 0L)
            r.routeName = o.optString("route_name", "")
            r.routeDistanceM = o.optDouble("route_distance_m", -1.0)
            r.routeAscentM = o.optDouble("route_ascent_m", -1.0)
            r.karooAscentRemainingAtStartM = o.optDouble("karoo_ascent_remaining_at_start_m", -1.0)
            r.startPct = o.optInt("start_pct", 100)
            r.startAssumed = o.optBoolean("start_assumed", true)
            r.arrivalPct = if (o.isNull("arrival_pct")) null else o.getInt("arrival_pct")
            r.condition = if (o.isNull("condition")) null else runCatching { Condition.valueOf(o.getString("condition")) }.getOrNull()
            o.optJSONArray("manual_updates")?.let { a -> for (i in 0 until a.length()) r.manualUpdates += ManualUpdate.fromJson(a.getJSONObject(i)) }
            r.distanceM = o.optDouble("distance_km", 0.0) * 1000.0
            r.movingS = o.optDouble("moving_s", 0.0)
            r.ascentM = o.optDouble("ascent_karoo_m", -1.0)
            r.descentM = o.optDouble("descent_karoo_m", -1.0)
            r.ascentIntegratedM = o.optDouble("ascent_integrated_m", 0.0)
            if (!o.isNull("temperature_c")) { r.temperatureSum = o.getDouble("temperature_c"); r.temperatureN = 1 }
            o.optJSONObject("seconds_by_grade")?.let { g -> GRADE_BANDS.forEachIndexed { i, b -> r.secondsByGrade[i] = g.optDouble(b, 0.0) } }
            o.optJSONObject("km_by_surface")?.let { g -> Surface.entries.forEach { r.kmBySurface[it.ordinal] = g.optDouble(it.name, 0.0) } }
            o.optJSONObject("ascent_by_surface_m")?.let { g -> Surface.entries.forEach { r.ascentBySurface[it.ordinal] = g.optDouble(it.name, 0.0) } }
            r.whMech = o.optDouble("wh_mech", 0.0)
            r.anchorPct = o.optDouble("anchor_pct", r.startPct.toDouble())
            r.anchorWhMech = o.optDouble("anchor_wh_mech", 0.0)
            r.flowRiderSharePct = if (o.isNull("flow_rider_share_pct")) null else o.getDouble("flow_rider_share_pct")
            r.flowKmByMode = o.optJSONObject("flow_km_by_mode")?.let { m -> m.keys().asSequence().associateWith { m.getDouble(it) } }
            r.notes = o.optString("notes", "")
            return r
        }
    }
}

private fun r0(v: Double): Double = Math.round(v).toDouble()
private fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0
