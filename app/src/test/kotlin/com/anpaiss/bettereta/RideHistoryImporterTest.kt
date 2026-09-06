package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A past ride replayed into the model must land in the right buckets, the
 * right way memory, and leave the day factor alone; a fake ride at rest
 * must be rejected by the GPS gate.
 */
class RideHistoryImporterTest {

    private val lat0 = 46.0
    private val lon0 = 11.0
    private val degPerMLat = 1.0 / 111_320.0

    /** Ride north: 10 min flat at 5 m/s, then 10 min climbing 8 % at 2.5 m/s. */
    private fun ride(): List<FitRecordReader.Record> {
        val out = ArrayList<FitRecordReader.Record>()
        var lat = lat0; var alt = 500.0; var dist = 0.0; var t = 1_700_000_000_000L
        repeat(600) {
            lat += 5.0 * degPerMLat; dist += 5.0
            out.add(FitRecordReader.Record(t, lat, lon0, alt, 5.0, dist)); t += 1000
        }
        repeat(600) {
            lat += 2.5 * degPerMLat; dist += 2.5; alt += 2.5 * 0.08
            out.add(FitRecordReader.Record(t, lat, lon0, alt, 2.5, dist)); t += 1000
        }
        return out
    }

    private fun gravelEverywhere(key: Long): (List<RouteWaypoint>) -> Pair<List<Surface>, List<Long>>? =
        { pts -> List(pts.size) { Surface.GRAVEL } to List(pts.size) { key } }

    @Test
    fun `a real ride fills the gradient and surface buckets and the way memory`() {
        val model = AdaptiveSpeedModel()
        val memory = WaySpeedMemory()
        val key = WaySpeedMemory.wayKey(77L, true)
        val imp = RideHistoryImporter(model, null, memory, gravelEverywhere(key))
        val s = imp.importRecords(ride())
        assertNotNull(s)
        assertTrue("accepted ${s!!.accepted}", s.accepted > 1000)
        assertEquals(4500.0, s.distanceM, 50.0)
        assertEquals(4500.0, s.kmByClass[Surface.GRAVEL]!!, 50.0)
        assertEquals(5.0, model.predictSpeed(0.0, Surface.GRAVEL), 0.3)
        assertEquals(2.5, model.predictSpeed(8.0, Surface.GRAVEL), 0.3)
        assertEquals(1.0, model.currentDayFactor, 1e-9)          // not today's factor
        val est = memory.estimate(key)
        assertNotNull(est)
        assertEquals(1, est!!.passes)                              // one continuous pass on the way
        assertEquals(s.passes, est.passes)
    }

    @Test
    fun `a fake ride at rest with GPS jitter is rejected`() {
        val model = AdaptiveSpeedModel()
        val imp = RideHistoryImporter(model, null, WaySpeedMemory(), gravelEverywhere(0L))
        val out = ArrayList<FitRecordReader.Record>()
        var t = 1_700_000_000_000L
        repeat(1800) { i ->
            val lat = lat0 + ((i * 7) % 13 - 6) * degPerMLat
            val lon = lon0 + ((i * 11) % 13 - 6) * degPerMLat
            out.add(FitRecordReader.Record(t, lat, lon, 500.0, 0.9 + (i % 5) * 0.2, null)); t += 1000
        }
        val s = imp.importRecords(out)
        assertNotNull(s)
        assertEquals(0, s!!.accepted)
        assertEquals(5.0, model.predictSpeed(0.0), 1e-9)          // still the cold default
    }

    @Test
    fun `without surface data the samples still train the global buckets`() {
        val model = AdaptiveSpeedModel()
        val imp = RideHistoryImporter(model, null, WaySpeedMemory()) { null }
        val s = imp.importRecords(ride())!!
        assertTrue(s.accepted > 1000)
        assertEquals(5.0, model.predictSpeed(0.0), 0.3)
        assertEquals(4500.0, s.kmByClass[Surface.UNKNOWN]!!, 50.0)
    }

    @Test
    fun `too few positioned records is not a ride`() {
        val imp = RideHistoryImporter(AdaptiveSpeedModel(), null, WaySpeedMemory()) { null }
        assertNull(imp.importRecords(listOf(FitRecordReader.Record(1L, 46.0, 11.0, null, 3.0, null))))
    }
}
