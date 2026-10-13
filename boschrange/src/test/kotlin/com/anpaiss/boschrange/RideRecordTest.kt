package com.anpaiss.boschrange

import com.anpaiss.bettereta.Surface
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRecordTest {

    private val model = EnergyModel(riderKg = 84.0)

    @Test fun `climbing at 10 km per h on 10 percent gravel costs about the backtest energy`() {
        // 109 kg, v = 2.78 m/s, 10 %: climb 109·9.81·2.78·sin(atan 0.1) ≈ 296 W, rolling ≈ 35 W, aero ≈ 6 W
        val p = model.powerW(2.78, 10.0, Surface.GRAVEL)
        assertEquals(337.0, p, 5.0)
        assertEquals(p / 3600.0 * 5.0, model.tickWh(2.78, 10.0, Surface.GRAVEL, 5.0), 1e-9)
        // above the cutoff the battery pays nothing, downhill neither
        assertEquals(0.0, model.tickWh(8.0, 2.0, Surface.PAVED, 5.0), 0.0)
        assertEquals(0.0, model.tickWh(5.0, -8.0, Surface.PAVED, 5.0), 0.0)
    }

    @Test fun `estimate falls with the energy and re-anchors on a manual entry`() {
        val r = RideRecord(startMs = 1_000L, capacityWh = 1000.0, sUsed = 1.25)
        r.setStart(90, assumed = false)
        // 720 ticks of 5 s climbing 10 % at 10 km/h on gravel: 1 h ≈ 337 Wh mechanical
        repeat(720) { r.tick(2.78, 10.0, Surface.GRAVEL, 5.0, model, 12.0) }
        assertEquals(337.0, r.whMech, 6.0)
        assertEquals(90.0 - 337.0 * 1.25 / 10.0, r.estimatedPct(), 1.0)
        assertEquals(3600.0, r.movingS, 1e-6)
        assertEquals(3600.0, r.secondsByGrade[RideRecord.gradeBand(10.0)], 1e-6)
        assertEquals(10.0, r.kmBySurface[Surface.GRAVEL.ordinal], 0.05)
        assertEquals(12.0, r.temperatureC!!, 1e-9)

        r.anchor(60, 2_000L)
        assertEquals(60.0, r.estimatedPct(), 1e-9)
        assertEquals(1, r.manualUpdates.size)
        r.tick(2.78, 10.0, Surface.GRAVEL, 5.0, model, null)
        assertTrue(r.estimatedPct() < 60.0)
    }

    @Test fun `gradient bands`() {
        assertEquals(0, RideRecord.gradeBand(-12.0))
        assertEquals(1, RideRecord.gradeBand(-6.0))
        assertEquals(2, RideRecord.gradeBand(0.0))
        assertEquals(3, RideRecord.gradeBand(3.0))
        assertEquals(4, RideRecord.gradeBand(9.9))
        assertEquals(5, RideRecord.gradeBand(10.0))
        assertEquals(6, RideRecord.gradeBand(15.0))
    }

    @Test fun `json round trip keeps every field`() {
        val r = RideRecord(startMs = 123L, capacityWh = 750.0, sUsed = 1.25)
        r.setStart(95, assumed = false)
        r.condition = Condition.WET
        r.routeName = "Carezza"; r.routeDistanceM = 38050.0; r.routeAscentM = 1569.0; r.karooAscentRemainingAtStartM = 1569.2
        repeat(10) { r.tick(3.0, 6.0, Surface.PAVED, 5.0, model, 8.0) }
        r.anchor(80, 456L)
        r.distanceM = 12345.0; r.ascentM = 300.0; r.descentM = 290.0; r.endMs = 999L
        r.arrivalPct = 41
        r.flowRiderSharePct = 27.0
        r.flowKmByMode = mapOf("tour_plus" to 17.7, "emtb_plus" to 8.2)

        val back = RideRecord.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r.startMs, back.startMs)
        assertEquals(r.startPct, back.startPct)
        assertEquals(false, back.startAssumed)
        assertEquals(41, back.arrivalPct)
        assertEquals(Condition.WET, back.condition)
        assertEquals("Carezza", back.routeName)
        assertEquals(1569.0, back.routeAscentM, 0.0)
        assertEquals(r.whMech, back.whMech, 0.051)
        assertEquals(r.anchorPct, back.anchorPct, 0.0)
        assertEquals(1, back.manualUpdates.size)
        assertEquals(80, back.manualUpdates[0].enteredPct)
        assertEquals(r.secondsByGrade[RideRecord.gradeBand(6.0)], back.secondsByGrade[RideRecord.gradeBand(6.0)], 0.5)
        assertEquals(r.kmBySurface[Surface.PAVED.ordinal], back.kmBySurface[Surface.PAVED.ordinal], 0.051)
        assertEquals(8.0, back.temperatureC!!, 0.051)
        assertEquals(27.0, back.flowRiderSharePct!!, 0.0)
        assertEquals(8.2, back.flowKmByMode!!["emtb_plus"]!!, 0.0)
        assertEquals((95 - 41) / 100.0 * 750.0, back.whReal!!, 0.0)

        val open = RideRecord(startMs = 1L, capacityWh = 1000.0, sUsed = 1.25)
        val openBack = RideRecord.fromJson(JSONObject(open.toJson().toString()))
        assertNull(openBack.arrivalPct)
        assertNull(openBack.condition)
        assertNull(openBack.whReal)
        assertTrue(openBack.startAssumed)
    }
}
