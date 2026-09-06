package com.anpaiss.bettereta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One rule for both sources (Overpass JSON, Mapsforge tiles): surface, then tracktype, then highway. */
class SurfaceTagClassifierTest {

    private fun c(vararg kv: Pair<String, String>) = SurfaceTagClassifier.classify(mapOf(*kv))

    @Test
    fun `surface tag decides first`() {
        assertEquals(Surface.PAVED,  c("highway" to "track", "surface" to "asphalt"))
        assertEquals(Surface.GRAVEL, c("highway" to "path", "surface" to "gravel"))
        assertEquals(Surface.GRAVEL, c("highway" to "residential", "surface" to "compacted"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "surface" to "ground"))
        assertEquals(Surface.TRAIL,  c("highway" to "unclassified", "surface" to "grass"))
        assertEquals(Surface.PAVED,  c("highway" to "path", "surface" to "ASPHALT"))   // case-insensitive
    }

    @Test
    fun `tracktype grades a track when surface is missing`() {
        assertEquals(Surface.PAVED,  c("highway" to "track", "tracktype" to "grade1"))
        assertEquals(Surface.GRAVEL, c("highway" to "track", "tracktype" to "grade2"))
        assertEquals(Surface.GRAVEL, c("highway" to "track", "tracktype" to "grade3"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "tracktype" to "grade4"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "tracktype" to "grade5"))
    }

    @Test
    fun `highway type is the last resort`() {
        assertEquals(Surface.TRAIL,  c("highway" to "path"))
        assertEquals(Surface.TRAIL,  c("highway" to "bridleway"))
        assertEquals(Surface.GRAVEL, c("highway" to "track"))
        assertEquals(Surface.PAVED,  c("highway" to "residential"))
        assertEquals(Surface.PAVED,  c("highway" to "cycleway"))
        assertNull(c("highway" to "steps"))
        assertNull(c("building" to "yes"))
    }

    @Test
    fun `mtb difficulty makes a trail gnarly, whatever the surface says`() {
        assertEquals(Surface.GNARLY, c("highway" to "path", "mtb_difficulty" to "4"))
        assertEquals(Surface.GNARLY, c("highway" to "path", "surface" to "gravel", "mtb_difficulty" to "5"))
        assertEquals(Surface.GNARLY, c("highway" to "path", "mtb:scale" to "3"))
        assertEquals(Surface.GNARLY, c("highway" to "path", "mtb:scale" to "4+"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "surface" to "gravel", "mtb_difficulty" to "3"))   // blue: trail even on gravel
        assertEquals(Surface.PAVED,  c("highway" to "track", "surface" to "asphalt", "mtb_difficulty" to "3"))
        assertEquals(Surface.TRAIL,  c("highway" to "path", "mtb_difficulty" to "2"))      // green singletrack
        assertEquals(Surface.GRAVEL, c("highway" to "track", "mtb_difficulty" to "2"))     // green forest road
        assertEquals(Surface.GRAVEL, c("highway" to "track", "mtb_difficulty" to "1"))
        assertEquals(Surface.GRAVEL, c("highway" to "track", "surface" to "gravel", "mtb_difficulty" to "1"))
        assertEquals(Surface.TRAIL,  c("highway" to "path", "mtb:scale" to "0"))
        assertEquals(Surface.TRAIL,  c("highway" to "path", "mtb:scale" to "2"))
        assertEquals(3, SurfaceTagClassifier.mtbDifficulty(mapOf("mtb:scale" to "2")))
        assertEquals(0, SurfaceTagClassifier.mtbDifficulty(mapOf("highway" to "path")))
    }

    @Test
    fun `an unknown surface value falls through to the other tags`() {
        assertEquals(Surface.GRAVEL, c("highway" to "track", "surface" to "metal_grid"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "surface" to "weird", "tracktype" to "grade5"))
    }
}
