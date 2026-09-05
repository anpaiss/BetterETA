package com.anpaiss.bettereta

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured per-tick CSV log, deliberately kept apart from the human-readable
 * [BetterEtaLogger]. Its only job is post-ride analysis: pull the file after a
 * ride and compare our own estimates against the Karoo's native ones and
 * against what actually happened.
 *
 * File (Android 10+):
 *   /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta_samples.csv
 *   adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta_samples.csv
 *
 * The column set is fixed and already carries columns for values that are not
 * produced yet (shadow ETA, learned power, model rung). They stay empty until
 * the code that fills them lands, so files recorded weeks apart stay directly
 * comparable and can simply be concatenated.
 *
 * Writes go through an unbounded channel drained by a dedicated IO coroutine,
 * so the ride tick never blocks on disk. Every I/O failure is swallowed after a
 * single Logcat warning: diagnostics must never be able to disturb a ride.
 *
 * All numbers are formatted with [Locale.US]. On an Italian device the default
 * locale would render decimals with a comma and silently corrupt every row.
 */
class SampleLog(context: Context) {

    private val file: File
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<String>(Channel.UNLIMITED)
    private var writeFailLogged = false

    companion object {
        private const val MAX_SIZE_BYTES = 4 * 1024 * 1024L  // 4 MB ≈ 7-8 h of riding
        private const val GENERATIONS    = 3
        private const val LOGCAT_TAG     = "BetterEtaSamples"

        /**
         * Fixed column order. Columns beyond the ones currently produced are
         * intentionally present and left empty — see the class docs.
         */
        const val COLUMNS =
            "t_ms,ride_state,gps_age_ms,speed_ms,grade_pct,surface," +
            "pos_ours_m,pos_karoo_m,on_route,rejoin_active," +
            "rem_dist_ours_m,rem_dist_karoo_m," +
            "rem_asc_thresh_m,rem_asc_full_m,rem_asc_karoo_m,rem_asc_climbs_m," +
            "eta_learned_s,eta_physics_s,eta_karoo_s," +
            "p_low_w,p_high_w,model_rung,mta_margin_s"

        /**
         * Serialises one row. Pure and in the companion object on purpose: the
         * locale trap below is worth a unit test, and a test must not need a Context.
         *
         * Every number is formatted with [Locale.US]. On an Italian device the
         * default locale renders decimals with a comma, which would silently turn
         * each row into a different number of columns.
         */
        fun toCsv(row: Row): String = with(row) {
            listOf(
                tMs.toString(),
                rideState,
                gpsAgeMs.toString(),
                num(speedMs, 2),
                num(gradePct, 2),
                surface.name,
                num(posOursM, 1),
                num(posKarooM, 1),
                num(onRoute, 0),
                bool(rejoinActive),
                num(remDistOursM, 1),
                num(remDistKarooM, 1),
                num(remAscThreshM, 1),
                num(remAscFullM, 1),
                num(remAscKarooM, 1),
                num(remAscClimbsM, 1),
                etaLearnedS.toString(),
                etaPhysicsS?.toString().orEmpty(),
                num(etaKarooS, 1),
                num(pLowW, 1),
                num(pHighW, 1),
                modelRung.orEmpty(),
                mtaMarginS?.toString().orEmpty(),
            ).joinToString(",")
        }

        private fun num(v: Double?, decimals: Int): String =
            v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""

        private fun bool(v: Boolean?): String = when (v) {
            true  -> "1"
            false -> "0"
            null  -> ""
        }
    }

    /** One CSV row. Null fields are written as empty cells. */
    data class Row(
        val tMs: Long,
        val rideState: String,
        val gpsAgeMs: Long,
        val speedMs: Double,
        val gradePct: Double,
        val surface: Surface,
        val posOursM: Double,
        val posKarooM: Double? = null,
        val onRoute: Double? = null,
        val rejoinActive: Boolean? = null,
        val remDistOursM: Double,
        val remDistKarooM: Double? = null,
        val remAscThreshM: Double,
        val remAscFullM: Double,
        val remAscKarooM: Double? = null,
        val remAscClimbsM: Double? = null,
        val etaLearnedS: Long,
        val etaPhysicsS: Long? = null,
        val etaKarooS: Double? = null,
        val pLowW: Double? = null,
        val pHighW: Double? = null,
        val modelRung: String? = null,
        /** Seconds of slack against the mandatory arrival time; null when none is set. */
        val mtaMarginS: Long? = null,
    )

    init {
        val dir = if (Build.VERSION.SDK_INT >= 29) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
        file = File(dir, "better_eta_samples.csv")
        startWriter()
    }

    /** Absolute path, so the human log can point at it. */
    val path: String get() = file.absolutePath

    /**
     * Marks the start of a new route in the file, so rides recorded into the
     * same file stay separable during analysis.
     */
    fun ride(name: String, routeDistanceM: Double, polylineHash: Int) {
        enqueue(
            "# ride ${tsFmt.format(Date())} name=${name.replace(',', ' ')} " +
                "dist=${routeDistanceM.toInt()} hash=$polylineHash"
        )
    }

    /** Free-form marker line, for anything worth finding again in the file. */
    fun note(text: String) = enqueue("# $text")

    fun record(row: Row) = enqueue(toCsv(row))

    fun close() {
        channel.close()
        scope.cancel()
    }

    // ── private ──────────────────────────────────────────────────────────

    private fun enqueue(line: String) {
        // Unbounded channel: trySend only fails once the log has been closed,
        // in which case dropping the line is exactly what we want.
        channel.trySend(line)
    }

    private fun startWriter() = scope.launch {
        for (first in channel) {
            val batch = StringBuilder().append(first).append('\n')
            // Drain whatever else is already queued so a burst costs one write.
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                batch.append(next).append('\n')
            }
            flush(batch.toString())
        }
    }

    private fun flush(text: String) {
        runCatching {
            if (!file.exists() || file.length() == 0L) writeHeader()
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText(text)
            writeFailLogged = false
        }.onFailure { e ->
            // One warning per run of failures, so a full disk cannot flood Logcat.
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Sample log write failed: ${file.absolutePath}", e)
            }
        }
    }

    private fun writeHeader() {
        file.appendText("$COLUMNS\n")
    }

    private fun rotate() {
        // Three generations kept: a ride not pulled in time survives the next rotation
        fun gen(i: Int) = File(file.parent, if (i == 1) "better_eta_samples.old.csv" else "better_eta_samples.old$i.csv")
        gen(GENERATIONS).delete()
        for (i in GENERATIONS - 1 downTo 1) gen(i).takeIf { it.exists() }?.renameTo(gen(i + 1))
        file.renameTo(gen(1))
        file.createNewFile()
        writeHeader()
    }
}
