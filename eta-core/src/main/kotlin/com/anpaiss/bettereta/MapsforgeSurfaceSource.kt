package com.anpaiss.bettereta

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * Fondo dalle mappe offline del Karoo: i file Mapsforge (estensione .map) in
 * `/sdcard/offline/maps/` contengono le way OSM con `surface`,
 * `tracktype` e `highway`. Stessa tecnica di karoo-routegraph (libreria
 * ufficiale Hammerhead): tile a zoom 16 attorno ai punti campionati della
 * route, way con tag highway classificate da [SurfaceTagClassifier].
 *
 * Serve lo storage legacy (targetSdk 28) e READ_EXTERNAL_STORAGE concesso a
 * runtime dalla schermata dell'app. Senza, o senza file che coprano la zona,
 * risponde null e il classificatore ripiega su Overpass.
 *
 * Le way Mapsforge non portano l'id OSM: l'id qui è un hash stabile della
 * geometria, che regge finché non cambia il file mappa. Serve alla cache e
 * alla memoria dei tempi per tratto; a un aggiornamento mappa quelle voci
 * decadono da sole.
 */
class MapsforgeSurfaceSource(
    private val context: Context,
    private val logger: RideLogger,
    private val mapsDir: File = File(Environment.getExternalStorageDirectory(), "offline/maps"),
) {
    class Fetch(val ways: List<OsmWay>, val tiles: Int, val files: Int, val millis: Long, val tagKeys: Set<String> = emptySet()) {
        /** "12 paved, 7 gravel, 3 trail" — quante way per classe, per log e prova. */
        fun classCounts(): String = Surface.entries.filter { it != Surface.UNKNOWN }
            .joinToString(", ") { s -> "${ways.count { it.surface == s }} ${s.name.lowercase()}" }
    }

    companion object {
        const val ZOOM = 16
        private const val TILE_SIZE = 256
    }

    private var permissionLogged = false
    private var noMapsLogged = false

    /** Entrambi: solo WRITE_EXTERNAL_STORAGE dà il gruppo sdcard_rw che i file .map richiedono. */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun mapFiles(): List<File> =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name }.orEmpty()

    /**
     * Way classificate nei tile toccati dalle catene di punti [chains];
     * null se non c'è permesso, nessun file mappa, o nessun file copre la zona.
     */
    fun fetchWays(chains: List<List<Pair<Double, Double>>>): Fetch? {
        if (!hasPermission()) {
            if (!permissionLogged) { permissionLogged = true; logger.log("SURF ", "Mappe offline: permesso storage assente (servono lettura E scrittura) — concedilo dall'app e riavvia l'estensione; uso Overpass") }
            return null
        }
        val files = mapFiles()
        if (files.isEmpty()) {
            if (!noMapsLogged) { noMapsLogged = true; logger.log("SURF ", "Mappe offline: nessun file in ${mapsDir.absolutePath}; uso Overpass") }
            return null
        }
        val t0 = System.currentTimeMillis()
        val tiles = LinkedHashSet<SlippyTiles.Tile>()
        for (chain in chains) for ((lat, lon) in chain) tiles.add(SlippyTiles.tileFor(lat, lon, ZOOM))
        if (tiles.isEmpty()) return null

        val ways = LinkedHashMap<Long, OsmWay>()
        val tagKeys = java.util.TreeSet<String>()
        var filesUsed = 0
        var tilesRead = 0
        for (file in files) {
            var reader: MapFile? = null
            try {
                reader = MapFile(file)
                val bbox = reader.boundingBox()
                var usedThisFile = false
                for (t in tiles) {
                    val (cLat, cLon) = SlippyTiles.tileCenter(t)
                    if (!bbox.contains(cLat, cLon)) continue
                    val result = reader.readMapData(Tile(t.x, t.y, ZOOM.toByte(), TILE_SIZE)) ?: continue
                    usedThisFile = true
                    tilesRead++
                    for (w in result.ways) {
                        val tags = HashMap<String, String>(w.tags.size)
                        for (tag in w.tags) tags[tag.key] = tag.value
                        if (!tags.containsKey("highway")) continue
                        tagKeys.addAll(tags.keys)
                        val surface = SurfaceTagClassifier.classify(tags) ?: continue
                        val outer = w.latLongs.firstOrNull() ?: continue
                        if (outer.size < 2) continue
                        val pts = outer.map { it.latitude to it.longitude }
                        val id = geometryId(pts)
                        if (!ways.containsKey(id)) ways[id] = OsmWay(id, surface, pts)
                    }
                }
                if (usedThisFile) filesUsed++
            } catch (e: Exception) {
                logger.logError("SURF ", "Mappa offline ${file.name} non leggibile", e)
            } finally {
                runCatching { reader?.close() }
            }
        }
        if (tilesRead == 0) {
            logger.log("SURF ", "Mappe offline: nessun file copre la zona (${tiles.size} tile, ${files.size} file); uso Overpass")
            return null
        }
        return Fetch(ways.values.toList(), tilesRead, filesUsed, System.currentTimeMillis() - t0, tagKeys)
    }

    /**
     * Hash FNV-1a delle coordinate arrotondate a 1e-6°, ridotto a 62 bit: la chiave
     * (way, verso) di WaySpeedMemory è id×2+verso e con 63 bit andava in overflow
     * (trovato col replay del 06/09: chiavi irrisolvibili).
     */
    private fun geometryId(pts: List<Pair<Double, Double>>): Long {
        var h = -0x340d631b7bdddcdbL   // FNV offset basis
        fun mix(v: Long) {
            var x = v
            repeat(8) { h = (h xor (x and 0xff)) * 0x100000001b3L; x = x ushr 8 }
        }
        for ((lat, lon) in pts) {
            mix(Math.round(lat * 1e6)); mix(Math.round(lon * 1e6))
        }
        val id = h and 0x3FFFFFFFFFFFFFFFL
        return if (id == 0L) 1L else id
    }
}
