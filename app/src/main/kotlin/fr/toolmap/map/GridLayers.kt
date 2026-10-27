package fr.toolmap.map

import fr.toolmap.core.geo.GridLabelKind
import fr.toolmap.core.geo.GridLineLevel
import fr.toolmap.core.geo.ScreenGridPlan
import fr.toolmap.data.text.GlyphInterceptor
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The coordinate grid over the map: lines as heavy as their `level` ([GridLineLevel]), and the edges of the UTM and
 * MGRS grid zones in a darker, heavier line that stays solid when the grid is dashed (P24.2). The names of the zones
 * and squares (P24.3, ADR-073) are written in the system font of [GlyphInterceptor]; they never hide a waypoint's or
 * a member's name.
 */
object GridLayers {
    private const val SOURCE = "tm-g-grid"
    private const val LABELS_SOURCE = "tm-g-grid-labels"
    internal const val LAYER = "tm-g-grid-layer"
    internal const val DASHED_LAYER = "tm-g-grid-dashed-layer"
    internal const val EDGE_LAYER = "tm-g-grid-edge-layer"
    internal const val LABELS_LAYER = "tm-g-grid-labels-layer"
    private const val COLOR = "#455a64"
    private const val EDGE_COLOR = "#1c2429"
    private const val OPACITY = 0.75f
    private const val DASH_ON = 3f
    private const val DASH_OFF = 2f

    // The names as in the validated preview (P24.0): dark grey with a white halo, the zone larger, the letters of a
    // square in the regular weight; « 31T GL » just above the middle of its square (clear of the centre cross), « GL »
    // just below.
    private const val TEXT_COLOR = "#263238"
    private const val HALO_COLOR = "#ffffff"
    private const val HALO_WIDTH = 1.5f
    private const val HALO_BLUR = 0.4f
    private const val TEXT_SIZE = 12f
    private const val ZONE_TEXT_SIZE = 14f
    private const val NAME_RAISE_EMS = -1.2f
    private const val SQUARE_DROP_EMS = 1.3f

    // Widths in dp. WGS84, Lambert 93 and UTM under 1 km as before; UTM and MGRS from 1 km and the zone edges as in
    // the validated preview (P24.0, variant A, `docs/previews/p24-mgrs.html`).
    private const val MINOR_WIDTH = 1f
    private const val MAJOR_WIDTH = 2.2f
    private const val STEP_WIDTH = 0.8f
    private const val TEN_KM_WIDTH = 1.5f
    private const val SQUARE_WIDTH = 2.3f
    private const val EDGE_WIDTH = 3.2f

    fun install(style: Style) {
        if (style.getSource(SOURCE) != null) return
        style.addSource(GeoJsonSource(SOURCE, ScreenGridPlan.EMPTY_GEOJSON))
        val level = Expression.get("level")
        val width =
            Expression.match(
                level,
                Expression.literal(MINOR_WIDTH),
                Expression.stop(GridLineLevel.MAJOR, MAJOR_WIDTH),
                Expression.stop(GridLineLevel.STEP, STEP_WIDTH),
                Expression.stop(GridLineLevel.TEN_KM, TEN_KM_WIDTH),
                Expression.stop(GridLineLevel.SQUARE, SQUARE_WIDTH),
            )
        val gridLines = Expression.neq(level, Expression.literal(GridLineLevel.ZONE_EDGE))
        style.addLayer(
            LineLayer(LAYER, SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(COLOR),
                    PropertyFactory.lineOpacity(OPACITY),
                    PropertyFactory.lineWidth(width),
                ).withFilter(gridLines),
        )
        // line-dasharray cannot be data-driven: the dashed grid is a second layer, shown instead of the solid one.
        style.addLayer(
            LineLayer(DASHED_LAYER, SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(COLOR),
                    PropertyFactory.lineOpacity(OPACITY),
                    PropertyFactory.lineWidth(width),
                    PropertyFactory.lineDasharray(arrayOf(DASH_ON, DASH_OFF)),
                    PropertyFactory.visibility(Property.NONE),
                ).withFilter(gridLines),
        )
        style.addLayer(
            LineLayer(EDGE_LAYER, SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(EDGE_COLOR),
                    PropertyFactory.lineWidth(EDGE_WIDTH),
                ).withFilter(Expression.eq(level, Expression.literal(GridLineLevel.ZONE_EDGE))),
        )
        installLabels(style)
    }

    private fun installLabels(style: Style) {
        style.addSource(GeoJsonSource(LABELS_SOURCE, ScreenGridPlan.EMPTY_GEOJSON))
        val kind = Expression.get("kind")
        val isSquare = Expression.eq(kind, Expression.literal(GridLabelKind.SQUARE))
        style.addLayer(
            SymbolLayer(LABELS_LAYER, LABELS_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("text")),
                PropertyFactory.textFont(
                    Expression.switchCase(
                        isSquare,
                        Expression.literal(arrayOf(GlyphInterceptor.REGULAR_FONT)),
                        Expression.literal(arrayOf(GlyphInterceptor.FONT)),
                    ),
                ),
                PropertyFactory.textSize(
                    Expression.switchCase(
                        Expression.eq(kind, Expression.literal(GridLabelKind.ZONE)),
                        Expression.literal(ZONE_TEXT_SIZE),
                        Expression.literal(TEXT_SIZE),
                    ),
                ),
                PropertyFactory.textOffset(
                    Expression.match(
                        kind,
                        Expression.literal(arrayOf(0f, 0f)),
                        Expression.stop(GridLabelKind.NAME, Expression.literal(arrayOf(0f, NAME_RAISE_EMS))),
                        Expression.stop(GridLabelKind.SQUARE, Expression.literal(arrayOf(0f, SQUARE_DROP_EMS))),
                    ),
                ),
                PropertyFactory.textColor(TEXT_COLOR),
                PropertyFactory.textHaloColor(HALO_COLOR),
                PropertyFactory.textHaloWidth(HALO_WIDTH),
                PropertyFactory.textHaloBlur(HALO_BLUR),
                // A name of the grid never hides a waypoint's or a member's name (it may run over one).
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
    }

    /** « Style des grilles » (P11.8): dashed or solid lines. The zone edges stay solid. */
    fun setDashed(
        style: Style,
        dashed: Boolean,
    ) {
        style.getLayerAs<LineLayer>(LAYER)?.setProperties(
            PropertyFactory.visibility(if (dashed) Property.NONE else Property.VISIBLE),
        )
        style.getLayerAs<LineLayer>(DASHED_LAYER)?.setProperties(
            PropertyFactory.visibility(if (dashed) Property.VISIBLE else Property.NONE),
        )
    }

    fun show(
        style: Style,
        geoJson: String,
        labelsGeoJson: String,
    ) {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson)
        style.getSourceAs<GeoJsonSource>(LABELS_SOURCE)?.setGeoJson(labelsGeoJson)
    }
}
