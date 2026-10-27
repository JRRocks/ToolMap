package fr.toolmap.map

import fr.toolmap.data.text.GlyphInterceptor
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue

/**
 * The names written next to the waypoints and the group's members (ADR-062): dark text with a white halo, on the right
 * of the marker, in the system font served by [GlyphInterceptor]. A name that would cover another one is not drawn
 * (the marker stays); a tap on the marker still opens its sheet.
 */
object MapLabels {
    private const val TEXT_SIZE = 13f
    private const val GAP = 4f
    private const val HALO_WIDTH = 1.6f
    private const val HALO_BLUR = 0.4f
    private const val MAX_WIDTH_EMS = 12f
    private const val PADDING = 2f
    private const val TEXT_COLOR = "#1f1f1f"
    private const val HALO_COLOR = "#ffffff"

    /** All the properties of a label layer whose features carry a `name`, for markers of [radius] dp. */
    fun properties(radius: Float): Array<PropertyValue<*>> =
        arrayOf(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf(GlyphInterceptor.FONT)),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_LEFT),
            PropertyFactory.textJustify(Property.TEXT_JUSTIFY_LEFT),
            PropertyFactory.textMaxWidth(MAX_WIDTH_EMS),
            PropertyFactory.textPadding(PADDING),
            PropertyFactory.textColor(TEXT_COLOR),
            PropertyFactory.textHaloColor(HALO_COLOR),
            PropertyFactory.textHaloWidth(HALO_WIDTH),
            PropertyFactory.textHaloBlur(HALO_BLUR),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textIgnorePlacement(false),
            *sized(radius, 1f),
        )

    /** The size of the text and its distance from the centre of the marker, which follow the placemark size. */
    fun sized(
        radius: Float,
        factor: Float,
    ): Array<PropertyValue<*>> {
        val size = TEXT_SIZE * factor
        return arrayOf(
            PropertyFactory.textSize(size),
            PropertyFactory.textOffset(arrayOf((radius + GAP * factor) / size, 0f)),
        )
    }
}
