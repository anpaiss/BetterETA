package com.anpaiss.boschrange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveDataTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun `the first message of the Wild ST20 decodes completely`() {
        // READ of eb21 at the connection of 30/10/2026 18:23:12, 40 bytes
        val d = LiveData.decode(hex("08001000280048bdb901502c5880a593d70660aaa63e880102a80100b00100b80100c00100c80101"))
        assertEquals(0.0, d.speedKmh!!, 0.0)
        assertEquals(0, d.cadenceRpm)
        assertEquals(0, d.riderPowerW)
        assertEquals(23.741, d.lux!!, 0.001)
        assertEquals(44, d.socPct)
        assertEquals(1793380992L, d.timeS)
        assertEquals(1020714L, d.odometerM)
        assertEquals(true, d.lightOn)
        assertEquals(false, d.locked)
        assertEquals(false, d.chargerConnected)
        assertEquals(false, d.lightReserve)
        assertEquals(false, d.diagnosisActive)
        assertEquals(true, d.notDriving)
    }

    @Test fun `a notification with only the changed fields leaves the others null`() {
        val d = LiveData.decode(hex("502b" + "60abaa3e"))   // SOC 43, odometer 1021227
        assertEquals(43, d.socPct)
        assertEquals(1021227L, d.odometerM)
        assertNull(d.speedKmh); assertNull(d.lightOn); assertNull(d.timeS)
    }

    @Test fun `unknown fields are skipped and a truncated message throws`() {
        val d = LiveData.decode(hex("f00101" + "502c"))        // field 30 varint, then SOC 44
        assertEquals(44, d.socPct)
        assertThrows(IllegalArgumentException::class.java) { LiveData.decode(hex("58")) }
    }
}
