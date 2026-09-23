package com.anpaiss.bettereta

/**
 * The sample CSV, stripped of what says who rode where, for sending to the
 * developer. Everything the replay tools (tools/replay_*.py) need survives:
 * speed, gradient, surface, position along the route, our and the Karoo's
 * estimates, all per tick.
 *
 * What goes:
 *  - the route NAME in the `# ride` marker (place names), replaced by a short
 *    hash so rides on the same route still group together;
 *  - absolute time: the marker timestamp becomes a ride ordinal and `t_ms`
 *    restarts from 0 at every ride, so the file says how long, never when.
 *    A ride is a calendar day, as the replay tools count it: the Karoo re-emits
 *    the route several times per ride and every emission writes a marker;
 *  - free-form `# ...` notes.
 * What stays identifying, and is said so in the app: the gradient/speed profile
 * of a ride and its sequence of surfaces describe the route and the rider.
 * This is pseudonymous data for a known developer, not anonymous data.
 *
 * Pure: a sequence of lines in, a sequence of lines out. The rest of the
 * columns pass through untouched, so a file exported by an older build stays
 * readable by the same tools.
 */
object ShareExport {

    private val RIDE_RE = Regex("""^# ride (\S+ \S+) name=(.*) dist=(-?\d+) hash=(-?\d+)\s*$""")

    fun transform(lines: Sequence<String>): Sequence<String> = sequence {
        var ride = 0
        var rideDay = ""
        var t0 = -1L
        var headerSeen = false
        for (raw in lines) {
            val line = raw.trimEnd('\r', '\n')
            if (line.isEmpty()) continue
            if (line.startsWith("t_ms,")) {
                if (!headerSeen) { headerSeen = true; yield(line) }
                continue
            }
            val m = RIDE_RE.matchEntire(line)
            if (m != null) {
                val day = m.groupValues[1].substringBefore(' ')
                if (day != rideDay) { rideDay = day; ride++; t0 = -1L }
                yield("# ride ride-%03d name=%s dist=%s hash=%s".format(
                    java.util.Locale.ROOT, ride, routeTag(m.groupValues[2]), m.groupValues[3], m.groupValues[4]))
                continue
            }
            if (line.startsWith("#")) continue   // notes: free text, dropped
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val t = line.substring(0, comma).toLongOrNull() ?: continue
            if (t0 < 0L) t0 = t
            yield((t - t0).toString() + line.substring(comma))
        }
    }

    /** Eight hex digits of the route name: same route, same tag; nothing to read back. */
    fun routeTag(name: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in name.trim()) { h = (h xor c.code) * 0x01000193 }
        return "%08x".format(java.util.Locale.ROOT, h)
    }
}
