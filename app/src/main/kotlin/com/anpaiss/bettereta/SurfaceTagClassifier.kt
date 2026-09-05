package com.anpaiss.bettereta

/**
 * Da tag OSM a classe di fondo, in tre classi. Condiviso da Overpass (JSON)
 * e dalle mappe offline Mapsforge (tag delle way nei tile): stessa regola,
 * qualunque sia la sorgente.
 *
 *  - PAVED: surface asfaltato/cemento/autobloccanti, o strada di rete senza tag
 *  - GRAVEL: ghiaia, sterrato battuto, tracktype grade1-3, track senza altro
 *  - TRAIL: terra, erba, sabbia, roccia, fango, tracktype grade4-5, path/bridleway
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

    /** Classe di fondo dai tag della way; null se non classificabile. */
    fun classify(tags: Map<String, String>): Surface? {
        val surface   = tags["surface"]?.lowercase().orEmpty()
        val tracktype = tags["tracktype"]?.lowercase().orEmpty()
        val highway   = tags["highway"]?.lowercase().orEmpty()

        // 1. Il tag surface, quando c'è, comanda
        if (surface.isNotBlank()) {
            when {
                surface in PAVED_SURFACES  -> return Surface.PAVED
                surface in GRAVEL_SURFACES -> return Surface.GRAVEL
                surface in TRAIL_SURFACES  -> return Surface.TRAIL
            }
            // surface sconosciuto (es. "metal_grid"): si prosegue con gli altri tag
        }
        // 2. tracktype: quanto è battuto uno sterrato
        when (tracktype) {
            "grade1"                    -> return Surface.PAVED
            "grade2", "grade3"          -> return Surface.GRAVEL
            "grade4", "grade5"          -> return Surface.TRAIL
        }
        // 3. Il tipo di strada come proxy
        return when (highway) {
            in TRAIL_HIGHWAYS  -> Surface.TRAIL
            in GRAVEL_HIGHWAYS -> Surface.GRAVEL
            in PAVED_HIGHWAYS  -> Surface.PAVED
            else               -> null
        }
    }
}
