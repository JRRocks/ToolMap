package fr.toolmap.core.measure

import fr.toolmap.core.track.EditorGeoJson
import fr.toolmap.core.track.TrackGeoJson
import fr.toolmap.core.track.TrackPoint

/** What the tool measures: the length of a line, or the area of the polygon through the points. */
enum class MeasureMode { PATH, AREA }

/** GeoJSON of the measure overlay: the line (closed in area mode), the fill, and the vertices. */
object MeasureGeoJson {
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val MIN_POLYGON_POINTS = 3

    fun line(
        points: List<TrackPoint>,
        mode: MeasureMode,
    ): String =
        if (mode == MeasureMode.AREA && points.size >= MIN_POLYGON_POINTS) {
            EditorGeoJson.line(points + points.first())
        } else {
            EditorGeoJson.line(points)
        }

    /** The polygon to fill, only in area mode with three points or more. */
    fun fill(
        points: List<TrackPoint>,
        mode: MeasureMode,
    ): String {
        if (mode != MeasureMode.AREA || points.size < MIN_POLYGON_POINTS) return EMPTY
        val ring = points + points.first()
        val out = StringBuilder(ring.size * 24 + 96)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}")
        out.append(",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
        ring.forEachIndexed { i, p ->
            if (i > 0) out.append(',')
            out.append('[')
            TrackGeoJson.appendDegrees(out, p.lon)
            out.append(',')
            TrackGeoJson.appendDegrees(out, p.lat)
            out.append(']')
        }
        return out.append("]]}}]}").toString()
    }

    fun vertices(points: List<TrackPoint>): String = EditorGeoJson.handles(points, selected = null, midpoints = false)
}
