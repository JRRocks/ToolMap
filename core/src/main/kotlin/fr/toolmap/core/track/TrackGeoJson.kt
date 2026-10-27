package fr.toolmap.core.track

import fr.toolmap.core.xml.JsonText

/** A line to draw: one track or route. [id] is the database id, given back by taps on the map. */
class LineFeature(
    val id: Long,
    val colorArgb: Int,
    val widthDp: Float,
    val dashed: Boolean,
    val segments: List<List<TrackPoint>>,
    /** Direction arrows along the line. */
    val arrows: Boolean = false,
)

/** A place to draw. */
class PointFeature(
    val id: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    /** GPX symbol name; decides the colour and the icon of the marker. */
    val sym: String? = null,
    /** Colour of the disc instead of the symbol's (the ends of a computed route). */
    val colorArgb: Int? = null,
    /** Glyph instead of the symbol's (a letter on the ends of a computed route). */
    val iconId: String? = null,
)

/**
 * GeoJSON text for MapLibre's `GeoJsonSource`. Built by hand rather than with a JSON tree: 50 000 points are a few
 * hundred kilobytes, written in a few tens of milliseconds, on a background thread. Lines are simplified to
 * [toleranceMeters] first (invisible at any zoom that shows a whole track) and positions are written with six
 * decimals (about 10 cm).
 */
object TrackGeoJson {
    fun lines(
        items: List<LineFeature>,
        toleranceMeters: Double = 1.0,
    ): String {
        val out = StringBuilder(items.sumOf { f -> f.segments.sumOf { it.size } } * 24 + 64)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (item in items) {
            val segments = item.segments.map { PathSimplifier.simplify(it, toleranceMeters) }.filter { it.size >= 2 }
            if (segments.isEmpty()) continue
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"id\":").append(item.id)
            out.append(",\"color\":\"").append(hex(item.colorArgb)).append('"')
            out.append(",\"opacity\":").append(opacity(item.colorArgb))
            out.append(",\"width\":").append(item.widthDp)
            out.append(",\"dashed\":").append(item.dashed)
            out.append(",\"arrows\":").append(item.arrows).append("},\"geometry\":")
            if (segments.size == 1) {
                out.append("{\"type\":\"LineString\",\"coordinates\":")
                coordinates(out, segments[0])
            } else {
                out.append("{\"type\":\"MultiLineString\",\"coordinates\":[")
                segments.forEachIndexed { i, s ->
                    if (i > 0) out.append(',')
                    coordinates(out, s)
                }
                out.append(']')
            }
            out.append("}}")
        }
        return out.append("]}").toString()
    }

    fun points(items: List<PointFeature>): String {
        val out = StringBuilder(items.size * 96 + 64)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        items.forEachIndexed { i, p ->
            if (i > 0) out.append(',')
            out.append("{\"type\":\"Feature\",\"properties\":{\"id\":").append(p.id)
            out.append(",\"name\":").append(JsonText.quote(p.name.orEmpty()))
            val icon = WaypointSymbols.iconOf(p.sym)
            out.append(",\"color\":\"").append(hex(p.colorArgb ?: icon.colorArgb)).append('"')
            out.append(",\"icon\":\"").append(p.iconId ?: icon.id).append('"')
            out.append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            appendDegrees(out, p.lon)
            out.append(',')
            appendDegrees(out, p.lat)
            out.append("]}}")
        }
        return out.append("]}").toString()
    }

    /** One point, for the cursor that follows the elevation profile. */
    fun point(
        lat: Double,
        lon: Double,
    ): String = points(listOf(PointFeature(0, null, lat, lon)))

    private fun coordinates(
        out: StringBuilder,
        points: List<TrackPoint>,
    ) {
        out.append('[')
        points.forEachIndexed { i, p ->
            if (i > 0) out.append(',')
            out.append('[')
            appendDegrees(out, p.lon)
            out.append(',')
            appendDegrees(out, p.lat)
            out.append(']')
        }
        out.append(']')
    }

    private fun hex(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

    private fun opacity(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        return if (alpha == 0xFF) "1" else "%.2f".format(java.util.Locale.ROOT, alpha / 255.0)
    }

    /** Six decimals, trailing zeros removed, never in scientific notation. */
    internal fun appendDegrees(
        out: StringBuilder,
        value: Double,
    ) {
        val scaled = Math.round(value * 1e6)
        if (scaled < 0) out.append('-')
        val abs = Math.abs(scaled)
        out.append(abs / 1_000_000)
        val fraction = (abs % 1_000_000).toInt()
        if (fraction == 0) return
        out.append('.')
        val digits = fraction.toString().padStart(6, '0').trimEnd('0')
        out.append(digits)
    }
}
