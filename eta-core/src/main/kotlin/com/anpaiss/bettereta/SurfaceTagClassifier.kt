package com.anpaiss.bettereta

/**
 * Da tag OSM a classe di fondo, in tre classi. Condiviso da Overpass (JSON)
 * e dalle mappe offline Mapsforge (tag delle way nei tile): stessa regola,
 * qualunque sia la sorgente.
 *
 *  - PAVED: surface asfaltato/cemento/autobloccanti, o strada di rete senza tag
 *  - GRAVEL: ghiaia, sterrato battuto, tracktype grade1-3, track senza altro
 *  - TRAIL: terra, erba, sabbia, roccia, fango, tracktype grade4-5, path/bridleway
 *  - GNARLY: sentiero tecnico — `mtb_difficulty` >= 3 (tag proprio delle mappe
 *    Karoo, quello che disegna la difficoltà a schermo con la scala delle
 *    piste: 1 viola = sterrata percorribile da veicoli, 2 verde = facile,
 *    3 blu = intermedio, 4 nero = difficile, 5 rosso scuro = estremo) oppure
 *    `mtb:scale` >= 2 in OSM. Vince su tutto il resto. Soglia al 3 perché il
 *    tratto "gnarly" del giro 29/08 in mappa è un 3, e il 4-5 in zona quasi
 *    non esiste (2 way su 928 nei tile di quel giro). 1-2 su un track restano
 *    GRAVEL: il verde in sella è quasi sempre una forestale (Andrea, 06/09).
 *  - null: non classificabile (esclusa dal match)
 *
 * Fino al 05/09 le classi erano due, e nel "non asfalto" finivano insieme la
 * strada bianca battuta e il sentiero di radici: stesso difetto del bucket
 * "piano" prima dello sdoppiamento. Mappatura allineata a karoo-routegraph.
 */
object SurfaceTagClassifier {

    private val PAVED_SURFACES = setOf(
        "asphalt", "paved", "concrete", "concrete:plates", "concrete:lanes",
        "paving_stones", "sett", "chipseal", "metal", "wood", "cobblestone", "unhewn_cobblestone",
    )
    private val GRAVEL_SURFACES = setOf(
        "gravel", "fine_gravel", "compacted", "pebblestone", "unpaved", "grass_paver",
    )
    private val TRAIL_SURFACES = setOf(
        "ground", "dirt", "earth", "sand", "grass", "mud", "rock", "stone", "woodchips", "snow", "ice",
    )
    private val TRAIL_HIGHWAYS  = setOf("path", "bridleway")
    private val GRAVEL_HIGHWAYS = setOf("track")
    private val PAVED_HIGHWAYS = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "service", "living_street", "cycleway", "footway",
        "pedestrian", "motorway_link", "trunk_link", "primary_link",
        "secondary_link", "tertiary_link",
    )

    /** Difficoltà MTB 1..5 dai tag (Karoo `mtb_difficulty`, o OSM `mtb:scale` 0..6 → +1); 0 se assente. */
    fun mtbDifficulty(tags: Map<String, String>): Int {
        tags["mtb_difficulty"]?.trim()?.toIntOrNull()?.let { return it.coerceIn(1, 5) }
        val scale = tags["mtb:scale"]?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: return 0
        return (scale + 1).coerceIn(1, 5)
    }

    /** Classe di fondo dai tag della way; null se non classificabile. */
    fun classify(tags: Map<String, String>): Surface? {
        val surface   = tags["surface"]?.lowercase().orEmpty()
        val tracktype = tags["tracktype"]?.lowercase().orEmpty()
        val highway   = tags["highway"]?.lowercase().orEmpty()
        val mtb       = mtbDifficulty(tags)

        // 0. Difficoltà MTB da intermedia in su: tecnico, qualunque sia il fondo dichiarato
        if (mtb >= 3) return Surface.GNARLY

        // 1. Asfalto dichiarato: asfalto
        if (surface in PAVED_SURFACES) return Surface.PAVED
        // 2. tracktype, quanto è battuto uno sterrato, vale più del materiale:
        //    "ground" con grade2 è una forestale compatta (giro 05/09), non un sentiero
        when (tracktype) {
            "grade1"                    -> return Surface.PAVED
            "grade2", "grade3"          -> return Surface.GRAVEL
            "grade4", "grade5"          -> return Surface.TRAIL
        }
        // 3. Il materiale, quando c'è
        when {
            surface in GRAVEL_SURFACES -> return Surface.GRAVEL
            surface in TRAIL_SURFACES  -> return Surface.TRAIL
            // surface sconosciuto (es. "metal_grid"): si prosegue con gli altri tag
        }
        // 4. Difficoltà MTB bassa (1 viola, 2 verde) senza altri indizi: su un
        //    track è una forestale, altrimenti un sentiero facile
        if (mtb in 1..2) return if (highway in GRAVEL_HIGHWAYS) Surface.GRAVEL else Surface.TRAIL
        // 5. Il tipo di strada come proxy
        return when (highway) {
            in TRAIL_HIGHWAYS  -> Surface.TRAIL
            in GRAVEL_HIGHWAYS -> Surface.GRAVEL
            in PAVED_HIGHWAYS  -> Surface.PAVED
            else               -> null
        }
    }
}
