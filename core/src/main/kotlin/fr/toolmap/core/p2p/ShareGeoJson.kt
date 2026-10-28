package fr.toolmap.core.p2p

import fr.toolmap.core.xml.JsonText
import java.util.Locale

/**
 * The members of the group as map features. The member id is a 64-bit number, which GeoJSON consumers read as a
 * double and lose digits of, so it travels as a string (`mid`).
 */
object ShareGeoJson {
    /** A position older than this is drawn faded: the member may have moved a lot since. */
    const val OLD_FIX_MS = 10 * 60 * 1000L

    private const val RGB_MASK = 0xFFFFFF
    private const val MS_PER_S = 1000L

    /**
     * One point per other member with a known position; [sos] are the ids that called for help. A member that only
     * listens (P22.3) has no point, unless its alert brought a position.
     */
    fun members(
        members: List<MemberView>,
        sos: Set<Long>,
        nowMs: Long,
    ): String {
        val out = StringBuilder(members.size * 160 + 64)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (m in members) {
            val fix = m.fix
            if (m.isSelf || fix == null || (m.sharing == false && m.id !in sos)) continue
            if (!first) out.append(',')
            first = false
            val old =
                m.status == MemberStatus.LOST || m.status == MemberStatus.LEFT ||
                    nowMs - fix.timeSec * MS_PER_S > OLD_FIX_MS
            out.append("{\"type\":\"Feature\",\"properties\":{\"mid\":").append(JsonText.quote(m.id.toString()))
            out.append(",\"name\":").append(JsonText.quote(m.name))
            out.append(",\"color\":\"").append(hex(m.color)).append('"')
            out.append(",\"img\":").append(JsonText.quote(MemberLook.imageKey(m.color, m.name, m.picture)))
            out.append(",\"old\":").append(old)
            out.append(",\"sos\":").append(m.id in sos)
            out.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            out.append(coordinate(fix.lon)).append(',').append(coordinate(fix.lat)).append("]}}")
        }
        out.append("]}")
        return out.toString()
    }

    /** The recent positions of each other member as a line, in the member's colour. */
    fun trails(members: List<MemberView>): String {
        val out = StringBuilder(64)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (m in members) {
            if (m.isSelf || m.trail.size < 2 || m.sharing == false) continue
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"color\":\"").append(hex(m.color))
            out.append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            m.trail.forEachIndexed { i, f ->
                if (i > 0) out.append(',')
                out.append('[').append(coordinate(f.lon)).append(',').append(coordinate(f.lat)).append(']')
            }
            out.append("]}}")
        }
        out.append("]}")
        return out.toString()
    }

    private fun hex(argb: Int) = String.format(Locale.ROOT, "#%06X", argb and RGB_MASK)

    private fun coordinate(v: Double) = String.format(Locale.ROOT, "%.7f", v)
}
