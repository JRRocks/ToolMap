package fr.toolmap.map

import android.content.Context
import fr.toolmap.share.ShareMapData
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The other members of the sharing group: their recent trail as a line (unless the user hid trails) and their last
 * position as the icon they chose on a disc of their colour (faded when old or lost), with a red ring around those
 * who sent an SOS, and their name on the right (ADR-062). A tap on a marker opens the member's sheet.
 */
object ShareLayers {
    const val MEMBERS_LAYER = "tm-s-members-layer"
    private const val TRAILS_SOURCE = "tm-s-trails"
    private const val MEMBERS_SOURCE = "tm-s-members"
    private const val TRAILS_LAYER = "tm-s-trails-layer"
    private const val SOS_LAYER = "tm-s-sos-layer"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val TRAIL_WIDTH = 3f
    private const val TRAIL_OPACITY = 0.8f
    private const val FADED = 0.45f
    private const val SOS_RADIUS = 18f
    private const val SOS_STROKE = 4f
    private const val SOS_COLOR = "#e53935"

    // Half of the 32 dp marker of [MemberMarkerBitmap]: the names start just right of it.
    private const val MARKER_RADIUS = 16f
    private const val LABELS_LAYER = "tm-s-labels-layer"

    fun install(style: Style) {
        if (style.getSource(MEMBERS_SOURCE) != null) return
        style.addSource(GeoJsonSource(TRAILS_SOURCE, EMPTY))
        style.addSource(GeoJsonSource(MEMBERS_SOURCE, EMPTY))
        style.addLayer(
            LineLayer(TRAILS_LAYER, TRAILS_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(TRAIL_WIDTH),
                PropertyFactory.lineOpacity(TRAIL_OPACITY),
            ),
        )
        style.addLayer(
            CircleLayer(SOS_LAYER, MEMBERS_SOURCE).withProperties(
                PropertyFactory.circleRadius(SOS_RADIUS),
                PropertyFactory.circleColor("#00000000"),
                PropertyFactory.circleStrokeColor(SOS_COLOR),
                PropertyFactory.circleStrokeWidth(SOS_STROKE),
            ).apply { setFilter(Expression.eq(Expression.get("sos"), Expression.literal(true))) },
        )
        style.addLayer(
            SymbolLayer(MEMBERS_LAYER, MEMBERS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("img")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconOpacity(
                    Expression.switchCase(
                        Expression.eq(Expression.get("old"), Expression.literal(true)),
                        Expression.literal(FADED),
                        Expression.literal(1f),
                    ),
                ),
            ),
        )
        style.addLayer(SymbolLayer(LABELS_LAYER, MEMBERS_SOURCE).withProperties(*MapLabels.properties(MARKER_RADIUS)))
    }

    /** Shows or hides the names of the members, with the « Noms sur la carte » setting of the placemarks. */
    fun showLabels(
        style: Style,
        on: Boolean,
    ) {
        style.getLayerAs<SymbolLayer>(LABELS_LAYER)?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE),
        )
    }

    /** Adds the marker images still missing from [style], then shows the members and their trails. */
    fun show(
        context: Context,
        style: Style,
        data: ShareMapData,
    ) {
        for (marker in data.markers) {
            if (style.getImage(marker.key) == null) {
                style.addImage(marker.key, MemberMarkerBitmap.draw(context, marker.look, marker.name, marker.picture))
            }
        }
        style.getSourceAs<GeoJsonSource>(MEMBERS_SOURCE)?.setGeoJson(data.membersGeoJson)
        style.getSourceAs<GeoJsonSource>(TRAILS_SOURCE)?.setGeoJson(data.trailsGeoJson)
    }
}
