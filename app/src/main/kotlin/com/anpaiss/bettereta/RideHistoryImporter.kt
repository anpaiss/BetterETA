package com.anpaiss.bettereta

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Importa un giro passato (i record di un FIT) nel modello, come se
 * l'estensione fosse stata in sella quel giorno: bucket pendenza e fondo,
 * fisica in ombra, memoria dei tempi per tratto.
 *
 * Differenze dal vivo, volute e documentate nel README:
 *  - la pendenza viene dalla quota del FIT smussata su ~50 m, non dal Karoo;
 *  - i passaggi sui tratti entrano a fattore di giornata 1 (quello di allora
 *    non è noto): la media degli ultimi tre passaggi assorbe le giornate storte;
 *  - la guardia è quella GPS dei giri liberi (spostamento netto plausibile),
 *    così i giri finti in casa non entrano.
 *
 * Nessuna dipendenza Android: la classificazione del fondo arriva da fuori.
 */
class RideHistoryImporter(
    private val speedModel: AdaptiveSpeedModel,
    private val physicsModel: PhysicsSpeedModel?,
    private val wayMemory: WaySpeedMemory,
    /** Fondo e chiave (way, verso) per una lista di punti; null = fondo non disponibile. */
    private val classify: (List<RouteWaypoint>) -> Pair<List<Surface>, List<Long>>?,
) {
    class Summary(
        val startMs: Long,
        val records: Int,
        val accepted: Int,
        val distanceM: Double,
        val kmByClass: Map<Surface, Double>,
        val passes: Int,
    )

    companion object {
        const val MIN_ACCEPTED_SAMPLES = 300      // sotto: giro troppo corto o fermo, non conta
        // Un FIT che inizia entro questa distanza dall'avvio di un giro registrato
        // con l'estensione in sella è quel giro: già appreso dal vivo, non si reimporta
        const val LIVE_RIDE_TOLERANCE_MS = 15 * 60_000L

        /** True se il FIT che inizia a [fitStartMs] è un giro già appreso dal vivo (avviato in [rideStartsMs]). */
        fun learnedLive(fitStartMs: Long?, rideStartsMs: Collection<Long>, toleranceMs: Long = LIVE_RIDE_TOLERANCE_MS): Boolean =
            fitStartMs != null && rideStartsMs.any { kotlin.math.abs(it - fitStartMs) <= toleranceMs }
        private const val MAX_DT_S = 5.0
        private const val SMOOTH_HALF_M = 25.0
        private const val CLASSIFY_SPACING_M = 20.0
        private const val PASS_MIN_M = 50.0
        private const val PASS_MIN_SEC = 10.0
    }

    fun importRecords(records: List<FitRecordReader.Record>): Summary? {
        val pts = records.filter { it.timeMs != null && it.lat != null && it.lon != null }
        if (pts.size < 2) return null
        val n = pts.size

        // Distanza cumulativa dalla posizione (il campo distance non sempre c'è)
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + approxDistM(pts[i - 1].lat!!, pts[i - 1].lon!!, pts[i].lat!!, pts[i].lon!!)

        // Quota smussata su ±SMOOTH_HALF_M, pendenza tra i due estremi della finestra
        val alt = DoubleArray(n) { pts[it].altM ?: Double.NaN }
        val altS = smooth(alt, cum, SMOOTH_HALF_M)
        val grade = DoubleArray(n) { i ->
            val a = lowerBound(cum, cum[i] - SMOOTH_HALF_M)
            val b = minOf(n - 1, lowerBound(cum, cum[i] + SMOOTH_HALF_M))
            val d = cum[b] - cum[a]
            if (d < 5.0 || altS[a].isNaN() || altS[b].isNaN()) 0.0
            else ((altS[b] - altS[a]) / d * 100.0).coerceIn(-30.0, 30.0)
        }

        // Fondo: classificazione su punti ogni ~20 m, propagata ai record intermedi
        val sampleIdx = ArrayList<Int>()
        var last = -1e9
        for (i in 0 until n) if (cum[i] - last >= CLASSIFY_SPACING_M) { sampleIdx.add(i); last = cum[i] }
        val classified = classify(sampleIdx.map { RouteWaypoint(pts[it].lat!!, pts[it].lon!!, alt[it].takeIf { a -> !a.isNaN() } ?: 0.0) })
        val surfaceAt = Array(n) { Surface.UNKNOWN }
        val keyAt = LongArray(n)
        if (classified != null) {
            var s = 0
            for (i in 0 until n) {
                while (s + 1 < sampleIdx.size && sampleIdx[s + 1] <= i) s++
                surfaceAt[i] = classified.first.getOrElse(s) { Surface.UNKNOWN }
                keyAt[i] = classified.second.getOrElse(s) { 0L }
            }
        }

        // Replay con la guardia GPS; passaggi per tratto a fattore 1
        val gate = GpsProgressGate()
        val km = HashMap<Surface, Double>()
        var accepted = 0
        var passes = 0
        var passKey = 0L; var passDist = 0.0; var passSec = 0.0
        fun closePass(timeMs: Long) {
            if (passKey != 0L && passDist >= PASS_MIN_M && passSec >= PASS_MIN_SEC) {
                wayMemory.record(passKey, passDist / passSec, timeMs)
                passes++
            }
            passKey = 0L; passDist = 0.0; passSec = 0.0
        }
        for (i in 1 until n) {
            val dt = ((pts[i].timeMs!! - pts[i - 1].timeMs!!) / 1000.0).coerceIn(0.0, MAX_DT_S)
            if (dt <= 0.0) continue
            val stepM = cum[i] - cum[i - 1]
            val speed = pts[i].speedMs ?: (stepM / dt)
            val progressing = gate.onTick(pts[i].lat!!, pts[i].lon!!, speed, dt)
            val moving = speed >= 0.5 && progressing
            km.merge(surfaceAt[i], stepM, Double::plus)
            if (keyAt[i] != passKey) { closePass(pts[i].timeMs!!); passKey = keyAt[i] }
            if (!moving) continue
            accepted++
            speedModel.update(speed, grade[i], surfaceAt[i])
            physicsModel?.update(speed, grade[i], surfaceAt[i])
            if (passKey != 0L) { passDist += speed * dt; passSec += dt }
        }
        closePass(pts[n - 1].timeMs!!)
        // L'aggiornamento del modello muove anche il fattore di giornata: non è di oggi
        speedModel.newRide()
        return Summary(pts.first().timeMs!!, n, accepted, cum[n - 1], km, passes)
    }

    private fun smooth(v: DoubleArray, cum: DoubleArray, halfM: Double): DoubleArray {
        val out = DoubleArray(v.size)
        for (i in v.indices) {
            val a = lowerBound(cum, cum[i] - halfM)
            val b = minOf(v.size - 1, lowerBound(cum, cum[i] + halfM))
            var sum = 0.0; var cnt = 0
            for (j in a..b) if (!v[j].isNaN()) { sum += v[j]; cnt++ }
            out[i] = if (cnt > 0) sum / cnt else Double.NaN
        }
        return out
    }

    private fun lowerBound(arr: DoubleArray, value: Double): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid] < value) lo = mid + 1 else hi = mid }
        return minOf(lo, arr.size - 1)
    }

    private fun approxDistM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }
}
