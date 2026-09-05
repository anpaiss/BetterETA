package com.anpaiss.bettereta

/**
 * Modello adattivo velocità/pendenza calibrato sull'uscita in corso.
 *
 * Usa 6 bucket di pendenza:
 *   0: < -6%          discesa libera
 *   1: -6% .. -2%     discesa dolce (pedalata + gravità: la fascia più veloce)
 *   2: -2% .. +2%     pianura vera
 *   3: +2% .. +6%     salita media
 *   4: +6% .. +10%    salita ripida
 *   5: > +10%         muro
 *
 * Fino al 05/09 erano cinque, con "pianura" da -5% a +2%: nel giro di quel
 * giorno la fascia -6..-2% andava a 15,5 km/h e la 0..+2% a 9,9, e la media
 * del bucket unico non descriveva nessuna delle due. Lo snapshot a cinque
 * bucket viene migrato in [restore] sdoppiando il vecchio bucket 1.
 *
 * Due scale di tempo:
 *  - la BASE: per ogni bucket una EMA lunga (ore di pedalata) della velocità
 *    osservata — "come pedalo di solito". Un giro lento la sposta di poco.
 *  - il FATTORE DI GIORNATA: rapporto osservato/base appreso nel giro in
 *    corso, un numero globale più uno per bucket. La stima è base × fattore.
 *    Il globale impara da ogni campione a qualsiasi pendenza, così dieci
 *    minuti di salita lenta abbassano anche piano e discesa che devono
 *    ancora arrivare; quello per bucket prende il sopravvento man mano che
 *    il bucket vede campioni suoi (le proporzioni non sono uniformi: il 05/09
 *    le discese andavano a 0,55 della base, le salite a 0,8).
 *  Fino al 05/09 la base era l'unica memoria, con finestra di 20 minuti:
 *  l'ultimo giro, chiunque fosse, dettava la partenza del successivo.
 *  Taratura da replay dei CSV (tools/replay_model.py): errore ETA sul giro
 *  05/09 20% col solo bucket a 20 min, 16% col fattore globale, 9-11% con
 *  fattore per bucket; finestre 600-1200 s equivalenti, scelti 900/120/120.
 *  Quando un bucket non ha abbastanza campioni, usa la velocità piana
 *  moltiplicata per un fattore di pendenza di default.
 *
 * Input/output velocità in m/s.
 */
class AdaptiveSpeedModel {

    companion object {
        // Finestra equivalente dell'EMA in campioni (~20 min a 1 Hz). Sotto questa
        // soglia si usa la media cumulativa. Con l'α fisso precedente (0.15) la
        // memoria effettiva era ~13 s e il "modello appreso" era in pratica la
        // velocità degli ultimi istanti → ETA instabile.
        private const val EMA_WINDOW         = 21600  // base: ~6 h di pedalata per bucket
        // Fattore di giornata: finestra EMA, prior (campioni fittizi a 1.0 alla
        // partenza) e shrink (campioni di bucket perché il suo fattore pesi metà)
        private const val FACTOR_WINDOW      = 900
        private const val FACTOR_PRIOR       = 120
        private const val FACTOR_SHRINK      = 120.0
        private const val RATIO_MIN          = 0.2
        private const val RATIO_MAX          = 3.0
        private const val ALPHA_GRADE        = 0.10   // Peso per lo smoothing della pendenza
        private const val MIN_TRUSTED        = 5      // campioni minimi per fidarsi del bucket
        private const val DEFAULT_FLAT_MS    = 5.0    // ~18 km/h, fallback iniziale

        const val BUCKETS     = 6
        const val FLAT_BUCKET = 2
        private const val LEGACY_BUCKETS = 5

        // Soglie di pendenza (%) che delimitano i bucket 0..5
        private val THRESHOLDS = doubleArrayOf(-6.0, -2.0, 2.0, 6.0, 10.0)

        /**
         * Bucket a gradient. Public because rung 3 of the cascade lives in a
         * [FallbackSpeedProvider] now and some of them are bucket-based.
         */
        fun bucketIndex(gradePct: Double): Int {
            for (i in THRESHOLDS.indices) if (gradePct < THRESHOLDS[i]) return i
            return THRESHOLDS.size
        }

        /**
         * Snapshot a cinque bucket (fino al 05/09) → sei: il vecchio bucket 1
         * (-5..+2%) diventa sia "discesa dolce" sia "pianura", che poi si
         * riallineano da sole in una finestra EMA. Liste vuote o già a sei
         * passano invariate.
         */
        fun <T> migrateLegacyFive(l: List<T>): List<T> =
            if (l.size == LEGACY_BUCKETS) listOf(l[0], l[1], l[1], l[2], l[3], l[4]) else l
    }

    private data class Bucket(
        val ema: Double = 0.0,
        val count: Int  = 0,
    )

    private val buckets = Array(BUCKETS) { Bucket() }
    // Bucket per-fondo: imparano solo quando il fondo corrente è noto.
    // I bucket globali restano la base (fondo ignoto, pochi campioni).
    private val pavedBuckets   = Array(BUCKETS) { Bucket() }
    private val unpavedBuckets = Array(BUCKETS) { Bucket() }
    private var overallEma     = 0.0
    private var overallSamples = 0
    private var smoothedGrade  = 0.0

    // Fattore di giornata: globale + per bucket, con i rispettivi conteggi
    private var dayFactor      = 1.0
    private var dayCount       = 0
    private val bucketFactor   = DoubleArray(BUCKETS) { 1.0 }
    private val bucketFactorN  = IntArray(BUCKETS)

    /**
     * Aggiorna il modello con un nuovo campione di (velocità, pendenza, fondo).
     * Chiamare ogni secondo durante la registrazione.
     */
    fun update(speedMs: Double, gradePct: Double, surface: Surface = Surface.UNKNOWN) {
        // Ignora campioni a velocità troppo bassa (fermi a un semaforo, ecc.):
        // sia per il bucket che per lo smoothing della pendenza, così evitiamo
        // di "trascinare" smoothedGrade con campioni non legati al movimento.
        if (speedMs < 0.5) return

        // Smoothing della pendenza per evitare spike istantanei
        smoothedGrade = if (overallSamples == 0) gradePct
                        else ALPHA_GRADE * gradePct + (1.0 - ALPHA_GRADE) * smoothedGrade

        val idx = bucketIndex(smoothedGrade)

        // Fattore di giornata: rapporto contro la base PRIMA di aggiornarla
        val base = rawPredict(idx, surface, GradientFactorFallback).speedMs
        if (base > 0.0) {
            val r  = (speedMs / base).coerceIn(RATIO_MIN, RATIO_MAX)
            val ag = 1.0 / minOf(dayCount + FACTOR_PRIOR + 1, FACTOR_WINDOW)
            dayFactor = ag * r + (1.0 - ag) * dayFactor
            dayCount++
            val ab = 1.0 / minOf(bucketFactorN[idx] + FACTOR_PRIOR + 1, FACTOR_WINDOW)
            bucketFactor[idx] = ab * r + (1.0 - ab) * bucketFactor[idx]
            bucketFactorN[idx]++
        }

        val b   = buckets[idx]
        buckets[idx] = Bucket(
            ema   = emaStep(b.ema, speedMs, b.count),
            count = b.count + 1,
        )
        surfaceBuckets(surface)?.let { sb ->
            val s = sb[idx]
            sb[idx] = Bucket(
                ema   = emaStep(s.ema, speedMs, s.count),
                count = s.count + 1,
            )
        }

        overallEma = emaStep(overallEma, speedMs, overallSamples)
        overallSamples++
    }

    /**
     * Restituisce la velocità prevista (m/s) per una data pendenza e fondo.
     *
     * Priorità:
     *   1. EMA del bucket (pendenza, fondo) se il fondo è noto e ha ≥ MIN_TRUSTED campioni
     *   2. EMA del bucket globale di pendenza (se ≥ MIN_TRUSTED campioni)
     *   3. [fallback], per le pendenze su cui non abbiamo ancora campioni
     */
    fun predictSpeed(
        gradePct: Double,
        surface: Surface = Surface.UNKNOWN,
        fallback: FallbackSpeedProvider = GradientFactorFallback,
    ): Double = predict(gradePct, surface, fallback).speedMs

    /**
     * As [predictSpeed], but also reports which rung of the cascade answered.
     *
     * Knowing how much of a route is served by the fallback is what says whether
     * changing the fallback can matter at all: if the learned buckets cover
     * almost everything, even a much better rung 3 moves the ETA very little.
     */
    fun predict(
        gradePct: Double,
        surface: Surface = Surface.UNKNOWN,
        fallback: FallbackSpeedProvider = GradientFactorFallback,
    ): Prediction {
        val idx = bucketIndex(gradePct)
        val raw = rawPredict(idx, surface, fallback, gradePct)
        return Prediction((raw.speedMs * effectiveFactor(idx)).coerceAtLeast(0.5), raw.fromFallback)
    }

    /** Base senza fattore di giornata: bucket (pendenza, fondo), bucket globale, o fallback. */
    private fun rawPredict(
        idx: Int,
        surface: Surface,
        fallback: FallbackSpeedProvider,
        gradePct: Double = THRESHOLDS.getOrElse(idx) { THRESHOLDS.last() + 1.0 } - 0.5,
    ): Prediction {
        surfaceBuckets(surface)?.let { sb ->
            if (sb[idx].count >= MIN_TRUSTED) return Prediction(sb[idx].ema, false)
        }
        val b = buckets[idx]
        if (b.count >= MIN_TRUSTED) return Prediction(b.ema, false)
        return Prediction(
            fallback.fallbackSpeedMs(gradePct, surface, flatSpeed()).coerceAtLeast(0.5),
            true,
        )
    }

    /** Fattore per il bucket: il suo, pesato dai campioni visti, altrimenti quello globale. */
    private fun effectiveFactor(idx: Int): Double {
        val n = bucketFactorN[idx].toDouble()
        val w = n / (n + FACTOR_SHRINK)
        return w * bucketFactor[idx] + (1.0 - w) * dayFactor
    }

    /** Fattore di giornata globale (1.0 = come la base). */
    val currentDayFactor: Double get() = dayFactor

    /** Fattore di giornata efficace per una pendenza: riscala velocità di base esterne (memoria per tratto). */
    fun dayFactorFor(gradePct: Double): Double = effectiveFactor(bucketIndex(gradePct))

    /** Fattori efficaci per bucket, per log e diagnostica. */
    fun effectiveFactors(): List<Double> = (0 until BUCKETS).map { effectiveFactor(it) }

    /**
     * Nuovo giro: il fattore di giornata riparte da 1 (la base resta).
     * Da chiamare al passaggio Idle → Recording, non alle pause.
     */
    fun newRide() {
        dayFactor = 1.0
        dayCount  = 0
        bucketFactor.fill(1.0)
        bucketFactorN.fill(0)
    }

    /** @param fromFallback true when rung 3 answered, i.e. no learned data applied. */
    data class Prediction(val speedMs: Double, val fromFallback: Boolean)

    private fun surfaceBuckets(surface: Surface): Array<Bucket>? = when (surface) {
        Surface.PAVED   -> pavedBuckets
        Surface.UNPAVED -> unpavedBuckets
        Surface.UNKNOWN -> null
    }

    /** Stato serializzabile del modello — usato per la persistenza. */
    data class Snapshot(
        val overallEma: Double,
        val overallSamples: Int,
        val bucketEmas: List<Double>,
        val bucketCounts: List<Int>,
        val pavedEmas: List<Double>    = emptyList(),
        val pavedCounts: List<Int>     = emptyList(),
        val unpavedEmas: List<Double>  = emptyList(),
        val unpavedCounts: List<Int>   = emptyList(),
        // Fattore di giornata (persistito perché l'estensione può ripartire a metà giro)
        val dayFactor: Double          = 1.0,
        val dayCount: Int              = 0,
        val bucketFactors: List<Double> = emptyList(),
        val bucketFactorCounts: List<Int> = emptyList(),
    )

    /** Restituisce uno snapshot dell'attuale stato addestrato. */
    fun snapshot() = Snapshot(
        overallEma     = overallEma,
        overallSamples = overallSamples,
        bucketEmas     = buckets.map { it.ema },
        bucketCounts   = buckets.map { it.count },
        pavedEmas      = pavedBuckets.map { it.ema },
        pavedCounts    = pavedBuckets.map { it.count },
        unpavedEmas    = unpavedBuckets.map { it.ema },
        unpavedCounts  = unpavedBuckets.map { it.count },
        dayFactor      = dayFactor,
        dayCount       = dayCount,
        bucketFactors  = bucketFactor.toList(),
        bucketFactorCounts = bucketFactorN.toList(),
    )

    /** Ripristina lo stato dal snapshot. Chiama reset() prima se necessario. */
    fun restore(snap: Snapshot) {
        overallEma     = snap.overallEma
        overallSamples = snap.overallSamples
        smoothedGrade  = 0.0
        val bEma = migrateLegacyFive(snap.bucketEmas);  val bCnt = migrateLegacyFive(snap.bucketCounts)
        val pEma = migrateLegacyFive(snap.pavedEmas);   val pCnt = migrateLegacyFive(snap.pavedCounts)
        val uEma = migrateLegacyFive(snap.unpavedEmas); val uCnt = migrateLegacyFive(snap.unpavedCounts)
        for (i in buckets.indices) {
            buckets[i]        = Bucket(bEma.getOrElse(i) { 0.0 }, bCnt.getOrElse(i) { 0 })
            pavedBuckets[i]   = Bucket(pEma.getOrElse(i) { 0.0 }, pCnt.getOrElse(i) { 0 })
            unpavedBuckets[i] = Bucket(uEma.getOrElse(i) { 0.0 }, uCnt.getOrElse(i) { 0 })
            bucketFactor[i]   = snap.bucketFactors.getOrElse(i) { 1.0 }
            bucketFactorN[i]  = snap.bucketFactorCounts.getOrElse(i) { 0 }
        }
        dayFactor = snap.dayFactor
        dayCount  = snap.dayCount
    }

    /** Azzera tutto — chiamato all'inizio di ogni nuova uscita. */
    fun reset() {
        for (i in buckets.indices) {
            buckets[i]        = Bucket()
            pavedBuckets[i]   = Bucket()
            unpavedBuckets[i] = Bucket()
        }
        overallEma     = 0.0
        overallSamples = 0
        smoothedGrade  = 0.0
        newRide()
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
        val flat = buckets[FLAT_BUCKET]
        return when {
            flat.count >= MIN_TRUSTED -> flat.ema
            overallSamples > 0        -> overallEma
            else                      -> DEFAULT_FLAT_MS
        }
    }
}
