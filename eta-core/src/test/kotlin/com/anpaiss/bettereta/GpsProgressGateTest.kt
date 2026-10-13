package com.anpaiss.bettereta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Free-ride learning gate: the GPS alone must separate riding from the two
 * things that poisoned the model — a fix dancing indoors, and a fix jumping.
 */
class GpsProgressGateTest {

    private val lat0 = 46.0
    private val lon0 = 11.0
    private val degPerMLat = 1.0 / 111_320.0

    @Test
    fun `stationary jitter with plausible speeds never passes`() {
        val g = GpsProgressGate()
        var passes = 0
        repeat(600) { i ->
            // fix wandering within ±6 m, reported speed around 1 m/s (the night of 04/09)
            val lat = lat0 + ((i * 7) % 13 - 6) * degPerMLat
            val lon = lon0 + ((i * 11) % 13 - 6) * degPerMLat
            if (g.onTick(lat, lon, 0.9 + (i % 5) * 0.2)) passes++
        }
        assertTrue("passes=$passes", passes == 0)
    }

    @Test
    fun `a sudden jump of the fix at near-zero speed is not progress`() {
        val g = GpsProgressGate()
        repeat(90) { g.onTick(lat0, lon0, 0.6) }
        // fix teleports 230 m north, speed still ~0.6 m/s
        var passes = 0
        repeat(60) { if (g.onTick(lat0 + 230 * degPerMLat, lon0, 0.6)) passes++ }
        assertTrue("passes=$passes", passes == 0)
    }

    @Test
    fun `riding at walking pace along a line passes once the window is full`() {
        val g = GpsProgressGate()
        var lat = lat0
        var passesAfterWarmup = 0
        repeat(240) { i ->
            lat += 0.6 * degPerMLat                     // 0.6 m/s northwards
            val r = g.onTick(lat, lon0, 0.6)
            if (i >= 61) { assertTrue("tick $i", r); passesAfterWarmup++ }
        }
        assertTrue(passesAfterWarmup > 0)
    }

    @Test
    fun `curvy riding passes too - net displacement below integrated distance is fine`() {
        val g = GpsProgressGate()
        var ok = 0
        repeat(180) { i ->
            // 50 m diameter circle every 60 s at ~2.6 m/s: net over a minute is small
            // but not zero (the circle drifts north 30 m per minute)
            val ang = i * 2 * Math.PI / 60
            val lat = lat0 + (25 * Math.sin(ang) + i * 0.5) * degPerMLat
            val lon = lon0 + (25 * Math.cos(ang)) * degPerMLat
            if (g.onTick(lat, lon, 2.6) && i > 61) ok++
        }
        assertTrue("ok=$ok", ok > 100)
    }

    @Test
    fun `reset forgets the reference`() {
        val g = GpsProgressGate()
        var lat = lat0
        repeat(70) { lat += 1.0 * degPerMLat; g.onTick(lat, lon0, 1.0) }
        g.reset()
        assertFalse(g.onTick(lat + 100 * degPerMLat, lon0, 1.0))
    }
}
