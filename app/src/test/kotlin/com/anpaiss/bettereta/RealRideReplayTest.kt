package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replay del rilevamento "in route" sul GPS di un giro vero. Usa il codice
 * del dispositivo (FitRecordReader, PolylineDecoder, SegmentPipeline,
 * SelfOnRouteDetector) sui file in logs/replay/ (gitignored):
 *   ride_*.fit       il FIT del giro, da /sdcard/FitFiles
 *   route_*.txt      la traccia salvata da BetterEta (files/routes)
 * Senza file il test si salta. Il resoconto va in logs/replay/replay_report.txt.
 */
class RealRideReplayTest {

    private val dir = File("../logs/replay")

    @Test fun `replay the on-route detection on a real ride`() = runBlocking {
        val fit = dir.listFiles { f -> f.name.endsWith(".fit") }?.sortedBy { it.name }?.lastOrNull()
        val routeFile = dir.listFiles { f -> f.name.startsWith("route_") }?.sortedBy { it.name }?.lastOrNull()
        assumeTrue("nessun giro da rigiocare in ${dir.absolutePath}", fit != null && routeFile != null)

        val lines = routeFile!!.readLines()
        val coords = PolylineDecoder.decodeLatLng(lines[1])
        val pipeline = SegmentPipeline()
        pipeline.setRoute(coords.map { RouteWaypoint(it.first, it.second, 0.0) })

        val records = FitRecordReader.read(fit!!.readBytes())
            .filter { it.lat != null && it.lon != null && it.timeMs != null }
        val detector = SelfOnRouteDetector()
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ITALY)
        val report = StringBuilder()
        report.appendLine("Giro ${fit.name}, route '${lines[0]}' (${coords.size} punti), ${records.size} fix GPS")

        var onSince: Long? = null
        var onMs = 0L
        var matchedMs = 0L
        var prevT: Long? = null
        var prevOn = false
        for (r in records) {
            val t = r.timeMs!!
            val match = pipeline.matchOnRoute(r.lat!!, r.lon!!, 30.0, detector.lastAlongM)
            val on = detector.onTick(t, match?.alongM)
            val dt = prevT?.let { (t - it).coerceIn(0, 10_000) } ?: 0L
            if (match != null) matchedMs += dt
            if (prevOn) onMs += dt
            if (on != prevOn) {
                if (on) {
                    onSince = t
                    report.appendLine("${fmt.format(Date(t))}  IN ROUTE  km %.2f".format(detector.lastAlongM!! / 1000))
                } else {
                    report.appendLine("${fmt.format(Date(t))}  fuori     dopo %d min in route".format((t - onSince!!) / 60_000))
                }
            }
            prevOn = on
            prevT = t
        }
        val total = records.last().timeMs!! - records.first().timeMs!!
        report.appendLine("Durata %d min: traccia entro 30 m per %d min, in route confermato per %d min".format(
            total / 60_000, matchedMs / 60_000, onMs / 60_000))
        File(dir, "replay_report.txt").writeText(report.toString())
        println(report)
    }

    /**
     * Prova positiva sui dati veri: la route è la traccia del giro stesso (un
     * punto ogni ~30 m). Il rilevamento deve confermare presto e restare in
     * route per quasi tutto il giro, soste comprese.
     */
    @Test fun `on its own track the ride is on route almost all the time`() = runBlocking {
        val fit = dir.listFiles { f -> f.name.endsWith(".fit") }?.sortedBy { it.name }?.lastOrNull()
        assumeTrue("nessun FIT in ${dir.absolutePath}", fit != null)
        val records = FitRecordReader.read(fit!!.readBytes())
            .filter { it.lat != null && it.lon != null && it.timeMs != null }
        val track = mutableListOf<RouteWaypoint>()
        for (r in records) {
            val last = track.lastOrNull()
            val far = last == null || Math.hypot((r.lat!! - last.lat) * 111_320.0,
                (r.lon!! - last.lon) * 111_320.0 * Math.cos(Math.toRadians(r.lat))) >= 30.0
            if (far) track += RouteWaypoint(r.lat!!, r.lon!!, 0.0)
        }
        val pipeline = SegmentPipeline()
        pipeline.setRoute(track)
        val detector = SelfOnRouteDetector()
        var firstOnMs: Long? = null
        var onMs = 0L
        var prevT: Long? = null
        var prevOn = false
        var drops = 0
        var total = 0L   // tempo registrato: le auto-pause non hanno punti nel FIT
        for (r in records) {
            val t = r.timeMs!!
            val ref = detector.lastAlongM ?: pipeline.distanceAlongRouteM()
            val on = detector.onTick(t, pipeline.matchOnRoute(r.lat!!, r.lon!!, 30.0, ref)?.alongM)
            if (on && firstOnMs == null) {
                firstOnMs = t
                pipeline.locateAt(detector.lastAlongM!!)
            }
            if (prevOn && !on) drops++
            val dt = prevT?.let { (t - it).coerceIn(0, 10_000) } ?: 0L
            total += dt
            if (prevOn) onMs += dt
            prevOn = on
            prevT = t
        }
        val firstS = (firstOnMs!! - records.first().timeMs!!) / 1000
        val share = 100.0 * onMs / total
        File(dir, "replay_selftrack_report.txt").writeText(
            "Traccia del giro come route (${track.size} punti): in route dopo ${firstS}s, " +
                "per %.1f%% del giro, perso %d volte".format(share, drops))
        org.junit.Assert.assertTrue("in route dopo ${firstS}s", firstS <= 120)
        org.junit.Assert.assertTrue("in route solo il %.1f%%".format(share), share >= 95.0)
    }
}

