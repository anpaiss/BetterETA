package com.anpaiss.boschrange

import android.content.Context
import com.anpaiss.bettereta.Surface
import java.io.File
import java.util.Locale

/**
 * One CSV row per tick while recording: what the model saw and believed, next
 * to the Karoo's own position and remaining ascent, for the validation
 * script (prediction error at km 0 / 25 / 50 / 75 %). Rotates at 4 MB.
 *   adb pull /sdcard/Android/data/com.anpaiss.boschrange/files/bosch_range_samples.csv
 */
class SampleLog(context: Context) {

    private val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, "bosch_range_samples.csv")

    companion object {
        private const val MAX_SIZE_BYTES = 4 * 1024 * 1024L
        const val HEADER = "t_ms,speed_ms,grade_pct,surface,dist_m,dist_to_dest_m,asc_rem_karoo_m,temp_c,wh_mech,est_pct,anchor_pct"
    }

    @Synchronized
    fun ride(r: RideRecord) {
        ensureHeader()
        file.appendText("# ride ${r.startMs} name=${r.routeName} capacity=${r.capacityWh} s=${r.sUsed}\n")
    }

    @Synchronized
    fun record(tMs: Long, speedMs: Double, gradePct: Double, surface: Surface, distM: Double,
               distToDestM: Double, ascRemM: Double, tempC: Double?, r: RideRecord) {
        ensureHeader()
        file.appendText(String.format(Locale.ROOT, "%d,%.2f,%.2f,%s,%.0f,%.0f,%.0f,%s,%.2f,%.2f,%.0f\n",
            tMs, speedMs, gradePct, surface.name, distM, distToDestM, ascRemM,
            tempC?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "", r.whMech, r.estimatedPct(), r.anchorPct))
    }

    private fun ensureHeader() {
        if (file.length() > MAX_SIZE_BYTES) {
            File(dir, "bosch_range_samples.old.csv").delete()
            file.renameTo(File(dir, "bosch_range_samples.old.csv"))
        }
        if (!file.exists() || file.length() == 0L) file.writeText(HEADER + "\n")
    }
}
