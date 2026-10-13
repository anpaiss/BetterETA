package com.anpaiss.bettereta

/**
 * Supplies a speed for a gradient the learned model has no data for yet.
 *
 * This is rung 3 of [AdaptiveSpeedModel]'s cascade — reached only when neither
 * the per-surface bucket nor the global gradient bucket has enough samples.
 * It exists as an interface so a candidate replacement can be run alongside the
 * one in production and judged on real rides before anything is switched over.
 */
interface FallbackSpeedProvider {

    /** Short identifier, recorded in the sample log. */
    val name: String

    /**
     * @param gradePct    gradient of the segment, % (positive = uphill)
     * @param surface     road surface, if known
     * @param flatSpeedMs the rider's observed flat speed, the historical anchor
     * @return predicted speed in m/s
     */
    fun fallbackSpeedMs(gradePct: Double, surface: Surface, flatSpeedMs: Double): Double
}

/**
 * The original fallback: observed flat speed scaled by a per-bucket factor.
 *
 * The factors are hand-tuned for an e-bike, and anchored to flat speed — which
 * on a pedelec is pinned near the 25 km/h assist cutoff rather than being a
 * measure of the rider. That makes them a fragile basis for extrapolating to
 * gradients never ridden, which is what [PhysicsFallback] is meant to improve on.
 */
object GradientFactorFallback : FallbackSpeedProvider {

    override val name = "factors"

    // Speed relative to the flat bucket (index 1 = 1.0), for an e-bike.
    // Un fattore per bucket: discesa, discesa dolce, piano, salita, ripida, muro
    private val FACTORS = doubleArrayOf(1.15, 1.10, 1.0, 0.80, 0.65, 0.50, 0.42)

    override fun fallbackSpeedMs(gradePct: Double, surface: Surface, flatSpeedMs: Double): Double =
        (flatSpeedMs * FACTORS[AdaptiveSpeedModel.bucketIndex(gradePct)]).coerceAtLeast(0.5)
}

/**
 * Physics-based fallback: solves the rider's learned effective power for the
 * gradient in question. See [PhysicsSpeedModel] for why this works on an e-bike
 * even though the motor's contribution is never measured.
 *
 * Delegates to [descentFallback] where the physics does not describe how people
 * actually ride: on descents the model returns a coasting equilibrium speed,
 * but riders brake for corners, surface and visibility, so the learned buckets
 * are genuinely better there. It also delegates until the power model has
 * enough samples to be trusted.
 */
class PhysicsFallback(
    private val physics: PhysicsSpeedModel,
    private val descentFallback: FallbackSpeedProvider = GradientFactorFallback,
) : FallbackSpeedProvider {

    override val name = "physics"

    override fun fallbackSpeedMs(gradePct: Double, surface: Surface, flatSpeedMs: Double): Double =
        physics.predictSpeedMs(gradePct, surface)
            ?: descentFallback.fallbackSpeedMs(gradePct, surface, flatSpeedMs)
}
