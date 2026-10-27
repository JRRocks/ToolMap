package fr.toolmap.core.track

/**
 * GeoJSON of the line being drawn or edited: the line itself and its handles. Handles carry a `kind` the map layer
 * styles by: `v` vertex, `s` selected vertex, `a` first, `z` last, `m` midpoint (tap to insert a vertex there).
 */
object EditorGeoJson {
    /** Beyond this many vertices only the ends and the selected one are drawn: thousands of circles are no help. */
    const val MAX_HANDLES = 1_500

    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    fun line(points: List<TrackPoint>): String {
        if (points.size < 2) return EMPTY
        val out = StringBuilder(points.size * 24 + 96)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}")
        out.append(",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        points.forEachIndexed { i, p ->
            if (i > 0) out.append(',')
            out.append('[')
            TrackGeoJson.appendDegrees(out, p.lon)
            out.append(',')
            TrackGeoJson.appendDegrees(out, p.lat)
            out.append(']')
        }
        return out.append("]}}]}").toString()
    }

    fun handles(
        points: List<TrackPoint>,
        selected: Int?,
        midpoints: Boolean = true,
    ): String {
        if (points.isEmpty()) return EMPTY
        val all = points.size <= MAX_HANDLES
        val out = StringBuilder(points.size * 80 + 96)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true

        fun feature(
            kind: String,
            index: Int,
            lat: Double,
            lon: Double,
        ) {
            if (!first) out.append(',')
            first = false
            out.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"").append(kind)
            out.append("\",\"i\":").append(index).append("},\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            TrackGeoJson.appendDegrees(out, lon)
            out.append(',')
            TrackGeoJson.appendDegrees(out, lat)
            out.append("]}}")
        }
        if (all && midpoints) {
            for (i in 0 until points.lastIndex) {
                val m = TrackOps.midpoint(points[i], points[i + 1])
                feature("m", i, m.lat, m.lon)
            }
        }
        points.forEachIndexed { i, p ->
            val kind =
                when {
                    i == selected -> "s"
                    i == 0 -> "a"
                    i == points.lastIndex -> "z"
                    all -> "v"
                    else -> return@forEachIndexed
                }
            feature(kind, i, p.lat, p.lon)
        }
        return out.append("]}").toString()
    }

    /**
     * What a tap at ([lat], [lon]) means: the vertex within [reachMeters] (nearest wins), else the midpoint handle within
     * reach, else a new vertex at that place.
     */
    fun interpret(
        points: List<TrackPoint>,
        lat: Double,
        lon: Double,
        reachMeters: Double,
    ): TapAction {
        var best = -1
        var bestDistance = reachMeters
        for (i in points.indices) {
            val d = Geodesy.distance(lat, lon, points[i].lat, points[i].lon)
            if (d <= bestDistance) {
                bestDistance = d
                best = i
            }
        }
        if (best >= 0) return TapAction.Select(best)
        if (points.size in 2..MAX_HANDLES) {
            var mid = -1
            var midDistance = reachMeters
            for (i in 0 until points.lastIndex) {
                val m = TrackOps.midpoint(points[i], points[i + 1])
                val d = Geodesy.distance(lat, lon, m.lat, m.lon)
                if (d <= midDistance) {
                    midDistance = d
                    mid = i
                }
            }
            if (mid >= 0) return TapAction.InsertMidpoint(mid)
        }
        return TapAction.Add
    }

    sealed interface TapAction {
        data class Select(
            val index: Int,
        ) : TapAction

        data class InsertMidpoint(
            val segment: Int,
        ) : TapAction

        data object Add : TapAction
    }
}
