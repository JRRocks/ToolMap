package fr.toolmap.core.print

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.pdf.PdfImage
import java.util.Locale

/** A track as it is drawn on the page: its segments, colour and width. */
class PrintTrack(
    val name: String,
    val colorArgb: Int,
    /** Line width on the paper, in millimeters. */
    val widthMm: Double,
    val segments: List<List<LonLat>>,
)

class PrintWaypoint(
    val name: String?,
    val lat: Double,
    val lon: Double,
    val colorArgb: Int,
    /** The icon of the waypoint (catalogue id), whose white glyph is drawn on the disc when [PrintContent.glyphs] has it. */
    val iconId: String? = null,
)

/**
 * The words written on the page. The defaults are English so the layout can be tested; the app passes the user's
 * language. `%s` / `%1$s` / `%d` are filled by [String.format] with the page locale.
 */
data class PrintTexts(
    val legendTitle: String = "Legend",
    val layers: String = "Layers",
    val tracks: String = "Tracks",
    val waypoints: String = "Waypoints (%d)",
    val printedOn: String = "Printed on %s",
    val gridDescription: String = "%1\$s grid, %2\$s",
    val declination: String = "Declination",
    val gridConvergence: String = "Grid convergence",
    val page: String = "Page %1\$d of %2\$d",
    val overviewTitle: String = "Overview of the %d pages",
    val producedWith: String = "Made with ToolMap",
    val missingTiles: String = "Hatched areas: tiles not available",
    val oversampled: String = "%1\$s: enlarged x%2\$s",
    val east: String = "E",
    val west: String = "W",
    val north: String = "N",
    val scaleLabel: String = "1:%s",
    val coordinateSystem: String = "WGS84 / Web Mercator",
    // ---- sheet of P17
    val south: String = "S",
    val mapSource: String = "Map: %s",
    val cmOnGround: String = "1 cm on the map = %s on the ground",
    val scaleNote: String = "exact at the centre, ±%s %% at the edges",
    val declinationDetail: String = "in %1\$d, changing %2\$s a year",
    val gridSquares: String = "Squares of %s",
    val gridLines: String = "Lines every %s",
    val trueNorth: String = "TN",
    val magneticNorth: String = "MN",
    val gridNorth: String = "GN",
    val trueNorthName: String = "true north",
    val annualChange: String = "annual change %s",
    val gridMagneticAngle: String = "Grid–magnetic angle",
    val mils: String = "%s mils",
    val notToScale: String = "Diagram not to scale",
    val noGrid: String = "No grid",
    val principalDigits: String = "Large figures: kilometers",
    /** MGRS: what the two figures of a line are (ADR-087). */
    val squareKilometers: String = "= %s km inside the 100 km square",
    val zoneEdgeMeridian: String = "Zones %1\$s / %2\$s boundary: meridian %3\$s (thick line)",
    val zoneEdgeParallel: String = "Bands %1\$s / %2\$s boundary: parallel %3\$s (thick line)",
    val neighbourGrid: String = "In blue in the margin: grid of the neighbouring zone",
    val and: String = "and",
    val squares: String = "100 km square: %s",
    val squaresOfZones: String = "100 km squares: %s",
    val westSide: String = "west",
    val eastSide: String = "east",
    val southSide: String = "south",
    val northSide: String = "north",
    val referenceTitle: String = "GIVING A GRID REFERENCE (100 m)",
    val referenceStep1: String = "1. Letters of the 100 km square: %s",
    val referenceStep2: String = "2. Vertical line west of the point: its 2 large figures, then tenths eastwards",
    val referenceStep3: String = "3. Horizontal line south of the point: its 2 large figures, then tenths northwards",
    /** UTM and Lambert 93: a reference in meters (ADR-087). */
    val referenceUtmStep1: String = "1. Grid zone: %s",
    val referenceMetersStep2: String =
        "2. Vertical line west of the point: its kilometers, then hundreds of meters eastwards",
    val referenceMetersStep3: String =
        "3. Horizontal line south of the point: its kilometers, then hundreds of meters northwards",
    val referenceExample: String = "Sheet centre: %s",
    val scaleHeading: String = "SCALE",
    val northHeading: String = "NORTHS",
    val gridHeading: String = "GRID",
)

/** What goes on the pages besides the map tiles; everything is optional. */
class PrintContent(
    val tracks: List<PrintTrack> = emptyList(),
    val waypoints: List<PrintWaypoint> = emptyList(),
    /** `© …` credits of the sources printed, required by their licences. */
    val attributions: List<String> = emptyList(),
    /** The layers actually drawn, bottom to top, for the legend. */
    val layerNames: List<String> = emptyList(),
    /** Magnetic declination at the centre in degrees, east positive; `null` when unknown. */
    val declinationDegrees: Double? = null,
    /** Year the declination model was evaluated for. */
    val declinationYear: Int? = null,
    /** How much the declination changes in a year, in degrees (east positive). */
    val declinationAnnualDegrees: Double? = null,
    /** White glyphs of the waypoint icons, by icon id, with their transparency. */
    val glyphs: Map<String, PdfImage> = emptyMap(),
    val dateText: String = "",
    val texts: PrintTexts = PrintTexts(),
    val locale: Locale = Locale.ROOT,
)

/** Number and angle formats shared by the page elements. */
object PrintFormat {
    /** `1:25 000`: the scale with a no-break space as thousands separator, the way a French map writes it. */
    fun scale(
        denominator: Double,
        texts: PrintTexts,
    ): String {
        val digits = Math.round(denominator).toString()
        val grouped = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % GROUP == 0) grouped.append(' ')
            grouped.append(c)
        }
        return String.format(Locale.ROOT, texts.scaleLabel, grouped.toString())
    }

    /** `2°15′ E`: degrees and minutes of an angle, with the side given by [east] / [west]. */
    fun declination(
        degrees: Double,
        east: String,
        west: String,
    ): String {
        val totalMinutes = Math.round(Math.abs(degrees) * MINUTES_PER_DEGREE)
        val d = totalMinutes / MINUTES_PER_DEGREE.toLong()
        val m = totalMinutes % MINUTES_PER_DEGREE.toLong()
        val side = if (degrees >= 0) east else west
        return String.format(Locale.ROOT, "%d°%02d′ %s", d, m, side)
    }

    /**
     * The ground size of a frame, both sides written alike in [locale]: "6,6 km" and "3,7 km" (one decimal under
     * 100 km, none above), or "850 m" and "480 m" under a kilometer.
     */
    fun groundSize(
        widthMeters: Double,
        heightMeters: Double,
        locale: Locale,
    ): Pair<String, String> {
        val largest = maxOf(widthMeters, heightMeters)
        val side = { meters: Double ->
            when {
                largest < METERS_PER_KM -> String.format(locale, "%.0f m", meters)
                largest < WHOLE_KM_FROM_M -> String.format(locale, "%.1f km", meters / METERS_PER_KM)
                else -> String.format(locale, "%.0f km", meters / METERS_PER_KM)
            }
        }
        return side(widthMeters) to side(heightMeters)
    }

    /** NATO mils (6 400 to the turn), rounded: 2°15′ → 40. */
    fun mils(degrees: Double): Long = Math.round(Math.abs(degrees) * MILS_PER_DEGREE)

    /**
     * A file name for the PDF: `ToolMap_Belledonne_1-25000_2026-10-02.pdf`. Characters a file system or a share sheet
     * dislikes become `_`; a blank title gives "carte".
     */
    fun fileName(
        title: String,
        scale: Double,
        isoDate: String,
    ): String {
        val cleaned =
            title
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|·\\p{Cntrl}\\s]+"), "_")
                .trim('_', '.')
                .take(MAX_TITLE_IN_NAME)
                .ifEmpty { "carte" }
        return "ToolMap_${cleaned}_1-${Math.round(scale)}_$isoDate.pdf"
    }

    private const val GROUP = 3
    private const val METERS_PER_KM = 1_000.0
    private const val WHOLE_KM_FROM_M = 100_000.0
    private const val MAX_TITLE_IN_NAME = 48
    private const val MINUTES_PER_DEGREE = 60.0
    private const val MILS_PER_DEGREE = 6_400.0 / 360.0
}
