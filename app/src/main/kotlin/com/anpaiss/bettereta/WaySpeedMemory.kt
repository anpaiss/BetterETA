package com.anpaiss.bettereta

/**
 * Memoria dei tempi reali per tratto: per ogni (way OSM, verso) la velocità
 * media degli ultimi passaggi, normalizzata alla base del modello (divisa per
 * il fattore di giornata del momento), così un passaggio lento non la rovina.
 *
 * È il gradino sopra i bucket pendenza×fondo: dove la pendenza non spiega la
 * velocità (sentiero tecnico, centro abitato, sterrato rotto) un passaggio
 * precedente sullo stesso tratto vale più di qualsiasi media. In previsione
 * la velocità memorizzata, riscalata col fattore di giornata, si fonde con
 * quella del bucket pesata dal numero di passaggi: n/(n+1), quindi metà con
 * un passaggio, tre quarti con tre.
 *
 * Il verso è parte della chiave: la stessa way in salita e in discesa non ha
 * nulla in comune. Nessuna dipendenza Android: formato testuale, testabile.
 *
 * Un passaggio interrotto resta un passaggio: l'auto-pausa del Karoo chiude
 * il tratto in corso, e fino al 19/09 ogni spezzone entrava come passaggio a
 * sé (giro 19/09: una way con tre "passaggi" dallo stesso giro, 597 m, 54 m e
 * 85 m, lo spezzone di 28 s pesava quanto i quattro minuti e il conteggio
 * gonfiava il peso n/(n+1)). Gli spezzoni della stessa (way, verso) chiusi
 * entro [MERGE_WINDOW_MS] si fondono nell'ultimo passaggio, pesati sul tempo.
 */
class WaySpeedMemory(private val maxEntries: Int = MAX_ENTRIES) {

    class Estimate(val speedMs: Double, val passes: Int)

    // lastDistM: metri dell'ultimo passaggio, solo in memoria (0 dopo un riavvio
    // o per le voci lette da file: lì non si fonde, si aggiunge)
    private class Entry(val speeds: ArrayDeque<Double>, var passes: Int, var lastMs: Long, var lastDistM: Double = 0.0)

    private val entries = HashMap<Long, Entry>()

    val size: Int get() = entries.size

    /**
     * Registra un passaggio: [baseSpeedMs] è la velocità media già divisa per il
     * fattore di giornata, [distM] i metri percorsi. Se l'ultimo passaggio sulla
     * stessa chiave è di meno di [MERGE_WINDOW_MS] fa è lo stesso passaggio
     * interrotto da una pausa: si fonde (distanza totale / tempo totale) invece
     * di occupare un altro dei [KEEP_PASSES] posti. Restituisce true se ha fuso.
     */
    fun record(wayKey: Long, baseSpeedMs: Double, nowMs: Long, distM: Double = 0.0): Boolean {
        if (wayKey == 0L || baseSpeedMs <= 0.0) return false
        val e = entries.getOrPut(wayKey) { Entry(ArrayDeque(), 0, nowMs) }
        val sinceLast = nowMs - e.lastMs
        val merge = distM > 0.0 && e.lastDistM > 0.0 && e.speeds.isNotEmpty() && sinceLast in 0..MERGE_WINDOW_MS
        if (merge) {
            val prev  = e.speeds.removeLast()
            val total = e.lastDistM + distM
            e.speeds.addLast(total / (e.lastDistM / prev + distM / baseSpeedMs))
            e.lastDistM = total
        } else {
            e.speeds.addLast(baseSpeedMs)
            while (e.speeds.size > KEEP_PASSES) e.speeds.removeFirst()
            e.passes++
            e.lastDistM = distM
        }
        e.lastMs = nowMs
        if (entries.size > maxEntries) evictOldest()
        return merge
    }

    /** Velocità di base attesa sul tratto e passaggi registrati; null se mai percorso. */
    fun estimate(wayKey: Long): Estimate? {
        val e = entries[wayKey] ?: return null
        if (e.speeds.isEmpty()) return null
        return Estimate(e.speeds.average(), e.passes)
    }

    fun clear() = entries.clear()

    /** Sostituisce il contenuto con quello serializzato in [text]; testo non valido = memoria vuota. */
    fun loadFrom(text: String) {
        entries.clear()
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != HEADER) return
        runCatching {
            for (raw in lines) {
                val line = raw.trim()
                if (!line.startsWith("K ")) continue
                val p = line.split(' ', limit = 5)
                val speeds = p.getOrNull(4).orEmpty().split(';').filter { it.isNotBlank() }.map { it.toDouble() }
                if (speeds.isEmpty()) continue
                entries[p[1].toLong()] = Entry(ArrayDeque(speeds.takeLast(KEEP_PASSES)), p[2].toInt(), p[3].toLong())
            }
        }.onFailure { entries.clear() }
    }

    private fun evictOldest() {
        val victims = entries.entries.sortedBy { it.value.lastMs }.take(entries.size - maxEntries)
        for (v in victims) entries.remove(v.key)
    }

    // ── serializzazione ──────────────────────────────────────────────────
    //  WAYS1
    //  K <wayKey> <passes> <lastMs> v1;v2;v3

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        for ((k, e) in entries) {
            append("K ").append(k).append(' ').append(e.passes).append(' ').append(e.lastMs).append(' ')
                .append(e.speeds.joinToString(";") { "%.3f".format(java.util.Locale.ROOT, it) }).append('\n')
        }
    }

    companion object {
        const val KEEP_PASSES = 3
        // Soste più lunghe di così nei giri visti finora: 12 min. Due passaggi veri
        // sulla stessa way nello stesso verso in mezz'ora sono un anello corto: fonderli
        // dà comunque la media giusta.
        const val MERGE_WINDOW_MS = 30 * 60_000L
        const val MAX_ENTRIES = 5000
        private const val HEADER = "WAYS1"

        /** Chiave (way, verso): verso = ordine dei punti della way percorsi in avanti o all'indietro. */
        fun wayKey(wayId: Long, forward: Boolean): Long = wayId * 2 + (if (forward) 0 else 1)
        fun wayIdOf(key: Long): Long = key / 2
        fun isForward(key: Long): Boolean = key % 2 == 0L

        fun parse(text: String, maxEntries: Int = MAX_ENTRIES): WaySpeedMemory =
            WaySpeedMemory(maxEntries).apply { loadFrom(text) }
    }
}
