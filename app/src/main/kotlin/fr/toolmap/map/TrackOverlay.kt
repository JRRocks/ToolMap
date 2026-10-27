package fr.toolmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import fr.toolmap.core.settings.PlacemarkSettings
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The map layers of tracks, routes and waypoints (ADR-018): two GeoJSON sources fed by [fr.toolmap.data.tracks.TrackDisplay]
 * and a few layers kept above the raster layers. Colour and width come from each feature's properties, so a style
 * change is a data update, not a layer change.
 */
object TrackOverlay {
    const val LINES_SOURCE = "tm-t-lines"
    const val POINTS_SOURCE = "tm-t-points"
    const val CURSOR_SOURCE = "tm-t-cursor"
    const val CASING_LAYER = "tm-t-casing"
    const val SOLID_LAYER = "tm-t-solid"
    const val DASHED_LAYER = "tm-t-dashed"
    const val ARROWS_LAYER = "tm-t-arrows"
    const val POINTS_SHADOW_LAYER = "tm-t-points-shadow"
    const val POINTS_LAYER = "tm-t-points-layer"
    const val POINTS_ICON_LAYER = "tm-t-points-icon"
    const val POINTS_LABEL_LAYER = "tm-t-points-label"
    const val GLYPH_PREFIX = "tm-wp-"
    const val CURSOR_LAYER = "tm-t-cursor-layer"
    private const val ARROW_IMAGE = "tm-arrow"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val CASING_EXTRA_WIDTH = 3f
    private const val CASING_OPACITY = 0.55f
    private const val POINT_RADIUS = 10f
    private const val POINT_STROKE = 2f
    private const val CURSOR_RADIUS = 7f
    private const val ARROW_SPACING = 90f
    private const val ARROW_SIZE_PX = 28
    private const val DASH_ON = 2f
    private const val DASH_OFF = 1.5f
    private const val SHADOW_EXTRA_RADIUS = 1f
    private const val SHADOW_OPACITY = 0.35f
    private const val SHADOW_BLUR = 0.6f
    private const val SHADOW_OFFSET_Y = 1.5f

    /** Layers that answer to a tap, topmost first. */
    val TAPPABLE = arrayOf(POINTS_LAYER, SOLID_LAYER, DASHED_LAYER)

    fun install(style: Style) {
        if (style.getSource(LINES_SOURCE) != null) return
        style.addSource(GeoJsonSource(LINES_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(POINTS_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(CURSOR_SOURCE, EMPTY))
        style.addImage(ARROW_IMAGE, arrowBitmap())

        val dashed = Expression.eq(Expression.get("dashed"), Expression.literal(true))
        val width = Expression.get("width")
        style.addLayer(
            LineLayer(CASING_LAYER, LINES_SOURCE).withProperties(
                PropertyFactory.lineColor("#ffffff"),
                PropertyFactory.lineOpacity(CASING_OPACITY),
                PropertyFactory.lineWidth(Expression.sum(width, Expression.literal(CASING_EXTRA_WIDTH))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(SOLID_LAYER, LINES_SOURCE)
                .withFilter(Expression.not(dashed))
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineOpacity(Expression.get("opacity")),
                    PropertyFactory.lineWidth(width),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        // line-dasharray cannot be data-driven: dashed lines are a second layer with a fixed pattern.
        style.addLayer(
            LineLayer(DASHED_LAYER, LINES_SOURCE)
                .withFilter(dashed)
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineOpacity(Expression.get("opacity")),
                    PropertyFactory.lineWidth(width),
                    PropertyFactory.lineDasharray(arrayOf(DASH_ON, DASH_OFF)),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        style.addLayer(
            SymbolLayer(ARROWS_LAYER, LINES_SOURCE)
                .withFilter(Expression.eq(Expression.get("arrows"), Expression.literal(true)))
                .withProperties(
                    PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                    PropertyFactory.symbolSpacing(ARROW_SPACING),
                    PropertyFactory.iconImage(ARROW_IMAGE),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
        // The shadow sits under the points, a little lower and blurred (« Ombres » of the placemarks).
        style.addLayer(
            CircleLayer(POINTS_SHADOW_LAYER, POINTS_SOURCE).withProperties(
                PropertyFactory.circleRadius(POINT_RADIUS + SHADOW_EXTRA_RADIUS),
                PropertyFactory.circleColor("#000000"),
                PropertyFactory.circleOpacity(SHADOW_OPACITY),
                PropertyFactory.circleBlur(SHADOW_BLUR),
                PropertyFactory.circleTranslate(arrayOf(0f, SHADOW_OFFSET_Y)),
            ),
        )
        style.addLayer(
            CircleLayer(POINTS_LAYER, POINTS_SOURCE).withProperties(
                PropertyFactory.circleRadius(POINT_RADIUS),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(POINT_STROKE),
            ),
        )
        // The white glyph of the waypoint's icon, on the disc of its category colour (P16.6).
        style.addLayer(
            SymbolLayer(POINTS_ICON_LAYER, POINTS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.concat(Expression.literal(GLYPH_PREFIX), Expression.get("icon"))),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
        // The name on the right of the marker, as in AlpineQuest (ADR-062); names that would overlap are left out.
        style.addLayer(
            SymbolLayer(POINTS_LABEL_LAYER, POINTS_SOURCE).withProperties(*MapLabels.properties(POINT_RADIUS)),
        )
        style.addLayer(
            CircleLayer(CURSOR_LAYER, CURSOR_SOURCE).withProperties(
                PropertyFactory.circleRadius(CURSOR_RADIUS),
                PropertyFactory.circleColor("#1e88e5"),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(POINT_STROKE),
            ),
        )
    }

    /**
     * Applies the « Placemarks » settings (P11.8): the size of the lines and points, the arrows, the contour of the
     * tracks, the borders and the shadows of the points. Only properties change, so the layers are kept.
     */
    fun applyStyle(
        style: Style,
        placemarks: PlacemarkSettings,
    ) {
        val factor = placemarks.size.factor.toFloat()
        val scaledWidth = Expression.product(Expression.get("width"), Expression.literal(factor))
        style.getLayerAs<LineLayer>(CASING_LAYER)?.setProperties(
            PropertyFactory.visibility(visible(placemarks.trackContour)),
            PropertyFactory.lineWidth(Expression.sum(scaledWidth, Expression.literal(CASING_EXTRA_WIDTH * factor))),
        )
        style.getLayerAs<LineLayer>(SOLID_LAYER)?.setProperties(PropertyFactory.lineWidth(scaledWidth))
        style.getLayerAs<LineLayer>(DASHED_LAYER)?.setProperties(PropertyFactory.lineWidth(scaledWidth))
        style.getLayerAs<SymbolLayer>(ARROWS_LAYER)?.setProperties(
            PropertyFactory.visibility(visible(placemarks.trackArrows)),
        )
        style.getLayerAs<CircleLayer>(POINTS_SHADOW_LAYER)?.setProperties(
            PropertyFactory.visibility(visible(placemarks.shadows)),
            PropertyFactory.circleRadius(POINT_RADIUS * factor + SHADOW_EXTRA_RADIUS),
        )
        style.getLayerAs<CircleLayer>(POINTS_LAYER)?.setProperties(
            PropertyFactory.circleRadius(POINT_RADIUS * factor),
            PropertyFactory.circleStrokeWidth(if (placemarks.borders) POINT_STROKE else 0f),
        )
        style.getLayerAs<SymbolLayer>(POINTS_ICON_LAYER)?.setProperties(PropertyFactory.iconSize(factor))
        style.getLayerAs<SymbolLayer>(POINTS_LABEL_LAYER)?.setProperties(
            PropertyFactory.visibility(visible(placemarks.labels)),
            *MapLabels.sized(POINT_RADIUS * factor, factor),
        )
    }

    private fun visible(on: Boolean): String = if (on) Property.VISIBLE else Property.NONE

    fun setLines(
        style: Style,
        geoJson: String,
    ) {
        style.getSourceAs<GeoJsonSource>(LINES_SOURCE)?.setGeoJson(geoJson)
    }

    /** Shows the waypoints; the glyph of each icon in [icons] is drawn by [glyph] the first time it is needed. */
    fun setPoints(
        style: Style,
        geoJson: String,
        icons: Set<String>,
        glyph: (String) -> Bitmap?,
    ) {
        addGlyphs(style, icons, glyph)
        style.getSourceAs<GeoJsonSource>(POINTS_SOURCE)?.setGeoJson(geoJson)
    }

    /** Loads the glyphs of [icons] not in the style yet (also used by the stops of a route being edited, P19). */
    fun addGlyphs(
        style: Style,
        icons: Set<String>,
        glyph: (String) -> Bitmap?,
    ) {
        for (id in icons) {
            val name = GLYPH_PREFIX + id
            if (style.getImage(name) == null) glyph(id)?.let { style.addImage(name, it) }
        }
    }

    fun setCursor(
        style: Style,
        geoJson: String?,
    ) {
        style.getSourceAs<GeoJsonSource>(CURSOR_SOURCE)?.setGeoJson(geoJson ?: EMPTY)
    }

    /**
     * The item a tapped feature stands for: lines carry the track id (negative for a route), points the waypoint id
     * (negative for the markers of a computed route).
     */
    fun refOf(
        layerId: String?,
        id: Long,
    ): ItemRef? =
        when (layerId) {
            POINTS_LAYER -> if (id < 0) ItemRef(ItemKind.ROUTE, -id) else ItemRef(ItemKind.WAYPOINT, id)
            SOLID_LAYER, DASHED_LAYER -> if (id < 0) ItemRef(ItemKind.ROUTE, -id) else ItemRef(ItemKind.TRACK, id)
            else -> null
        }

    /** A white arrow with a dark edge, readable on any track colour, pointing right (along the line). */
    private fun arrowBitmap(): Bitmap {
        val size = ARROW_SIZE_PX
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val arrow =
            Path().apply {
                moveTo(size * 0.20f, size * 0.22f)
                lineTo(size * 0.82f, size * 0.50f)
                lineTo(size * 0.20f, size * 0.78f)
                lineTo(size * 0.38f, size * 0.50f)
                close()
            }
        canvas.drawPath(
            arrow,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.12f
                strokeJoin = Paint.Join.ROUND
                color = 0xCC000000.toInt()
            },
        )
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        return bitmap
    }
}
