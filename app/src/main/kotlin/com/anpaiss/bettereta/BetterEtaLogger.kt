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
 * Rotazione automatica a 4 MB; si conservano tre generazioni precedenti
 * (better_eta.old.log, .old2.log, .old3.log), così un giro non copiato in
 * tempo non sparisce alla rotazione successiva.
 * Ogni riga è replicata anche su Logcat (tag "BetterEta") per il debug live via
 * `adb logcat -s BetterEta`; se la scrittura su file fallisce, l'errore finisce
 * su Logcat invece di sparire in silenzio.
 */
class BetterEtaLogger(context: Context, announce: Boolean = true) {

    private val file: File
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var writeFailLogged = false

    companion object {
        private const val MAX_SIZE_BYTES = 4 * 1024 * 1024L  // 4 MB ≈ 40k righe
        private const val GENERATIONS    = 3
        const val LOGCAT_TAG             = "BetterEta"

        /**
         * Istanza dell'estensione, per i DataType che non ricevono il logger
         * nel costruttore (il ciclo di vita delle viste va nel file: il
         * ride-app che non riaggancia le viste, 04/09, si è visto solo da logcat).
         */
        @Volatile var shared: BetterEtaLogger? = null
    }

    init {
        val dir = if (Build.VERSION.SDK_INT >= 29) {
            context.getExternalFilesDir(null) ?: context.filesDir
        } else {
            context.filesDir
        }
        file = File(dir, "better_eta.log")
        if (announce) {
            log("SYS", "=== BetterETA avviato ===")
            log("SYS", "Log: ${file.absolutePath}")
            log("SYS", "Recupero: adb pull \"${file.absolutePath}\"")
        }
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
        // .old2 → .old3, .old → .old2, corrente → .old (la più vecchia cade)
        fun gen(i: Int) = File(file.parent, if (i == 1) "better_eta.old.log" else "better_eta.old$i.log")
        gen(GENERATIONS).delete()
        for (i in GENERATIONS - 1 downTo 1) gen(i).takeIf { it.exists() }?.renameTo(gen(i + 1))
        file.renameTo(gen(1))
        file.createNewFile()
        file.appendText("${fmt.format(Date())} [SYS  ] === Log ruotato (file precedente → .old.log, $GENERATIONS generazioni) ===\n")
    }
}
