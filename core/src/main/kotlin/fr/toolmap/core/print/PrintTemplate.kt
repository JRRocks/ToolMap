package fr.toolmap.core.print

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The elements drawn around and over the map, each one switchable (spec §4). */
@Serializable
data class Dressing(
    val scaleText: Boolean = true,
    val scaleBar: Boolean = true,
    val northArrow: Boolean = true,
    val legend: Boolean = true,
    val cartouche: Boolean = true,
    /** Situation map of an atlas page; the single-page print of P17 does not draw it. */
    val miniMap: Boolean = false,
    val cornerCoordinates: Boolean = true,
    val tracks: Boolean = true,
    val waypoints: Boolean = true,
    /** The name next to each waypoint; off leaves the icons alone (P17, Q16). */
    val waypointNames: Boolean = true,
)

/**
 * Everything the user chooses for a print except where the frame is and what is on the map: the frame is placed on
 * the map, the content comes from what the map shows. The last one used is remembered (P17, Q22).
 */
@Serializable
data class PrintTemplate(
    val page: PageSetup = PageSetup(),
    /** The scale denominator: 25 000 for 1:25 000. */
    val scale: Double = DEFAULT_SCALE,
    val dpi: Int = DEFAULT_DPI,
    /** One zoom level less than the sharp one: a lighter file, a touch softer. */
    val lighter: Boolean = false,
    /** Leave out the layers whose display range (N3) does not reach this scale. */
    val respectLayerRanges: Boolean = true,
    val title: String = "",
    val subtitle: String = "",
    val dressing: Dressing = Dressing(),
    val grid: GridKind = GridKind.UTM,
    /** Spacing of the grid lines in the grid's own unit (meters, or degrees); `null` follows the scale ([GridSpacing]). */
    val gridStep: Double? = null,
    val atlas: AtlasMode = AtlasMode.SINGLE,
    val overlapPercent: Double = DEFAULT_OVERLAP_PERCENT,
) {
    /** The same template with every figure brought back into its valid range (a hand-edited or old file). */
    fun sanitized(): PrintTemplate =
        copy(
            scale = if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE,
            dpi = dpi.coerceIn(MIN_DPI, MAX_DPI),
            gridStep = gridStep?.takeIf { it.isFinite() && it > 0.0 },
            // The situation map is no longer drawn (P17): its tiles are not fetched either.
            dressing = dressing.copy(miniMap = false),
            overlapPercent =
                overlapPercent.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_OVERLAP_PERCENT)
                    ?: DEFAULT_OVERLAP_PERCENT,
            page =
                page.copy(
                    marginMm =
                        page.marginMm.takeIf { it.isFinite() }?.coerceIn(0.0, PageSetup.MAX_MARGIN_MM)
                            ?: PageSetup.DEFAULT_MARGIN_MM,
                ),
        )

    /** The grid spacing actually drawn: the one chosen, or the one of the scale. */
    val effectiveGridStep: Double get() = gridStep ?: GridSpacing.auto(grid, scale)

    companion object {
        const val DEFAULT_SCALE = 25_000.0
        const val DEFAULT_DPI = 300
        const val DEFAULT_OVERLAP_PERCENT = 10.0
        const val MIN_SCALE = 500.0
        const val MAX_SCALE = 10_000_000.0
        const val MIN_DPI = 72
        const val MAX_DPI = 600
        const val MAX_OVERLAP_PERCENT = 50.0

        /** The scales offered as presets (P17, Q3); any other value can be typed. */
        val SCALES: List<Int> = listOf(10_000, 20_000, 25_000, 50_000, 100_000, 250_000)

        val DPIS: List<Int> = listOf(150, 200, 300)
    }
}

/** Reads and writes templates as JSON, tolerating files from other versions (unknown fields, missing ones). */
object PrintTemplateCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(template: PrintTemplate): String = json.encodeToString(PrintTemplate.serializer(), template)

    /** The template in [text], sanitized; `null` when it is not a template at all. */
    fun decode(text: String): PrintTemplate? =
        try {
            json.decodeFromString(PrintTemplate.serializer(), text).sanitized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
