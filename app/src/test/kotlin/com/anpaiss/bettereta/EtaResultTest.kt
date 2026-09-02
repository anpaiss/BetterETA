package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stored history predates the physics column. Reading it back must keep
 * working, or every past ride's verdict is lost the day the format grows.
 */
class EtaResultTest {

    @Test
    fun `a v1 entry parses with no physics error`() {
        val r = EtaResult.parse("2026-08-30|5|12|17")!!
        assertEquals("2026-08-30", r.date)
        assertEquals(5, r.diffPct)
        assertEquals(12, r.errBePct)
        assertEquals(17, r.errKarooPct)
        assertNull(r.errPhysicsPct)
        assertNull(r.physicsGainPct)
    }

    @Test
    fun `a v2 entry round-trips`() {
        val r = EtaResult("2026-09-02", -3, 14, 11, 9)
        assertEquals("2026-09-02|-3|14|11|9", r.encode())
        assertEquals(r, EtaResult.parse(r.encode()))
        assertEquals(5, r.physicsGainPct)   // 14 - 9: physics would have been 5 pp better
    }

    @Test
    fun `a v1 entry re-encodes as v2 with an empty tail and still parses`() {
        val r = EtaResult.parse("2026-08-30|5|12|17")!!
        assertEquals("2026-08-30|5|12|17|", r.encode())
        assertEquals(r, EtaResult.parse(r.encode()))
    }

    @Test
    fun `corrupt and implausible entries are dropped`() {
        assertNull(EtaResult.parse(""))
        assertNull(EtaResult.parse("garbage"))
        assertNull(EtaResult.parse("2026-08-29|x|1|2"))
        // The ms/s bug produced errors in the tens of thousands of percent.
        assertNull(EtaResult.parse("2026-08-29|0|99900|99800"))
        assertNull(EtaResult.parse("2026-08-29|0|10|12|99900"))
    }

    @Test
    fun `history parsing skips bad entries and keeps order`() {
        val list = EtaResult.parseHistory("2026-09-02|1|10|11|8;junk;;2026-08-30|5|12|17")
        assertEquals(2, list.size)
        assertEquals("2026-09-02", list[0].date)
        assertEquals("2026-08-30", list[1].date)
        assertEquals("2026-09-02|1|10|11|8;2026-08-30|5|12|17|", EtaResult.encodeHistory(list))
    }
}
