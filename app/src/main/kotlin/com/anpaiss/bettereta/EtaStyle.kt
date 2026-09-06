package com.anpaiss.bettereta

/** Stato del "pace vs plan" che gli stili traducono in colore. */
enum class Pace { EARLY, LATE, ON_PACE, UNKNOWN }

/** Colori risolti per un campo: fondo (tinta piena O drawable con bordo), testi. */
data class FieldColors(
    val bgColor: Int?,
    val bgRes: Int?,
    val label: Int,
    val value: Int,
    val delta: Int,
)

/**
 * Preset di stile dei campi, selezionabile dalla schermata dell'app.
 * Stessi nomi, colori e criterio di AxsRatio (TileStyle.kt): ogni abbinamento
 * testo/sfondo garantisce contrasto WCAG >= 4.5:1 (rapporto documentato a
 * fianco). Ogni stile esprime lo stato pace nel canale che usa in AxsRatio
 * per le zone HR: VIVID e PASTEL col fondo, OUTLINE col bordo, INK col colore
 * del testo del delta. KAROO è l'eccezione: bianco su nero come i campi nativi
 * del Karoo, nessun colore di stato — il segno lo dicono le parole nelle
 * didascalie (AHEAD/LATE) e il "+"/"-" del valore.
 */
enum class EtaStyle(val label: String) {
    VIVID("Vivid"),
    OUTLINE("Outline"),
    PASTEL("Pastel"),
    INK("Ink"),
    KAROO("Karoo standard"),
    ;

    /** Colori del campo; [pace] null = campo singolo senza stato (ETA, REM, TO FAR POINT). */
    fun colorsFor(pace: Pace?): FieldColors = when (this) {
        VIVID -> when (pace) {
            Pace.EARLY   -> solid(0xFF4CAF50.toInt(), BLACK)  // 7.6:1
            Pace.LATE    -> solid(0xFFD32F2F.toInt(), WHITE)  // 5.0:1
            Pace.UNKNOWN -> solid(0xFF9E9E9E.toInt(), BLACK)  // 7.8:1
            else         -> solid(ACCENT, BLACK)              // 6.7:1 (ON_PACE o campo singolo)
        }

        // Testo sempre bianco su quasi-nero (19:1); lo stato parla attraverso il bordo.
        OUTLINE -> {
            val res = when (pace) {
                Pace.EARLY   -> R.drawable.bg_outline_green
                Pace.LATE    -> R.drawable.bg_outline_red
                Pace.UNKNOWN -> R.drawable.bg_outline_grey
                else         -> R.drawable.bg_outline_accent
            }
            // Label grigia per gerarchia (7.7:1 su #101010); AxsRatio non ha label.
            FieldColors(null, res, GREY, WHITE, WHITE)
        }

        PASTEL -> when (pace) {
            Pace.EARLY   -> solid(0xFFA5D6A7.toInt(), BLACK)  // 12.8:1
            Pace.LATE    -> solid(0xFFEF9A9A.toInt(), BLACK)  //  9.8:1
            Pace.UNKNOWN -> solid(0xFFE0E0E0.toInt(), BLACK)  // 13.9:1
            else         -> solid(0xFFFFAB91.toInt(), BLACK)  // 11.5:1
        }

        // Valori in accent su fondo nero (6.9:1), delta nel colore dello stato
        // (min 5.3:1); bordo sottile per staccare il campo dalle mappe scure.
        INK -> FieldColors(null, R.drawable.bg_ink, GREY, ACCENT, paceAccent(pace))

        // Come i campi nativi: bianco su nero (21:1), didascalia grigia (7.8:1).
        KAROO -> FieldColors(BLACK, null, GREY, WHITE, WHITE)
    }

    /**
     * Colore del testo del margine MTA. Il tile della dashboard esprime già il
     * pace col fondo (VIVID/PASTEL), quindi il margine deve parlare col testo in
     * ogni stile: verde/rosso pieni su nero (INK/OUTLINE), scuri sui fondi chiari.
     */
    fun marginColor(pace: Pace?): Int = when (this) {
        KAROO -> WHITE
        INK, OUTLINE -> paceAccent(pace)
        VIVID, PASTEL -> when (pace) {
            Pace.EARLY -> 0xFF1B5E20.toInt()   // verde scuro: >= 4.5:1 su pastel, 3:1 su grigio vivid
            Pace.LATE  -> 0xFFB71C1C.toInt()   // rosso scuro
            else       -> colorsFor(pace).delta
        }
    }

    companion object {
        const val ACCENT = 0xFFFF5823.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY  = 0xFF9E9E9E.toInt()

        private fun solid(bg: Int, fg: Int) = FieldColors(bg, null, fg, fg, fg)

        /** Colori pieni dello stato pace (testo su fondo nero, come zoneAccent di AxsRatio). */
        private fun paceAccent(pace: Pace?): Int = when (pace) {
            Pace.EARLY   -> 0xFF4CAF50.toInt()  // 5.3:1 su nero
            Pace.LATE    -> 0xFFF44336.toInt()  // 5.7:1 su nero
            Pace.UNKNOWN -> GREY                // 7.8:1 su nero
            else         -> ACCENT              // 6.7:1 su nero
        }
    }
}
