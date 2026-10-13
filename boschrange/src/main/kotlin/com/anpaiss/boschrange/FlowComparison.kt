package com.anpaiss.boschrange

import kotlin.math.abs

/**
 * Our arrival prediction against Bosch Flow's, on the rides where both were
 * recorded, to decide whether the project is worth going on with.
 *
 * Decision rule fixed on 13/10/2026 (Andrea):
 *  - after [MIN_RIDES] valid rides, mean absolute error in % points against the
 *    real arrival, at the start and mid-ride, ours against Flow's;
 *  - if at the start we do not beat Flow by at least [MIN_GAIN_PCT] points, the
 *    proposal is to stop the project;
 *  - if we beat Flow at the start only, consider reducing the function to
 *    pre-ride planning.
 *
 * A ride counts when it is valid for the regression (start SET, arrival entered)
 * and both the Flow and our prediction at the start are filled in.
 */
object FlowComparison {
    const val MIN_RIDES = 6
    const val MIN_GAIN_PCT = 3.0

    enum class Verdict { STOP, PLANNING_ONLY, GO_ON }

    class Result(val rides: Int, val maeStartOurs: Double, val maeStartFlow: Double,
                 val midRides: Int, val maeMidOurs: Double?, val maeMidFlow: Double?, val verdict: Verdict)

    fun comparable(rides: List<RideRecord>) =
        rides.filter { it.validForRegression && it.flowPredStartPct != null && it.ourPredStartPct != null }

    /** Null until [MIN_RIDES] comparable rides exist. */
    fun evaluate(rides: List<RideRecord>): Result? {
        val rs = comparable(rides)
        if (rs.size < MIN_RIDES) return null
        val maeStartOurs = rs.map { abs(it.ourPredStartPct!! - it.arrivalPct!!) }.average()
        val maeStartFlow = rs.map { abs(it.flowPredStartPct!! - it.arrivalPct!!).toDouble() }.average()
        val mid = rs.filter { it.flowPredMidPct != null && it.ourPredMidPct != null }
        val maeMidOurs = if (mid.isEmpty()) null else mid.map { abs(it.ourPredMidPct!! - it.arrivalPct!!) }.average()
        val maeMidFlow = if (mid.isEmpty()) null else mid.map { abs(it.flowPredMidPct!! - it.arrivalPct!!).toDouble() }.average()
        val verdict = when {
            maeStartFlow - maeStartOurs < MIN_GAIN_PCT -> Verdict.STOP
            maeMidOurs != null && maeMidFlow != null && maeMidOurs >= maeMidFlow -> Verdict.PLANNING_ONLY
            else -> Verdict.GO_ON
        }
        return Result(rs.size, maeStartOurs, maeStartFlow, mid.size, maeMidOurs, maeMidFlow, verdict)
    }

    fun report(rides: List<RideRecord>): String? {
        val res = evaluate(rides) ?: return null
        val sb = StringBuilder()
        sb.appendLine("boschRange vs Bosch Flow: errore medio assoluto sull'arrivo reale (punti %)")
        sb.appendLine("Giri confrontabili: ${res.rides} (validi per la regressione, con previsione Flow e nostra alla partenza)")
        sb.appendLine()
        sb.appendLine("                 nostra   Flow   vantaggio")
        sb.appendLine("alla partenza    ${f(res.maeStartOurs)}   ${f(res.maeStartFlow)}   ${f(res.maeStartFlow - res.maeStartOurs)}")
        if (res.maeMidOurs != null && res.maeMidFlow != null)
            sb.appendLine("a meta' giro     ${f(res.maeMidOurs)}   ${f(res.maeMidFlow)}   ${f(res.maeMidFlow - res.maeMidOurs)}   (${res.midRides} giri)")
        else
            sb.appendLine("a meta' giro     nessun giro con entrambe le previsioni")
        sb.appendLine()
        sb.appendLine("Giro per giro (arrivo reale | nostra partenza / Flow partenza | nostra meta' / Flow meta' @ km):")
        comparable(rides).forEach { r ->
            sb.appendLine("  ${r.routeName.ifEmpty { "senza route" }}: ${r.arrivalPct} % | ${f(r.ourPredStartPct!!)} / ${r.flowPredStartPct} | " +
                (if (r.ourPredMidPct != null && r.flowPredMidPct != null) "${f(r.ourPredMidPct!!)} / ${r.flowPredMidPct} @ ${f(r.flowShotMidKm)} km" else "-"))
        }
        sb.appendLine()
        sb.appendLine("Regola: dopo $MIN_RIDES giri validi, alla partenza dobbiamo battere Flow di almeno ${MIN_GAIN_PCT.toInt()} punti.")
        sb.appendLine(when (res.verdict) {
            Verdict.STOP -> "VERDETTO: alla partenza non battiamo Flow di ${MIN_GAIN_PCT.toInt()} punti -> PROPOSTA: fermare il progetto."
            Verdict.PLANNING_ONLY -> "VERDETTO: battiamo Flow solo alla partenza -> PROPOSTA: ridurre la funzione alla pianificazione pre-giro."
            Verdict.GO_ON -> "VERDETTO: battiamo Flow alla partenza e a meta' giro -> si prosegue."
        })
        return sb.toString()
    }

    private fun f(v: Double) = "%5.1f".format(java.util.Locale.ROOT, v)
}
