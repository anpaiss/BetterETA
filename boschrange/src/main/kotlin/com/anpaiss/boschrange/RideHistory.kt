package com.anpaiss.boschrange

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The per-ride history and the ride in progress, as JSON files next to the
 * log (adb pull, edit the Flow fields by hand, adb push back):
 *   files/bosch_range_history.json   array of RideRecord, oldest first
 *   files/ride_current.json          the open ride, rewritten every minute so
 *                                    a restart of the extension mid-ride goes on
 */
class RideHistory(context: Context) {

    private val dir: File = context.getExternalFilesDir(null) ?: context.filesDir
    val historyFile = File(dir, "bosch_range_history.json")
    val currentFile = File(dir, "ride_current.json")

    @Synchronized
    fun saveCurrent(r: RideRecord) {
        currentFile.writeText(r.toJson().toString(2))
    }

    @Synchronized
    fun loadCurrent(): RideRecord? =
        runCatching { if (currentFile.isFile) RideRecord.fromJson(JSONObject(currentFile.readText())) else null }.getOrNull()

    @Synchronized
    fun clearCurrent() { currentFile.delete() }

    @Synchronized
    fun append(r: RideRecord) {
        val arr = loadArray()
        arr.put(r.toJson())
        historyFile.writeText(arr.toString(2))
        currentFile.delete()
    }

    @Synchronized
    fun load(): List<RideRecord> {
        val arr = loadArray()
        return (0 until arr.length()).mapNotNull { i -> runCatching { RideRecord.fromJson(arr.getJSONObject(i)) }.getOrNull() }
    }

    private fun loadArray(): JSONArray =
        runCatching { if (historyFile.isFile) JSONArray(historyFile.readText()) else null }.getOrNull() ?: JSONArray()
}
