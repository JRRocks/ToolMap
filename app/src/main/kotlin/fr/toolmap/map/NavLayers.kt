package fr.toolmap.map

import fr.toolmap.core.track.TrackGeoJson
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** The target the user is walking to: a ring on it and a dotted line from the user's position. */
object NavLayers {
    private const val LINE_SOURCE = "tm-n-line"
    private const val TARGET_SOURCE = "tm-n-target"
    private const val LINE_LAYER = "tm-n-line-layer"
    private const val TARGET_LAYER = "tm-n-target-layer"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val LINE_WIDTH = 3f
    private const val DASH_ON = 1f
    private const val DASH_OFF = 2f
    private const val RING_RADIUS = 12f
    private const val RING_WIDTH = 3f

    fun install(style: Style) {
        if (style.getSource(LINE_SOURCE) != null) return
        style.addSource(GeoJsonSource(LINE_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(TARGET_SOURCE, EMPTY))
        style.addLayer(
            LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
                PropertyFactory.lineColor("#d81b60"),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineDasharray(arrayOf(DASH_ON, DASH_OFF)),
            ),
        )
        style.addLayer(
            CircleLayer(TARGET_LAYER, TARGET_SOURCE).withProperties(
                PropertyFactory.circleRadius(RING_RADIUS),
                PropertyFactory.circleColor("#00000000"),
                PropertyFactory.circleStrokeColor("#d81b60"),
                PropertyFactory.circleStrokeWidth(RING_WIDTH),
            ),
        )
    }

    /**
     * [from] is the user's position when known; [target] `null` hides everything. [color] is the « Target color » of
     * the placemarks (P11.8).
     */
    fun show(
        style: Style,
        from: Pair<Double, Double>?,
        target: Pair<Double, Double>?,
        color: Int,
    ) {
        style.getLayerAs<LineLayer>(LINE_LAYER)?.setProperties(PropertyFactory.lineColor(color))
        style.getLayerAs<CircleLayer>(TARGET_LAYER)?.setProperties(PropertyFactory.circleStrokeColor(color))
        val line =
            if (from != null && target != null) {
                """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":""" +
                    """[[${from.second},${from.first}],[${target.second},${target.first}]]}}"""
            } else {
                EMPTY
            }
        style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(line)
        style.getSourceAs<GeoJsonSource>(TARGET_SOURCE)?.setGeoJson(
            target?.let { TrackGeoJson.point(it.first, it.second) } ?: EMPTY,
        )
    }
}
