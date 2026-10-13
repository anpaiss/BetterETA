package com.anpaiss.bettereta

import android.content.ContextWrapper
import android.content.pm.PackageManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Rifà sul PC la classificazione del fondo che l'estensione fa sul Karoo al
 * caricamento della route: SurfaceClassifier + MapsforgeSurfaceSource sulla
 * copia della mappa offline del Karoo (italy.map), con le route salvate in
 * logs/replay/. Scrive una riga per waypoint (fondo e chiave way) in
 * logs/replay/surface_replay_out.csv: prova di non regressione per le
 * modifiche che non vogliono cambiare la logica (13/10: spostamento in
 * eta-core con il logger dietro un'interfaccia). Senza mappa o route si salta.
 *
 * Il Context è uno stub (unitTests.isReturnDefaultValues): filesDir in una
 * cartella temporanea, permesso storage concesso. Nessuna rete: la mappa
 * copre entrambe le route, Overpass non viene mai chiamato.
 */
class SurfaceReplayTest {

    private val replay = File("../logs/replay")
    private val mapsDir = File("C:/Users/Andrea/Desktop/personale/KAROO3-orig")
    private val routes = listOf("route_61e89c41.txt", "route_af2b1067.txt")   // 10/10 Sopra Susà, 11/10 Carezza

    private class StubContext(private val files: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getExternalFilesDir(type: String?): File = files
        override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED
    }

    @Test fun `classify the saved routes from the offline map`() {
        assumeTrue("nessuna mappa in ${mapsDir.absolutePath}", File(mapsDir, "italy.map").isFile)
        assumeTrue("route mancanti in ${replay.absolutePath}", routes.all { File(replay, it).isFile })

        val ctx = StubContext(Files.createTempDirectory("surface_replay").toFile())
        val logger = BetterEtaLogger(ctx, announce = false)
        val classifier = SurfaceClassifier(ctx, logger, MapsforgeSurfaceSource(ctx, logger, mapsDir))

        val out = StringBuilder("route,i,lat,lon,surface,way_key\n")
        for (name in routes) {
            val lines = File(replay, name).readLines()
            val wps = PolylineDecoder.decodeLatLng(lines[1]).map { RouteWaypoint(it.first, it.second, 0.0) }
            val r = classifier.classify(wps) ?: error("classify() null per $name")
            check(r.complete) { "classificazione incompleta per $name" }
            wps.forEachIndexed { i, w ->
                out.append(name).append(',').append(i).append(',')
                    .append(String.format(Locale.ROOT, "%.5f,%.5f", w.lat, w.lon)).append(',')
                    .append(r.surfaces[i]).append(',').append(r.wayKeys[i]).append('\n')
            }
            val km = Surface.entries.associateWith { s -> r.surfaces.count { it == s } }
            println("SurfaceReplay $name: ${wps.size} waypoint, $km")
        }
        File(replay, "surface_replay_out.csv").writeText(out.toString())
    }
}
