package com.anpaiss.boschrange

/**
 * One com.bosch.ebike.LiveData message of the Bosch Live Data Interface
 * (LDI V1.0, characteristic eb21), decoded by hand: proto3 with varint fields
 * only, so no protobuf library. A field absent from the message is null: the
 * first message after the connection carries everything, the notifications
 * that follow only what changed (seen on the Wild ST20, 30/10/2026).
 */
class LiveData(
    val speedKmh: Double? = null,
    val cadenceRpm: Int? = null,
    val riderPowerW: Int? = null,
    val lux: Double? = null,
    /** Battery state of charge, 0..100. */
    val socPct: Int? = null,
    val timeS: Long? = null,
    val odometerM: Long? = null,
    val lightOn: Boolean? = null,
    val locked: Boolean? = null,
    val chargerConnected: Boolean? = null,
    val lightReserve: Boolean? = null,
    val diagnosisActive: Boolean? = null,
    val notDriving: Boolean? = null,
) {
    override fun toString(): String = listOfNotNull(
        speedKmh?.let { "speed=%.2f km/h".format(it) },
        cadenceRpm?.let { "cadence=$it" },
        riderPowerW?.let { "power=$it W" },
        lux?.let { "lux=%.1f".format(it) },
        socPct?.let { "SOC=$it %" },
        timeS?.let { "time=$it" },
        odometerM?.let { "odometer=$it m" },
        lightOn?.let { "light=${if (it) "on" else "off"}" },
        locked?.let { "locked=$it" },
        chargerConnected?.let { "charger=$it" },
        lightReserve?.let { "light_reserve=$it" },
        diagnosisActive?.let { "diagnosis=$it" },
        notDriving?.let { "not_driving=$it" },
    ).joinToString(" ")

    companion object {
        /** Throws on a malformed message (a truncated one, LDI-003, when the MTU stayed at 23). */
        fun decode(b: ByteArray): LiveData {
            var speed: Double? = null; var cadence: Int? = null; var power: Int? = null; var lux: Double? = null
            var soc: Int? = null; var time: Long? = null; var odo: Long? = null; var light: Boolean? = null
            var locked: Boolean? = null; var charger: Boolean? = null; var reserve: Boolean? = null
            var diag: Boolean? = null; var notDriving: Boolean? = null
            var i = 0
            while (i < b.size) {
                val (tag, n) = varint(b, i); i += n
                val field = (tag shr 3).toInt()
                when ((tag and 7L).toInt()) {
                    0 -> {
                        val (v, m) = varint(b, i); i += m
                        when (field) {
                            1 -> speed = v / 100.0
                            2 -> cadence = v.toInt()
                            5 -> power = v.toInt()
                            9 -> lux = v / 1000.0
                            10 -> soc = v.toInt().coerceIn(0, 100)
                            11 -> time = v
                            12 -> odo = v
                            17 -> light = v == 2L          // enum: 1 = off, 2 = on
                            21 -> locked = v != 0L
                            22 -> charger = v != 0L
                            23 -> reserve = v != 0L
                            24 -> diag = v != 0L
                            25 -> notDriving = v != 0L
                        }
                    }
                    1 -> i += 8
                    2 -> { val (len, m) = varint(b, i); i += m + len.toInt() }
                    5 -> i += 4
                    else -> throw IllegalArgumentException("wire type ${tag and 7} at $i")
                }
            }
            return LiveData(speed, cadence, power, lux, soc, time, odo, light, locked, charger, reserve, diag, notDriving)
        }

        private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L; var shift = 0; var i = start
            while (true) {
                if (i >= b.size) throw IllegalArgumentException("truncated varint at $start")
                val byte = b[i].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                i++
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long at $start")
            }
            return result to (i - start)
        }
    }
}
