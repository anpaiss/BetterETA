package com.anpaiss.bettereta

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Backup and restore of what BetterETA has learned, for RideStats, which keeps
 * the copy in the rider's Google Drive so it survives a reset of the Karoo.
 * Only our own extensions get in: the provider asks for a signature permission.
 *
 *  call("export") → Bundle with "data": the model, the way memory, the comparison
 *                   history, the list of imported FITs and the "don't count it" photo
 *  call("import", extras "data") → Bundle with "result" (a line for the screen) and
 *                   "ok"; refused while a ride is being recorded or the history import runs
 */
class BackupProvider : ContentProvider() {

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context!!
        return when (method) {
            METHOD_EXPORT -> Bundle().apply { putByteArray(KEY_DATA, export(ctx)) }
            METHOD_IMPORT -> {
                val data = extras?.getByteArray(KEY_DATA)
                val ext = BetterEtaExtension.instance
                val busy = ext?.restoreRefusal()
                val result = when {
                    data == null -> "No backup data"
                    busy != null -> busy
                    else -> runCatching { restore(ctx, data); ext?.reloadAfterRestore(); "BetterETA restored" }
                        .getOrElse { "Restore failed: ${it.message}" }
                }
                Bundle().apply { putString(KEY_RESULT, result); putBoolean(KEY_OK, result == "BetterETA restored") }
            }
            else -> Bundle()
        }
    }

    companion object {
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val KEY_DATA = "data"
        const val KEY_RESULT = "result"
        const val KEY_OK = "ok"

        private const val FORMAT = 1
        private val PREFS = listOf("better_eta_prefs", "better_eta_model", "better_eta_results")
        /** Text files under filesDir; last_ride is the "don't count it" photo. */
        private val FILES = listOf("way_speeds.txt", "imported_fits.txt", "last_ride")

        fun export(ctx: Context): ByteArray {
            val prefs = JSONObject()
            for (name in PREFS) {
                val o = JSONObject()
                for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                    val t = when (v) {
                        is Int -> "i"; is Long -> "l"; is Float -> "f"; is Boolean -> "b"; is String -> "s"
                        is Set<*> -> "S"
                        else -> continue
                    }
                    o.put(k, JSONArray().put(t).put(if (v is Set<*>) JSONArray(v.toList()) else v))
                }
                prefs.put(name, o)
            }
            val files = JSONObject()
            for (rel in FILES) {
                val f = File(ctx.filesDir, rel)
                val all = if (f.isDirectory) f.listFiles().orEmpty().filter { it.isFile } else listOf(f).filter { it.isFile }
                for (x in all) files.put(x.relativeTo(ctx.filesDir).invariantSeparatorsPath, x.readText())
            }
            // no timestamp: same data, same bytes, and RideStats has nothing to send
            val json = JSONObject().put("format", FORMAT).put("app", "bettereta")
                .put("prefs", prefs).put("files", files)
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(json.toString().toByteArray()) }
            return out.toByteArray()
        }

        /** Puts everything back as it was in the backup; files the backup does not have are removed. */
        fun restore(ctx: Context, data: ByteArray) {
            val json = JSONObject(GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes().decodeToString() })
            require(json.optString("app") == "bettereta") { "not a BetterETA backup" }
            require(json.optInt("format") <= FORMAT) { "backup from a newer BetterETA, update it first" }
            val prefs = json.getJSONObject("prefs")
            val files = json.getJSONObject("files")
            for (rel in files.keys()) {
                require(FILES.any { rel == it || rel.startsWith("$it/") } && ".." !in rel) { "unexpected file $rel" }
            }
            for (name in PREFS) {
                val o = prefs.optJSONObject(name) ?: JSONObject()
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().apply {
                    clear()
                    for (k in o.keys()) {
                        val a = o.getJSONArray(k)
                        when (a.getString(0)) {
                            "i" -> putInt(k, a.getInt(1))
                            "l" -> putLong(k, a.getLong(1))
                            "f" -> putFloat(k, a.getDouble(1).toFloat())
                            "b" -> putBoolean(k, a.getBoolean(1))
                            "s" -> putString(k, a.getString(1))
                            "S" -> a.getJSONArray(1).let { s -> putStringSet(k, (0 until s.length()).map(s::getString).toSet()) }
                        }
                    }
                }.commit()
            }
            for (rel in FILES) File(ctx.filesDir, rel).deleteRecursively()
            for (rel in files.keys()) {
                File(ctx.filesDir, rel).apply { parentFile?.mkdirs() }.writeText(files.getString(rel))
            }
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
