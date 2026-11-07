package com.anpaiss.boschrange

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TyresTest {

    private fun t(model: String, casing: String = "EXO+", compound: String = "3C MaxxTerra", w: String = "2.50") =
        Tyre("Maxxis", model, "29", w, casing, compound)

    @Test fun `the popup name is the models only, one name when both ends match`() {
        assertEquals("Assegai / Minion DHR II", TyrePair("a", t("Assegai"), t("Minion DHR II")).name())
        assertEquals("Assegai", TyrePair("b", t("Assegai"), t("Assegai")).name())
    }

    @Test fun `two pairs that read the same get what tells them apart, the others stay plain`() {
        val exo = TyrePair("1", t("Assegai"), t("Minion DHR II"))
        val dd = TyrePair("2", t("Assegai", casing = "DoubleDown"), t("Minion DHR II", casing = "DoubleDown"))
        val other = TyrePair("3", t("Rekon"), t("Rekon"))
        val l = TyrePair.labels(listOf(exo, dd, other))
        assertEquals("Assegai / Minion DHR II · EXO+", l["1"])
        assertEquals("Assegai / Minion DHR II · DoubleDown", l["2"])
        assertEquals("Rekon", l["3"])
    }

    @Test fun `pairs and a ride's tyres survive the JSON`() {
        val p = TyrePair("1", t("Assegai"), Tyre("Other", "Mystery", "29", "2.40", null, null))
        assertEquals(listOf(p), TyrePair.listFromJson(TyrePair.listToJson(listOf(p))))
        val r = RideRecord(1_000L, 1000.0, 1.25).apply { tyres = p }
        assertEquals(p, RideRecord.fromJson(JSONObject(r.toJson().toString())).tyres)
        // A ride of before the tyres has none
        val old = r.toJson().apply { remove("tyres") }
        assertNull(RideRecord.fromJson(old).tyres)
    }

    @Test fun `the bundled catalog parses, every variant with a size`() {
        val c = TyreCatalog.parse(File("src/main/assets/tyres.json").readText())
        assertEquals(8, c.brands.size)
        val all = c.brands.flatMap { b -> b.models.flatMap { it.variants } }
        assertTrue(all.size > 800)
        assertTrue(all.all { it.width.isNotBlank() && (it.diameter == "29" || it.diameter == "27.5") })
        assertTrue(c.brands.first { it.name == "Maxxis" }.models.any { it.name == "Assegai" })
    }

    @Test fun `stats per pair weigh the long rides and wait for enough of them`() {
        val a = TyrePair("a", t("Assegai"), t("Assegai")); val b = TyrePair("b", t("Rekon"), t("Rekon"))
        fun ride(p: TyrePair, start: Int, end: Int?, whMech: Double, ascent: Double, c: Condition = Condition.DRY) =
            RideRecord(0L, 1000.0, 1.25).apply {
                setStart(start, assumed = false); arrivalPct = end; this.whMech = whMech; ascentM = ascent
                distanceM = 30_000.0; condition = c; tyres = p
            }
        val h = listOf(
            ride(a, 100, 70, 240.0, 1000.0),           // 300 Wh / 240 = 1.25
            ride(a, 90, 50, 300.0, 1200.0, Condition.WET), // 400 / 300 = 1.33
            ride(a, 80, null, 200.0, 800.0),          // no arrival: counted, not measured
            ride(b, 100, 75, 200.0, 900.0),
        )
        val s = TyreStats.of(h).associateBy { it.pairId }
        val sa = s.getValue("a")
        assertEquals(3, sa.rides); assertEquals(90.0, sa.km, 1e-9); assertEquals(2, sa.measured)
        assertEquals(700.0 / 540.0, sa.s!!, 1e-9)
        assertEquals(70.0 / 2200.0 * 1000.0, sa.pctPer1000m!!, 1e-9)
        assertEquals(1.25, sa.sDry!!, 1e-9); assertEquals(400.0 / 300.0, sa.sWet!!, 1e-9)
        assertFalse(sa.enough)
        assertNull(s.getValue("b").sSpread)
    }
}
