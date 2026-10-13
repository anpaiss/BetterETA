package com.anpaiss.bettereta

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Rigioca i campioni registrati (logs/better_eta_samples_*.csv, gitignored)
 * attraverso il codice del dispositivo — AdaptiveSpeedModel, PhysicsSpeedModel,
 * SegmentPipeline, EtaEngine — e scrive una riga per campione in
 * logs/replay/csv_replay_out.csv. Serve come prova di non regressione: lo
 * stesso input deve dare lo stesso file prima e dopo una modifica che non
 * vuole cambiare la logica (13/10: estrazione del modulo eta-core).
 *
 * Non riproduce l'ETA loggata dal Karoo (manca la memoria per tratto, lo
 * stato salvato del modello, le soste): è un replay deterministico, non un
 * confronto col giro vero. Senza file il test si salta.
 */
class CsvReplayTest {

    private val logs = File("../logs")
    private val replay = File(logs, "replay")

    @Test fun `replay the sample CSVs through the ride models`() = runBlocking {
        val csvs = listOf("better_eta_samples_2026-10-10.csv", "better_eta_samples_2026-10-11_merged.csv")
            .map { File(logs, it) }.filter { it.isFile }
        assumeTrue("nessun CSV di campioni in ${logs.absolutePath}", csvs.isNotEmpty())

        val speedModel   = AdaptiveSpeedModel()
        val physicsModel = PhysicsSpeedModel()
        val pipeline     = SegmentPipeline()
        val engine       = EtaEngine(speedModel, pipeline)
        val shadow       = EtaEngine(speedModel, pipeline, physicsFirst = physicsModel)

        val out = StringBuilder("csv,t_ms,pos_m,eta_learned_s,eta_physics_s,day_factor,p_low_w,p_high_w,rem_dist_m,rem_segments\n")
        var rows = 0; var rides = 0; var currentHash = 0
        var hasRoute = false
        for (csv in csvs) {
            var header: List<String> = emptyList()
            var lastDay = ""
            for (line in csv.readLines()) {
                when {
                    line.startsWith("# ride") -> {
                        val m = Regex("# ride (\\S+) \\S+ name=.* dist=(-?\\d+) hash=(-?\\d+)").find(line) ?: continue
                        val day = m.groupValues[1]
                        if (day != lastDay) { speedModel.newRide(); lastDay = day }
                        val hash = m.groupValues[3].toInt()
                        val routeFile = File(replay, "route_%08x.txt".format(hash))
                        if (m.groupValues[2].toInt() <= 0 || !routeFile.isFile) { hasRoute = false; continue }
                        if (hash != currentHash) {
                            val lines = routeFile.readLines()
                            val coords = PolylineDecoder.decodeLatLng(lines[1])
                            val elev = interpolateElevations(coords, PolylineDecoder.decodeDistanceElevation(lines.getOrElse(2) { "" }))
                            pipeline.setRoute(coords.mapIndexed { i, c -> RouteWaypoint(c.first, c.second, elev[i]) })
                            currentHash = hash; rides++
                        }
                        hasRoute = true
                    }
                    line.startsWith("t_ms") -> header = line.split(',')
                    header.isEmpty() || line.isBlank() -> {}
                    else -> {
                        val f = line.split(',')
                        fun col(name: String) = f.getOrNull(header.indexOf(name)).orEmpty()
                        val t = col("t_ms").toLongOrNull() ?: continue
                        val v = col("speed_ms").toDoubleOrNull() ?: continue
                        val g = col("grade_pct").toDoubleOrNull() ?: continue
                        val surface = runCatching { Surface.valueOf(col("surface")) }.getOrDefault(Surface.UNKNOWN)
                        val pos = col("pos_ours_m").toDoubleOrNull() ?: continue
                        if (col("ride_state") == "Recording" && v >= 0.5) {
                            speedModel.update(v, g, surface)
                            physicsModel.update(v, g, surface)
                        }
                        if (!hasRoute) continue
                        val segs = pipeline.remainingSegmentsFrom(pos)
                        out.append(csv.name).append(',').append(t).append(',')
                            .append(String.format(Locale.ROOT, "%.1f", pos)).append(',')
                            .append(engine.computeFromSegments(segs)).append(',')
                            .append(shadow.computeFromSegments(segs)).append(',')
                            .append(String.format(Locale.ROOT, "%.4f", speedModel.currentDayFactor)).append(',')
                            .append(physicsModel.powerLowW?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "").append(',')
                            .append(physicsModel.powerHighW?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "").append(',')
                            .append(String.format(Locale.ROOT, "%.1f", segs.sumOf { it.distanceM })).append(',')
                            .append(segs.size).append('\n')
                        rows++
                    }
                }
            }
        }
        replay.mkdirs()
        File(replay, "csv_replay_out.csv").writeText(out.toString())
        println("CsvReplay: $rows righe, $rides route, in ${File(replay, "csv_replay_out.csv").absolutePath}")
    }
}
