package com.anpaiss.boschrange

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowComparisonTest {

    private fun ride(arrival: Int, ours: Double, flow: Int, oursMid: Double? = null, flowMid: Int? = null, valid: Boolean = true) =
        RideRecord(1_000L, 1000.0, 1.25).apply {
            setStart(100, assumed = !valid)
            arrivalPct = arrival
            ourPredStartPct = ours; flowPredStartPct = flow
            ourPredMidPct = oursMid; flowPredMidPct = flowMid; flowShotMidKm = if (flowMid != null) 20.0 else 0.0
        }

    @Test fun `no report before six comparable rides`() {
        val rides = List(5) { ride(30, 32.0, 40) } + ride(30, 32.0, 40, valid = false) + ride(30, 32.0, 40).apply { flowPredStartPct = null }
        assertNull(FlowComparison.report(rides))
        assertEquals(5, FlowComparison.comparable(rides).size)
    }

    @Test fun `stop when the start gain is under three points`() {
        val res = FlowComparison.evaluate(List(6) { ride(30, 34.0, 36) })!!
        assertEquals(4.0, res.maeStartOurs, 1e-9)
        assertEquals(6.0, res.maeStartFlow, 1e-9)
        assertEquals(FlowComparison.Verdict.STOP, res.verdict)
    }

    @Test fun `planning only when we win at the start but not mid-ride`() {
        val res = FlowComparison.evaluate(List(6) { ride(30, 32.0, 40, oursMid = 33.0, flowMid = 32) })!!
        assertEquals(FlowComparison.Verdict.PLANNING_ONLY, res.verdict)
        assertEquals(6, res.midRides)
    }

    @Test fun `go on when we win at both points, and without mid data`() {
        assertEquals(FlowComparison.Verdict.GO_ON, FlowComparison.evaluate(List(6) { ride(30, 32.0, 40, oursMid = 31.0, flowMid = 35) })!!.verdict)
        assertEquals(FlowComparison.Verdict.GO_ON, FlowComparison.evaluate(List(6) { ride(30, 32.0, 40) })!!.verdict)
        val text = FlowComparison.report(List(6) { ride(30, 32.0, 40) })!!
        assertTrue(text, text.contains("si prosegue"))
    }

    @Test fun `comparison fields survive the json round trip and default to null`() {
        val r = ride(30, 32.5, 40, oursMid = 31.0, flowMid = 35)
        val back = RideRecord.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(32.5, back.ourPredStartPct!!, 1e-9)
        assertEquals(40, back.flowPredStartPct)
        assertEquals(31.0, back.ourPredMidPct!!, 1e-9)
        assertEquals(35, back.flowPredMidPct)
        assertEquals(20.0, back.flowShotMidKm, 1e-9)
        val empty = RideRecord.fromJson(JSONObject(RideRecord(1L, 1000.0, 1.25).toJson().toString()))
        assertNull(empty.flowPredStartPct); assertNull(empty.ourPredStartPct); assertEquals(0L, empty.flowShotMidMs)
    }
}
