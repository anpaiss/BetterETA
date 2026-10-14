package com.anpaiss.bettereta

import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * "Ultimo giro: non contarlo". At the first ride start of a day the extension
 * photographs what the ride is about to change (learned model and physics,
 * per-way memory, comparison history, list of imported FITs); the app button
 * puts the photo back and the rides of that day are gone from the learning.
 * Later starts on the same day join the same photo: a lunch stop with the
 * recording closed and reopened is still the same ride for Andrea, and the
 * replay tools on the PC exclude rides by day too. Pure files, no Android.
 */
class LastRideStore(private val dir: File) {

    class Snapshot(
        val starts: List<Long>,
        val model: Map<String, Any>,
        val results: Map<String, Any>,
        val wayMemory: String,
        val importedFits: String?,   // null = the list did not exist
    )

    private val meta = File(dir, "meta.txt")
    private val model = File(dir, "model.txt")
    private val results = File(dir, "results.txt")
    private val way = File(dir, "way_speeds.txt")
    private val imported = File(dir, "imported_fits.txt")
    private val importedMissing = File(dir, "imported_fits.missing")

    /** Ride starts (epoch ms) covered by the photo, oldest first; empty = no photo. */
    fun starts(): List<Long> =
        if (!meta.isFile) emptyList() else meta.readLines().mapNotNull { it.trim().toLongOrNull() }

    /** Takes a new photo for the ride starting at [startMs]; any older photo is replaced. */
    fun take(startMs: Long, modelPrefs: Map<String, *>, resultPrefs: Map<String, *>, wayMemoryText: String, importedFitsFile: File) {
        delete()
        dir.mkdirs()
        model.writeText(PrefsText.encode(modelPrefs))
        results.writeText(PrefsText.encode(resultPrefs))
        way.writeText(wayMemoryText)
        if (importedFitsFile.isFile) importedFitsFile.copyTo(imported, overwrite = true) else importedMissing.writeText("")
        meta.writeText("$startMs\n")
    }

    /** A later start of the same day joins the existing photo. */
    fun addStart(startMs: Long) {
        if (meta.isFile) meta.appendText("$startMs\n")
    }

    fun read(): Snapshot? {
        if (!meta.isFile || !model.isFile || !results.isFile || !way.isFile) return null
        return Snapshot(
            starts = starts(),
            model = PrefsText.decode(model.readText()),
            results = PrefsText.decode(results.readText()),
            wayMemory = way.readText(),
            importedFits = if (imported.isFile) imported.readText() else null,
        )
    }

    fun delete() {
        if (dir.isDirectory) dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
}

/** SharedPreferences content as text, one `key<TAB>type<TAB>value` per line. */
object PrefsText {
    fun encode(values: Map<String, *>): String = buildString {
        for ((k, v) in values) {
            val (type, text) = when (v) {
                is Int -> "i" to v.toString()
                is Long -> "l" to v.toString()
                is Float -> "f" to v.toString()
                is Boolean -> "b" to v.toString()
                is String -> "s" to v.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
                else -> continue
            }
            append(k).append('\t').append(type).append('\t').append(text).append('\n')
        }
    }

    fun decode(text: String): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) continue
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) continue
            val (k, type, v) = parts
            val value: Any = when (type) {
                "i" -> v.toIntOrNull() ?: continue
                "l" -> v.toLongOrNull() ?: continue
                "f" -> v.toFloatOrNull() ?: continue
                "b" -> v.toBoolean()
                "s" -> unescape(v)
                else -> continue
            }
            out[k] = value
        }
        return out
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}

/** Two instants on the same local calendar day: the unit of "a ride" for the exclusion. */
object RideDay {
    fun same(aMs: Long, bMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val a = Calendar.getInstance(zone).apply { timeInMillis = aMs }
        val b = Calendar.getInstance(zone).apply { timeInMillis = bMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}
