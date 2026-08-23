package com.anpaiss.bettereta

/**
 * Modello adattivo velocità/pendenza calibrato sull'uscita in corso.
 *
 * Usa 5 bucket di pendenza ottimizzati per ebike Bosch:
 *   0: < -5%          discesa libera
 *   1: -5% .. +2%     pianura / falsopiano
 *   2: +2% .. +6%     salita media
 *   3: +6% .. +10%    salita ripida
 *   4: > +10%         muro
 *
 * Per ogni bucket mantiene una EMA (Exponential Moving Average) della
 * velocità osservata. Quando un bucket non ha abbastanza campioni,
 * interpola dai bucket vicini oppure usa la velocità complessiva
 * moltiplicata per un fattore di pendenza di default.
 *
 * Input/output velocità in m/s.
 */
class AdaptiveSpeedModel {

    companion object {
        // Finestra equivalente dell'EMA in campioni (~20 min a 1 Hz). Sotto questa
        // soglia si usa la media cumulativa. Con l'α fisso precedente (0.15) la
        // memoria effettiva era ~13 s e il "modello appreso" era in pratica la
        // velocità degli ultimi istanti → ETA instabile.
        private const val EMA_WINDOW         = 1200
        private const val ALPHA_GRADE        = 0.10   // Peso per lo smoothing della pendenza
        private const val MIN_TRUSTED        = 5      // campioni minimi per fidarsi del bucket
        private const val DEFAULT_FLAT_MS    = 5.0    // ~18 km/h, fallback iniziale

        // Soglie di pendenza (%) che delimitano i bucket 0..4
        private val THRESHOLDS = doubleArrayOf(-5.0, 2.0, 6.0, 10.0)

        // Fattori di velocità rispetto al piano (bucket 1 = 1.0) per ebike
        private val FALLBACK_FACTORS = doubleArrayOf(1.15, 1.0, 0.80, 0.65, 0.50)
    }

    private data class Bucket(
        val ema: Double = 0.0,
        val count: Int  = 0,
    )

    private val buckets = Array(5) { Bucket() }
    private var overallEma     = 0.0
    private var overallSamples = 0
    private var smoothedGrade  = 0.0

    /**
     * Aggiorna il modello con un nuovo campione di (velocità, pendenza).
     * Chiamare ogni secondo durante la registrazione.
     */
    fun update(speedMs: Double, gradePct: Double) {
        // Ignora campioni a velocità troppo bassa (fermi a un semaforo, ecc.):
        // sia per il bucket che per lo smoothing della pendenza, così evitiamo
        // di "trascinare" smoothedGrade con campioni non legati al movimento.
        if (speedMs < 0.5) return

        // Smoothing della pendenza per evitare spike istantanei
        smoothedGrade = if (overallSamples == 0) gradePct
                        else ALPHA_GRADE * gradePct + (1.0 - ALPHA_GRADE) * smoothedGrade

        val idx = bucketIndex(smoothedGrade)
        val b   = buckets[idx]
        buckets[idx] = Bucket(
            ema   = emaStep(b.ema, speedMs, b.count),
            count = b.count + 1,
        )

        overallEma = emaStep(overallEma, speedMs, overallSamples)
        overallSamples++
    }

    /**
     * Restituisce la velocità prevista (m/s) per una data pendenza.
     *
     * Priorità:
     *   1. EMA del bucket specifico (se ≥ MIN_TRUSTED campioni)
     *   2. Velocità piana osservata × fattore di default del bucket
     *   3. EMA complessiva × fattore di default del bucket
     *   4. DEFAULT_FLAT_MS × fattore di default del bucket
     */
    fun predictSpeed(gradePct: Double): Double {
        val idx = bucketIndex(gradePct)
        val b   = buckets[idx]

        if (b.count >= MIN_TRUSTED) return b.ema

        val factor   = FALLBACK_FACTORS[idx]
        val flatBase = flatSpeed()
        return (flatBase * factor).coerceAtLeast(0.5)
    }

    /** Stato serializzabile del modello — usato per la persistenza. */
    data class Snapshot(
        val overallEma: Double,
        val overallSamples: Int,
        val bucketEmas: List<Double>,
        val bucketCounts: List<Int>,
    )

    /** Restituisce uno snapshot dell'attuale stato addestrato. */
    fun snapshot() = Snapshot(
        overallEma     = overallEma,
        overallSamples = overallSamples,
        bucketEmas     = buckets.map { it.ema },
        bucketCounts   = buckets.map { it.count },
    )

    /** Ripristina lo stato dal snapshot. Chiama reset() prima se necessario. */
    fun restore(snap: Snapshot) {
        overallEma     = snap.overallEma
        overallSamples = snap.overallSamples
        smoothedGrade  = 0.0
        for (i in buckets.indices) {
            buckets[i] = Bucket(
                ema   = snap.bucketEmas.getOrElse(i) { 0.0 },
                count = snap.bucketCounts.getOrElse(i) { 0 },
            )
        }
    }

    /** Azzera tutto — chiamato all'inizio di ogni nuova uscita. */
    fun reset() {
        for (i in buckets.indices) buckets[i] = Bucket()
        overallEma     = 0.0
        overallSamples = 0
        smoothedGrade  = 0.0
    }

    // ── private helpers ──────────────────────────────────────────────

    // Media cumulativa fino a EMA_WINDOW campioni, poi EMA con finestra
    // equivalente EMA_WINDOW: stabile ma ancora adattiva entro l'uscita.
    private fun emaStep(ema: Double, sample: Double, prevCount: Int): Double {
        val alpha = 1.0 / minOf(prevCount + 1, EMA_WINDOW)
        return alpha * sample + (1.0 - alpha) * ema
    }

    private fun flatSpeed(): Double {
        // Usa l'EMA del bucket pianura se disponibile, altrimenti overall, altrimenti default
        val flat = buckets[1]
        return when {
            flat.count >= MIN_TRUSTED -> flat.ema
            overallSamples > 0        -> overallEma
            else                      -> DEFAULT_FLAT_MS
        }
    }

    private fun bucketIndex(gradePct: Double): Int = when {
        gradePct < THRESHOLDS[0] -> 0
        gradePct < THRESHOLDS[1] -> 1
        gradePct < THRESHOLDS[2] -> 2
        gradePct < THRESHOLDS[3] -> 3
        else                     -> 4
    }
}
