package fr.toolmap.sketch

import fr.toolmap.core.sketch.Sketch
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPalette
import fr.toolmap.core.sketch.SketchScale
import fr.toolmap.core.sketch.XY
import fr.toolmap.data.tracks.SavedSketch
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.Locale

/**
 * The saved sketches on the map (P28.3, ADR-110): each stroke a line of its colour whose width is kept in map meters,
 * so it doubles at each zoom level in and halves at each level out, like ink on a paper map; a tap without moving is a
 * dot. A sketch whose widest stroke would be thinner than one pixel is left out ([geoJson]). A zone (P28.4) is filled
 * under its outline: a tint, or its pattern, which keeps its size on the screen ([SketchPatterns]).
 */
object SketchLayers {
    private const val SOURCE = "tm-sketch"
    private const val TINT_LAYER = "tm-sketch-tint"
    private const val PATTERN_LAYER = "tm-sketch-pattern"
    private const val LINE_LAYER = "tm-sketch-line"
    private const val DOT_LAYER = "tm-sketch-dot"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val MAX_ZOOM = 24

    fun install(style: Style) {
        if (style.getSource(SOURCE) != null) return
        style.addSource(GeoJsonSource(SOURCE, EMPTY))
        // The patterns of every fill in every colour of the pen: a few dozen small tiles.
        for (fill in SketchFill.entries.filter { it.patterned }) {
            for (color in SketchPalette.COLORS) {
                SketchPatterns.bitmap(fill, color)?.let { style.addImage(SketchPatterns.id(fill, color), it) }
            }
        }
        val polygon = Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))
        style.addLayer(
            FillLayer(TINT_LAYER, SOURCE)
                .withFilter(Expression.all(polygon, Expression.eq(Expression.get("fill"), SketchFill.TINT.code)))
                .withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.fillOpacity(SketchFill.TINT.opacity.toFloat()),
                ),
        )
        style.addLayer(
            FillLayer(PATTERN_LAYER, SOURCE)
                .withFilter(Expression.all(polygon, Expression.has("pattern")))
                .withProperties(PropertyFactory.fillPattern(Expression.get("pattern"))),
        )
        // Width on screen = w0 × 2^zoom, w0 being the width in pixels at zoom 0, and never under « min » pixels: one stop
        // per zoom level, as the zoom may only drive the interpolation at the top of the expression.
        val width = widthStops(1.0)
        val radius = widthStops(0.5)
        style.addLayer(
            LineLayer(LINE_LAYER, SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
                .withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.lineWidth(width),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        style.addLayer(
            CircleLayer(DOT_LAYER, SOURCE)
                .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
                .withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                    PropertyFactory.circleRadius(radius),
                ),
        )
    }

    private fun widthStops(factor: Double): Expression =
        Expression.interpolate(
            Expression.exponential(2),
            Expression.zoom(),
            *(0..MAX_ZOOM).map { z ->
                Expression.stop(
                    z,
                    Expression.max(
                        Expression.product(
                            Expression.get("w0"),
                            Expression.literal(factor * Math.pow(2.0, z.toDouble())),
                        ),
                        Expression.product(Expression.get("min"), Expression.literal(factor)),
                    ),
                )
            }.toTypedArray(),
        )

    fun show(
        style: Style,
        geoJson: String,
    ) {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson)
    }

    /**
     * The visible sketches drawn at [cameraZoom], the one being edited ([skipId]) apart: it is drawn by the tool
     * itself. The curves are cut into lines finer than a tenth of their width. With [minPixels] (the frame of a print,
     * P28.11), every sketch is drawn at least that wide, even those thinner than a pixel, since the paper shows them.
     */
    fun geoJson(
        sketches: List<SavedSketch>,
        cameraZoom: Double,
        skipId: Long?,
        minPixels: Double = 0.0,
    ): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        val atZoom0 = SketchScale.metersPerPixel(0.0)
        for (saved in sketches) {
            if (!saved.visible || saved.id == skipId) continue
            val minZoom = saved.sketch.minVisibleZoom() ?: continue
            if (minPixels <= 0.0 && cameraZoom < minZoom) continue
            // What one sketch may cost to draw: no drawing comes near, a file made up for it stops there (P36 audit).
            var left = Sketch.MAX_DRAWN_POINTS
            for (stroke in saved.sketch.strokes) {
                if (left <= 0) break
                val w0 = stroke.style.widthMeters / atZoom0
                val color = String.format(Locale.ROOT, "#%06X", stroke.style.color and 0xFFFFFF)
                val points = stroke.drawn(left)
                left -= points.size
                val fill = stroke.shape?.takeIf { it.closed && points.size > 3 }?.fill ?: SketchFill.NONE
                if (fill != SketchFill.NONE) {
                    // The zone under its outline: a polygon with its fill, then the outline as for any stroke.
                    if (!first) sb.append(',')
                    first = false
                    sb.append("""{"type":"Feature","properties":{"color":"""").append(color)
                    sb.append("""","fill":"""").append(fill.code).append('"')
                    if (fill.patterned && stroke.style.color in SketchPalette.COLORS) {
                        sb.append(""","pattern":"""").append(SketchPatterns.id(fill, stroke.style.color)).append('"')
                    }
                    sb.append("""},"geometry":{"type":"Polygon","coordinates":[""")
                    coordinates(sb, points)
                    sb.append("]}}")
                }
                if (!first) sb.append(',')
                first = false
                sb.append("""{"type":"Feature","properties":{"color":"""").append(color)
                sb.append("""","w0":""").append(w0).append(""","min":""").append(minPixels).append("""},"geometry":""")
                if (points.size < 2) {
                    val p = points[0].toLonLat()
                    sb.append(
                        """{"type":"Point","coordinates":[""",
                    ).append(p.lon).append(',').append(p.lat).append("]}")
                } else {
                    sb.append("""{"type":"LineString","coordinates":""")
                    coordinates(sb, points)
                    sb.append("}")
                }
                sb.append('}')
            }
        }
        return sb.append("]}").toString()
    }

    private fun coordinates(
        sb: StringBuilder,
        points: List<XY>,
    ) {
        sb.append('[')
        points.forEachIndexed { i, xy ->
            val p = xy.toLonLat()
            if (i > 0) sb.append(',')
            sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
        }
        sb.append(']')
    }
}
