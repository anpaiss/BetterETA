package com.anpaiss.bettereta

/**
 * A POI the rider put on the route (Komoot, Hammerhead dashboard): the Karoo
 * publishes them with [io.hammerhead.karooext.models.Symbol.POI.distancesAlongRoute].
 * One entry per pass: a POI the route passes twice is listed twice.
 *
 * @param alongM distance along the route at which the POI is passed
 */
data class RoutePoi(val name: String, val alongM: Double)

object PoiEta {
    /**
     * A POI counts as passed this far before its point, so the field moves on
     * to the next one while the rider is still rolling up to it instead of
     * flapping on a position estimate that oscillates around it.
     */
    const val PASSED_MARGIN_M = 30.0

    /** "46.332551,11.710235": the Karoo's name for a route POI that has none (Komoot, 30/10/2026). */
    private val COORDINATES = Regex("""^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$""")

    /**
     * The name to show for a route POI: its own, or "POI n" (n = [ordinal],
     * 1-based in route order) when the Karoo sent none or only its coordinates.
     */
    fun nameFor(raw: String?, ordinal: Int): String =
        if (raw.isNullOrBlank() || COORDINATES.matches(raw)) "POI $ordinal" else raw.trim()

    /** The first POI still ahead of [traveledM], or null when none is left. */
    fun next(pois: List<RoutePoi>, traveledM: Double): RoutePoi? =
        pois.filter { it.alongM > traveledM + PASSED_MARGIN_M }.minByOrNull { it.alongM }

    /**
     * The leading [distM] metres of [segments], the last one cut to size: the
     * part of the remaining route that ends at the POI, ready for
     * [EtaEngine.computeBreakdown]. Past the end of the route everything is
     * returned.
     */
    fun segmentsUpTo(segments: List<RouteSegment>, distM: Double): List<RouteSegment> {
        val result = mutableListOf<RouteSegment>()
        var left = distM
        for (seg in segments) {
            if (left <= 0.0) break
            if (seg.distanceM <= left) {
                result.add(seg)
                left -= seg.distanceM
            } else {
                result.add(seg.copy(distanceM = left))
                left = 0.0
            }
        }
        return result
    }
}
