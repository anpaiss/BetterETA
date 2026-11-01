package com.anpaiss.bettereta

import android.content.ContextWrapper
import android.content.pm.PackageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Replay of the way-home estimate on the recorded rides (logs/fit/FitFiles,
 * gitignored, pulled from /sdcard/FitFiles). For every ride that ends where
 * it started, once a minute: the [ReturnTrack] built from the fixes so far,
 * the time the [EtaEngine] gives for retracing it, and the truth, which is
 * the moving time the rider actually took from the last pass by that point
 * to the end of the ride. A sample counts as "retraced" when the distance
 * actually ridden from that pass matches the retrace distance within 15 %:
 * on the other samples the rider went home another way and the estimate
 * cannot be judged.
 *
 * The speed model sees each ride as it would have on the day: the rides
 * before it imported as history (gradient and surface buckets, way memory),
 * the ride itself learned minute by minute. Surfaces and way keys come from
 * the Karoo offline map copied on the PC, as in SurfaceReplayTest; without
 * the map the test still runs, with every surface unknown.
 *
 * Output: logs/replay/return_replay_report.txt and return_replay_samples.csv.
 */
class ReturnReplayTest {

    private val fitDir = File("../logs/fit/FitFiles")
    private val outDir = File("../logs/replay")
    private val mapsDir = File("C:/Users/Andrea/Desktop/personale/KAROO3-orig")

    private class StubContext(private val files: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getExternalFilesDir(type: String?): File = files
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    private class Ride(val file: File, val recs: List<FitRecordReader.Record>) {
        val startMs get() = recs.first().timeMs!!
    }

    private class Sample(
        val day: String, val minute: Int, val posM: Double, val following: Boolean,
        val estM: Double, val estSec: Long, val estBaseSec: Long, val climbM: Double, val descM: Double,
        val fallbackShare: Double, val memoryShare: Double, val pavedShare: Double,
        val actM: Double, val actSec: Long, val retraced: Boolean, val rejoins: Int, val droppedM: Double,
    )

    @Test fun `replay the way home on the recorded rides`() = runBlocking {
        val files = fitDir.listFiles { f -> f.name.endsWith(".fit") }?.toList().orEmpty()
        assumeTrue("nessun FIT in ${fitDir.absolutePath}", files.isNotEmpty())
        val rides = files.mapNotNull { f ->
            val recs = FitRecordReader.read(f.readBytes()).filter { it.timeMs != null && it.lat != null && it.lon != null }
            if (recs.size < 300) null else Ride(f, recs)
        }.sortedBy { it.startMs }

        val ctx = StubContext(Files.createTempDirectory("return_replay").toFile())
        val logger = BetterEtaLogger(ctx, announce = false)
        val hasMap = File(mapsDir, "italy.map").isFile
        val classifier = if (hasMap) SurfaceClassifier(ctx, logger, MapsforgeSurfaceSource(ctx, logger, mapsDir)) else null
        val classify: (List<RouteWaypoint>) -> Pair<List<Surface>, List<Long>>? = { wps ->
            classifier?.classify(wps)?.let { it.surfaces to it.wayKeys }
        }

        val history = AdaptiveSpeedModel()
        val memory = WaySpeedMemory()
        val importer = RideHistoryImporter(history, null, memory, classify)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Europe/Rome") }
        val report = StringBuilder()
        val csv = StringBuilder("day,minute,pos_m,following,est_m,est_s,est_base_s,climb_m,desc_m,fallback_share,memory_share,paved_share,act_m,act_s,retraced,rejoins,dropped_m\n")
        val all = ArrayList<Sample>()
        var evaluated = 0
        report.appendLine("%d FIT, %d giri con posizione, storico appreso in ordine di data, fondo %s".format(
            files.size, rides.size, if (hasMap) "dalla mappa offline" else "ignoto (mappa assente)"))
        report.appendLine()
        report.appendLine("giro              km    min  camp  retr   err%  med%  p90%  bias%  base%  rejoin  cut_km  fine_m")
        for (ride in rides) {
            val day = fmt.format(Date(ride.startMs))
            val first = ride.recs.first(); val last = ride.recs.last()
            val closes = distM(first.lat!!, first.lon!!, last.lat!!, last.lon!!) < 500.0
            val km = ride.recs.last().distM?.div(1000.0) ?: 0.0
            if (closes && km >= 5.0) {
                val live = AdaptiveSpeedModel().apply { restore(history.snapshot()); newRide() }
                val r = evaluate(day, ride, live, memory, classify)
                evaluated++
                val retr = r.samples.filter { it.retraced && it.actSec >= 300 }
                val errs = retr.map { (it.estSec - it.actSec).toDouble() / it.actSec }
                val absErrs = errs.map { abs(it) }.sorted()
                val baseErrs = retr.map { abs(it.estBaseSec - it.actSec).toDouble() / it.actSec }
                fun pct(l: List<Double>, q: Double) = if (l.isEmpty()) Double.NaN else l[((l.size - 1) * q).toInt()] * 100
                report.appendLine("%s %5.1f %5d %5d %5d %6.1f %5.1f %5.1f %6.1f %6.1f %6d %7.2f %6.0f".format(
                    day, km, (last.timeMs!! - first.timeMs!!) / 60_000, r.samples.size, retr.size,
                    if (absErrs.isEmpty()) Double.NaN else absErrs.average() * 100, pct(absErrs, 0.5), pct(absErrs, 0.9),
                    if (errs.isEmpty()) Double.NaN else errs.average() * 100,
                    if (baseErrs.isEmpty()) Double.NaN else baseErrs.average() * 100,
                    r.rejoins, r.droppedM / 1000, r.finalReturnM))
                all += r.samples
                for (s in r.samples) csv.append(s.day).append(',').append(s.minute).append(',')
                    .append("%.0f,%s,%.0f,%d,%d,%.0f,%.0f,%.2f,%.2f,%.2f,%.0f,%d,%s,%d,%.0f\n".format(Locale.ROOT,
                        s.posM, s.following, s.estM, s.estSec, s.estBaseSec, s.climbM, s.descM,
                        s.fallbackShare, s.memoryShare, s.pavedShare, s.actM, s.actSec, s.retraced, s.rejoins, s.droppedM))
            }
            importer.importRecords(ride.recs)
        }
        val retr = all.filter { it.retraced && it.actSec >= 300 }
        val errs = retr.map { (it.estSec - it.actSec).toDouble() / it.actSec }
        val absErrs = errs.map { abs(it) }.sorted()
        report.appendLine()
        report.appendLine("Giri valutati (chiusi, >= 5 km): $evaluated. Campioni: ${all.size}, di cui con ritorno sulla stessa traccia e >= 5 min: ${retr.size}")
        if (absErrs.isNotEmpty()) {
            report.appendLine("Errore |stima-vero|/vero: medio %.1f %%, mediana %.1f %%, p90 %.1f %%; bias %.1f %% (positivo = stima pessimista)".format(
                absErrs.average() * 100, absErrs[absErrs.size / 2] * 100, absErrs[(absErrs.size - 1) * 9 / 10] * 100, errs.average() * 100))
            fun mean(l: List<Sample>, f: (Sample) -> Long) = if (l.isEmpty()) Double.NaN else l.map { abs(f(it) - it.actSec).toDouble() / it.actSec }.average() * 100
            fun bias(l: List<Sample>, f: (Sample) -> Long) = if (l.isEmpty()) Double.NaN else l.map { (f(it) - it.actSec).toDouble() / it.actSec }.average() * 100
            report.appendLine("Senza fattore di giornata: errore medio %.1f %%, bias %.1f %%".format(mean(retr) { it.estBaseSec }, bias(retr) { it.estBaseSec }))
            val out = retr.filter { !it.following }; val back = retr.filter { it.following }
            report.appendLine("All'andata (nuovo terreno): %d campioni, errore medio %.1f %%, bias %.1f %%; al ritorno (sulla traccia): %d campioni, %.1f %%, bias %.1f %%".format(
                out.size, mean(out) { it.estSec }, bias(out) { it.estSec }, back.size, mean(back) { it.estSec }, bias(back) { it.estSec }))
            val mem = retr.filter { it.memoryShare >= 0.5 }; val noMem = retr.filter { it.memoryShare < 0.5 }
            report.appendLine("Con memoria tratti su >= 50 %% del ritorno: %d campioni, errore %.1f %%, bias %.1f %%; senza: %d, %.1f %%, bias %.1f %%".format(
                mem.size, mean(mem) { it.estSec }, bias(mem) { it.estSec }, noMem.size, mean(noMem) { it.estSec }, bias(noMem) { it.estSec }))
            val downhill = retr.filter { it.descM > 2 * it.climbM + 50 }; val other = retr - downhill.toSet()
            report.appendLine("Ritorno in discesa (D- > 2 D+): %d campioni, errore %.1f %%, bias %.1f %%; altri: %d, %.1f %%, bias %.1f %%".format(
                downhill.size, mean(downhill) { it.estSec }, bias(downhill) { it.estSec }, other.size, mean(other) { it.estSec }, bias(other) { it.estSec }))
        }
        outDir.mkdirs()
        File(outDir, "return_replay_report.txt").writeText(report.toString())
        File(outDir, "return_replay_samples.csv").writeText(csv.toString())
        println(report)
    }

    private class Result(val samples: List<Sample>, val rejoins: Int, val droppedM: Double, val finalReturnM: Double)

    private suspend fun evaluate(
        day: String, ride: Ride, live: AdaptiveSpeedModel, memory: WaySpeedMemory,
        classify: (List<RouteWaypoint>) -> Pair<List<Surface>, List<Long>>?,
    ): Result {
        val pts = ride.recs
        val n = pts.size
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + distM(pts[i - 1].lat!!, pts[i - 1].lon!!, pts[i].lat!!, pts[i].lon!!)
        val alt = DoubleArray(n) { pts[it].altM ?: Double.NaN }
        val altS = smooth(alt, cum, SMOOTH_HALF_M)
        val grade = DoubleArray(n) { i ->
            val a = lowerBound(cum, cum[i] - SMOOTH_HALF_M)
            val b = minOf(n - 1, lowerBound(cum, cum[i] + SMOOTH_HALF_M))
            val d = cum[b] - cum[a]
            if (d < 5.0 || altS[a].isNaN() || altS[b].isNaN()) 0.0 else ((altS[b] - altS[a]) / d * 100.0).coerceIn(-30.0, 30.0)
        }
        // Surface and way per record, classified on points every 20 m as the importer does
        val sampleIdx = ArrayList<Int>()
        var lastS = -1e9
        for (i in 0 until n) if (cum[i] - lastS >= 20.0) { sampleIdx.add(i); lastS = cum[i] }
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
        // Moving time still to come from each record, the truth the estimate is judged against
        val movingToEnd = LongArray(n)
        for (i in n - 2 downTo 0) {
            val dt = (pts[i + 1].timeMs!! - pts[i].timeMs!!) / 1000.0
            val v = pts[i + 1].speedMs ?: ((cum[i + 1] - cum[i]) / dt.coerceAtLeast(1.0))
            movingToEnd[i] = movingToEnd[i + 1] + (if (dt in 0.0..MAX_DT_S && v >= 0.5) dt.toLong() else 0L)
        }

        val engine = EtaEngine(live, SegmentPipeline(), wayMemory = memory)
        val track = ReturnTrack()
        val gate = GpsProgressGate()
        val samples = ArrayList<Sample>()
        var nextSampleMs = pts[0].timeMs!! + 60_000L
        for (i in 0 until n) {
            val p = pts[i]
            if (i > 0) {
                val dt = ((p.timeMs!! - pts[i - 1].timeMs!!) / 1000.0).coerceIn(0.0, MAX_DT_S)
                val speed = p.speedMs ?: ((cum[i] - cum[i - 1]) / dt.coerceAtLeast(1.0))
                val progressing = gate.onTick(p.lat!!, p.lon!!, speed, dt)
                if (dt > 0.0 && speed >= 0.5 && progressing) live.update(speed, grade[i], surfaceAt[i])
            }
            track.add(p.lat!!, p.lon!!, altS[i].takeIf { !it.isNaN() } ?: (alt[i].takeIf { !it.isNaN() } ?: 0.0), surfaceAt[i], keyAt[i])
            if (p.timeMs!! < nextSampleMs) continue
            nextSampleMs += 60_000L
            val segs = track.returnSegments()
            if (segs.isEmpty()) continue
            val b = engine.computeBreakdown(segs)
            val base = engine.computeBreakdown(segs, baseOnly = true)
            val estM = segs.sumOf { it.distanceM }
            val climbM = segs.filter { it.gradePct > 0 }.sumOf { it.distanceM * it.gradePct / 100 }
            val descM = segs.filter { it.gradePct < 0 }.sumOf { -it.distanceM * it.gradePct / 100 }
            val pavedShare = segs.filter { it.surface == Surface.PAVED }.sumOf { it.distanceM } / estM
            // Truth: the last pass within 30 m of this point, and what followed it
            var k = -1
            for (j in n - 1 downTo i + 1) if (distM(p.lat!!, p.lon!!, pts[j].lat!!, pts[j].lon!!) <= 30.0) { k = j; break }
            val actM = if (k < 0) -1.0 else cum[n - 1] - cum[k]
            val actSec = if (k < 0) -1L else movingToEnd[k]
            // Retraced: similar length AND the path actually ridden from that pass runs
            // along the spine (a loop of the same length as the way out fooled the
            // length check alone: 2026-09-20, +50 % "error" against a different road)
            val retraced = k >= 0 && abs(actM - estM) <= 0.15 * estM + 100.0 &&
                shareOnSpine(track.returnWaypoints(), pts, cum, k) >= 0.9
            samples += Sample(day, ((p.timeMs!! - pts[0].timeMs!!) / 60_000).toInt(), cum[i], track.isFollowing,
                estM, b.seconds, base.seconds, climbM, descM, b.fallbackDistanceShare, b.memoryDistanceShare, pavedShare,
                actM, actSec, retraced, track.rejoins, track.droppedM)
        }
        return Result(samples, track.rejoins, track.droppedM, track.returnDistanceM)
    }

    /**
     * Share of the ride from record [from] to the end, sampled every 250 m,
     * that lies within 40 m of the spine. A grid of ~50 m cells keeps it cheap.
     */
    private fun shareOnSpine(spine: List<RouteWaypoint>, pts: List<FitRecordReader.Record>, cum: DoubleArray, from: Int): Double {
        if (spine.isEmpty()) return 0.0
        val cellLat = 50.0 / 111_320.0
        val cellLon = 50.0 / (111_320.0 * cos(Math.toRadians(spine[0].lat)))
        val grid = HashMap<Long, ArrayList<RouteWaypoint>>()
        fun key(lat: Double, lon: Double) = (Math.floor(lat / cellLat).toLong() shl 32) xor (Math.floor(lon / cellLon).toLong() and 0xffffffffL)
        for (w in spine) grid.getOrPut(key(w.lat, w.lon)) { ArrayList() } += w
        var total = 0; var on = 0
        var nextCum = cum[from]
        for (i in from until pts.size) {
            if (cum[i] < nextCum) continue
            nextCum += 250.0
            total++
            val lat = pts[i].lat!!; val lon = pts[i].lon!!
            var near = false
            outer@ for (dy in -1..1) for (dx in -1..1) {
                val cell = grid[key(lat + dy * cellLat, lon + dx * cellLon)] ?: continue
                for (w in cell) if (distM(lat, lon, w.lat, w.lon) <= 40.0) { near = true; break@outer }
            }
            if (near) on++
        }
        return if (total == 0) 0.0 else on.toDouble() / total
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

    private fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val SMOOTH_HALF_M = 25.0
        private const val MAX_DT_S = 5.0
    }
}
