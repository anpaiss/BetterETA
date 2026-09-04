package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ArrivalMarginTest {

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 4, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `margin is MTA minus predicted arrival`() {
        val mta = at(17, 30)
        // 16:00 now, 80 min of riding left → arrival 17:20 → 10 min slack
        assertEquals(600L, ArrivalMargin.marginSeconds(mta, at(16, 0), 80 * 60L))
        // same ETA but 15 min later arrival → 5 min late
        assertEquals(-300L, ArrivalMargin.marginSeconds(mta, at(16, 15), 80 * 60L))
    }

    @Test
    fun `margin decays with the wall clock while remaining is frozen`() {
        val mta = at(17, 30)
        val remaining = 60 * 60L
        val before = ArrivalMargin.marginSeconds(mta, at(16, 0), remaining)
        val after  = ArrivalMargin.marginSeconds(mta, at(16, 10), remaining)
        assertEquals(600L, before - after)   // a 10 min stop costs 10 min of margin
    }

    @Test
    fun `default picker time is the ETA rounded up to the quarter hour`() {
        // 10:07 + 100 min = 11:47 → 12:00
        assertEquals(12 to 0, ArrivalMargin.defaultPickerTime(at(10, 7), 100 * 60L))
        // exact quarter stays put: 10:00 + 60 min = 11:00
        assertEquals(11 to 0, ArrivalMargin.defaultPickerTime(at(10, 0), 60 * 60L))
        // 10:00 + 61 min = 11:01 → 11:15
        assertEquals(11 to 15, ArrivalMargin.defaultPickerTime(at(10, 0), 61 * 60L))
    }

    @Test
    fun `default picker time without an ETA is now plus one hour`() {
        assertEquals(11 to 15, ArrivalMargin.defaultPickerTime(at(10, 10), -1L))
    }

    @Test
    fun `default picker time wraps past midnight`() {
        assertEquals(0 to 15, ArrivalMargin.defaultPickerTime(at(23, 50), 20 * 60L))
    }

    @Test
    fun `epochMsFor picks today, or tomorrow when the time has passed`() {
        val now = at(16, 0)
        assertEquals(at(17, 30), ArrivalMargin.epochMsFor(17, 30, now))
        val tomorrow = ArrivalMargin.epochMsFor(1, 0, now)
        assertEquals(9 * 3600_000L, tomorrow - now)   // 16:00 → 01:00 next day
    }

    @Test
    fun `format rounds towards zero and keeps the sign`() {
        assertEquals("+12 min", ArrivalMargin.format(12 * 60L + 59))
        assertEquals("+0 min",  ArrivalMargin.format(30L))
        assertEquals("-5 min",  ArrivalMargin.format(-5 * 60L - 1))
        assertEquals("+0 min",  ArrivalMargin.format(-30L))   // -30 s is still "on time"
        assertEquals("+12", ArrivalMargin.formatShort(12 * 60L))
        assertEquals("-5",  ArrivalMargin.formatShort(-5 * 60L))
    }

    @Test
    fun `alert fires once per crossing into negative`() {
        val gate = MarginAlertGate(rearmSeconds = 120L)
        assertFalse(gate.onMargin(300L))
        assertTrue(gate.onMargin(-1L))     // crossing
        assertFalse(gate.onMargin(-60L))   // still negative, no repeat
        assertFalse(gate.onMargin(30L))    // back above zero but below re-arm
        assertFalse(gate.onMargin(-10L))   // no repeat: never re-armed
        assertFalse(gate.onMargin(200L))   // re-arms
        assertTrue(gate.onMargin(-5L))     // fires again
    }
}
