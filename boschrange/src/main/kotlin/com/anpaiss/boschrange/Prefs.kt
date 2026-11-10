package com.anpaiss.boschrange

import android.content.Context
import android.content.SharedPreferences

/** User settings. Same pattern as the other extensions. */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Master switch: off = no ride is opened, no popup, nothing recorded. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** PowerMore 250 Wh range extender mounted: capacity 1000 Wh instead of 750. */
    var powerMore: Boolean
        get() = sp.getBoolean(KEY_POWER_MORE, true)
        set(v) = sp.edit().putBoolean(KEY_POWER_MORE, v).apply()

    /** Nominal capacity the Bosch percentage refers to (the display shows the total). */
    val capacityWh: Double get() = if (powerMore) MAIN_WH + POWERMORE_WH else MAIN_WH

    /** Alert when the predicted battery at arrival drops below this. */
    var alertThresholdPct: Int
        get() = sp.getInt(KEY_THRESHOLD, 10)   // 10 %: the Bosch reserve (Andrea, 19/10)
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

    /** Tyres switch: off = no tyre row in the popup, no tyre section in the app, nothing recorded about tyres. */
    var tyresEnabled: Boolean
        get() = sp.getBoolean(KEY_TYRES, false)
        set(v) = sp.edit().putBoolean(KEY_TYRES, v).apply()

    /** Dry / wet switch (with the tyres on): off = no DRY / WET / MUD row in the start popup, no dry / wet split in the stats. */
    var conditionEnabled: Boolean
        get() = sp.getBoolean(KEY_CONDITION, false)
        set(v) = sp.edit().putBoolean(KEY_CONDITION, v).apply()

    /** The tyre pairs the rider saved. */
    var tyrePairs: List<TyrePair>
        get() = TyrePair.listFromJson(sp.getString(KEY_TYRE_PAIRS, null))
        set(v) = sp.edit().putString(KEY_TYRE_PAIRS, TyrePair.listToJson(v)).apply()

    /** The pair on the bike now: every ride records it. */
    var mountedPairId: String?
        get() = sp.getString(KEY_MOUNTED, null)
        set(v) = sp.edit().putString(KEY_MOUNTED, v).apply()

    val mountedPair: TyrePair? get() = mountedPairId?.let { id -> tyrePairs.firstOrNull { it.id == id } }

    companion object {
        private const val NAME = "bosch_range_prefs"
        private const val KEY_TYRES      = "tyres_enabled"
        private const val KEY_CONDITION  = "condition_enabled"
        private const val KEY_TYRE_PAIRS = "tyre_pairs"
        private const val KEY_MOUNTED    = "tyre_mounted_pair"
        private const val KEY_ENABLED    = "enabled"
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
