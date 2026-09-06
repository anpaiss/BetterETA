package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "±" next to the time to go: relative to what is left, narrowed by way memory, widened by fallback and rough estimates. */
class EtaUncertaintyTest {

    @Test
    fun `no estimate means no interval`() {
        assertEquals(-1L, EtaUncertainty.seconds(-1L, 0.0, 0.0, false))
    }

    @Test
    fun `an hour to go with nothing known is about ten minutes`() {
        assertEquals(612L, EtaUncertainty.seconds(3600L, 0.0, 0.0, false))   // 17 %
    }

    @Test
    fun `way memory narrows and fallback widens the interval`() {
        val bare     = EtaUncertainty.seconds(3600L, 0.0, 0.0, false)
        val remembered = EtaUncertainty.seconds(3600L, 1.0, 0.0, false)
        val fallback = EtaUncertainty.seconds(3600L, 0.0, 1.0, false)
        assertTrue(remembered < bare)
        assertTrue(fallback > bare)
        assertEquals(396L, remembered)   // 11 %
        assertEquals(972L, fallback)     // 27 %
    }

    @Test
    fun `a rough estimate is wider`() {
        assertTrue(EtaUncertainty.seconds(3600L, 0.5, 0.0, true) > EtaUncertainty.seconds(3600L, 0.5, 0.0, false))
    }

    @Test
    fun `never below one minute`() {
        assertEquals(60L, EtaUncertainty.seconds(120L, 1.0, 0.0, false))
        assertEquals(60L, EtaUncertainty.seconds(0L, 0.0, 0.0, false))
    }

    @Test
    fun `shares outside 0-1 are clamped`() {
        assertEquals(EtaUncertainty.seconds(3600L, 1.0, 1.0, false), EtaUncertainty.seconds(3600L, 5.0, 3.0, false))
    }
}
