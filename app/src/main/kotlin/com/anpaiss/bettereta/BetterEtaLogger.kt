package com.anpaiss.bettereta

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger su file per il diagnostico di BetterETA.
 *
 * Percorso del file (Android 10+):
 *   /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta.log
 *
 * Recupero via ADB (non richiede root):
 *   adb pull /sdcard/Android/data/com.anpaiss.bettereta/files/better_eta.log
 *
 * Se il log è nel filesDir interno (Android < 10 / storage non disponibile):
 *   adb shell run-as com.anpaiss.bettereta cat files/better_eta.log
 *
 * Rotazione automatica a 1 MB → il file precedente viene rinominato better_eta.old.log.
 * Ogni riga è replicata anche su Logcat (tag "BetterEta") per il debug live via
 * `adb logcat -s BetterEta`; se la scrittura su file fallisce, l'errore finisce
 * su Logcat invece di sparire in silenzio.
 */
class BetterEtaLogger(context: Context) {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writeFailLogged = false

    companion object {
        private const val MAX_SIZE_BYTES = 1024 * 1024L  // 1 MB
        private const val LOGCAT_TAG     = "BetterEta"
    }

    init {
        val dir = if (Build.VERSION.SDK_INT >= 29) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
        file = File(dir, "better_eta.log")
        log("SYS", "=== BetterETA avviato ===")
        log("SYS", "Log: ${file.absolutePath}")
        log("SYS", "Recupero: adb pull \"${file.absolutePath}\"")
    }

    /** Scrive una riga timestampata. Thread-safe. */
    @Synchronized
    fun log(tag: String, message: String) {
        Log.d(LOGCAT_TAG, "[${tag.trim()}] $message")
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText("${fmt.format(Date())} [${tag.padEnd(5)}] $message\n")
            writeFailLogged = false
        }.onFailure { e ->
            // Una sola segnalazione per serie di fallimenti, per non inondare Logcat
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Scrittura log su file fallita: ${file.absolutePath}", e)
            }
        }
    }

    /** Come [log], ma accoda anche lo stacktrace dell'eccezione. */
    fun logError(tag: String, message: String, t: Throwable) {
        log(tag, "$message: ${t::class.simpleName}: ${t.message}")
        t.stackTraceToString().lineSequence()
            .filter { it.isNotBlank() }
            .take(15)
            .forEach { log(tag, "    ${it.trim()}") }
    }

    private fun rotate() {
        val old = File(file.parent, "better_eta.old.log")
        old.delete()
        file.renameTo(old)
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato (file precedente → .old.log) ===\n")
    }
}
