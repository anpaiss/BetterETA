package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share file must lose who and when, and keep everything the replay reads. */
class ShareExportTest {

    private val input = listOf(
        "t_ms,ride_state,gps_age_ms,speed_ms,grade_pct,surface,pos_ours_m",
        "# ride 2026-09-20 16:35:33 name=Sopra Susà 2026 dist=30489 hash=1642634305",
        "1789919733000,Recording,120,5.50,2.10,PAVED,120.0",
        "1789919734000,Recording,110,5.60,2.20,PAVED,125.6",
        "# shadow would have fired here",
        "# ride 2026-09-20 16:40:00 name=Sopra Susà 2026 dist=30489 hash=1642634305",   // re-emitted: same ride
        "1789919740000,Recording,110,5.70,2.30,PAVED,160.0",
        "# ride 2026-09-21 09:00:00 name=Malghe, Redebus dist=18742 hash=1464750760",
        "1790000000000,Recording,100,3.00,8.00,GRAVEL,10.0",
        "t_ms,ride_state,gps_age_ms,speed_ms,grade_pct,surface,pos_ours_m",   // a rotated file concatenated
        "1790000001000,Paused,100,0.00,8.00,GRAVEL,10.0",
    )

    @Test
    fun `names, dates and notes go, the header and the columns stay`() {
        val out = ShareExport.transform(input.asSequence()).toList()
        assertEquals("t_ms,ride_state,gps_age_ms,speed_ms,grade_pct,surface,pos_ours_m", out[0])
        assertEquals("# ride ride-001 name=${ShareExport.routeTag("Sopra Susà 2026")} dist=30489 hash=1642634305", out[1])
        assertEquals("0,Recording,120,5.50,2.10,PAVED,120.0", out[2])
        assertEquals("1000,Recording,110,5.60,2.20,PAVED,125.6", out[3])
        assertEquals("# ride ride-001 name=${ShareExport.routeTag("Sopra Susà 2026")} dist=30489 hash=1642634305", out[4])
        assertEquals("7000,Recording,110,5.70,2.30,PAVED,160.0", out[5])   // clock of the same day continues
        assertEquals("# ride ride-002 name=${ShareExport.routeTag("Malghe, Redebus")} dist=18742 hash=1464750760", out[6])
        assertEquals("0,Recording,100,3.00,8.00,GRAVEL,10.0", out[7])
        assertEquals("1000,Paused,100,0.00,8.00,GRAVEL,10.0", out[8])
        assertEquals(9, out.size)
        val text = out.joinToString("\n")
        assertFalse(text.contains("Susà")); assertFalse(text.contains("Redebus"))
        assertFalse(text.contains("2026-09")); assertFalse(text.contains("shadow"))
        assertFalse(text.contains("1789919733000"))
    }

    @Test
    fun `the route tag is stable, short and not the name`() {
        val a = ShareExport.routeTag("Sopra Susà 2026")
        assertEquals(a, ShareExport.routeTag(" Sopra Susà 2026 "))
        assertEquals(8, a.length)
        assertTrue(a != ShareExport.routeTag("Sopra Susà 2025"))
    }

    @Test
    fun `rows before any marker still get a relative clock`() {
        val out = ShareExport.transform(sequenceOf("t_ms,x", "500,a", "700,b")).toList()
        assertEquals(listOf("t_ms,x", "0,a", "200,b"), out)
    }
}
