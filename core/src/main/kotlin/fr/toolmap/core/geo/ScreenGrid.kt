package fr.toolmap.core.geo

import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.GridPlanner
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/** The grid drawn over the map for the visible region: lines as GeoJSON, and what to say about it in a chip. */
class ScreenGridPlan(
    val kind: GridKind,
    /** A FeatureCollection of LineStrings; `level` ([GridLineLevel]) tells how each one is drawn. */
    val geoJson: String,
    /**
     * The spacing of the lines in the unit of the grid (`1 km`, `30′`), empty when there are no grid lines (a UTM
     * view too wide for them has its zone edges alone).
     */
    val stepText: String,
    /** The coordinate system (`UTM 31T`, `Lambert 93`), empty when there are no grid lines. */
    val systemText: String,
    /** Why there is no grid, or a caution about it. */
    val issue: ScreenGridIssue = ScreenGridIssue.NONE,
    /** UTM and MGRS: a FeatureCollection of Points with the names of the zones and squares in view (P24.3). */
    val labelsGeoJson: String = EMPTY_GEOJSON,
    /**
     * The angle from true north to grid north at the centre, east positive (UTM, MGRS, Lambert 93 in France); `null`
     * when the chip is not shown or the grid has none there.
     */
    val convergenceDegrees: Double? = null,
) {
    val isEmpty: Boolean get() = stepText.isEmpty()

    companion object {
        const val EMPTY_GEOJSON = """{"type":"FeatureCollection","features":[]}"""

        fun none(
            kind: GridKind = GridKind.NONE,
            issue: ScreenGridIssue = ScreenGridIssue.NONE,
        ) = ScreenGridPlan(kind, EMPTY_GEOJSON, "", "", issue)
    }
}

enum class ScreenGridIssue {
    NONE,

    /** The region is beyond the latitudes UTM and MGRS are defined for (80 S .. 84 N). */
    POLAR,

    /** Lambert 93 is drawn, but the region is outside the area it was made for (France). */
    OUTSIDE_VALIDITY,

    /** The region is too wide for a useful grid at any step. */
    TOO_WIDE,
}

/** How a grid line is drawn: the `level` property of its feature in [ScreenGridPlan.geoJson]. */
object GridLineLevel {
    /** WGS84, Lambert 93, and UTM under 1 km: a line of the step. */
    const val MINOR = 0

    /** WGS84, Lambert 93, and UTM under 1 km: every fifth line. */
    const val MAJOR = 1

    /** UTM and MGRS from 1 km: a line of the step (P24.2, variant A of the P24.0 preview). */
    const val STEP = 2

    /** UTM and MGRS from 1 km: a 10 km line. */
    const val TEN_KM = 3

    /** UTM and MGRS from 1 km: a 100 km line, the edge of an MGRS square. */
    const val SQUARE = 4

    /** UTM and MGRS: the edge of a grid zone, a 6° meridian or the parallel between two latitude bands. */
    const val ZONE_EDGE = 5
}

/** How a name of the grid is written: the `kind` property of its feature in [ScreenGridPlan.labelsGeoJson]. */
object GridLabelKind {
    /** « 31T GL »: a zone with one square in view (« 31T » in UTM), just above the middle of the square. */
    const val NAME = "name"

    /** « 31T »: a zone with several squares in view, larger, in the middle of its part of the screen. */
    const val ZONE = "zone"

    /** « GL »: the letters of a square of such a zone, just below the middle of its part of the screen. */
    const val SQUARE = "square"
}

/**
 * Plans the grid of the visible region (feature P9.3). The lines are straight in their own system and slightly
 * curved on the Web Mercator map: each one is sampled and projected back to longitude and latitude. UTM and MGRS draw
 * each grid zone in view with its own grid, cut at the zone's edges, and the edges themselves (P24.2), with the names
 * of the zones and of the MGRS squares (P24.3, ADR-073). The chip tells the system, the spacing and the convergence;
 * the coordinates of the centre are in the box of the screen.
 */
object ScreenGrid {
    /** Roughly this many lines across the longest side of the region. */
    private const val TARGET_LINES = 6.0
    private const val MAX_LINES = 40
    private const val SAMPLES = 24
    private const val FIRST_STEP_METERS = 10.0
    private const val MAX_STEP_METERS = 1_000_000.0
    private const val MAJOR_EVERY = 5
    private const val SQUARE_METERS = 100_000.0
    private const val UTM_MAX_LAT = 84.0
    private const val UTM_MIN_LAT = -80.0

    /** Angular steps of the WGS84 graticule in degrees: 5″ 10″ 30″ 1′ 2′ 5′ 10′ 30′ 1° 2° 5° 10° 20°. */
    private val DEGREE_STEPS =
        listOf(
            5.0 / 3600,
            10.0 / 3600,
            30.0 / 3600,
            1.0 / 60,
            2.0 / 60,
            5.0 / 60,
            10.0 / 60,
            30.0 / 60,
            1.0,
            2.0,
            5.0,
            10.0,
            20.0,
        )

    /**
     * The grid of [kind] over [area] (west, south, east, north in degrees). [spacing] scales the step between two lines:
     * 2 gives half as many lines, 0.5 twice as many (« Densité des grilles », P11.8).
     */
    fun plan(
        kind: GridKind,
        area: Bounds,
        spacing: Double = 1.0,
    ): ScreenGridPlan {
        if (kind == GridKind.NONE) return ScreenGridPlan.none()
        if (area.maxX <= area.minX || area.maxY <= area.minY) return ScreenGridPlan.none(kind)
        val centerLat = (area.minY + area.maxY) / 2
        val centerLon = (area.minX + area.maxX) / 2
        return when (kind) {
            GridKind.NONE -> {
                ScreenGridPlan.none()
            }

            // The screen draws one graticule; the decimal one only changes how a print writes its labels.
            GridKind.WGS84, GridKind.WGS84_DECIMAL -> {
                graticule(area, spacing)
            }

            GridKind.UTM, GridKind.MGRS -> {
                if (area.maxY > UTM_MAX_LAT || area.minY < UTM_MIN_LAT) {
                    ScreenGridPlan.none(kind, ScreenGridIssue.POLAR)
                } else {
                    zoned(kind, area, centerLat, centerLon, spacing)
                }
            }

            GridKind.LAMBERT93 -> {
                lambert(area, outside = !Lambert93.covers(centerLat, centerLon), spacing)
            }
        }
    }

    private fun graticule(
        area: Bounds,
        spacing: Double,
    ): ScreenGridPlan {
        val span = max(area.maxX - area.minX, area.maxY - area.minY)
        val step = DEGREE_STEPS.firstOrNull { span / it <= TARGET_LINES * 1.5 / spacing } ?: DEGREE_STEPS.last()
        if (span / step > MAX_LINES) return ScreenGridPlan.none(GridKind.WGS84, ScreenGridIssue.TOO_WIDE)
        val features = ArrayList<String>()
        val firstLon = ceil(area.minX / step).toLong()
        val lastLon = floor(area.maxX / step).toLong()
        for (k in firstLon..lastLon) {
            val lon = k * step
            features += line(listOf(LonLat(lon, area.minY), LonLat(lon, area.maxY)), graticuleLevel(k))
        }
        val firstLat = ceil(area.minY / step).toLong()
        val lastLat = floor(area.maxY / step).toLong()
        for (k in firstLat..lastLat) {
            val lat = k * step
            features += line(listOf(LonLat(area.minX, lat), LonLat(area.maxX, lat)), graticuleLevel(k))
        }
        return ScreenGridPlan(GridKind.WGS84, collection(features), degreesText(step), "WGS84")
    }

    private fun graticuleLevel(k: Long): Int = if (k % MAJOR_EVERY == 0L) GridLineLevel.MAJOR else GridLineLevel.MINOR

    /**
     * UTM and MGRS: each grid zone in view draws its own grid, cut at its edges, and the edges are drawn too (P24.2).
     * The chip names the zone of the centre.
     */
    private fun zoned(
        kind: GridKind,
        area: Bounds,
        centerLat: Double,
        centerLon: Double,
        spacing: Double,
    ): ScreenGridPlan {
        val zone = Utm.zoneOf(centerLat, centerLon)
        // One hemisphere for the whole region: its northings run on across the equator.
        val north = centerLat >= 0
        val band = Utm.bandLetter(centerLat) ?: 'N'
        val name = if (kind == GridKind.MGRS) "MGRS $zone$band" else "UTM $zone$band"
        val edges = GridZones.edges(area).map { line(it, GridLineLevel.ZONE_EDGE) }
        val labels = zoneLabels(kind, area)
        // The step comes from the region measured in the grid of the centre zone, as before P24.2. Past 100 km, one
        // line of the MGRS squares in two would be missing: the step stops there (variant A of the P24.0 preview).
        val extent = extent(area, utmForward(zone, north))
        val minStep = if (kind == GridKind.MGRS) MGRS_MIN_STEP else FIRST_STEP_METERS
        val step = min(niceStep(extent.span / TARGET_LINES * spacing, minStep), SQUARE_METERS)
        // Too wide for grid lines: the zone edges and the names alone, without a chip or a note.
        if (extent.span / step > MAX_LINES) {
            return ScreenGridPlan(kind, collection(edges), "", "", labelsGeoJson = labels)
        }

        // Variant A: the 10 and 100 km lines are heavier at every step, so they stay as they were when it grows.
        fun level(value: Double): Int =
            when {
                // Under 1 km (UTM only, not in the preview): every fifth line, as before P24.2.
                step < MGRS_MIN_STEP -> {
                    if (isMultiple(value, step * MAJOR_EVERY)) GridLineLevel.MAJOR else GridLineLevel.MINOR
                }

                isMultiple(value, SQUARE_METERS) -> {
                    GridLineLevel.SQUARE
                }

                isMultiple(value, TEN_KM_METERS) -> {
                    GridLineLevel.TEN_KM
                }

                else -> {
                    GridLineLevel.STEP
                }
            }
        val features = ArrayList<String>()
        for (piece in GridZones.pieces(area)) {
            // The piece in the longitudes of its zone, those of the screen less the shift of its world copy.
            val own =
                Bounds(piece.area.minX - piece.shift, piece.area.minY, piece.area.maxX - piece.shift, piece.area.maxY)
            val meridian = Utm.centralMeridian(piece.zone)
            val inverse = { x: Double, y: Double ->
                // Unwrapped around the central meridian: zone 1 carried past 180° W stays below -180.
                val p = Utm.toLonLat(UtmCoordinate(piece.zone, north, x, y))
                LonLat(meridian + Utm.normalizeLon(p.lon - meridian), p.lat)
            }
            for ((points, value) in straightLines(extent(own, utmForward(piece.zone, north)), step, inverse)) {
                for (part in clip(points, own)) {
                    features += line(part.map { LonLat(it.lon + piece.shift, it.lat) }, level(value))
                }
            }
        }
        features += edges
        return ScreenGridPlan(
            kind = kind,
            geoJson = collection(features),
            stepText = ScaleBar.format(step),
            systemText = name,
            labelsGeoJson = labels,
            convergenceDegrees = GridPlanner.convergenceDegrees(kind, centerLat, centerLon),
        )
    }

    /** A place on the screen where a name goes: the middle of the samples that fell in its zone or square. */
    private class Spot {
        var lon = 0.0
        var y = 0.0
        var count = 0

        fun add(
            lon: Double,
            y: Double,
        ) {
            this.lon += lon
            this.y += y
            count++
        }

        fun at(): LonLat = LonLat(lon / count, latitudeOf(y / count))
    }

    /**
     * The names of the zones and of the MGRS squares in view, placed as in the P24.0 preview: the screen is sampled
     * (13 points across its narrower side); a zone with one square in view is named with it (« 31T GL ») above the
     * middle of the square, otherwise the zone is named in the middle of its part of the screen (« 31T ») and each
     * square that takes enough of it is named below its middle (« GL »). UTM names the zones only.
     */
    private fun zoneLabels(
        kind: GridKind,
        area: Bounds,
    ): String {
        val south = mercatorY(area.minY)
        val north = mercatorY(area.maxY)
        val width = Math.toRadians(area.maxX - area.minX)
        val height = north - south
        val ratio = max(width, height) / min(width, height)
        val across = (LABEL_SAMPLES * ratio).roundToInt().coerceIn(1, MAX_LABEL_SAMPLES)
        val columns = if (width <= height) LABEL_SAMPLES else across
        val rows = if (width <= height) across else LABEL_SAMPLES
        val zones = LinkedHashMap<String, Spot>()
        val squares = LinkedHashMap<String, Spot>()
        val squaresOfZone = LinkedHashMap<String, LinkedHashSet<String>>()
        for (i in 0 until columns) {
            val lon = area.minX + (area.maxX - area.minX) * (i + 0.5) / columns
            for (j in 0 until rows) {
                val y = south + height * (j + 0.5) / rows
                val ref = Mgrs.of(latitudeOf(y), lon) ?: continue
                val zone = "${ref.zone}${ref.band}"
                val square = "$zone ${ref.columnLetter}${ref.rowLetter}"
                zones.getOrPut(zone) { Spot() }.add(lon, y)
                squares.getOrPut(square) { Spot() }.add(lon, y)
                squaresOfZone.getOrPut(zone) { LinkedHashSet() } += square
            }
        }
        val enough = columns * rows * SQUARE_SHARE
        val features = ArrayList<String>()
        for ((zone, spot) in zones) {
            val inZone = squaresOfZone.getValue(zone)
            when {
                kind != GridKind.MGRS -> {
                    features += label(spot.at(), zone, GridLabelKind.NAME)
                }

                inZone.size == 1 -> {
                    features += label(squares.getValue(inZone.single()).at(), inZone.single(), GridLabelKind.NAME)
                }

                else -> {
                    features += label(spot.at(), zone, GridLabelKind.ZONE)
                    for (square in inZone) {
                        val s = squares.getValue(square)
                        if (s.count >
                            enough
                        ) {
                            features += label(s.at(), square.substringAfter(' '), GridLabelKind.SQUARE)
                        }
                    }
                }
            }
        }
        return collection(features)
    }

    private fun mercatorY(lat: Double): Double = ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))

    private fun latitudeOf(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - Math.PI / 2)

    private fun label(
        at: LonLat,
        text: String,
        kind: String,
    ): String =
        """{"type":"Feature","properties":{"text":"$text","kind":"$kind"},"geometry":""" +
            String.format(Locale.ROOT, """{"type":"Point","coordinates":[%.7f,%.7f]}}""", at.lon, at.lat)

    private fun utmForward(
        zone: Int,
        north: Boolean,
    ): (Double, Double) -> Pair<Double, Double> =
        { lat, lon -> Utm.toUtm(lat, lon, zone, north).let { it.easting to it.northing } }

    private fun lambert(
        area: Bounds,
        outside: Boolean,
        spacing: Double,
    ): ScreenGridPlan {
        val extent = extent(area) { lat, lon -> Lambert93.toLambert(lat, lon).let { it.easting to it.northing } }
        val step = niceStep(extent.span / TARGET_LINES * spacing, FIRST_STEP_METERS)
        if (step > MAX_STEP_METERS || extent.span / step > MAX_LINES) {
            return ScreenGridPlan.none(GridKind.LAMBERT93, ScreenGridIssue.TOO_WIDE)
        }
        val features =
            straightLines(extent, step) { x, y -> Lambert93.toLonLat(PlanarCoordinate(x, y)) }.map { (points, value) ->
                line(points, if (isMultiple(value, step * MAJOR_EVERY)) GridLineLevel.MAJOR else GridLineLevel.MINOR)
            }
        val issue = if (outside) ScreenGridIssue.OUTSIDE_VALIDITY else ScreenGridIssue.NONE
        return ScreenGridPlan(
            kind = GridKind.LAMBERT93,
            geoJson = collection(features),
            stepText = ScaleBar.format(step),
            systemText = "Lambert 93",
            issue = issue,
            convergenceDegrees =
                GridPlanner.convergenceDegrees(
                    GridKind.LAMBERT93,
                    (area.minY + area.maxY) / 2,
                    (area.minX + area.maxX) / 2,
                ),
        )
    }

    private class Extent(
        val minX: Double,
        val maxX: Double,
        val minY: Double,
        val maxY: Double,
    ) {
        val span: Double get() = max(maxX - minX, maxY - minY)
    }

    /** The region's extent in grid coordinates: its outline is sampled, a curved side may bulge past the corners. */
    private fun extent(
        area: Bounds,
        forward: (lat: Double, lon: Double) -> Pair<Double, Double>,
    ): Extent {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in 0..SAMPLES) {
            val t = i.toDouble() / SAMPLES
            val lon = area.minX + (area.maxX - area.minX) * t
            val lat = area.minY + (area.maxY - area.minY) * t
            for ((la, lo) in listOf(area.minY to lon, area.maxY to lon, lat to area.minX, lat to area.maxX)) {
                val (x, y) = forward(la, lo)
                xs += x
                ys += y
            }
        }
        return Extent(xs.min(), xs.max(), ys.min(), ys.max())
    }

    /** Lines of constant x and of constant y at [step] over [extent], in longitude and latitude, with their value. */
    private fun straightLines(
        extent: Extent,
        step: Double,
        inverse: (x: Double, y: Double) -> LonLat,
    ): List<Pair<List<LonLat>, Double>> {
        val lines = ArrayList<Pair<List<LonLat>, Double>>()
        for (x in multiples(extent.minX, extent.maxX, step)) {
            lines += List(SAMPLES + 1) { i -> inverse(x, extent.minY + (extent.maxY - extent.minY) * i / SAMPLES) } to x
        }
        for (y in multiples(extent.minY, extent.maxY, step)) {
            lines += List(SAMPLES + 1) { i -> inverse(extent.minX + (extent.maxX - extent.minX) * i / SAMPLES, y) } to y
        }
        return lines
    }

    /**
     * The parts of [points] inside [area], longitude and latitude taken as plane coordinates: the samples of a line
     * are close enough for its curve between two of them not to show.
     */
    internal fun clip(
        points: List<LonLat>,
        area: Bounds,
    ): List<List<LonLat>> {
        val parts = ArrayList<List<LonLat>>()
        var part = ArrayList<LonLat>()

        fun close() {
            if (part.size >= 2) parts += part
            part = ArrayList()
        }
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val t = clipSegment(a, b, area)
            if (t == null) {
                close()
                continue
            }
            val (t0, t1) = t
            // Entering the area starts a new part, leaving it ends the part.
            if (t0 > 0.0) close()
            if (part.isEmpty()) part += pointAt(a, b, t0)
            part += pointAt(a, b, t1)
            if (t1 < 1.0) close()
        }
        close()
        return parts
    }

    /** Liang–Barsky: the fractions of the segment [a] [b] where it enters and leaves [area], `null` when outside. */
    private fun clipSegment(
        a: LonLat,
        b: LonLat,
        area: Bounds,
    ): Pair<Double, Double>? {
        val dx = b.lon - a.lon
        val dy = b.lat - a.lat
        var t0 = 0.0
        var t1 = 1.0
        val sides =
            listOf(
                -dx to a.lon - area.minX,
                dx to area.maxX - a.lon,
                -dy to a.lat - area.minY,
                dy to area.maxY - a.lat,
            )
        for ((p, q) in sides) {
            if (p == 0.0) {
                if (q < 0.0) return null
            } else if (p < 0.0) {
                t0 = max(t0, q / p)
            } else {
                t1 = min(t1, q / p)
            }
        }
        return if (t0 < t1) t0 to t1 else null
    }

    private fun pointAt(
        a: LonLat,
        b: LonLat,
        t: Double,
    ): LonLat =
        when (t) {
            0.0 -> a
            1.0 -> b
            else -> LonLat(a.lon + (b.lon - a.lon) * t, a.lat + (b.lat - a.lat) * t)
        }

    /** The 1, 2 or 5 × 10^n not smaller than [target] and at least [min]. */
    internal fun niceStep(
        target: Double,
        min: Double,
    ): Double {
        val wanted = max(target, min)
        val base = Math.pow(10.0, floor(ln(wanted) / ln(10.0)))
        for (m in listOf(1.0, 2.0, 5.0, 10.0)) if (m * base >= wanted * (1 - EPS)) return m * base
        return 10 * base
    }

    private fun multiples(
        from: Double,
        to: Double,
        step: Double,
    ): List<Double> {
        val first = ceil(from / step).toLong()
        val last = floor(to / step).toLong()
        return (first..last).map { it * step }
    }

    private fun isMultiple(
        value: Double,
        of: Double,
    ): Boolean {
        val q = value / of
        return abs(q - Math.round(q)) < EPS
    }

    private fun degreesText(step: Double): String {
        val seconds = Math.round(step * 3600.0)
        return when {
            seconds < 60 -> "$seconds″"
            seconds < 3600 -> "${seconds / 60}′"
            else -> "${seconds / 3600}°"
        }
    }

    private fun line(
        points: List<LonLat>,
        level: Int,
    ): String {
        val coordinates =
            points.joinToString(",", "[", "]") {
                String.format(Locale.ROOT, "[%.7f,%.7f]", it.lon, it.lat)
            }
        return """{"type":"Feature","properties":{"level":$level},"geometry":""" +
            """{"type":"LineString","coordinates":$coordinates}}"""
    }

    private fun collection(features: List<String>): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private const val MGRS_MIN_STEP = 1000.0
    private const val TEN_KM_METERS = 10_000.0

    /** Samples of the screen for the names: across its narrower side, and at most along the other. */
    private const val LABEL_SAMPLES = 13
    private const val MAX_LABEL_SAMPLES = 40

    /** A square is named when it takes this share of the screen: 6 of the 13 × 25 samples of the preview. */
    private const val SQUARE_SHARE = 6.0 / 325
    private const val EPS = 1e-9
}
