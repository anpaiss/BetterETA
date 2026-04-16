package com.anpaiss.karoobettereta

import android.content.Context
import android.os.Environment
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * Legge i waypoint con quota dal percorso Komoot caricato sul Karoo.
 *
 * Strategia:
 *   1. Scansiona le directory note di Karoo alla ricerca di file .gpx
 *   2. Prende il file modificato più di recente (= percorso attivo)
 *   3. Effettua il parsing GPX con XmlPullParser (no dipendenze esterne)
 *
 * TODO: quando il Karoo SDK esporrà i waypoint del percorso attivo via
 *       consumerFlow<OnNavigationState>() o simile, sostituire la lettura
 *       da storage con quella API per avere aggiornamento in tempo reale
 *       della posizione sul percorso.
 *
 * Paths verificati su Karoo 3 (aggiornare se cambiano con OS update):
 *   /sdcard/karoo/routes/
 *   /storage/emulated/0/karoo/routes/
 */
class RouteReader(private val context: Context) {

    /**
     * Flow che emette la lista di waypoint del percorso trovato.
     * Emette una lista vuota se non trova nulla.
     */
    fun waypointFlow(): Flow<List<RouteWaypoint>> = flow {
        emit(findAndParse())
    }.flowOn(Dispatchers.IO)

    // ── private ──────────────────────────────────────────────────────

    private fun findAndParse(): List<RouteWaypoint> {
        val searchDirs = listOf(
            File("/sdcard/karoo/routes"),
            File("/storage/emulated/0/karoo/routes"),
            File(Environment.getExternalStorageDirectory(), "karoo/routes"),
        )

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val gpx = dir.listFiles { f -> f.extension.equals("gpx", ignoreCase = true) }
                ?.maxByOrNull { it.lastModified() }
                ?: continue
            val pts = parseGpx(gpx)
            if (pts.isNotEmpty()) return pts
        }
        return emptyList()
    }

    /**
     * Parsing GPX standard: supporta <trkpt>, <rtept> e <wpt>.
     * L'elevazione viene letta dal tag figlio <ele>.
     */
    private fun parseGpx(file: File): List<RouteWaypoint> {
        val result = mutableListOf<RouteWaypoint>()
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(file.inputStream(), null)

            var lat     = Double.NaN
            var lon     = Double.NaN
            var ele     = 0.0
            var inPoint = false
            var inEle   = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when {
                    event == XmlPullParser.START_TAG &&
                    parser.name in listOf("trkpt", "rtept", "wpt") -> {
                        lat     = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon     = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        ele     = 0.0
                        inPoint = true
                        inEle   = false
                    }
                    event == XmlPullParser.START_TAG &&
                    parser.name == "ele" && inPoint -> {
                        inEle = true
                    }
                    event == XmlPullParser.TEXT && inEle -> {
                        ele   = parser.text.trim().toDoubleOrNull() ?: 0.0
                        inEle = false
                    }
                    event == XmlPullParser.END_TAG &&
                    parser.name in listOf("trkpt", "rtept", "wpt") -> {
                        if (inPoint && !lat.isNaN() && !lon.isNaN()) {
                            result.add(RouteWaypoint(lat = lat, lon = lon, elevationM = ele))
                        }
                        inPoint = false
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Parsing fallito: restituisce lista vuota
        }
        return result
    }
}
