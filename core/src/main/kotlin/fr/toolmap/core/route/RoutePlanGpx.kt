package fr.toolmap.core.route

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.core.xml.XmlText

/**
 * The plan of a computed route in the extensions of its GPX `<rte>` (`docs/specs/routing.md` §7.3). The `<rtept>` hold
 * the whole geometry, readable by any app; the plan says which of them are stops and how each leg was made:
 *
 * ```xml
 * <extensions><tm:plan xmlns:tm="https://toolmap.app/gpx/1">
 *   <tm:stop lat="45.9" lon="6.8" kind="named" name="Refuge" sym="Lodge"/>
 *   <tm:leg profile="hiking-mountain" mode="routed" n="152" time="5400000"/>
 *   <tm:stop lat="45.8" lon="6.9" kind="passage"/>
 *   <tm:zone lat="45.85" lon="6.86" r="250"/>
 * </tm:plan></extensions>
 * ```
 *
 * `n` is the number of points of the leg, both stops included: the geometry is cut back into legs with it. `tm:zone`
 * are the zones to avoid (P19.8), their radius in meters. The plan is kept in the route's extras, so the library, the
 * backups and the exports carry it with no change.
 */
object RoutePlanGpx {
    const val NAMESPACE = "https://toolmap.app/gpx/1"

    private val PLAN = Regex("""<(?:[\w.-]+:)?plan\b[^>]*>[\s\S]*?</(?:[\w.-]+:)?plan>""")
    private val CHILD = Regex("""<(?:[\w.-]+:)?(stop|leg|zone)\b([^>]*?)/?>""")
    private val ATTR = Regex("""([\w.:-]+)\s*=\s*"([^"]*)"""")
    private val EMPTY_EXTENSIONS =
        Regex("""^<(?:[\w.-]+:)?extensions\b[^>]*>\s*</(?:[\w.-]+:)?extensions>$|^<(?:[\w.-]+:)?extensions\b[^>]*/>$""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""")

    /** Positions read back must fall this close to the stops for the plan to be trusted. */
    private const val MATCH_METERS = 1.0

    /** The `<tm:plan>` element of [plan]. */
    fun element(plan: RoutePlan): String {
        val out = StringBuilder()
        out.append("<tm:plan xmlns:tm=\"").append(NAMESPACE).append("\">")
        plan.stops.forEachIndexed { i, s ->
            out.append("<tm:stop lat=\"").append(s.lat).append("\" lon=\"").append(s.lon).append('"')
            out.append(" kind=\"").append(if (s.kind == StopKind.NAMED) "named" else "passage").append('"')
            s.name?.let { out.append(" name=\"").append(XmlText.attribute(it)).append('"') }
            s.sym?.let { out.append(" sym=\"").append(XmlText.attribute(it)).append('"') }
            out.append("/>")
            plan.legs.getOrNull(i)?.let { leg ->
                out.append("<tm:leg profile=\"").append(XmlText.attribute(leg.profile)).append('"')
                out.append(" mode=\"").append(if (leg.mode == LegMode.STRAIGHT) "straight" else "routed").append('"')
                out.append(" n=\"").append(leg.points.size).append('"')
                if (leg.state != LegState.OK) out.append(" state=\"").append(stateName(leg.state)).append('"')
                leg.timeMillis?.let { out.append(" time=\"").append(it).append('"') }
                out.append("/>")
            }
        }
        for (z in plan.zones) {
            out.append("<tm:zone lat=\"").append(z.lat).append("\" lon=\"").append(z.lon).append('"')
            out.append(" r=\"").append(z.radiusMeters).append("\"/>")
        }
        return out.append("</tm:plan>").toString()
    }

    /** The route's extras with [plan] in place of the one they held (none when [plan] is `null`). */
    fun attach(
        extras: List<RawXml>,
        plan: RoutePlan?,
    ): List<RawXml> {
        val cleaned =
            extras.mapNotNull { raw ->
                if (!isOurs(raw)) return@mapNotNull raw
                val rest = raw.xml.replace(PLAN, "").trim()
                if (EMPTY_EXTENSIONS.matches(rest)) null else RawXml(rest)
            }
        if (plan == null) return cleaned
        val element = element(plan)
        val at = cleaned.indexOfFirst { it.localName == "extensions" && it.xml.endsWith(">") && "</" in it.xml }
        if (at < 0) return cleaned + RawXml("<extensions>$element</extensions>")
        val host = cleaned[at].xml
        val close = host.lastIndexOf("</")
        return cleaned.toMutableList().apply {
            this[at] =
                RawXml(host.substring(0, close) + element + host.substring(close))
        }
    }

    /**
     * The plan of a route whose points are [geometry] and extensions [extras], or `null` when it has none, or when the
     * points no longer match it (the route was changed by another app): it is then a route drawn by hand.
     */
    fun planOf(
        geometry: List<TrackPoint>,
        extras: List<RawXml>,
    ): RoutePlan? {
        val raw = extras.firstOrNull { isOurs(it) } ?: return null
        val body = PLAN.find(raw.xml)?.value ?: return null
        val stops = ArrayList<RouteStop>()
        val legs = ArrayList<Triple<Map<String, String>, Int, Long?>>()
        val zones = ArrayList<AvoidZone>()
        for (m in CHILD.findAll(body)) {
            val a = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            if (m.groupValues[1] == "zone") {
                val lat = a["lat"]?.toDoubleOrNull() ?: return null
                val lon = a["lon"]?.toDoubleOrNull() ?: return null
                val r = a["r"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                zones += AvoidZone(lat, lon, r)
            } else if (m.groupValues[1] == "stop") {
                val lat = a["lat"]?.toDoubleOrNull() ?: return null
                val lon = a["lon"]?.toDoubleOrNull() ?: return null
                val named = a["kind"] == "named"
                stops += RouteStop(lat, lon, if (named) StopKind.NAMED else StopKind.PASSAGE, a["name"], a["sym"])
            } else {
                val n = a["n"]?.toIntOrNull() ?: return null
                legs += Triple(a, n, a["time"]?.toLongOrNull())
            }
        }
        if (stops.isEmpty() || legs.size != stops.size - 1) return null
        if (stops.size ==
            1
        ) {
            return if (geometry.size == 1 &&
                matches(geometry[0], stops[0])
            ) {
                RoutePlan(stops, zones = zones)
            } else {
                null
            }
        }
        if (legs.any { it.second < 2 } || legs.sumOf { it.second - 1 } + 1 != geometry.size) return null
        var at = 0
        val built =
            legs.mapIndexed { i, (a, n, time) ->
                val points = geometry.subList(at, at + n).toList()
                at += n - 1
                if (!matches(points.first(), stops[i]) || !matches(points.last(), stops[i + 1])) return null
                RouteLeg(
                    profile = a["profile"] ?: RoutingProfiles.HIKING,
                    mode = if (a["mode"] == "straight") LegMode.STRAIGHT else LegMode.ROUTED,
                    state = stateOf(a["state"]),
                    points = points,
                    timeMillis = time,
                )
            }
        return RoutePlan(stops, built, zones)
    }

    /** The `<rtept>` of a planned route: its whole geometry, the points on a named stop with its name and icon. */
    fun routePoints(plan: RoutePlan): List<Waypoint> {
        val geometry = plan.geometry
        val named = HashMap<Int, RouteStop>()
        plan.stops.forEachIndexed { i, s -> if (s.kind == StopKind.NAMED) named[plan.stopIndices[i]] = s }
        return geometry.mapIndexed { i, p ->
            val stop = named[i]
            Waypoint(p.lat, p.lon, p.ele, name = stop?.name, sym = stop?.sym)
        }
    }

    /**
     * The `<rtept>` of a route wholly drawn by hand, stored without its plan (ADR-065): its stops, each with the
     * elevation its legs found for it, named stops with their name and icon.
     */
    fun stopPoints(plan: RoutePlan): List<Waypoint> {
        val geometry = plan.geometry
        return plan.stops.mapIndexed { i, s ->
            val named = s.kind == StopKind.NAMED
            Waypoint(
                s.lat,
                s.lon,
                geometry.getOrNull(plan.stopIndices[i])?.ele,
                name = s.name.takeIf { named },
                sym = s.sym.takeIf { named },
            )
        }
    }

    private fun isOurs(raw: RawXml): Boolean = NAMESPACE in raw.xml || "<tm:plan" in raw.xml

    private fun matches(
        p: TrackPoint,
        s: RouteStop,
    ): Boolean = Geodesy.distance(p.lat, p.lon, s.lat, s.lon) <= MATCH_METERS

    private fun stateName(state: LegState): String =
        when (state) {
            LegState.OK -> "ok"
            LegState.PENDING -> "pending"
            LegState.NO_DATA -> "nodata"
            LegState.FAILED -> "failed"
        }

    private fun stateOf(name: String?): LegState =
        when (name) {
            "pending" -> LegState.PENDING
            "nodata" -> LegState.NO_DATA
            "failed" -> LegState.FAILED
            else -> LegState.OK
        }

    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return ENTITY.replace(value) { m ->
            val e = m.groupValues[1]
            when {
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                else -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }
}
