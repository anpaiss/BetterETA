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
    fun `an unknown surface value falls through to the other tags`() {
        assertEquals(Surface.GRAVEL, c("highway" to "track", "surface" to "metal_grid"))
        assertEquals(Surface.TRAIL,  c("highway" to "track", "surface" to "weird", "tracktype" to "grade5"))
    }
}
