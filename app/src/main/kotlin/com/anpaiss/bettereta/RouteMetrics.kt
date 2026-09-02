package com.anpaiss.bettereta

import io.hammerhead.karooext.models.OnNavigationState

/**
 * Route-derived metrics computed from data the Karoo publishes, kept apart from
 * [SegmentPipeline] so they stay independent cross-checks rather than restatements
 * of our own numbers, and so they can be unit tested without any Android context.
 */

/**
 * Ascent still to come according to the Karoo's own climb list, given the
 * distance already covered along the route.
 *
 * Climbs entirely ahead count in full; the climb we are currently inside is
 * prorated by the fraction of its length that is left. Returns null when the
 * route carries no climb data, which is different from "no ascent left".
 */
internal fun remainingClimbsAscentM(
    climbs: List<OnNavigationState.NavigationState.Climb>,
    posM: Double,
): Double? {
    if (climbs.isEmpty()) return null
    return climbs.sumOf { c ->
        val end = c.startDistance + c.length
        when {
            posM >= end             -> 0.0
            posM <= c.startDistance -> c.totalElevation
            c.length <= 0.0         -> 0.0
            else                    -> c.totalElevation * (end - posM) / c.length
        }
    }
}
