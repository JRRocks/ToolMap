package fr.toolmap.map

import fr.toolmap.core.track.LineFeature
import fr.toolmap.core.track.TrackGeoJson
import fr.toolmap.core.track.TrackPoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The target the user is walking to: a ring on it, and the way BRouter computed to it (P30.3: white edge, in the
 * colour of the target; grey and dashed while the walker is off it), else a dotted line from the user's position.
 */
object NavLayers {
    private const val LINE_SOURCE = "tm-n-line"
    private const val WAY_SOURCE = "tm-n-way"
    private const val TARGET_SOURCE = "tm-n-target"
    private const val LINE_LAYER = "tm-n-line-layer"
    private const val WAY_CASING_LAYER = "tm-n-way-casing"
    private const val WAY_LAYER = "tm-n-way-layer"
    private const val WAY_PALE_LAYER = "tm-n-way-pale"
    private const val TARGET_LAYER = "tm-n-target-layer"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val LINE_WIDTH = 3f
    private const val DASH_ON = 1f
    private const val DASH_OFF = 2f
    private const val WAY_WIDTH = 4f
    private const val WAY_CASING_WIDTH = 7f
    private const val PALE_WIDTH = 2.5f
    private const val PALE_COLOR = "#9aa6ab"
    private const val PALE_DASH_ON = 1.6f
    private const val PALE_DASH_OFF = 1.2f
    private const val RING_RADIUS = 12f
    private const val RING_WIDTH = 3f

    /** Simplification of the way for the drawing: invisible at the zooms of a walk. */
    private const val WAY_TOLERANCE_METERS = 2.0

    fun install(style: Style) {
        if (style.getSource(LINE_SOURCE) != null) return
        style.addSource(GeoJsonSource(WAY_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(LINE_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(TARGET_SOURCE, EMPTY))
        style.addLayer(
            LineLayer(WAY_CASING_LAYER, WAY_SOURCE).withProperties(
                PropertyFactory.lineColor("#ffffff"),
                PropertyFactory.lineWidth(WAY_CASING_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(WAY_LAYER, WAY_SOURCE).withProperties(
                PropertyFactory.lineColor("#d81b60"),
                PropertyFactory.lineWidth(WAY_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(WAY_PALE_LAYER, WAY_SOURCE).withProperties(
                PropertyFactory.lineColor(PALE_COLOR),
                PropertyFactory.lineWidth(PALE_WIDTH),
                PropertyFactory.lineDasharray(arrayOf(PALE_DASH_ON, PALE_DASH_OFF)),
                PropertyFactory.visibility(Property.NONE),
            ),
        )
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
     * the placemarks (P11.8). [way] is what remains of the way to the target (P30.3), drawn instead of the straight
     * line; [pale] while the walker is off it and a new one is computed.
     */
    fun show(
        style: Style,
        from: Pair<Double, Double>?,
        target: Pair<Double, Double>?,
        color: Int,
        way: List<TrackPoint>? = null,
        pale: Boolean = false,
    ) {
        style.getLayerAs<LineLayer>(LINE_LAYER)?.setProperties(PropertyFactory.lineColor(color))
        style.getLayerAs<CircleLayer>(TARGET_LAYER)?.setProperties(PropertyFactory.circleStrokeColor(color))
        val solid = if (pale) Property.NONE else Property.VISIBLE
        style.getLayerAs<LineLayer>(WAY_CASING_LAYER)?.setProperties(PropertyFactory.visibility(solid))
        style.getLayerAs<LineLayer>(WAY_LAYER)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.visibility(solid),
        )
        style.getLayerAs<LineLayer>(WAY_PALE_LAYER)?.setProperties(
            PropertyFactory.visibility(if (pale) Property.VISIBLE else Property.NONE),
        )
        val shownWay = way?.takeIf { target != null && it.size >= 2 }
        val line =
            if (from != null && target != null && shownWay == null) {
                """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":""" +
                    """[[${from.second},${from.first}],[${target.second},${target.first}]]}}"""
            } else {
                EMPTY
            }
        style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(line)
        style.getSourceAs<GeoJsonSource>(WAY_SOURCE)?.setGeoJson(
            shownWay?.let {
                TrackGeoJson.lines(listOf(LineFeature(0, color, WAY_WIDTH, false, listOf(it))), WAY_TOLERANCE_METERS)
            } ?: EMPTY,
        )
        style.getSourceAs<GeoJsonSource>(TARGET_SOURCE)?.setGeoJson(
            target?.let { TrackGeoJson.point(it.first, it.second) } ?: EMPTY,
        )
    }
}
