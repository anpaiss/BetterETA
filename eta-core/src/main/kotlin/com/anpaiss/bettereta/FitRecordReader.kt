package com.anpaiss.bettereta

/**
 * Lettore FIT minimo: estrae i messaggi `record` (posizione, quota, velocità,
 * distanza, orario) da un file FIT del Karoo. Niente SDK Garmin (non è su
 * Maven Central): il formato è documentato e per i record bastano ~150 righe.
 *
 * Formato: header (14 byte: dimensione, protocollo, profilo, data size, ".FIT",
 * crc), poi messaggi. Header di messaggio normale: bit6 = definizione, bit5 =
 * campi developer, bit0-3 = tipo locale. Header compresso (bit7): bit5-6 tipo
 * locale, bit0-4 offset di tempo. Le definizioni dichiarano dimensione e tipo
 * di ogni campo, quindi i campi ignoti si saltano. Valori "invalid" (tutti i
 * bit a 1 per unsigned, 0x7FFFFFFF per sint32) → null.
 */
object FitRecordReader {

    data class Record(
        val timeMs: Long?,
        val lat: Double?,
        val lon: Double?,
        val altM: Double?,
        val speedMs: Double?,
        val distM: Double?,
    )

    private const val MSG_RECORD = 20
    private const val SEMICIRCLE = 180.0 / (1L shl 31)
    private const val FIT_EPOCH_S = 631_065_600L      // 1989-12-31 00:00 UTC

    private class FieldDef(val num: Int, val size: Int, val baseType: Int)
    private class MsgDef(val globalNum: Int, val littleEndian: Boolean, val fields: List<FieldDef>, val devBytes: Int) {
        val dataSize = fields.sumOf { it.size } + devBytes
    }

    /** Record con almeno l'orario; posizione/quota/velocità possono mancare. */
    fun read(bytes: ByteArray): List<Record> {
        if (bytes.size < 14 || bytes[8] != '.'.code.toByte() || bytes[9] != 'F'.code.toByte()) return emptyList()
        val headerSize = bytes[0].toInt() and 0xff
        val dataSize = u32(bytes, 4, true)
        val end = minOf(bytes.size.toLong(), headerSize + dataSize).toInt()
        val defs = HashMap<Int, MsgDef>()
        val out = ArrayList<Record>()
        var lastTimeS = 0L
        var i = headerSize
        while (i < end) {
            val h = bytes[i].toInt() and 0xff
            i++
            if (h and 0x80 != 0) {
                // Header compresso: dati con tipo locale nei bit 5-6, offset tempo nei bit 0-4
                val local = (h shr 5) and 0x3
                val def = defs[local] ?: return out
                val offset = (h and 0x1f).toLong()
                lastTimeS = (lastTimeS and 0x1f.inv().toLong()) + offset + (if (offset < (lastTimeS and 0x1f)) 32 else 0)
                if (def.globalNum == MSG_RECORD) out.add(parseRecord(bytes, i, def, lastTimeS))
                i += def.dataSize
                continue
            }
            val local = h and 0x0f
            if (h and 0x40 != 0) {
                // Definizione
                if (i + 5 > end) break
                val little = bytes[i + 1].toInt() == 0
                val global = u16(bytes, i + 2, little).toInt()
                val nFields = bytes[i + 4].toInt() and 0xff
                var p = i + 5
                val fields = ArrayList<FieldDef>(nFields)
                repeat(nFields) {
                    fields.add(FieldDef(bytes[p].toInt() and 0xff, bytes[p + 1].toInt() and 0xff, bytes[p + 2].toInt() and 0xff))
                    p += 3
                }
                var devBytes = 0
                if (h and 0x20 != 0) {
                    val nDev = bytes[p].toInt() and 0xff
                    p++
                    repeat(nDev) { devBytes += bytes[p + 1].toInt() and 0xff; p += 3 }
                }
                defs[local] = MsgDef(global, little, fields, devBytes)
                i = p
            } else {
                val def = defs[local] ?: return out
                if (i + def.dataSize > end) break
                if (def.globalNum == MSG_RECORD) {
                    val r = parseRecord(bytes, i, def, lastTimeS)
                    r.timeMs?.let { lastTimeS = it / 1000 - FIT_EPOCH_S }
                    out.add(r)
                } else {
                    // Tiene traccia del tempo anche dagli altri messaggi (campo 253)
                    var p = i
                    for (f in def.fields) {
                        if (f.num == 253 && f.size == 4) u32(bytes, p, def.littleEndian).takeIf { it != 0xFFFFFFFFL }?.let { lastTimeS = it }
                        p += f.size
                    }
                }
                i += def.dataSize
            }
        }
        return out
    }

    private fun parseRecord(b: ByteArray, start: Int, def: MsgDef, fallbackTimeS: Long): Record {
        var p = start
        var timeS: Long? = null
        var lat: Double? = null; var lon: Double? = null
        var alt: Double? = null; var speed: Double? = null; var dist: Double? = null
        val le = def.littleEndian
        for (f in def.fields) {
            when {
                f.num == 253 && f.size == 4 -> u32(b, p, le).takeIf { it != 0xFFFFFFFFL }?.let { timeS = it }
                f.num == 0 && f.size == 4   -> s32(b, p, le).takeIf { it != 0x7FFFFFFF }?.let { lat = it * SEMICIRCLE }
                f.num == 1 && f.size == 4   -> s32(b, p, le).takeIf { it != 0x7FFFFFFF }?.let { lon = it * SEMICIRCLE }
                f.num == 2 && f.size == 2   -> u16(b, p, le).takeIf { it != 0xFFFFL }?.let { if (alt == null) alt = it / 5.0 - 500.0 }
                f.num == 78 && f.size == 4  -> u32(b, p, le).takeIf { it != 0xFFFFFFFFL }?.let { alt = it / 5.0 - 500.0 }
                f.num == 6 && f.size == 2   -> u16(b, p, le).takeIf { it != 0xFFFFL }?.let { if (speed == null) speed = it / 1000.0 }
                f.num == 73 && f.size == 4  -> u32(b, p, le).takeIf { it != 0xFFFFFFFFL }?.let { speed = it / 1000.0 }
                f.num == 5 && f.size == 4   -> u32(b, p, le).takeIf { it != 0xFFFFFFFFL }?.let { dist = it / 100.0 }
            }
            p += f.size
        }
        val t = timeS ?: fallbackTimeS.takeIf { it > 0 }
        return Record(t?.let { (it + FIT_EPOCH_S) * 1000L }, lat, lon, alt, speed, dist)
    }

    private fun u16(b: ByteArray, p: Int, le: Boolean): Long {
        val x = b[p].toLong() and 0xff; val y = b[p + 1].toLong() and 0xff
        return if (le) x or (y shl 8) else (x shl 8) or y
    }
    private fun u32(b: ByteArray, p: Int, le: Boolean): Long {
        var v = 0L
        for (k in 0 until 4) {
            val byte = b[p + (if (le) k else 3 - k)].toLong() and 0xff
            v = v or (byte shl (8 * k))
        }
        return v
    }
    private fun s32(b: ByteArray, p: Int, le: Boolean): Int = u32(b, p, le).toInt()
}
