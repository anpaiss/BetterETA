package com.anpaiss.bettereta

import android.content.Context
import android.os.Build
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
 * Rotazione automatica a 512 KB → il file precedente viene rinominato better_eta.old.log.
 */
class BetterEtaLogger(context: Context) {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    companion object {
        private const val MAX_SIZE_BYTES = 512 * 1024L  // 512 KB
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
        runCatching {
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText("${fmt.format(Date())} [${tag.padEnd(5)}] $message\n")
        }
    }

    private fun rotate() {
        val old = File(file.parent, "better_eta.old.log")
        old.delete()
        file.renameTo(old)
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato (file precedente → .old.log) ===\n")
    }
}
