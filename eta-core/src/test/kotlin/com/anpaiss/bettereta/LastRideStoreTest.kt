package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * The photo taken before a ride must come back exactly as it was, starts of
 * the same day must join it, and a day boundary must start a new one.
 */
class LastRideStoreTest {

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "lastride-" + System.nanoTime()).also { it.deleteOnExit() }

    @Test
    fun prefsTextRoundTripKeepsTypesAndEscapes() {
        val values = linkedMapOf<String, Any>(
            "overall_samples" to 240867, "b1_ema" to 6.0f, "mta_epoch_ms" to 1_760_000_000_000L,
            "flag" to true, "ride_starts_ms" to "1;2;3", "odd" to "tab\there\nnew\\slash",
        )
        val back = PrefsText.decode(PrefsText.encode(values))
        assertEquals(values, back)
        assertTrue(back["overall_samples"] is Int)
        assertTrue(back["b1_ema"] is Float)
        assertTrue(back["mta_epoch_ms"] is Long)
    }

    @Test
    fun snapshotRoundTripAndSameDayStarts() {
        val dir = tempDir()
        val store = LastRideStore(dir)
        assertTrue(store.starts().isEmpty())
        assertNull(store.read())

        val importedList = File(dir.parentFile, "imported-" + System.nanoTime() + ".txt").apply { writeText("a.fit\nb.fit\n"); deleteOnExit() }
        store.take(1_000L, mapOf("overall_samples" to 5, "b0_ema" to 4.5f), mapOf("r0" to "x"), "WAYS1\nK 7 1 99 5.000\n", importedList)
        store.addStart(2_000L)
        val snap = store.read()!!
        assertEquals(listOf(1_000L, 2_000L), snap.starts)
        assertEquals(5, snap.model["overall_samples"])
        assertEquals(4.5f, snap.model["b0_ema"])
        assertEquals("x", snap.results["r0"])
        assertEquals("WAYS1\nK 7 1 99 5.000\n", snap.wayMemory)
        assertEquals("a.fit\nb.fit\n", snap.importedFits)

        store.delete()
        assertTrue(store.starts().isEmpty())
        assertFalse(dir.exists())
    }

    @Test
    fun missingImportedListIsRememberedAsMissing() {
        val dir = tempDir()
        val store = LastRideStore(dir)
        store.take(1L, emptyMap<String, Any>(), emptyMap<String, Any>(), "", File(dir.parentFile, "does-not-exist.txt"))
        assertNull(store.read()!!.importedFits)
        store.delete()
    }

    @Test
    fun rideDayIsTheLocalCalendarDay() {
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val morning = 1_760_430_000_000L            // 2026-10-14 10:20 CEST
        val afternoon = morning + 5 * 3_600_000L    // same day, after lunch
        val nextDay = morning + 16 * 3_600_000L     // 2026-10-15 02:20 CEST
        assertTrue(RideDay.same(morning, afternoon, rome))
        assertFalse(RideDay.same(morning, nextDay, rome))
    }

    @Test
    fun excludedStartsAreSkippedLikeLiveOnes() {
        val excluded = listOf(1_700_000_000_000L)
        assertTrue(RideHistoryImporter.learnedLive(1_700_000_000_000L + 5 * 60_000L, excluded))
        assertFalse(RideHistoryImporter.learnedLive(1_700_000_000_000L + 60 * 60_000L, excluded))
    }
}
