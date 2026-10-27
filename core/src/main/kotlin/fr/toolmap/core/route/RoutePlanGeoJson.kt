package fr.toolmap.core.route

import fr.toolmap.core.track.EditorGeoJson
import fr.toolmap.core.track.TrackGeoJson
import fr.toolmap.core.track.TrackOps
import fr.toolmap.core.track.TrackPath
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.WaypointSymbols
import fr.toolmap.core.xml.JsonText

/**
 * GeoJSON of a route being edited (`docs/specs/routing.md` §5.7), for the editing layers of the map.
 *
 * Lines: one feature per leg, whose `st` is `r` (routed), `h` (by hand: dashed) or `p` (computing: grey dots); legs
 * by hand or computing also get a point at their middle carrying a `label` (none when that label is empty: a route
 * drawn all by hand needs no « à la main »). Handles: one feature per stop, whose `kind` is `start` (green, letter), `end` (red, letter), `named` (colour and icon of its symbol, with its `name`),
 * `pass` (small white disc) or `pass-sel`; `sel` marks the selected stop, `i` its index.
 */
object RoutePlanGeoJson {
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    /** Glyph ids the handles use: the map loads those images. */
    fun icons(plan: RoutePlan): Set<String> {
        val out = HashSet<String>()
        plan.stops.forEachIndexed { i, s ->
            when (kindOf(plan, i)) {
                "start" -> out += RouteMarkers.LETTER_PREFIX + RouteMarkers.letter(i)
                "end" -> if (!plan.isLoop) out += RouteMarkers.LETTER_PREFIX + RouteMarkers.letter(i)
                "named" -> out += WaypointSymbols.iconOf(s.sym).id
            }
        }
        return out
    }

    fun lines(
        plan: RoutePlan,
        computingLabel: String,
        byHandLabel: String,
    ): String {
        if (plan.legs.isEmpty()) return EMPTY
        val out = StringBuilder(plan.legs.sumOf { it.points.size } * 24 + 128)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        val labels = ArrayList<Pair<TrackPoint, String>>()
        for (leg in plan.legs) {
            if (leg.points.size < 2) continue
            val st =
                when {
                    leg.state == LegState.PENDING -> "p"
                    leg.isByHand -> "h"
                    else -> "r"
                }
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"st\":\"").append(st).append("\"}")
            out.append(",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            leg.points.forEachIndexed { i, p ->
                if (i > 0) out.append(',')
                position(out, p.lat, p.lon)
            }
            out.append("]}}")
            val label = if (st == "p") computingLabel else byHandLabel
            if (st != "r" && label.isNotEmpty()) {
                val path = TrackPath(listOf(leg.points))
                path.interpolate(path.length / 2)?.let { labels += it to label }
            }
        }
        for ((p, text) in labels) {
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"label\":").append(JsonText.quote(text)).append('}')
            out.append(",\"geometry\":{\"type\":\"Point\",\"coordinates\":")
            position(out, p.lat, p.lon)
            out.append("}}")
        }
        return out.append("]}").toString()
    }

    fun handles(
        plan: RoutePlan,
        selected: Int?,
        midpoints: Boolean = false,
    ): String {
        if (plan.stops.isEmpty()) return EMPTY
        val all = plan.stops.size <= EditorGeoJson.MAX_HANDLES
        val loop = plan.isLoop
        val out = StringBuilder(plan.stops.size * 160 + 64)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        // Drawing by hand: the middle of each straight leg is a handle that inserts a stop there (as for a track).
        if (all && midpoints) {
            plan.legs.forEachIndexed { k, leg ->
                if (leg.mode != LegMode.STRAIGHT) return@forEachIndexed
                val m = midpoint(plan, k)
                if (!first) out.append(',')
                first = false
                out.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"m\",\"leg\":").append(k)
                out.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
                position(out, m.lat, m.lon)
                out.append("}}")
            }
        }
        // Drawn in order: the start last, so that it stays on top of the end of a loop.
        val order = plan.stops.indices.reversed()
        for (i in order) {
            val s = plan.stops[i]
            val base = kindOf(plan, i)
            val sel = i == selected
            // Thousands of passages are no help: only the ends, the named stops and the selected one then.
            if (!all && base == "pass" && !sel) continue
            val kind = if (base == "pass" && sel) "pass-sel" else base
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"").append(kind).append('"')
            out.append(",\"i\":").append(i).append(",\"sel\":").append(sel)
            when (base) {
                "start" -> out.append(",\"color\":\"").append(hex(RouteMarkers.START_COLOR)).append('"')
                "end" -> out.append(",\"color\":\"").append(hex(RouteMarkers.END_COLOR)).append('"')
                "named" -> out.append(",\"color\":\"").append(hex(WaypointSymbols.colorOf(s.sym))).append('"')
            }
            when (base) {
                // A loop ends on its start: « A » alone, not a second letter drawn through it.
                "start", "end" -> {
                    if (base == "start" || !loop) {
                        out.append(
                            ",\"icon\":\"",
                        ).append(RouteMarkers.LETTER_PREFIX + RouteMarkers.letter(i)).append('"')
                    }
                }

                "named" -> {
                    out.append(",\"icon\":\"").append(WaypointSymbols.iconOf(s.sym).id).append('"')
                }
            }
            s.name?.takeIf { it.isNotBlank() }?.let { out.append(",\"name\":").append(JsonText.quote(it)) }
            out.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
            position(out, s.lat, s.lon)
            out.append("}}")
        }
        return out.append("]}").toString()
    }

    /** The middle of the straight line of leg [leg] (where its midpoint handle stands). */
    fun midpoint(
        plan: RoutePlan,
        leg: Int,
    ): TrackPoint = TrackOps.midpoint(plan.stops[leg].point, plan.stops[leg + 1].point)

    private fun kindOf(
        plan: RoutePlan,
        i: Int,
    ): String =
        when {
            i == 0 -> "start"
            i == plan.stops.lastIndex -> "end"
            plan.stops[i].kind == StopKind.NAMED -> "named"
            else -> "pass"
        }

    private fun position(
        out: StringBuilder,
        lat: Double,
        lon: Double,
    ) {
        out.append('[')
        TrackGeoJson.appendDegrees(out, lon)
        out.append(',')
        TrackGeoJson.appendDegrees(out, lat)
        out.append(']')
    }

    private fun hex(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)
}
