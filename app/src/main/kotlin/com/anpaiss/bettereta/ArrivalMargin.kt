package com.anpaiss.bettereta

import java.util.Calendar

/**
 * Mandatory time of arrival (MTA): the wall-clock time the rider must be at
 * the destination. Pure arithmetic, kept out of the extension so it can be
 * unit tested.
 *
 * The margin is `MTA - (now + remaining)`: how many seconds of slack are left.
 * It is recomputed from the wall clock every tick, including while paused, so
 * a stop that drags on eats the margin exactly as it eats the real time.
 */
object ArrivalMargin {

    /** Picker granularity: the wheel offers 00/15/30/45. */
    const val MINUTE_STEP = 15

    /** An MTA saved more than this long ago belongs to a previous day's ride. */
    const val STALE_AFTER_MS = 12 * 3600_000L

    /** Seconds of slack; negative = arrival later than the MTA. */
    fun marginSeconds(mtaEpochMs: Long, nowMs: Long, remainingSeconds: Long): Long =
        (mtaEpochMs - nowMs) / 1000L - remainingSeconds

    /**
     * Initial picker value: the current ETA rounded UP to the next quarter
     * hour, or now + 1 h when there is no ETA yet. Returns (hour, minute).
     */
    fun defaultPickerTime(nowMs: Long, remainingSeconds: Long): Pair<Int, Int> {
        val targetMs = if (remainingSeconds > 0) nowMs + remainingSeconds * 1000L else nowMs + 3600_000L
        val cal = Calendar.getInstance().apply { timeInMillis = targetMs }
        val stepMs = MINUTE_STEP * 60_000L
        val minuteOfDayMs = (cal.get(Calendar.HOUR_OF_DAY) * 60L + cal.get(Calendar.MINUTE)) * 60_000L
        val rounded = ((minuteOfDayMs + stepMs - 1) / stepMs) * stepMs
        val totalMin = (rounded / 60_000L).toInt() % (24 * 60)
        return (totalMin / 60) to (totalMin % 60)
    }

    /**
     * Epoch millis of the next occurrence of [hour]:[minute] after [nowMs]:
     * today, or tomorrow when that time has already passed (night rides).
     */
    fun epochMsFor(hour: Int, minute: Int, nowMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** "+12 min" / "-5 min", rounding towards zero to whole minutes. */
    fun format(marginSeconds: Long): String = formatShort(marginSeconds) + " min"

    /** "+12" / "-5": for the dashboard tile, where the unit sits in the label. */
    fun formatShort(marginSeconds: Long): String {
        val mins = marginSeconds / 60L
        return if (mins < 0) "$mins" else "+$mins"
    }
}

/**
 * Decides when the "margin lost" alert fires: once on each crossing from
 * non-negative to negative. It re-arms only after the margin has climbed back
 * above [rearmSeconds], so a value hovering around zero does not spam.
 */
class MarginAlertGate(private val rearmSeconds: Long = 120L) {
    private var armed = true

    /** Returns true when the alert should fire for this sample. */
    fun onMargin(marginSeconds: Long): Boolean {
        if (marginSeconds < 0) {
            if (!armed) return false
            armed = false
            return true
        }
        if (marginSeconds >= rearmSeconds) armed = true
        return false
    }

    fun reset() { armed = true }
}
