package com.anpaiss.boschrange

import android.content.Context
import android.content.SharedPreferences

/** User settings. Same pattern as the other extensions. */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** PowerMore 250 Wh range extender mounted: capacity 1000 Wh instead of 750. */
    var powerMore: Boolean
        get() = sp.getBoolean(KEY_POWER_MORE, true)
        set(v) = sp.edit().putBoolean(KEY_POWER_MORE, v).apply()

    /** Nominal capacity the Bosch percentage refers to (the display shows the total). */
    val capacityWh: Double get() = if (powerMore) MAIN_WH + POWERMORE_WH else MAIN_WH

    /** Alert when the predicted battery at arrival drops below this. */
    var alertThresholdPct: Int
        get() = sp.getInt(KEY_THRESHOLD, 15)
        set(v) = sp.edit().putInt(KEY_THRESHOLD, v).apply()

    /**
     * Battery Wh per mechanical Wh in the assisted regime. Fixed at the
     * backtest value until the 5th valid ride; the regression that recomputes
     * it comes with the validation step.
     */
    var sCoefficient: Double
        get() = sp.getFloat(KEY_S, S_INITIAL.toFloat()).toDouble()
        set(v) = sp.edit().putFloat(KEY_S, v.toFloat()).apply()

    /** Rider mass from the Karoo profile, kept across restarts. */
    var riderKg: Double
        get() = sp.getFloat(KEY_RIDER_KG, 84f).toDouble()
        set(v) = sp.edit().putFloat(KEY_RIDER_KG, v.toFloat()).apply()

    companion object {
        private const val NAME = "bosch_range_prefs"
        private const val KEY_POWER_MORE = "power_more"
        private const val KEY_THRESHOLD  = "alert_threshold_pct"
        private const val KEY_S          = "s_coefficient"
        private const val KEY_RIDER_KG   = "rider_kg"
        const val MAIN_WH      = 750.0
        const val POWERMORE_WH = 250.0
        /** Backtest 13/10/2026 on the rides of 10/10 and 11/10 with the surface classes: 1.25 / 1.24. */
        const val S_INITIAL    = 1.25
    }
}
