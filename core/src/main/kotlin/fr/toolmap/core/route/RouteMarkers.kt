package fr.toolmap.core.route

import fr.toolmap.core.track.PointFeature

/**
 * The markers of a computed route (`docs/specs/routing.md` §5.7): a green disc « A » on the start, a red disc with the
 * last letter on the end (none on a loop, which ends on « A »), the named stops with their waypoint icon and name.
 * Passages are not marked once saved.
 */
object RouteMarkers {
    const val START_COLOR: Int = 0xFF43A047.toInt()
    const val END_COLOR: Int = 0xFFE53935.toInt()

    /** Glyph ids of the letters: the map draws them as it draws the waypoint icons. */
    const val LETTER_PREFIX = "letter-"

    /** `A` to `Z`, then numbers: the label of the stop at [index]. */
    fun letter(index: Int): String = if (index < LETTERS) ('A' + index).toString() else (index + 1).toString()

    /** Markers of a saved route; [id] is given back by a tap (the negative route id, as for its line). */
    fun saved(
        id: Long,
        plan: RoutePlan,
    ): List<PointFeature> {
        val s = plan.stops
        if (s.size < 2) return emptyList()
        val out = ArrayList<PointFeature>()
        out +=
            PointFeature(id, s[0].name, s[0].lat, s[0].lon, colorArgb = START_COLOR, iconId = LETTER_PREFIX + letter(0))
        for (i in 1 until s.lastIndex) {
            val stop = s[i]
            if (stop.kind != StopKind.NAMED) continue
            out += PointFeature(id, stop.name, stop.lat, stop.lon, stop.sym)
        }
        // A loop (or an out and back) ends on its start: « A » alone, not a second letter drawn over it.
        if (plan.isLoop) return out
        val last = s.lastIndex
        out +=
            PointFeature(
                id,
                s[last].name,
                s[last].lat,
                s[last].lon,
                colorArgb = END_COLOR,
                iconId = LETTER_PREFIX + letter(last),
            )
        return out
    }

    private const val LETTERS = 26
}
