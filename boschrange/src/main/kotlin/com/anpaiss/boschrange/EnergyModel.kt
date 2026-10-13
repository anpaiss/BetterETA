package com.anpaiss.boschrange

import com.anpaiss.bettereta.Surface
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mechanical power the bike needs at a given speed, gradient and surface:
 *
 *   P = m·g·v·sin θ + Crr·m·g·v·cos θ + ½·ρ·CdA·v³
 *
 * The battery pays for it only in the assisted regime (below the 25 km/h
 * cutoff) and only when P is positive; the ratio battery Wh / mechanical Wh
 * is the single learned coefficient s (Prefs.sCoefficient). Constants as in
 * PhysicsSpeedModel of eta-core (CdA, air density, Crr per surface), so the
 * backtest of 13/10 and the device agree.
 */
class EnergyModel(riderKg: Double) {

    var riderKg: Double = riderKg
        set(v) { field = v; totalMassKg = v + BIKE_MASS_KG }
    var totalMassKg: Double = riderKg + BIKE_MASS_KG
        private set

    fun powerW(speedMs: Double, gradePct: Double, surface: Surface): Double {
        val th = atan(gradePct / 100.0)
        val climb = totalMassKg * G * speedMs * sin(th)
        val roll  = crrFor(surface) * totalMassKg * G * speedMs * cos(th)
        val aero  = 0.5 * AIR_DENSITY * CDA * speedMs * speedMs * speedMs
        return climb + roll + aero
    }

    /** Battery-relevant mechanical energy of one tick, in Wh (0 when coasting or above the cutoff). */
    fun tickWh(speedMs: Double, gradePct: Double, surface: Surface, dtSec: Double): Double {
        if (speedMs >= ASSIST_CUTOFF_MS) return 0.0
        val p = powerW(speedMs, gradePct, surface)
        return if (p > 0.0) p * dtSec / 3600.0 else 0.0
    }

    companion object {
        const val BIKE_MASS_KG     = 25.0   // Orbea Wild FS with 750 Wh and PowerMore
        const val ASSIST_CUTOFF_MS = 6.7    // EU pedelec cutoff, as PhysicsSpeedModel
        private const val CDA         = 0.45
        private const val AIR_DENSITY = 1.225
        private const val G           = 9.81

        /** Rolling resistance per surface class, same table as PhysicsSpeedModel. */
        fun crrFor(surface: Surface): Double = when (surface) {
            Surface.PAVED   -> 0.005
            Surface.GRAVEL  -> 0.012
            Surface.TRAIL   -> 0.020
            Surface.GNARLY  -> 0.035
            Surface.UNKNOWN -> 0.006
        }
    }
}
