package com.anpaiss.bettereta

import io.hammerhead.karooext.models.OnNavigationState.NavigationState.Climb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Karoo's climb list is one of the two independent references we log our own
 * remaining ascent against, so the proration has to be right or the comparison
 * that drives the phase 2 decision is worthless.
 */
class RouteMetricsTest {

    // Two climbs: 1000-2000 m (+100 m) and 5000-6000 m (+200 m).
    private val climbs = listOf(
        Climb(startDistance = 1000.0, length = 1000.0, grade = 10.0, totalElevation = 100.0),
        Climb(startDistance = 5000.0, length = 1000.0, grade = 20.0, totalElevation = 200.0),
    )

    @Test
    fun `no climb data is distinct from no ascent left`() {
        assertNull(remainingClimbsAscentM(emptyList(), 0.0))
        assertEquals(0.0, remainingClimbsAscentM(climbs, 9999.0)!!, 1e-9)
    }

    @Test
    fun `at the start every climb counts in full`() {
        assertEquals(300.0, remainingClimbsAscentM(climbs, 0.0)!!, 1e-9)
    }

    @Test
    fun `a climb already passed contributes nothing`() {
        assertEquals(200.0, remainingClimbsAscentM(climbs, 2000.0)!!, 1e-9)
        assertEquals(200.0, remainingClimbsAscentM(climbs, 3000.0)!!, 1e-9)
    }

    @Test
    fun `the climb we are inside is prorated by the length left`() {
        // 300 m into the first climb: 70 % of its 100 m left, plus the second in full.
        assertEquals(70.0 + 200.0, remainingClimbsAscentM(climbs, 1300.0)!!, 1e-9)
        // Exactly halfway up the second climb.
        assertEquals(100.0, remainingClimbsAscentM(climbs, 5500.0)!!, 1e-9)
    }

    @Test
    fun `a zero length climb cannot divide by zero`() {
        val degenerate = listOf(Climb(startDistance = 100.0, length = 0.0, grade = 0.0, totalElevation = 50.0))
        assertEquals(0.0, remainingClimbsAscentM(degenerate, 100.0)!!, 1e-9)
        // Still ahead of it, so it counts in full.
        assertEquals(50.0, remainingClimbsAscentM(degenerate, 50.0)!!, 1e-9)
    }
}
