package com.anpaiss.bettereta

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Schermata informativa dell'estensione.
 * Mostra anche il modello velocità appreso (sezione INFO), letto dallo
 * snapshot salvato nelle SharedPreferences a ogni pausa/stop dell'uscita
 * — quindi "ultimo noto", non in tempo reale.
 */
class MainActivity : AppCompatActivity() {

    private val bucketLabels = listOf("Descent", "Gentle descent", "Flat", "Climb", "Steep", "Wall")

    private lateinit var prefs: Prefs
    private val styleValues = EtaStyle.values()
    private val styleLabels = styleValues.map { it.label }

    private companion object {
        // Promotion criterion for the shadow physics fallback, fixed before any
        // data was looked at. Mirrored in the README.
        const val PROMOTE_MIN_RIDES   = 5
        const val PROMOTE_MIN_GAIN_PP = 2.0
        const val PROMOTE_MAX_LOSS_PP = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bindStyleSpinner()
        findViewById<Button>(R.id.btn_grant_storage).setOnClickListener {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        findViewById<Button>(R.id.btn_grant_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // Stesso pattern del selettore stile di AxsRatio: i campi si ridisegnano
    // da soli via Prefs.styleFlow(), qui basta salvare la scelta.
    private fun bindStyleSpinner() {
        val sp = findViewById<Spinner>(R.id.sp_style)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styleLabels)
        sp.setSelection(prefs.tileStyle.ordinal, false)
        renderStylePreview(prefs.tileStyle)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newStyle = styleValues[position]
                if (prefs.tileStyle != newStyle) prefs.tileStyle = newStyle
                renderStylePreview(newStyle)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Anteprima dello stile: un campo singolo (senza stato, come ETA/REM) e il
     * delta "pace vs plan" nei quattro stati, con gli stessi colori dei campi
     * reali (EtaStyle.colorsFor).
     */
    private fun renderStylePreview(style: EtaStyle) {
        val container = findViewById<LinearLayout>(R.id.style_preview)
        container.removeAllViews()
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        fun tile(c: FieldColors, caption: String, big: String, bigColor: Int) =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                if (c.bgRes != null) setBackgroundResource(c.bgRes) else setBackgroundColor(c.bgColor ?: 0)
                addView(TextView(this@MainActivity).apply {
                    text = caption; textSize = 8f; setTextColor(c.label); maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = big; textSize = 13f; setTextColor(bigColor); maxLines = 1
                    setTypeface(typeface, Typeface.BOLD)
                })
            }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            .apply { setMargins(dp(2), 0, dp(2), 0) }

        val single = style.colorsFor(null)
        row.addView(tile(single, "ETA", "17:42", single.value), lp)
        val paces = listOf(
            Triple(Pace.EARLY, "EARLY", "-2m"),
            Triple(Pace.ON_PACE, "ON PACE", "+0m"),
            Triple(Pace.LATE, "LATE", "+3m"),
            Triple(Pace.UNKNOWN, "NO DATA", "--"),
        )
        for ((pace, caption, txt) in paces) {
            val c = style.colorsFor(pace)
            row.addView(tile(c, caption, txt, c.delta), lp)
        }
        container.addView(row)
    }

    // onResume così i dati si aggiornano riaprendo la schermata dopo un'uscita.
    override fun onResume() {
        super.onResume()
        renderModel()
        renderResults()
        findViewById<TextView>(R.id.tv_overlay_status).text =
            "Overlay permission: " + if (Settings.canDrawOverlays(this)) "granted" else "NOT granted"
        renderStorageStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderStorageStatus()
    }

    /**
     * Stato delle mappe offline con una lettura di prova: apre ogni file .map e
     * legge un tile al centro della sua area. Dice subito se permesso, file e
     * libreria funzionano, senza aspettare una route (log SURF nel file).
     */
    private fun renderStorageStatus() {
        val tv = findViewById<TextView>(R.id.tv_storage_status)
        val granted = checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) { tv.text = "Offline maps: storage permission NOT granted (tap the button)"; return }
        val dir  = java.io.File(android.os.Environment.getExternalStorageDirectory(), "offline/maps")
        val maps = dir.listFiles { f -> f.name.endsWith(".map") }?.sortedBy { it.name }.orEmpty()
        if (maps.isEmpty()) { tv.text = "Offline maps: none found in ${dir.absolutePath}"; return }
        tv.text = "Offline maps: ${maps.joinToString { it.name }} — probing…"
        Thread {
            val logger = BetterEtaLogger.shared ?: BetterEtaLogger(this, announce = false)
            val lines = maps.map { f ->
                runCatching {
                    val reader = org.mapsforge.map.reader.MapFile(f)
                    try {
                        val bb = reader.boundingBox()
                        // Punto di prova: l'ultimo fix GPS se dentro la mappa, altrimenti il
                        // centro dell'area (che per l'Italia cade in mare: zero way è normale)
                        val fixLat = prefs.lastFixLat; val fixLon = prefs.lastFixLon
                        val useFix = fixLat != 0.0 && bb.contains(fixLat, fixLon)
                        val cLat = if (useFix) fixLat else (bb.minLatitude + bb.maxLatitude) / 2
                        val cLon = if (useFix) fixLon else (bb.minLongitude + bb.maxLongitude) / 2
                        val src = MapsforgeSurfaceSource(this, logger, dir)
                        val fetch = src.fetchWays(listOf(listOf(cLat to cLon)))
                        val msg = "${f.name}: %.1f MB, ${fetch?.ways?.size ?: 0} highway ways [${fetch?.classCounts() ?: "-"}] in the tile at %.4f,%.4f (%s, ${fetch?.millis ?: 0} ms)"
                            .format(f.length() / 1e6, cLat, cLon, if (useFix) "last GPS fix" else "map center")
                        logger.log("SURF ", "Prova mappe offline — $msg")
                        msg
                    } finally { reader.close() }
                }.getOrElse { e ->
                    logger.logError("SURF ", "Prova mappe offline fallita su ${f.name}", e)
                    "${f.name}: ERROR ${e::class.simpleName}: ${e.message}"
                }
            }
            runOnUiThread { tv.text = "Offline maps:" + (10).toChar() + lines.joinToString((10).toChar().toString()) }
        }.start()
    }

    /** Storico confronto ETA vs Karoo: media in testa, poi le ultime uscite. */
    private fun renderResults() {
        val tvAvg  = findViewById<TextView>(R.id.tv_results_avg)
        val tvPhys = findViewById<TextView>(R.id.tv_results_physics)
        val tvList = findViewById<TextView>(R.id.tv_results_list)

        val prefs   = getSharedPreferences(BetterEtaExtension.RESULTS_PREFS, Context.MODE_PRIVATE)
        val raw     = prefs.getString(BetterEtaExtension.RESULTS_KEY, "").orEmpty()
        val entries = EtaResult.parseHistory(raw)

        // Parsing drops corrupt entries (see EtaResult.MAX_SANE_ERR_PCT); make
        // the cleanup permanent so they never come back.
        val cleaned = EtaResult.encodeHistory(entries)
        if (cleaned != raw) {
            prefs.edit().putString(BetterEtaExtension.RESULTS_KEY, cleaned).apply()
        }

        if (entries.isEmpty()) {
            tvAvg.text  = "No data yet"
            tvAvg.setTextColor(Color.parseColor("#666666"))
            tvPhys.text = ""
            tvList.text = ""
            return
        }

        val avg = entries.sumOf { it.diffPct }.toDouble() / entries.size
        tvAvg.text = "Avg: %.1f%% %s than Karoo ETA".format(
            kotlin.math.abs(avg), if (avg >= 0) "better" else "worse")
        // Rosso della palette condivisa (AxsRatio zoneAccent) al posto del RED puro
        tvAvg.setTextColor(if (avg >= 0) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))

        // Shadow physics fallback, judged on the same rides. Shown with the
        // promotion criterion applied (>= PROMOTE_MIN_RIDES rides, mean gain
        // >= PROMOTE_MIN_GAIN_PP, never worse than PROMOTE_MAX_LOSS_PP on one).
        val gains = entries.mapNotNull { it.physicsGainPct }
        if (gains.isEmpty()) {
            tvPhys.text = "Physics fallback (shadow): no data yet"
            tvPhys.setTextColor(Color.parseColor("#666666"))
        } else {
            val mean  = gains.average()
            val worst = gains.min()
            val ready = gains.size >= PROMOTE_MIN_RIDES && mean >= PROMOTE_MIN_GAIN_PP && worst >= -PROMOTE_MAX_LOSS_PP
            tvPhys.text = "Physics fallback (shadow): %+.1f pp vs factors, worst %+d, %d ride%s%s".format(
                mean, worst, gains.size, if (gains.size == 1) "" else "s",
                if (ready) " — ready to promote" else "")
            tvPhys.setTextColor(Color.parseColor(if (ready) "#4CAF50" else "#888888"))
        }

        tvList.text = entries.joinToString("\n") { r ->
            val phys = r.physicsGainPct?.let { "  phys %+d".format(it) } ?: ""
            "%s  %s%d%%%s".format(r.date, if (r.diffPct >= 0) "+" else "", r.diffPct, phys)
        }
    }

    private fun renderModel() {
        // Stessi nomi usati da BetterEtaExtension.saveModel()
        val prefs = getSharedPreferences("better_eta_model", Context.MODE_PRIVATE)
        val ids = listOf(R.id.tv_b0, R.id.tv_b1, R.id.tv_b2, R.id.tv_b3, R.id.tv_b4, R.id.tv_b5)

        // Prefs ancora a cinque bucket (prima del primo salvataggio dopo l'update):
        // stessa migrazione del modello, così le etichette restano giuste
        val saved  = prefs.getInt("bucket_layout", 5)
        val emas   = AdaptiveSpeedModel.migrateLegacyFive((0 until saved).map { prefs.getFloat("b${it}_ema", 0f) })
        val counts = AdaptiveSpeedModel.migrateLegacyFive((0 until saved).map { prefs.getInt("b${it}_cnt", 0) })
        bucketLabels.forEachIndexed { i, label ->
            val ema   = emas.getOrElse(i) { 0f }
            val count = counts.getOrElse(i) { 0 }
            val tv    = findViewById<TextView>(ids[i])
            tv.text = "$label: %.1f km/h (%d)".format(ema * 3.6f, count)
            tv.setTextColor(if (count > 0) Color.parseColor("#FF5823") else Color.parseColor("#888888"))
        }

        val overallSamples = prefs.getInt("overall_samples", 0)
        val overallEma     = prefs.getFloat("overall_ema", 0f)
        findViewById<TextView>(R.id.tv_model_overall).text = if (overallSamples > 0)
            "Overall: %.1f km/h (%d samples)".format(overallEma * 3.6f, overallSamples)
            else "No data yet"

        // Physics model (shadow): learned effective power in the assisted and
        // unassisted regimes. Same keys as BetterEtaExtension.saveModel().
        val pLow  = prefs.getFloat("phys_low_ema", 0f)
        val nLow  = prefs.getInt("phys_low_cnt", 0)
        val pHigh = prefs.getFloat("phys_high_ema", 0f)
        val nHigh = prefs.getInt("phys_high_cnt", 0)
        val mass  = prefs.getFloat("phys_mass", 0f)
        findViewById<TextView>(R.id.tv_model_physics).text = if (nLow + nHigh > 0)
            "Physics (shadow): %.0f W <24 km/h (%d) · %.0f W ≥24 km/h (%d) · %.0f kg"
                .format(pLow, nLow, pHigh, nHigh, mass)
            else "Physics (shadow): no data yet"
    }
}
