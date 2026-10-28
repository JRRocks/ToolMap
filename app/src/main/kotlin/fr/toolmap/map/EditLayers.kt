package fr.toolmap.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import fr.toolmap.data.text.GlyphInterceptor
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.roundToInt

/**
 * The line being drawn and its handles, above everything but the position: the line in the editing colour, the
 * vertices as circles whose size and colour come from each handle's `kind` (see
 * [fr.toolmap.core.track.EditorGeoJson]).
 *
 * A route being computed (P19, [fr.toolmap.core.route.RoutePlanGeoJson]) uses the same sources: its legs carry `st`
 * (`h` by hand: dashed; `p` computing: grey dots), a `label` at the middle of those legs, and stops whose `kind` is
 * `start`, `end` or `named` (a coloured disc with a letter or an icon, and the name) or `pass` (a small white disc).
 *
 * Below them, the zones to avoid of a route (P19.8, variant A of `docs/previews/p19-nogos.html`): a pale red disc with
 * a dashed edge and a no-entry sign at its centre, and the circle that follows the crosshair before it is placed.
 */
object EditLayers {
    private const val LINE_SOURCE = "tm-e-line"
    private const val HANDLES_SOURCE = "tm-e-handles"
    private const val LINE_LAYER = "tm-e-line-layer"
    private const val BY_HAND_LAYER = "tm-e-line-hand"
    private const val PENDING_LAYER = "tm-e-line-pending"
    private const val TAKEN_LAYER = "tm-e-line-taken"
    private const val ELASTIC_SOURCE = "tm-e-elastic"
    private const val ELASTIC_LAYER = "tm-e-elastic-layer"
    private const val ZONES_SOURCE = "tm-e-zones"
    private const val ZONES_FILL_LAYER = "tm-e-zones-fill"
    private const val ZONES_EDGE_LAYER = "tm-e-zones-edge"
    private const val ZONES_SIGN_LAYER = "tm-e-zones-sign"
    private const val NO_ENTRY_IMAGE = "tm-no-entry"
    private const val LEG_LABEL_LAYER = "tm-e-leg-label"
    private const val HANDLES_LAYER = "tm-e-handles-layer"
    private const val HANDLES_ICON_LAYER = "tm-e-handles-icon"
    private const val HANDLES_LABEL_LAYER = "tm-e-handles-label"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val LINE_WIDTH = 4f
    private const val MIDPOINT_RADIUS = 4f
    private const val VERTEX_RADIUS = 6f
    private const val SELECTED_RADIUS = 10f
    private const val STOP_RADIUS = 10f
    private const val PASS_RADIUS = 5f
    private const val PASS_SELECTED_RADIUS = 8f
    private const val MIDPOINT_OPACITY = 0.8f
    private const val STROKE = 2f
    private const val PASS_STROKE = 2.5f
    private const val SELECTED_STROKE = 3.5f
    private const val LEG_LABEL_SIZE = 11f
    private const val LEG_LABEL_HALO = 2.5f
    private const val TAKEN_OPACITY = 0.35f
    private const val ELASTIC_WIDTH = 3f
    private const val ORANGE_HEX = "#ff6f00"
    private const val PENDING_HEX = "#9aa0ae"
    private const val ZONE_HEX = "#d32f2f"
    private const val ZONE_OPACITY = 0.16f
    private const val RING_OPACITY = 0.07f
    private const val ZONE_EDGE_WIDTH = 2f
    private const val SIGN_DP = 16f

    fun install(style: Style) {
        if (style.getSource(LINE_SOURCE) != null) return
        style.addSource(GeoJsonSource(LINE_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(HANDLES_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(ELASTIC_SOURCE, EMPTY))
        installZones(style)
        val st = Expression.get("st")
        val byHand = Expression.eq(st, Expression.literal("h"))
        val pending = Expression.eq(st, Expression.literal("p"))
        val taken = Expression.eq(st, Expression.literal("g"))
        val isLine = Expression.eq(Expression.geometryType(), Expression.literal("LineString"))
        style.addLayer(
            LineLayer(LINE_LAYER, LINE_SOURCE)
                .withFilter(
                    Expression.all(isLine, Expression.not(byHand), Expression.not(pending), Expression.not(taken)),
                )
                .withProperties(
                    PropertyFactory.lineColor(ORANGE_HEX),
                    PropertyFactory.lineWidth(LINE_WIDTH),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        // line-dasharray cannot be data-driven: each kind of leg is its own layer.
        style.addLayer(
            LineLayer(BY_HAND_LAYER, LINE_SOURCE)
                .withFilter(Expression.all(isLine, byHand))
                .withProperties(
                    PropertyFactory.lineColor(ORANGE_HEX),
                    PropertyFactory.lineWidth(LINE_WIDTH),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        style.addLayer(
            LineLayer(PENDING_LAYER, LINE_SOURCE)
                .withFilter(Expression.all(isLine, pending))
                .withProperties(
                    PropertyFactory.lineColor(PENDING_HEX),
                    PropertyFactory.lineWidth(LINE_WIDTH),
                    PropertyFactory.lineDasharray(arrayOf(0.5f, 1.5f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                ),
        )
        // A stop taken at the crosshair (variant A of `docs/previews/p19-reprendre-etape.html`): its legs pale, and the
        // dashed elastic lines that join its neighbours to the crosshair until it is put down.
        style.addLayer(
            LineLayer(TAKEN_LAYER, LINE_SOURCE)
                .withFilter(Expression.all(isLine, taken))
                .withProperties(
                    PropertyFactory.lineColor(ORANGE_HEX),
                    PropertyFactory.lineWidth(LINE_WIDTH),
                    PropertyFactory.lineOpacity(TAKEN_OPACITY),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
        )
        style.addLayer(
            LineLayer(ELASTIC_LAYER, ELASTIC_SOURCE).withProperties(
                PropertyFactory.lineColor(ORANGE_HEX),
                PropertyFactory.lineWidth(ELASTIC_WIDTH),
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            SymbolLayer(LEG_LABEL_LAYER, LINE_SOURCE)
                .withFilter(Expression.has("label"))
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf(GlyphInterceptor.FONT)),
                    PropertyFactory.textSize(LEG_LABEL_SIZE),
                    PropertyFactory.textColor("#45464f"),
                    PropertyFactory.textHaloColor("#ffffff"),
                    PropertyFactory.textHaloWidth(LEG_LABEL_HALO),
                    PropertyFactory.textAllowOverlap(false),
                ),
        )
        val kind = Expression.get("kind")
        val selected = Expression.eq(Expression.get("sel"), Expression.literal(true))
        // The ends and the named stops have a white border like the waypoints; the rest the editing colour.
        val disc =
            Expression.any(
                Expression.eq(kind, Expression.literal("start")),
                Expression.eq(kind, Expression.literal("end")),
                Expression.eq(kind, Expression.literal("named")),
            )
        style.addLayer(
            CircleLayer(HANDLES_LAYER, HANDLES_SOURCE).withProperties(
                PropertyFactory.circleRadius(
                    Expression.match(
                        kind,
                        Expression.literal(VERTEX_RADIUS),
                        Expression.stop("s", Expression.literal(SELECTED_RADIUS)),
                        Expression.stop("m", Expression.literal(MIDPOINT_RADIUS)),
                        Expression.stop("start", Expression.literal(STOP_RADIUS)),
                        Expression.stop("end", Expression.literal(STOP_RADIUS)),
                        Expression.stop("named", Expression.literal(STOP_RADIUS)),
                        Expression.stop("pass", Expression.literal(PASS_RADIUS)),
                        Expression.stop("pass-sel", Expression.literal(PASS_SELECTED_RADIUS)),
                    ),
                ),
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        Expression.has("color"),
                        Expression.toColor(Expression.get("color")),
                        Expression.match(
                            kind,
                            Expression.color(WHITE),
                            Expression.stop("s", Expression.color(ORANGE)),
                            Expression.stop("pass-sel", Expression.color(ORANGE)),
                            Expression.stop("a", Expression.color(GREEN)),
                            Expression.stop("z", Expression.color(RED)),
                        ),
                    ),
                ),
                PropertyFactory.circleOpacity(
                    Expression.match(
                        kind,
                        Expression.literal(1f),
                        Expression.stop("m", Expression.literal(MIDPOINT_OPACITY)),
                    ),
                ),
                PropertyFactory.circleStrokeColor(
                    Expression.switchCase(
                        Expression.all(disc, Expression.not(selected)),
                        Expression.color(WHITE),
                        Expression.color(ORANGE),
                    ),
                ),
                PropertyFactory.circleStrokeWidth(
                    Expression.switchCase(
                        Expression.all(disc, selected),
                        Expression.literal(SELECTED_STROKE),
                        Expression.eq(kind, Expression.literal("pass")),
                        Expression.literal(PASS_STROKE),
                        Expression.literal(STROKE),
                    ),
                ),
            ),
        )
        // The letter of the ends and the icon of the named stops, drawn like the waypoints' glyphs.
        style.addLayer(
            SymbolLayer(HANDLES_ICON_LAYER, HANDLES_SOURCE)
                .withFilter(Expression.has("icon"))
                .withProperties(
                    PropertyFactory.iconImage(
                        Expression.concat(Expression.literal(TrackOverlay.GLYPH_PREFIX), Expression.get("icon")),
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    // In the order of the features: the start, drawn last, stays above the end of a loop.
                    PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
                ),
        )
        style.addLayer(
            SymbolLayer(HANDLES_LABEL_LAYER, HANDLES_SOURCE)
                .withFilter(Expression.has("name"))
                .withProperties(*MapLabels.properties(STOP_RADIUS)),
        )
    }

    /** A `null` [lineGeoJson] with [keepLine] leaves the line as it is: only the handles follow the crosshair. */
    fun show(
        style: Style,
        lineGeoJson: String?,
        handlesGeoJson: String?,
        elasticGeoJson: String? = null,
        keepLine: Boolean = false,
        zonesGeoJson: String? = null,
    ) {
        if (!keepLine) style.getSourceAs<GeoJsonSource>(LINE_SOURCE)?.setGeoJson(lineGeoJson ?: EMPTY)
        style.getSourceAs<GeoJsonSource>(HANDLES_SOURCE)?.setGeoJson(handlesGeoJson ?: EMPTY)
        style.getSourceAs<GeoJsonSource>(ELASTIC_SOURCE)?.setGeoJson(elasticGeoJson ?: EMPTY)
        style.getSourceAs<GeoJsonSource>(ZONES_SOURCE)?.setGeoJson(zonesGeoJson ?: EMPTY)
    }

    /** The zones to avoid, under the line of the route: their pale disc, their dashed edge and their sign. */
    private fun installZones(style: Style) {
        style.addSource(GeoJsonSource(ZONES_SOURCE, EMPTY))
        style.addImage(NO_ENTRY_IMAGE, noEntrySign())
        val z = Expression.get("z")
        val isArea = Expression.eq(Expression.geometryType(), Expression.literal("Polygon"))
        style.addLayer(
            FillLayer(ZONES_FILL_LAYER, ZONES_SOURCE)
                .withFilter(isArea)
                .withProperties(
                    PropertyFactory.fillColor(ZONE_HEX),
                    PropertyFactory.fillOpacity(
                        Expression.match(
                            z,
                            Expression.literal(ZONE_OPACITY),
                            Expression.stop("ring", Expression.literal(RING_OPACITY)),
                        ),
                    ),
                ),
        )
        style.addLayer(
            LineLayer(ZONES_EDGE_LAYER, ZONES_SOURCE)
                .withFilter(isArea)
                .withProperties(
                    PropertyFactory.lineColor(ZONE_HEX),
                    PropertyFactory.lineWidth(ZONE_EDGE_WIDTH),
                    PropertyFactory.lineDasharray(arrayOf(2.5f, 2f)),
                ),
        )
        style.addLayer(
            SymbolLayer(ZONES_SIGN_LAYER, ZONES_SOURCE)
                .withFilter(Expression.eq(z, Expression.literal("sign")))
                .withProperties(
                    PropertyFactory.iconImage(NO_ENTRY_IMAGE),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
    }

    /** A red disc ringed with white and crossed by a white bar, as the road sign. */
    private fun noEntrySign(): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val size = (SIGN_DP * density).roundToInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.color = WHITE
        canvas.drawCircle(r, r, r, paint)
        paint.color = ZONE_RED
        canvas.drawCircle(r, r, r - SIGN_RING_DP * density, paint)
        paint.color = WHITE
        val halfBar = r * SIGN_BAR_SHARE
        val halfThick = SIGN_BAR_DP * density / 2
        canvas.drawRect(r - halfBar, r - halfThick, r + halfBar, r + halfThick, paint)
        return bitmap
    }

    private const val SIGN_RING_DP = 1.5f
    private const val SIGN_BAR_DP = 2.5f
    private const val SIGN_BAR_SHARE = 0.55f
    private const val ZONE_RED = 0xFFD32F2F.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val ORANGE = 0xFFFF6F00.toInt()
    private const val GREEN = 0xFF43A047.toInt()
    private const val RED = 0xFFE53935.toInt()
}
