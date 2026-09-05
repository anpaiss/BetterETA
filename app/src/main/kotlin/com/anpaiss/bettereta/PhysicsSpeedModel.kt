package com.anpaiss.bettereta

import kotlin.math.sqrt

/**
 * Speed model built on the standard cycling power equation:
 *
 *     P = 0.5*CdA*rho*v^3 + m*g*(s + Crr)*v
 *
 * with s the gradient as a fraction and Crr the rolling resistance of the
 * surface. Learning runs the equation backwards: we already sample speed and
 * gradient once a second, so each sample yields an implied power, which is
 * averaged the way [AdaptiveSpeedModel] averages speeds. Prediction then solves
 * the same equation forwards for any gradient.
 *
 * **Why this works on an e-bike.** The power is never measured — it is fitted
 * from how fast the rider actually went, so on a pedelec it already contains
 * the motor's contribution. No power meter, and no knowledge of the motor, is
 * needed. Absolute power here is meaningless: it is a parameter, not a physical
 * quantity.
 *
 * **Why wrong constants do little harm.** Because we invert and predict with
 * the same constants, mass and CdA cancel within a regime. On a climb the
 * gravity term dominates, so learning at gradient s1 and predicting at s2 gives
 * v2 = v1*s1/s2, independent of m. The constants only matter when extrapolating
 * flat-to-climb, which is exactly what the hand-tuned factors in
 * [GradientFactorFallback] do today, and with far less structure.
 *
 * **The one e-bike specific term.** A pedelec cuts assistance at ~25 km/h, so
 * effective power is piecewise in speed: below the cutoff it is rider + motor,
 * above it is rider alone. Two averages are kept, split by the speed of the
 * sample. This is data-driven and needs no "is it an e-bike" setting: on a
 * muscular bike the two simply converge and nothing changes.
 *
 * Speeds in m/s, gradients in %.
 */
class PhysicsSpeedModel {

    companion object {
        // Fitted parameters, not measurements — see the class docs. Their absolute
        // values are absorbed into the learned power; only their ratio matters.
        private const val CDA         = 0.45    // m^2, upright-ish position
        private const val AIR_DENSITY = 1.225   // kg/m^3 at sea level
        private const val G           = 9.81
        internal const val BIKE_MASS_KG     = 22.0  // e-bike with battery
        internal const val DEFAULT_RIDER_KG = 75.0  // until UserProfile arrives

        // Rolling resistance by surface. UNKNOWN sits just above paved: most
        // roads are paved, but assuming perfectly so is optimistic.
        private const val CRR_PAVED   = 0.005
        private const val CRR_GRAVEL  = 0.012
        private const val CRR_TRAIL   = 0.020
        private const val CRR_UNKNOWN = 0.006

        /** EU pedelec assist cutoff, a little under 25 km/h to catch the regime cleanly. */
        internal const val ASSIST_CUTOFF_MS = 6.7

        // Same window as the learned speed model: cumulative mean, then an EMA
        // with an equivalent window of ~20 min of riding.
        private const val EMA_WINDOW = 1200

        /** Samples needed before a power average is used for prediction. */
        internal const val MIN_POWER_SAMPLES = 60

        /**
         * Below this gradient the coasting equilibrium dominates: the equation
         * returns the speed of an unbraked rider, which is not the speed anyone
         * actually rides down a hill. Both learning and prediction stay above it,
         * so the model is only ever asked about terrain where power is what
         * limits speed. Descents are left to the learned buckets.
         */
        internal const val MIN_GRADE_PCT = -2.0

        // Guards on the inferred power: non-positive means coasting, and the cap
        // rejects the speed spikes a bad GPS fix produces.
        private const val MAX_PLAUSIBLE_W = 2000.0

        private const val MIN_SPEED_MS = 0.5
        private const val MAX_SPEED_MS = 16.7   // 60 km/h
        private const val BISECTION_STEPS = 64

        internal fun crrFor(surface: Surface): Double = when (surface) {
            Surface.PAVED   -> CRR_PAVED
            Surface.GRAVEL  -> CRR_GRAVEL
            Surface.TRAIL   -> CRR_TRAIL
            Surface.UNKNOWN -> CRR_UNKNOWN
        }
    }

    private data class Avg(val ema: Double = 0.0, val count: Int = 0)

    private var pLow  = Avg()   // assisted regime: sample speed below the cutoff
    private var pHigh = Avg()   // unassisted regime: at or above the cutoff
    private var totalMassKg = DEFAULT_RIDER_KG + BIKE_MASS_KG

    val powerLowW: Double?  get() = pLow.ema.takeIf { pLow.count > 0 }
    val powerHighW: Double? get() = pHigh.ema.takeIf { pHigh.count > 0 }
    val lowSamples: Int  get() = pLow.count
    val highSamples: Int get() = pHigh.count
    val massKg: Double get() = totalMassKg

    /**
     * Sets total system mass from the rider's configured weight.
     *
     * A learned power is only meaningful together with the mass it was inferred
     * with, so a material change invalidates what has been learned. Returns true
     * when that reset happened, so the caller can log it.
     */
    fun setRiderMassKg(riderKg: Double): Boolean {
        if (riderKg <= 0.0) return false
        val newTotal = riderKg + BIKE_MASS_KG
        if (kotlin.math.abs(newTotal - totalMassKg) < 1.0) return false
        totalMassKg = newTotal
        pLow  = Avg()
        pHigh = Avg()
        return true
    }

    /** Feeds one 1 Hz sample. Rejected silently where the physics says nothing useful. */
    fun update(speedMs: Double, gradePct: Double, surface: Surface) {
        if (speedMs < MIN_SPEED_MS || gradePct < MIN_GRADE_PCT) return
        val power = powerAt(speedMs, gradePct, crrFor(surface))
        if (power <= 0.0 || power > MAX_PLAUSIBLE_W) return

        if (speedMs < ASSIST_CUTOFF_MS) {
            pLow = Avg(emaStep(pLow.ema, power, pLow.count), pLow.count + 1)
        } else {
            pHigh = Avg(emaStep(pHigh.ema, power, pHigh.count), pHigh.count + 1)
        }
    }

    /**
     * Predicted speed for a gradient, or null when the model cannot answer:
     * not enough samples yet, or a gradient below [MIN_GRADE_PCT] where the
     * caller should fall back to the learned descent behaviour.
     */
    fun predictSpeedMs(gradePct: Double, surface: Surface): Double? {
        if (gradePct < MIN_GRADE_PCT) return null
        val crr = crrFor(surface)

        val low  = pLow.ema.takeIf { pLow.count >= MIN_POWER_SAMPLES }
        val high = pHigh.ema.takeIf { pHigh.count >= MIN_POWER_SAMPLES }
        if (low == null && high == null) return null

        // Assist cuts out at the cutoff, so effective power is piecewise in v.
        // Solve in the assisted regime first; if that answer sits above the
        // cutoff the rider is unassisted there, so re-solve with the unassisted
        // power. When neither regime is self-consistent the rider is pinned at
        // the cutoff itself — which is exactly what an e-bike does on the flat.
        if (low != null) {
            val vLow = solveSpeed(low, gradePct, crr)
            if (vLow <= ASSIST_CUTOFF_MS) return vLow
            if (high == null) return vLow
            val vHigh = solveSpeed(high, gradePct, crr)
            return if (vHigh >= ASSIST_CUTOFF_MS) vHigh else ASSIST_CUTOFF_MS
        }
        return solveSpeed(high!!, gradePct, crr)
    }

    /** Power (W) needed to hold [speedMs] at this gradient and surface. */
    internal fun powerAt(speedMs: Double, gradePct: Double, crr: Double): Double {
        val s = gradePct / 100.0
        return 0.5 * CDA * AIR_DENSITY * speedMs * speedMs * speedMs +
            totalMassKg * G * (s + crr) * speedMs
    }

    /**
     * Solves P(v) = [powerW] for v by bisection.
     *
     * P is monotonic in v only while s + Crr >= 0. Below that, P(v) = a*v^3 - b*v
     * dips negative at low speed, crosses zero at the coasting equilibrium and
     * only then rises. For a positive target that leaves a single root, above the
     * crossing; starting the bracket there keeps P monotonic over it, which is
     * what the bisection invariant needs.
     */
    internal fun solveSpeed(powerW: Double, gradePct: Double, crr: Double): Double {
        val s = gradePct / 100.0
        val slopeTerm = s + crr

        var lo = MIN_SPEED_MS
        if (slopeTerm < 0.0) {
            // P(v) = 0 at v = sqrt(-2*m*g*(s + Crr) / (CdA*rho))
            val vCoast = sqrt(-2.0 * totalMassKg * G * slopeTerm / (CDA * AIR_DENSITY))
            if (vCoast >= MAX_SPEED_MS) return MAX_SPEED_MS
            lo = maxOf(lo, vCoast)
        }
        var hi = MAX_SPEED_MS
        if (powerAt(hi, gradePct, crr) <= powerW) return MAX_SPEED_MS
        if (powerAt(lo, gradePct, crr) >= powerW) return lo

        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2.0
            if (powerAt(mid, gradePct, crr) < powerW) lo = mid else hi = mid
        }
        return ((lo + hi) / 2.0).coerceIn(MIN_SPEED_MS, MAX_SPEED_MS)
    }

    /** Serialisable state. Mass is part of it: a power is only valid with its mass. */
    data class Snapshot(
        val lowEma: Double,
        val lowCount: Int,
        val highEma: Double,
        val highCount: Int,
        val totalMassKg: Double,
    )

    fun snapshot() = Snapshot(pLow.ema, pLow.count, pHigh.ema, pHigh.count, totalMassKg)

    fun restore(snap: Snapshot) {
        pLow  = Avg(snap.lowEma, snap.lowCount)
        pHigh = Avg(snap.highEma, snap.highCount)
        if (snap.totalMassKg > 0.0) totalMassKg = snap.totalMassKg
    }

    fun reset() {
        pLow  = Avg()
        pHigh = Avg()
    }

    // Cumulative mean up to EMA_WINDOW samples, then an EMA of that window —
    // the same shape as AdaptiveSpeedModel, so the two adapt at the same rate.
    private fun emaStep(ema: Double, sample: Double, prevCount: Int): Double {
        val alpha = 1.0 / minOf(prevCount + 1, EMA_WINDOW)
        return alpha * sample + (1.0 - alpha) * ema
    }
}
