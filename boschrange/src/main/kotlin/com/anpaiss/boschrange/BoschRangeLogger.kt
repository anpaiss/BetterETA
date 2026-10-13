package com.anpaiss.boschrange

import android.content.Context
import android.os.Build
import android.util.Log
import com.anpaiss.bettereta.RideLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File logger, same shape as BetterEtaLogger:
 *   adb pull /sdcard/Android/data/com.anpaiss.boschrange/files/bosch_range.log
 * Rotates at 4 MB keeping three generations; every line also goes to Logcat
 * (tag "BoschRange").
 */
class BoschRangeLogger(context: Context, announce: Boolean = true) : RideLogger {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writeFailLogged = false

    companion object {
        private const val MAX_SIZE_BYTES = 4 * 1024 * 1024L
        private const val GENERATIONS    = 3
        const val LOGCAT_TAG             = "BoschRange"
    }

    init {
        val dir = if (Build.VERSION.SDK_INT >= 29) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
        file = File(dir, "bosch_range.log")
        if (announce) {
            log("SYS", "=== boschRange avviato ===")
            log("SYS", "Log: ${file.absolutePath}")
        }
    }

    @Synchronized
    override fun log(tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[${tag.trim()}] $message")
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText("${fmt.format(Date())} [${tag.padEnd(5)}] $message\n")
            writeFailLogged = false
        }.onFailure { e ->
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Scrittura log su file fallita: ${file.absolutePath}", e)
            }
        }
    }

    override fun logError(tag: String, message: String, t: Throwable) {
        log(tag, "$message: ${t::class.simpleName}: ${t.message}")
        t.stackTraceToString().lineSequence()
            .filter { it.isNotBlank() }
            .take(15)
            .forEach { log(tag, "    ${it.trim()}") }
    }

    private fun rotate() {
        fun gen(i: Int) = File(file.parent, if (i == 1) "bosch_range.old.log" else "bosch_range.old$i.log")
        gen(GENERATIONS).delete()
        for (i in GENERATIONS - 1 downTo 1) gen(i).takeIf { it.exists() }?.renameTo(gen(i + 1))
        file.renameTo(gen(1))
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato ===\n")
    }
}
