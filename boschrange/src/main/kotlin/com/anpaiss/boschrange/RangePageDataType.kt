package com.anpaiss.boschrange

import kotlinx.coroutines.flow.StateFlow

/**
 * The boschRange page (step 3, 19/10/2026): battery now and at arrival in
 * big, then range, margin, climb left, Wh spent, route to go, last entry,
 * calibration and surface, one item per row. Same layout and sizing as the
 * debug page; a tap opens "Battery now?".
 */
class RangePageDataType(
    extension: String,
    state: StateFlow<DebugState>,
    visibleDataTypeIds: StateFlow<Set<String>?>,
) : DebugFieldDataType(extension, "page", "BOSCHRANGE", state, visibleDataTypeIds) {

    override fun render(s: DebugState): PageContent {
        if (!s.enabled && !s.rideOpen) return PageContent("OFF", listOf("boschRange" to "disabled in the app"))
        val rows = mutableListOf<Pair<String, String>>()
        val now = if (s.rideOpen) s.estimatedPct else s.planningPct.toDouble()
        val arrival = s.arrivalPct
        val big = if (arrival == null) "%.0f %%".format(now) else "%.0f → %.0f %%".format(now, arrival)
        if (arrival != null) {
            val cal = if (s.historyValid < RangeForecast.CALIBRATION_RIDES) " · CAL ${s.historyValid}/${RangeForecast.CALIBRATION_RIDES}" else ""
            rows += "range" to "%.0f–%.0f %%%s".format(arrival - s.halfBandPct, arrival + s.halfBandPct, cal)
            rows += "margin" to "%+d %% over %d %%".format(Math.round(s.marginPct), s.thresholdPct)
            rows += "climb left" to "%+d m".format(Math.round(s.extraClimbM))
            rows += "to go" to "%.1f km · D+%.0f m".format(s.remainingKm, s.remainingAscentM)
        } else {
            rows += "route" to "none"
        }
        if (s.rideOpen) {
            rows += "spent" to "%.0f Wh · %.0f %%".format(s.whEstimated, s.whEstimated / s.capacityWh * 100.0)
            rows += "last set" to when {
                s.manualUpdates > 0 -> "%d %% @ %.1f km · %d min".format(s.lastUpdatePct, s.lastUpdateKm, s.lastUpdateAgeMin.coerceAtLeast(0))
                s.startAssumed -> "none · start %d %% assumed".format(s.startPct)
                else -> "start %d %% ✓".format(s.startPct)
            }
            rows += "ride" to "%.1f km D+%s %s".format(s.distanceKm, m(s.ascentKarooM), hm(s.movingS))
        } else {
            rows += "start" to "%d %% planned · tap to set".format(s.planningPct)
        }
        rows += "s / rides" to "%.2f · %d/%d valid".format(s.sUsed, s.historyValid, RangeForecast.CALIBRATION_RIDES)
        if (s.hasRoute) rows += "surface" to surfaceValue(s)
        return PageContent(big, rows)
    }
}
