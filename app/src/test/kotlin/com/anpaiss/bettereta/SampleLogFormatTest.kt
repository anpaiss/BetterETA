package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The sample log is the substrate every later decision rests on, so its rows
 * have to survive the device they were recorded on.
 */
class SampleLogFormatTest {

    private fun row(
        posKarooM: Double? = null,
        onRoute: Double? = null,
        rejoinActive: Boolean? = null,
        etaKarooS: Double? = null,
    ) = SampleLog.Row(
        tMs           = 1_700_000_000_000L,
        rideState     = "Recording",
        gpsAgeMs      = 1200L,
        speedMs       = 6.5,
        gradePct      = -3.25,
        surface       = Surface.GRAVEL,
        posOursM      = 1234.56,
        posKarooM     = posKarooM,
        onRoute       = onRoute,
        rejoinActive  = rejoinActive,
        remDistOursM  = 20000.0,
        remAscThreshM = 310.0,
        remAscFullM   = 402.5,
        etaLearnedS   = 3600L,
        etaKarooS     = etaKarooS,
    )

    /**
     * The real hazard: on an Italian device the default locale renders decimals
     * with a comma, which would split every number into two columns and quietly
     * make the whole file unparseable.
     */
    @Test
    fun `decimals use a dot regardless of the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            val csv = SampleLog.toCsv(row())
            assertEquals("no comma decimals expected", SampleLog.COLUMNS.split(",").size, csv.split(",").size)
            assertTrue("speed should be 6.50", csv.split(",")[3] == "6.50")
            assertTrue("grade should be -3.25", csv.split(",")[4] == "-3.25")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `column count always matches the header`() {
        val expected = SampleLog.COLUMNS.split(",").size
        assertEquals(expected, SampleLog.toCsv(row()).split(",").size)
        assertEquals(
            expected,
            SampleLog.toCsv(row(posKarooM = 10.0, onRoute = 1.0, rejoinActive = true, etaKarooS = 99.9))
                .split(",").size
        )
    }

    @Test
    fun `absent values become empty cells, not zeros`() {
        val cells = SampleLog.toCsv(row()).split(",")
        val header = SampleLog.COLUMNS.split(",")
        assertEquals("", cells[header.indexOf("pos_karoo_m")])
        assertEquals("", cells[header.indexOf("on_route")])
        assertEquals("", cells[header.indexOf("rejoin_active")])
        // Phase 3 columns exist from the start and stay empty until then.
        assertEquals("", cells[header.indexOf("eta_physics_s")])
        assertEquals("", cells[header.indexOf("p_low_w")])
        assertEquals("", cells[header.indexOf("model_rung")])
        assertEquals("", cells[header.indexOf("mta_margin_s")])
    }

    @Test
    fun `booleans are written as 1 and 0`() {
        val header = SampleLog.COLUMNS.split(",")
        val idx = header.indexOf("rejoin_active")
        assertEquals("1", SampleLog.toCsv(row(rejoinActive = true)).split(",")[idx])
        assertEquals("0", SampleLog.toCsv(row(rejoinActive = false)).split(",")[idx])
    }
}
