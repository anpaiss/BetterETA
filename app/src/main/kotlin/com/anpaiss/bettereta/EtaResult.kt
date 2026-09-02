package com.anpaiss.bettereta

/**
 * One end-of-ride comparison result: how far each ETA was from the moving time
 * actually needed, as a mean absolute percentage error.
 *
 * Persisted as one `|`-separated entry per ride, entries joined with `;`.
 *
 * Format history — fields are only ever appended, never reordered:
 *   v1  date|diffPct|errBePct|errKarooPct
 *   v2  ...|errPhysicsPct        shadow ETA using the physics fallback
 * A v1 entry parses with [errPhysicsPct] = null.
 */
data class EtaResult(
    val date: String,
    /** errKarooPct - errBePct: positive means BetterETA beat the stock ETA. */
    val diffPct: Int,
    val errBePct: Int,
    val errKarooPct: Int,
    val errPhysicsPct: Int? = null,
) {
    /** errBePct - errPhysicsPct: positive means the physics fallback would have done better. */
    val physicsGainPct: Int? get() = errPhysicsPct?.let { errBePct - it }

    fun encode(): String =
        listOf(date, diffPct, errBePct, errKarooPct, errPhysicsPct?.toString().orEmpty())
            .joinToString("|")

    companion object {
        /**
         * A mean error above this is physically impossible for a working ETA;
         * such entries are corrupt (the ms/s bug of 29/08 produced some) and are
         * dropped on read.
         */
        const val MAX_SANE_ERR_PCT = 300

        fun parse(raw: String): EtaResult? {
            val p = raw.split('|')
            val date  = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            val diff  = p.getOrNull(1)?.toIntOrNull() ?: return null
            val be    = p.getOrNull(2)?.toIntOrNull() ?: return null
            val karoo = p.getOrNull(3)?.toIntOrNull() ?: return null
            val phys  = p.getOrNull(4)?.toIntOrNull()
            if (be > MAX_SANE_ERR_PCT || karoo > MAX_SANE_ERR_PCT) return null
            if (phys != null && phys > MAX_SANE_ERR_PCT) return null
            return EtaResult(date, diff, be, karoo, phys)
        }

        /** Parses a stored history, silently dropping entries that fail [parse]. */
        fun parseHistory(history: String): List<EtaResult> =
            history.split(';').filter { it.isNotBlank() }.mapNotNull(::parse)

        fun encodeHistory(results: List<EtaResult>): String =
            results.joinToString(";") { it.encode() }
    }
}
