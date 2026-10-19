package com.anpaiss.boschrange

import com.anpaiss.bettereta.PolylineDecoder
import com.anpaiss.bettereta.RouteSegment
import com.anpaiss.bettereta.RouteWaypoint
import com.anpaiss.bettereta.SegmentPipeline
import com.anpaiss.bettereta.Surface
import com.anpaiss.bettereta.interpolateElevations
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeForecastTest {

    private val model = EnergyModel(riderKg = 84.0)   // 109 kg with the bike

    @Test fun `a climb costs gravity plus rolling, a descent costs nothing`() {
        // 1 km at 10 % gravel, assisted all the way at 2.75 m/s: climb 109·9.81·sin(atan .1) ≈ 106.4 N,
        // rolling 0.012·109·9.81·cos ≈ 12.8 N, aero ½·1.225·0.45·2.75² ≈ 2.1 N → ≈ 121 N · 1000 m / 3600 ≈ 33.7 Wh
        val up = RangeForecast.remainingWhMech(listOf(RouteSegment(1000.0, 10.0, Surface.GRAVEL)), model)
        assertEquals(33.7, up, 0.5)
        val down = RangeForecast.remainingWhMech(listOf(RouteSegment(1000.0, -8.0, Surface.PAVED)), model)
        assertEquals(0.0, down, 0.0)
        // flat paved: rolling 5.3 N + aero at 4.6 m/s 5.8 N → 11.2 N · 1000 / 3600 · 0.85 ≈ 2.6 Wh
        val flat = RangeForecast.remainingWhMech(listOf(RouteSegment(1000.0, 0.0, Surface.PAVED)), model)
        assertEquals(2.6, flat, 0.2)
    }

    @Test fun `arrival, band, margin and climb left`() {
        val segs = listOf(RouteSegment(5000.0, 8.0, Surface.GRAVEL), RouteSegment(5000.0, -8.0, Surface.GRAVEL))
        val r = RangeForecast.forecast(segs, model, nowPct = 80.0, capacityWh = 750.0, sUsed = 1.25, thresholdPct = 15,
            relError = RangeForecast.UNCALIBRATED_REL_ERROR, whPerClimbM = RangeForecast.DEFAULT_WH_PER_CLIMB_M)
        val spentPct = r.remainingWhMech * 1.25 / 750.0 * 100.0
        assertEquals(80.0 - spentPct, r.arrivalPct, 1e-9)
        assertEquals(spentPct * 0.20, r.halfBandPct, 1e-9)
        assertEquals(r.arrivalPct - 15.0, r.marginPct, 1e-9)
        assertEquals(r.marginPct / 100.0 * 750.0 / 0.42, r.extraClimbM, 1e-6)
        assertEquals(10.0, r.remainingKm, 1e-9)
        assertEquals(5000.0 * Math.sin(Math.atan(0.08)), r.remainingAscentM, 1e-6)
        // a tiny remainder never shows a band under 2 points
        val end = RangeForecast.forecast(listOf(RouteSegment(50.0, 1.0, Surface.PAVED)), model, 40.0, 750.0, 1.25, 15, 0.2, 0.42)
        assertEquals(RangeForecast.MIN_HALF_BAND_PCT, end.halfBandPct, 1e-9)
    }

    @Test fun `relative error is fixed until five valid rides, then the mean miss of s`() {
        fun ride(sReal: Double): RideRecord = RideRecord(1L, 750.0, 1.25).apply {
            setStart(100, assumed = false)
            repeat(100) { tick(2.78, 10.0, Surface.GRAVEL, 5.0, model, null) }   // ≈ 47 Wh mechanical
            arrivalPct = 100 - Math.round(whMech * sReal / 750.0 * 100.0).toInt()
        }
        val four = List(4) { ride(1.25) }
        assertEquals(RangeForecast.UNCALIBRATED_REL_ERROR, RangeForecast.relativeError(four), 1e-9)
        val five = four + ride(1.25)
        assertEquals(0.05, RangeForecast.relativeError(five), 1e-9)        // perfect rides: the floor
        val open = RideRecord(1L, 750.0, 1.25)                              // not valid: ignored
        assertEquals(0.05, RangeForecast.relativeError(five + open), 1e-9)
    }

    @Test fun `wh per climb metre is measured on the ride after 100 m of ascent`() {
        assertEquals(RangeForecast.DEFAULT_WH_PER_CLIMB_M, RangeForecast.whPerClimbM(null, 1.25), 1e-9)
        val r = RideRecord(1L, 750.0, 1.25)
        repeat(300) { r.tick(2.78, 10.0, Surface.GRAVEL, 5.0, model, null) }   // 4.17 km at 10 %: 415 m
        assertTrue(r.ascentIntegratedM > 100.0)
        assertEquals(r.whMech * 1.25 / r.ascentIntegratedM, RangeForecast.whPerClimbM(r, 1.25), 1e-9)
        assertEquals(0.42, RangeForecast.whPerClimbM(r, 1.25), 0.05)  // the default sits where a steep climb lands
    }

    /**
     * The ride of 18/10/2026 (Forestali sopra Vignola, 21.6 km, D+ 712): the
     * model integrated 247.6 mechanical Wh along the real speeds, with the
     * surface classified. The forecast at km 0, with no surface (all UNKNOWN,
     * Crr 0.012) and the speed table, must land near that: real arrival 59 %,
     * estimate at the end 58.4 %.
     */
    @Test fun `forecast at km 0 of the ride of 18 October lands near what the ride cost`() = runBlocking {
        val lines = javaClass.getResourceAsStream("/route_forestali_2026-10-18.txt")!!.bufferedReader().readLines()
        val coords  = PolylineDecoder.decodeLatLng(lines[1])
        val samples = PolylineDecoder.decodeDistanceElevation(lines[2])
        val elevs   = interpolateElevations(coords, samples)
        val wps     = coords.mapIndexed { i, c -> RouteWaypoint(c.first, c.second, elevs[i]) }
        val pipeline = SegmentPipeline()
        pipeline.setRoute(wps)
        val segs = pipeline.remainingSegmentsFrom(0.0)
        val r = RangeForecast.forecast(segs, model, 100.0, 750.0, 1.25, 15, 0.2, 0.42)
        println("Forestali: ${"%.1f".format(r.remainingKm)} km, D+ ${"%.0f".format(r.remainingAscentM)} m, " +
            "${"%.1f".format(r.remainingWhMech)} Wh mech, arrival ${"%.1f".format(r.arrivalPct)} ± ${"%.1f".format(r.halfBandPct)}")
        assertEquals(21.6, r.remainingKm, 0.5)
        assertEquals(247.6, r.remainingWhMech, 247.6 * 0.15)
        assertEquals(59.0, r.arrivalPct, 6.0)
    }

    /**
     * Same ride, 17:25: Bosch Flow showed 66 % with 12.27 km to go and predicted
     * 52 % at arrival; our estimate of the moment was 66.5 % with 12.15 km to go
     * (samples CSV). Real arrival 59 %: our forecast from there must beat Flow.
     */
    @Test fun `mid-ride forecast of 18 October beats Flow's 52 percent`() = runBlocking {
        val lines = javaClass.getResourceAsStream("/route_forestali_2026-10-18.txt")!!.bufferedReader().readLines()
        val coords  = PolylineDecoder.decodeLatLng(lines[1])
        val samples = PolylineDecoder.decodeDistanceElevation(lines[2])
        val elevs   = interpolateElevations(coords, samples)
        val pipeline = SegmentPipeline()
        pipeline.setRoute(coords.mapIndexed { i, c -> RouteWaypoint(c.first, c.second, elevs[i]) })
        val total = pipeline.remainingSegmentsFrom(0.0).sumOf { it.distanceM }
        val segs = pipeline.remainingSegmentsFrom(total - 12_150.0)
        val r = RangeForecast.forecast(segs, model, 66.5, 750.0, 1.25, 15, 0.2, 0.42)
        println("Forestali 17:25: ${"%.1f".format(r.remainingKm)} km to go, D+ ${"%.0f".format(r.remainingAscentM)} m, " +
            "arrival ${"%.1f".format(r.arrivalPct)} ± ${"%.1f".format(r.halfBandPct)} (Flow 52, real 59)")
        assertTrue(Math.abs(r.arrivalPct - 59.0) < Math.abs(52.0 - 59.0))
    }
}
