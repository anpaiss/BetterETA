package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Karoo's own FIT of the 30/08 ride: 6654 positioned records, 33.9 km. */
class FitRecordReaderTest {

    private fun ride(): List<FitRecordReader.Record> {
        val bytes = javaClass.getResourceAsStream("/ride_2026-08-30.fit")!!.readBytes()
        return FitRecordReader.read(bytes)
    }

    @Test
    fun `reads every positioned record of a real Karoo file`() {
        val recs = ride().filter { it.lat != null && it.lon != null }
        assertEquals(6654, recs.size)
        assertEquals(46.089112, recs.first().lat!!, 1e-5)
        assertEquals(11.496240, recs.first().lon!!, 1e-5)
        assertEquals(33868.7, recs.last().distM!!, 1.0)
        assertTrue(recs.all { it.timeMs != null })
        assertTrue(recs.count { it.speedMs != null } > 6600)
        assertTrue(recs.count { it.altM != null } > 6600)
        assertEquals(772.4, recs.first().altM!!, 0.5)
    }

    @Test
    fun `timestamps are absolute and increase`() {
        val recs = ride().filter { it.lat != null }
        val first = recs.first().timeMs!!
        // 2026-08-30 08:10:16 UTC
        assertEquals(1788077416000L, first)
        assertTrue(recs.zipWithNext().all { (a, b) -> b.timeMs!! >= a.timeMs!! })
    }

    @Test
    fun `garbage is not a FIT file`() {
        assertTrue(FitRecordReader.read(ByteArray(10)).isEmpty())
        assertTrue(FitRecordReader.read("not a fit file at all".toByteArray()).isEmpty())
    }
}
