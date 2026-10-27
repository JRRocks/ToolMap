package fr.toolmap.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** The measure tool: the line (blue, to tell it from the orange of drawing), the translucent fill and the vertices. */
object MeasureLayers {
    private const val FILL_SOURCE = "tm-m-fill"
    private const val LINE_SOURCE = "tm-m-line"
    private const val VERTEX_SOURCE = "tm-m-vertices"
    private const val FILL_LAYER = "tm-m-fill-layer"
    private const val LINE_LAYER = "tm-m-line-layer"
    private const val VERTEX_LAYER = "tm-m-vertices-layer"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val COLOR = "#0277bd"
    private const val FILL_OPACITY = 0.2f
    private const val LINE_WIDTH = 4f
    private const val VERTEX_RADIUS = 6f
    private const val END_RADIUS = 8f

    fun install(style: Style) {
        if (style.getSource(LINE_SOURCE) != null) return
        style.addSource(GeoJsonSource(FILL_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(LINE_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(VERTEX_SOURCE, EMPTY))
        style.addLayer(
            FillLayer(FILL_LAYER, FILL_SOURCE).withProperties(
                PropertyFactory.fillColor(COLOR),
                PropertyFactory.fillOpacity(FILL_OPACITY),
            ),
        )
        style.addLayer(
            LineLayer(LINE_LAYER, LINE_SOURCE).withProperties(
                PropertyFactory.lineColor(COLOR),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        val kind = Expression.get("kind")
        style.addLayer(
            CircleLayer(VERTEX_LAYER, VERTEX_SOURCE).withProperties(
                PropertyFactory.circleRadius(
                    Expression.match(
                        kind,
                        Expression.literal(VERTEX_RADIUS),
                        Expression.stop("a", Expression.literal(END_RADIUS)),
                        Expression.stop("z", Expression.literal(END_RADIUS)),
                    ),
                ),
                PropertyFactory.circleColor(
                    Expression.match(
                        kind,
                        Expression.color(WHITE),
                        Expression.stop("a", Expression.color(GREEN)),
                        Expression.stop("z", Expression.color(RED)),
                    ),
                ),
                PropertyFactory.circleStrokeColor(COLOR),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }

    fun show(
        style: Style,
        lineGeoJson: String?,
        fillGeoJson: String?,
        verticesGeoJson: String?,
    ) {
        style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(lineGeoJson ?: EMPTY)
        style.getSourceAs<GeoJsonSource>(FILL_SOURCE)?.setGeoJson(fillGeoJson ?: EMPTY)
        style.getSourceAs<GeoJsonSource>(VERTEX_SOURCE)?.setGeoJson(verticesGeoJson ?: EMPTY)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREEN = 0xFF43A047.toInt()
    private const val RED = 0xFFE53935.toInt()
}
