package fr.toolmap.core.track

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads GeoJSON (RFC 7946): `Point`, `MultiPoint`, `LineString`, `MultiLineString`, `Polygon` (outer ring, as a
 * route), `MultiPolygon`, `GeometryCollection`, `Feature`, `FeatureCollection`. Names come from the `name` / `title`
 * property; the `stroke` and `stroke-width` properties of the simplestyle convention give the line style.
 */
object GeoJsonReader {
    fun read(text: String): TrackDocument {
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw TrackFormatException("Not a readable GeoJSON file: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw TrackFormatException("Not a readable GeoJSON file: ${e.message}", e)
            }
        val obj =
            root as? JsonObject ?: throw TrackFormatException("Not a GeoJSON file: the top level is not an object")
        val reader = Reader()
        reader.visit(obj, null)
        if (!reader.recognised) throw TrackFormatException("Not a GeoJSON file: no \"type\" of geometry or feature")
        return TrackDocument(
            name = obj["name"].string(),
            waypoints = reader.waypoints,
            routes = reader.routes,
            tracks = reader.tracks,
            issues = reader.issues.toList(),
            format = SourceFormat.GEOJSON,
        )
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private class Reader {
        val issues = IssueLog()
        val waypoints = ArrayList<Waypoint>()
        val routes = ArrayList<Route>()
        val tracks = ArrayList<Track>()
        var recognised = false

        fun visit(
            node: JsonObject,
            properties: JsonObject?,
        ) {
            when (val type = node["type"].string()) {
                "FeatureCollection" -> {
                    recognised = true
                    (node["features"] as? JsonArray)?.forEach { (it as? JsonObject)?.let { f -> visit(f, null) } }
                }

                "Feature" -> {
                    recognised = true
                    val geometry = node["geometry"] as? JsonObject ?: return
                    visit(geometry, node["properties"] as? JsonObject)
                }

                "GeometryCollection" -> {
                    recognised = true
                    (node["geometries"] as? JsonArray)?.forEach {
                        (it as? JsonObject)?.let { g ->
                            visit(g, properties)
                        }
                    }
                }

                "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon" -> {
                    recognised = true
                    geometry(type, node["coordinates"], properties)
                }

                else -> {
                    issues.add(TrackIssue.Kind.UNSUPPORTED_ELEMENT, type.orEmpty())
                }
            }
        }

        private fun geometry(
            type: String,
            coordinates: JsonElement?,
            props: JsonObject?,
        ) {
            val name = props?.get("name").string() ?: props?.get("title").string()
            val desc = props?.get("description").string() ?: props?.get("desc").string()
            val time = TrackTime.parse(props?.get("time").string())
            val style = style(props)
            when (type) {
                "Point" -> position(coordinates)?.let { waypoints += waypoint(it, name, desc, time) }
                "MultiPoint" -> line(coordinates).forEach { waypoints += waypoint(it, name, desc, time) }
                "LineString" -> addTrack(name, desc, listOf(line(coordinates, props)), style)
                "MultiLineString" -> addTrack(name, desc, array(coordinates).map { line(it, null) }, style)
                "Polygon" -> addRing(name, desc, array(coordinates).firstOrNull(), style)
                "MultiPolygon" -> array(coordinates).forEach { addRing(name, desc, array(it).firstOrNull(), style) }
            }
        }

        private fun waypoint(
            p: TrackPoint,
            name: String?,
            desc: String?,
            time: Long?,
        ) = Waypoint(p.lat, p.lon, p.ele, time, name = name, desc = desc)

        private fun addTrack(
            name: String?,
            desc: String?,
            segments: List<List<TrackPoint>>,
            style: PathStyle?,
        ) {
            val usable = segments.filter { it.isNotEmpty() }
            if (usable.isEmpty()) {
                issues.add(TrackIssue.Kind.EMPTY_ITEM_DROPPED, name.orEmpty())
            } else {
                tracks += Track(name = name, desc = desc, segments = usable, style = style)
            }
        }

        private fun addRing(
            name: String?,
            desc: String?,
            ring: JsonElement?,
            style: PathStyle?,
        ) {
            val points = line(ring).map { Waypoint(it.lat, it.lon, it.ele) }
            if (points.isEmpty()) return
            issues.add(TrackIssue.Kind.POLYGON_AS_ROUTE, name.orEmpty())
            routes += Route(name = name, desc = desc, points = points, style = style)
        }

        private fun array(e: JsonElement?): List<JsonElement> = (e as? JsonArray) ?: emptyList()

        /** A list of positions; `coordTimes` (togeojson) gives their times. */
        private fun line(
            e: JsonElement?,
            props: JsonObject? = null,
        ): List<TrackPoint> {
            val times = (props?.get("coordTimes") as? JsonArray)?.map { TrackTime.parse(it.string()) }
            val out = ArrayList<TrackPoint>()
            array(e).forEachIndexed { i, item ->
                val p = position(item)
                if (p == null) {
                    issues.add(TrackIssue.Kind.INVALID_COORDINATE, "position $i")
                } else {
                    out += if (times != null) p.copy(timeMillis = times.getOrNull(i)) else p
                }
            }
            return out
        }

        /** `[lon, lat]` or `[lon, lat, altitude]`. */
        private fun position(e: JsonElement?): TrackPoint? {
            val a = (e as? JsonArray) ?: return null
            if (a.size < 2) return null
            val lon = a[0].number() ?: return null
            val lat = a[1].number() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return TrackPoint(lat, lon, a.getOrNull(2)?.number())
        }

        private fun JsonElement.number(): Double? = (this as? JsonPrimitive)?.doubleOrNull

        private fun style(props: JsonObject?): PathStyle? {
            val color = props?.get("stroke").string()?.let { ExtensionStyle.parseColor(it) }
            val width = props?.get("stroke-width")?.number()?.toFloat()
            if (color == null && width == null) return null
            return PathStyle(colorArgb = color ?: PathStyle.DEFAULT_COLOR, widthDp = width ?: PathStyle.DEFAULT_WIDTH)
        }
    }
}
