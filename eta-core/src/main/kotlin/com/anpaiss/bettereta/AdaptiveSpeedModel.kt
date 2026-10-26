package com.anpaiss.bettereta

/**
 * Modello adattivo velocità/pendenza calibrato sull'uscita in corso.
 *
 * Usa 7 bucket di pendenza:
 *   0: < -6%          discesa libera
 *   1: -6% .. -2%     discesa dolce (pedalata + gravità: la fascia più veloce)
 *   2: -2% .. +2%     pianura vera
 *   3: +2% .. +6%     salita media
 *   4: +6% .. +10%    salita ripida
 *   5: +10% .. +16%   muro
 *   6: > +16%         muro estremo
 *
 * Il settimo è del 19/09: nel giro 19/09 (lastricato medievale, +400 m al
 * 18-21 %) il bucket unico "> 10 %" dava 9,0 km/h contro 7,7 reali. Sui sei
 * giri dal 05/09 c'è un gradino al 16 %: asfalto 9,4 → 8,0 km/h, non asfalto
 * 8,5 → 7,7; sotto, tra 10 e 16 %, le fasce stanno in ±0,5 km/h. In discesa
 * nessuno schema (dispersione ±4-10 km/h): lì non si è diviso. Sul replay
 * (tools/replay_factor.py) l'errore medio non cambia (6,0 %): il vantaggio
 * è locale, ~15 % sui tratti oltre il 16 % (~2 min sul giro 19/09).
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
 *  Dal 29/09 il fattore è diviso per fondo, asfalto / non asfalto: globale →
 *  classe di fondo → (classe, bucket), ogni livello pesato dai suoi campioni
 *  (n/(n+120)) verso il livello sopra. Nel giro 29/09 (Pasubio) la discesa
 *  sconnessa degli Scarubbi aveva portato il fattore a 0,76-0,83, applicato
 *  poi all'asfalto finale fatto a ritmo normale: ETA +13..+15 %, +5 % tenendo
 *  il fattore sul solo sterrato. Replay su 7 giri (tools/replay_factor.py
 *  --carry, variante H+B): 6,6 % contro 7,0. Fondo ignoto → come prima,
 *  globale + per bucket.
 *  Dal 10/10 il fattore non impara nel primo chilometro del giro: la partenza
 *  è paese, semafori, code (10/10: stanghe chiuse, f=0.89 sull'asfalto per
 *  tutto il giro; 03/10 lo stesso). Replay --carry su 10 giri: 6,5 % contro
 *  7,0 dell'H+B puro, meglio in 7 giri su 10 (10/10: 5,0 contro 7,9);
 *  500 m–1,5 km equivalenti, 2 km e oltre peggio perché buttano via anche la
 *  salita vera con cui alcuni giri iniziano. La base impara da subito.
 *  Quando un bucket non ha abbastanza campioni, usa la velocità piana
 *  moltiplicata per un fattore di pendenza di default.
 *
 * Input/output velocità in m/s.
 */
// Thread-safe (review 27/09): l'import dello storico gira su un altro thread del
// ciclo principale e può sovrapporsi all'avvio di un giro; metodi pubblici
// @Synchronized, nessuno chiama metodi sincronizzati di altri oggetti.
class AdaptiveSpeedModel {

    companion object {
        // Finestra equivalente dell'EMA in campioni (~20 min a 1 Hz). Sotto questa
        // soglia si usa la media cumulativa. Con l'α fisso precedente (0.15) la
        // memoria effettiva era ~13 s e il "modello appreso" era in pratica la
        // velocità degli ultimi istanti → ETA instabile.
        private const val EMA_WINDOW         = 21600  // base: ~6 h di pedalata per bucket
        // Fattore di giornata: finestra EMA, prior (campioni fittizi a 1.0 alla
        // partenza) e shrink (campioni di bucket perché il suo fattore pesi metà).
        // Il globale ha un prior più largo (17/09: 400, era 120): impara da ogni
        // pendenza e fondo, e nel giro 17/09 nove minuti di salita asfaltata a
        // 1,5× la base lo avevano portato a 1,35, applicato poi alla salita in
        // ghiaia (1,1×) → ETA ottimista del 10 %. Col replay (tools/replay_factor.py
        // --carry) l'estremo nei primi 15 min passa da −13 % a −5 % sul 17/09 e
        // da −6 % a −3 % sul 12/09, errore medio invariato o meglio di 0,3 pp.
        // Quelli per bucket restano a 120: pochi campioni, devono mordere presto.
        private const val FACTOR_WINDOW       = 900
        private const val FACTOR_PRIOR_GLOBAL = 400
        private const val FACTOR_PRIOR        = 120
        private const val FACTOR_SHRINK       = 120.0
        private const val FACTOR_WARMUP_M     = 1000.0  // metri di movimento prima che il fattore impari
        private const val RATIO_MIN          = 0.2
        private const val RATIO_MAX          = 3.0
        private const val ALPHA_GRADE        = 0.10   // Peso per lo smoothing della pendenza
        private const val MIN_TRUSTED        = 5      // campioni minimi per fidarsi del bucket
        private const val DEFAULT_FLAT_MS    = 5.0    // ~18 km/h, fallback iniziale

        const val BUCKETS     = 7
        const val FLAT_BUCKET = 2
        private const val LEGACY_FIVE = 5
        private const val LEGACY_SIX  = 6
        // Un bucket nato da una migrazione eredita la velocità del genitore ma
        // con pochi campioni: con i conteggi veri (decine di migliaia, finestra
        // EMA 6 h) non si staccherebbe mai dal valore copiato. 600 = 10 minuti:
        // dopo un giro con un muro vero il bucket è già suo.
        private const val SPLIT_SEED_COUNT = 600

        // Classi di fondo del fattore di giornata
        private const val CLASSES = 2
        private const val PAVED   = 0
        private const val UNPAVED = 1

        // Soglie di pendenza (%) che delimitano i bucket 0..6
        private val THRESHOLDS = doubleArrayOf(-6.0, -2.0, 2.0, 6.0, 10.0, 16.0)

        /**
         * Bucket a gradient. Public because rung 3 of the cascade lives in a
         * [FallbackSpeedProvider] now and some of them are bucket-based.
         */
        fun bucketIndex(gradePct: Double): Int {
            for (i in THRESHOLDS.indices) if (gradePct < THRESHOLDS[i]) return i
            return THRESHOLDS.size
        }

        /**
         * Snapshot dei layout precedenti → sette bucket. A cinque (fino al
         * 05/09): il vecchio bucket 1 (-5..+2%) diventa sia "discesa dolce" sia
         * "pianura". A sei (fino al giro del 19/09): il vecchio "> 10%" diventa sia
         * "10..16%" sia "> 16%". Liste vuote o già a sette passano invariate.
         */
        fun <T> migrateLegacy(l: List<T>): List<T> = when (l.size) {
            LEGACY_FIVE -> migrateLegacy(listOf(l[0], l[1], l[1], l[2], l[3], l[4]))
            LEGACY_SIX  -> l + l[LEGACY_SIX - 1]
            else        -> l
        }

        /** Come [migrateLegacy], ma il bucket nato dalla divisione del muro parte con pochi campioni. */
        fun migrateLegacyCounts(l: List<Int>): List<Int> {
            val m = migrateLegacy(l)
            if (m.size == l.size || m.size != BUCKETS) return m
            return m.toMutableList().also { it[BUCKETS - 1] = minOf(it[BUCKETS - 1], SPLIT_SEED_COUNT) }
        }
    }

    private data class Bucket(
        val ema: Double = 0.0,
        val count: Int  = 0,
    )

    private val buckets = Array(BUCKETS) { Bucket() }
    // Bucket per-fondo: imparano solo quando il fondo corrente è noto.
    // I bucket globali restano la base (fondo ignoto, pochi campioni).
    private val pavedBuckets   = Array(BUCKETS) { Bucket() }
    private val unpavedBuckets = Array(BUCKETS) { Bucket() }   // GRAVEL (erede del vecchio "unpaved")
    private val trailBuckets   = Array(BUCKETS) { Bucket() }
    private val gnarlyBuckets  = Array(BUCKETS) { Bucket() }
    private var overallEma     = 0.0
    private var overallSamples = 0
    private var smoothedGrade  = 0.0

    // Fattore di giornata: globale + per bucket, con i rispettivi conteggi
    private var dayFactor      = 1.0
    private var dayCount       = 0
    private var dayMovedM      = 0.0   // metri in movimento del giro (campioni a 1 Hz), per il warm-up
    private val bucketFactor   = DoubleArray(BUCKETS) { 1.0 }
    private val bucketFactorN  = IntArray(BUCKETS)
    // Per classe di fondo (asfalto / non asfalto) e per (classe, bucket)
    private val classFactor    = DoubleArray(CLASSES) { 1.0 }
    private val classFactorN   = IntArray(CLASSES)
    private val leafFactor     = Array(CLASSES) { DoubleArray(BUCKETS) { 1.0 } }
    private val leafFactorN    = Array(CLASSES) { IntArray(BUCKETS) }

    /**
     * Aggiorna il modello con un nuovo campione di (velocità, pendenza, fondo).
     * Chiamare ogni secondo durante la registrazione.
     */
    @Synchronized
    fun update(speedMs: Double, gradePct: Double, surface: Surface = Surface.UNKNOWN) {
        // Ignora campioni a velocità troppo bassa (fermi a un semaforo, ecc.):
        // sia per il bucket che per lo smoothing della pendenza, così evitiamo
        // di "trascinare" smoothedGrade con campioni non legati al movimento.
        if (speedMs < 0.5) return

        // Smoothing della pendenza per evitare spike istantanei
        smoothedGrade = if (overallSamples == 0) gradePct
                        else ALPHA_GRADE * gradePct + (1.0 - ALPHA_GRADE) * smoothedGrade

        val idx = bucketIndex(smoothedGrade)

        // Fattore di giornata: rapporto contro la base PRIMA di aggiornarla,
        // ma non nel primo chilometro (partenza in paese, code)
        val base = rawPredict(idx, surface, GradientFactorFallback).speedMs
        dayMovedM += speedMs
        if (base > 0.0 && dayMovedM >= FACTOR_WARMUP_M) {
            val r  = (speedMs / base).coerceIn(RATIO_MIN, RATIO_MAX)
            val ag = 1.0 / minOf(dayCount + FACTOR_PRIOR_GLOBAL + 1, FACTOR_WINDOW)
            dayFactor = ag * r + (1.0 - ag) * dayFactor
            dayCount++
            val ab = 1.0 / minOf(bucketFactorN[idx] + FACTOR_PRIOR + 1, FACTOR_WINDOW)
            bucketFactor[idx] = ab * r + (1.0 - ab) * bucketFactor[idx]
            bucketFactorN[idx]++
            surfaceClass(surface)?.let { c ->
                val ac = 1.0 / minOf(classFactorN[c] + FACTOR_PRIOR + 1, FACTOR_WINDOW)
                classFactor[c] = ac * r + (1.0 - ac) * classFactor[c]
                classFactorN[c]++
                val al = 1.0 / minOf(leafFactorN[c][idx] + FACTOR_PRIOR + 1, FACTOR_WINDOW)
                leafFactor[c][idx] = al * r + (1.0 - al) * leafFactor[c][idx]
                leafFactorN[c][idx]++
            }
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
    @Synchronized
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
    @Synchronized
    fun predict(
        gradePct: Double,
        surface: Surface = Surface.UNKNOWN,
        fallback: FallbackSpeedProvider = GradientFactorFallback,
    ): Prediction {
        val idx = bucketIndex(gradePct)
        val raw = rawPredict(idx, surface, fallback, gradePct)
        return Prediction((raw.speedMs * effectiveFactor(idx, surface)).coerceAtLeast(0.5), raw.fromFallback)
    }

    /**
     * Come [predict] ma dalla sola base, senza fattore di giornata: la stima a
     * tavolino di una route aperta e non ancora partita (26/10). Il fattore resta
     * quello dell'ultimo giro fino al prossimo [newRide] e non dice nulla sul
     * giro che verrà.
     */
    @Synchronized
    fun predictBase(
        gradePct: Double,
        surface: Surface = Surface.UNKNOWN,
        fallback: FallbackSpeedProvider = GradientFactorFallback,
    ): Prediction {
        val raw = rawPredict(bucketIndex(gradePct), surface, fallback, gradePct)
        return Prediction(raw.speedMs.coerceAtLeast(0.5), raw.fromFallback)
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

    /**
     * Fattore per (bucket, fondo). Fondo noto: quello di (classe, bucket), pesato
     * dai campioni visti, verso quello della classe, a sua volta verso il globale.
     * Fondo ignoto: quello del bucket verso il globale.
     */
    private fun effectiveFactor(idx: Int, surface: Surface = Surface.UNKNOWN): Double {
        val c = surfaceClass(surface) ?: return shrink(bucketFactor[idx], bucketFactorN[idx], dayFactor)
        return shrink(leafFactor[c][idx], leafFactorN[c][idx], classFactorOf(c))
    }

    private fun classFactorOf(c: Int): Double = shrink(classFactor[c], classFactorN[c], dayFactor)

    private fun shrink(own: Double, n: Int, parent: Double): Double {
        val w = n / (n + FACTOR_SHRINK)
        return w * own + (1.0 - w) * parent
    }

    private fun surfaceClass(surface: Surface): Int? = when (surface) {
        Surface.PAVED   -> PAVED
        Surface.GRAVEL, Surface.TRAIL, Surface.GNARLY -> UNPAVED
        Surface.UNKNOWN -> null
    }

    /** Fattore di giornata globale (1.0 = come la base). */
    @get:Synchronized
    val currentDayFactor: Double get() = dayFactor

    /** Fattore di giornata efficace per una pendenza e un fondo: riscala velocità di base esterne (memoria per tratto). */
    @Synchronized
    fun dayFactorFor(gradePct: Double, surface: Surface = Surface.UNKNOWN): Double =
        effectiveFactor(bucketIndex(gradePct), surface)

    /** Fattore di giornata della classe di fondo (asfalto / non asfalto); fondo ignoto → globale. */
    @Synchronized
    fun dayFactorForSurface(surface: Surface): Double =
        surfaceClass(surface)?.let { classFactorOf(it) } ?: dayFactor

    /** Fattori efficaci per bucket, per log e diagnostica. */
    @Synchronized
    fun effectiveFactors(): List<Double> = (0 until BUCKETS).map { effectiveFactor(it) }

    /**
     * Nuovo giro: il fattore di giornata riparte da 1 (la base resta).
     * Da chiamare al passaggio Idle → Recording, non alle pause.
     */
    @Synchronized
    fun newRide() {
        dayFactor = 1.0
        dayCount  = 0
        dayMovedM = 0.0
        bucketFactor.fill(1.0)
        bucketFactorN.fill(0)
        classFactor.fill(1.0)
        classFactorN.fill(0)
        leafFactor.forEach { it.fill(1.0) }
        leafFactorN.forEach { it.fill(0) }
    }

    /** @param fromFallback true when rung 3 answered, i.e. no learned data applied. */
    data class Prediction(val speedMs: Double, val fromFallback: Boolean)

    private fun surfaceBuckets(surface: Surface): Array<Bucket>? = when (surface) {
        Surface.PAVED   -> pavedBuckets
        Surface.GRAVEL  -> unpavedBuckets
        Surface.TRAIL   -> trailBuckets
        Surface.GNARLY  -> gnarlyBuckets
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
        val trailEmas: List<Double>    = emptyList(),
        val trailCounts: List<Int>     = emptyList(),
        val gnarlyEmas: List<Double>   = emptyList(),
        val gnarlyCounts: List<Int>    = emptyList(),
        // Fattore di giornata (persistito perché l'estensione può ripartire a metà giro)
        val dayFactor: Double          = 1.0,
        val dayCount: Int              = 0,
        val dayMovedM: Double          = 0.0,
        val bucketFactors: List<Double> = emptyList(),
        val bucketFactorCounts: List<Int> = emptyList(),
        // Per classe di fondo [asfalto, non asfalto] e per (classe, bucket); assenti = 1.0
        val classFactors: List<Double>      = emptyList(),
        val classFactorCounts: List<Int>    = emptyList(),
        val pavedFactors: List<Double>      = emptyList(),
        val pavedFactorCounts: List<Int>    = emptyList(),
        val unpavedFactors: List<Double>    = emptyList(),
        val unpavedFactorCounts: List<Int>  = emptyList(),
    )

    /** Restituisce uno snapshot dell'attuale stato addestrato. */
    @Synchronized
    fun snapshot() = Snapshot(
        overallEma     = overallEma,
        overallSamples = overallSamples,
        bucketEmas     = buckets.map { it.ema },
        bucketCounts   = buckets.map { it.count },
        pavedEmas      = pavedBuckets.map { it.ema },
        pavedCounts    = pavedBuckets.map { it.count },
        unpavedEmas    = unpavedBuckets.map { it.ema },
        unpavedCounts  = unpavedBuckets.map { it.count },
        trailEmas      = trailBuckets.map { it.ema },
        trailCounts    = trailBuckets.map { it.count },
        gnarlyEmas     = gnarlyBuckets.map { it.ema },
        gnarlyCounts   = gnarlyBuckets.map { it.count },
        dayFactor      = dayFactor,
        dayCount       = dayCount,
        dayMovedM      = dayMovedM,
        bucketFactors  = bucketFactor.toList(),
        bucketFactorCounts = bucketFactorN.toList(),
        classFactors        = classFactor.toList(),
        classFactorCounts   = classFactorN.toList(),
        pavedFactors        = leafFactor[PAVED].toList(),
        pavedFactorCounts   = leafFactorN[PAVED].toList(),
        unpavedFactors      = leafFactor[UNPAVED].toList(),
        unpavedFactorCounts = leafFactorN[UNPAVED].toList(),
    )

    /** Ripristina lo stato dal snapshot. Chiama reset() prima se necessario. */
    @Synchronized
    fun restore(snap: Snapshot) {
        overallEma     = snap.overallEma
        overallSamples = snap.overallSamples
        smoothedGrade  = 0.0
        val bEma = migrateLegacy(snap.bucketEmas);  val bCnt = migrateLegacyCounts(snap.bucketCounts)
        val pEma = migrateLegacy(snap.pavedEmas);   val pCnt = migrateLegacyCounts(snap.pavedCounts)
        val uEma = migrateLegacy(snap.unpavedEmas); val uCnt = migrateLegacyCounts(snap.unpavedCounts)
        val tEma = migrateLegacy(snap.trailEmas);   val tCnt = migrateLegacyCounts(snap.trailCounts)
        val gEma = migrateLegacy(snap.gnarlyEmas);  val gCnt = migrateLegacyCounts(snap.gnarlyCounts)
        for (i in buckets.indices) {
            buckets[i]        = Bucket(bEma.getOrElse(i) { 0.0 }, bCnt.getOrElse(i) { 0 })
            pavedBuckets[i]   = Bucket(pEma.getOrElse(i) { 0.0 }, pCnt.getOrElse(i) { 0 })
            unpavedBuckets[i] = Bucket(uEma.getOrElse(i) { 0.0 }, uCnt.getOrElse(i) { 0 })
            trailBuckets[i]   = Bucket(tEma.getOrElse(i) { 0.0 }, tCnt.getOrElse(i) { 0 })
            gnarlyBuckets[i]  = Bucket(gEma.getOrElse(i) { 0.0 }, gCnt.getOrElse(i) { 0 })
            bucketFactor[i]   = snap.bucketFactors.getOrElse(i) { 1.0 }
            bucketFactorN[i]  = snap.bucketFactorCounts.getOrElse(i) { 0 }
            leafFactor[PAVED][i]    = snap.pavedFactors.getOrElse(i) { 1.0 }
            leafFactorN[PAVED][i]   = snap.pavedFactorCounts.getOrElse(i) { 0 }
            leafFactor[UNPAVED][i]  = snap.unpavedFactors.getOrElse(i) { 1.0 }
            leafFactorN[UNPAVED][i] = snap.unpavedFactorCounts.getOrElse(i) { 0 }
        }
        for (c in 0 until CLASSES) {
            classFactor[c]  = snap.classFactors.getOrElse(c) { 1.0 }
            classFactorN[c] = snap.classFactorCounts.getOrElse(c) { 0 }
        }
        dayFactor = snap.dayFactor
        dayCount  = snap.dayCount
        dayMovedM = snap.dayMovedM
    }

    /** Azzera tutto — chiamato all'inizio di ogni nuova uscita. */
    @Synchronized
    fun reset() {
        for (i in buckets.indices) {
            buckets[i]        = Bucket()
            pavedBuckets[i]   = Bucket()
            unpavedBuckets[i] = Bucket()
            trailBuckets[i]   = Bucket()
            gnarlyBuckets[i]  = Bucket()
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
