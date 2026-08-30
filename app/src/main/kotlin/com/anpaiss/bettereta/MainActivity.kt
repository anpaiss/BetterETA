package com.anpaiss.bettereta

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Schermata informativa dell'estensione.
 * Mostra anche il modello velocità appreso (sezione INFO), letto dallo
 * snapshot salvato nelle SharedPreferences a ogni pausa/stop dell'uscita
 * — quindi "ultimo noto", non in tempo reale.
 */
class MainActivity : AppCompatActivity() {

    private val bucketLabels = listOf("Descent", "Flat", "Climb", "Steep", "Wall")

    private companion object {
        const val MAX_SANE_ERR_PCT = 300
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    // onResume così i dati si aggiornano riaprendo la schermata dopo un'uscita.
    override fun onResume() {
        super.onResume()
        renderModel()
        renderResults()
    }

    /** Storico confronto ETA vs Karoo: media in testa, poi le ultime uscite. */
    private fun renderResults() {
        val tvAvg  = findViewById<TextView>(R.id.tv_results_avg)
        val tvList = findViewById<TextView>(R.id.tv_results_list)

        val prefs   = getSharedPreferences(BetterEtaExtension.RESULTS_PREFS, Context.MODE_PRIVATE)
        val history = prefs.getString(BetterEtaExtension.RESULTS_KEY, "").orEmpty()
        val rawEntries = history.split(';').filter { it.isNotBlank() }

        // Bonifica: un errore medio oltre MAX_SANE_ERR_PCT è fisicamente impossibile
        // per un'ETA funzionante — scarta le entry corrotte (es. quelle del bug ms/s
        // del 29/08) e rende la pulizia permanente.
        val sane = rawEntries.filter { raw ->
            val p  = raw.split('|')
            val be = p.getOrNull(2)?.toIntOrNull()
            val k  = p.getOrNull(3)?.toIntOrNull()
            be != null && k != null && be <= MAX_SANE_ERR_PCT && k <= MAX_SANE_ERR_PCT
        }
        if (sane.size != rawEntries.size) {
            prefs.edit().putString(BetterEtaExtension.RESULTS_KEY, sane.joinToString(";")).apply()
        }

        // Entry: "data|diffPct|errBePct|errKarooPct" (diffPct > 0 = BetterEta meglio)
        val entries = sane.mapNotNull { raw ->
            val p = raw.split('|')
            val diff = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            Pair(p[0], diff)
        }

        if (entries.isEmpty()) {
            tvAvg.text  = "No data yet"
            tvAvg.setTextColor(Color.parseColor("#666666"))
            tvList.text = ""
            return
        }

        val avg = entries.sumOf { it.second }.toDouble() / entries.size
        tvAvg.text = "Avg: %.1f%% %s than Karoo ETA".format(
            kotlin.math.abs(avg), if (avg >= 0) "better" else "worse")
        tvAvg.setTextColor(if (avg >= 0) Color.parseColor("#4CAF50") else Color.RED)

        tvList.text = entries.joinToString("\n") { (date, diff) ->
            "%s  %s%d%%".format(date, if (diff >= 0) "+" else "", diff)
        }
    }

    private fun renderModel() {
        // Stessi nomi usati da BetterEtaExtension.saveModel()
        val prefs = getSharedPreferences("better_eta_model", Context.MODE_PRIVATE)
        val ids = listOf(R.id.tv_b0, R.id.tv_b1, R.id.tv_b2, R.id.tv_b3, R.id.tv_b4)

        bucketLabels.forEachIndexed { i, label ->
            val ema   = prefs.getFloat("b${i}_ema", 0f)
            val count = prefs.getInt("b${i}_cnt", 0)
            val tv    = findViewById<TextView>(ids[i])
            tv.text = "$label: %.1f km/h (%d)".format(ema * 3.6f, count)
            tv.setTextColor(if (count > 0) Color.parseColor("#FF5823") else Color.parseColor("#888888"))
        }

        val overallSamples = prefs.getInt("overall_samples", 0)
        val overallEma     = prefs.getFloat("overall_ema", 0f)
        findViewById<TextView>(R.id.tv_model_overall).text = if (overallSamples > 0)
            "Overall: %.1f km/h (%d samples)".format(overallEma * 3.6f, overallSamples)
            else "No data yet"
    }
}
