package com.anpaiss.bettereta

import kotlin.math.abs

/**
 * Disaccordo persistente tra la nostra posizione lungo la route e quella del
 * Karoo mentre tutti e due diciamo "in route". Giro 11/10: anello in cima
 * saltato, strada condivisa tra andata e ritorno; il Karoo si è riallineato
 * in un tick, noi siamo rimasti sul ramo di andata (snap locale coerente con
 * la stima, riaggancio globale bloccato dalla guardia di plausibilità) per
 * 7,5 minuti con l'ETA a +50..+70 %.
 *
 * Scatta quando per [confirmTicks] tick di fila il Karoo è oltre [minGapM]
 * da noi e nel frattempo avanza davvero: almeno [minAdvanceM] sia secondo lui
 * sia secondo la nostra velocità × tempo, e lui almeno la metà di noi. Un
 * Karoo fermo a "fine percorso" su un anello con partenza = arrivo (20/09) o
 * appena riavviato (04/10) non avanza e non passa.
 */
class HintDisagreeWatch(
    private val minGapM: Double = 1000.0,
    private val confirmTicks: Int = 60,
    private val minAdvanceM: Double = 100.0,
) {
    private var ticks = 0
    private var karooStartM = 0.0
    private var travelledM = 0.0   // Σ velocità × dt dall'inizio del disaccordo

    fun reset() { ticks = 0 }

    /** Un tick in route con suggerimento del Karoo; true = riagganciare sul suggerimento. */
    fun onTick(oursM: Double, karooM: Double, speedMs: Double, tickSec: Double): Boolean {
        if (abs(oursM - karooM) <= minGapM) { ticks = 0; return false }
        if (ticks == 0) { karooStartM = karooM; travelledM = 0.0 }
        ticks++
        travelledM += speedMs * tickSec
        if (ticks < confirmTicks) return false
        val karooAdvanceM = karooM - karooStartM
        return travelledM >= minAdvanceM && karooAdvanceM >= minAdvanceM && karooAdvanceM >= travelledM / 2
    }
}
