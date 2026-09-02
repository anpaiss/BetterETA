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
        // Promotion criterion for the shadow physics fallback, fixed before any
        // data was looked at. Mirrored in the README.
        const val PROMOTE_MIN_RIDES   = 5
        const val PROMOTE_MIN_GAIN_PP = 2.0
        const val PROMOTE_MAX_LOSS_PP = 3
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
        tvAvg.setTextColor(if (avg >= 0) Color.parseColor("#4CAF50") else Color.RED)

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
