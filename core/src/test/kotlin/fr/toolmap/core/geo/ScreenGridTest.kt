package fr.toolmap.core.geo

import fr.toolmap.core.print.GridKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenGridTest {
    private class Line(
        val points: List<LonLat>,
        val level: Int,
    ) {
        val isEdge: Boolean get() = level == GridLineLevel.ZONE_EDGE
        val midLon: Double get() = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2
        val midLat: Double get() = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
    }

    private fun lines(plan: ScreenGridPlan): List<Line> {
        val root = Json.parseToJsonElement(plan.geoJson).jsonObject
        return root["features"]!!.jsonArray.map { f ->
            val o = f.jsonObject
            val coords = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            Line(
                coords.map { c ->
                    (c as JsonArray).let {
                        LonLat(it[0].jsonPrimitive.content.toDouble(), it[1].jsonPrimitive.content.toDouble())
                    }
                },
                (o["properties"] as JsonObject)["level"]!!.jsonPrimitive.int,
            )
        }
    }

    /** A grid line read in [zone]: does it run north-south (constant easting), and its easting or northing. */
    private class GridValue(
        val zone: Int,
        val vertical: Boolean,
        val meters: Long,
        val level: Int,
    )

    private fun gridValue(
        line: Line,
        zone: Int,
        north: Boolean,
        tolerance: Double = toleranceMeters,
    ): GridValue {
        val utm = line.points.map { Utm.toUtm(it.lat, it.lon, zone, north) }
        val eSpread = utm.maxOf { it.easting } - utm.minOf { it.easting }
        val nSpread = utm.maxOf { it.northing } - utm.minOf { it.northing }
        val vertical = eSpread < tolerance
        assertTrue(
            vertical || nSpread < tolerance,
            "a line that is not straight in zone $zone ($eSpread, $nSpread) at ${line.midLat},${line.midLon}",
        )
        // A sample from the middle: the ends cut at an edge are interpolated.
        val middle = utm[utm.size / 2]
        val value = if (vertical) middle.easting else middle.northing
        return GridValue(zone, vertical, value.roundToLong(), line.level)
    }

    /**
     * Coordinates are written with 7 decimals of a degree (1 cm) and the samples of a line are on it; the ends cut at
     * the edge of the area or of the zone are interpolated between two samples, a few centimeters off on a walker's
     * screen, well under a meter tens of km across.
     */
    private val toleranceMeters = 1.0

    private companion object {
        /** Hundreds of km across, samples 15 to 35 km apart: a cut end a few meters off, a hundredth of a pixel. */
        const val WIDE_TOLERANCE = 20.0

        /** Lambert 93 lines are not cut: every point is a sample on the line. */
        const val UNCUT_TOLERANCE = 0.1
    }

    private fun distanceToMultiple(
        value: Double,
        step: Double,
    ): Double = abs(value - Math.round(value / step) * step)

    private fun stepMeters(plan: ScreenGridPlan): Double =
        plan.stepText.removeSuffix(" km").removeSuffix(" m").toDouble() * if (plan.stepText.endsWith("km")) 1000 else 1

    // A walker's screen: about 4 km across around Grenoble.
    private val grenoble = Bounds(5.70, 45.17, 5.75, 45.21)

    // The user's screenshots: Pontcharra, on both sides of 6° E (31T / 32T).
    private val pontcharra = Bounds(5.94, 45.39, 6.07, 45.47)

    @Test
    fun `no grid is an empty collection`() {
        val plan = ScreenGrid.plan(GridKind.NONE, grenoble)
        assertTrue(plan.isEmpty)
        assertEquals(ScreenGridPlan.EMPTY_GEOJSON, plan.geoJson)
    }

    @Test
    fun `UTM lines have a constant easting or northing and a round step`() {
        val plan = ScreenGrid.plan(GridKind.UTM, grenoble)
        assertEquals("UTM 31T", plan.systemText)
        assertTrue(plan.stepText in setOf("500 m", "1 km"), plan.stepText)
        val all = lines(plan)
        assertTrue(all.size in 6..40, "${all.size} lines")
        assertTrue(all.none { it.isEdge }, "no zone edge near Grenoble")
        val step = stepMeters(plan)
        var vertical = 0
        var horizontal = 0
        for (line in all) {
            val v = gridValue(line, 31, true)
            assertEquals(0.0, distanceToMultiple(v.meters.toDouble(), step), 1.0, "line at ${v.meters}")
            if (v.vertical) vertical++ else horizontal++
        }
        assertTrue(vertical >= 3 && horizontal >= 3, "$vertical vertical, $horizontal horizontal")
    }

    @Test
    fun `a finer density gives more lines than a coarser one`() {
        val fine = lines(ScreenGrid.plan(GridKind.UTM, grenoble, spacing = 0.5)).size
        val coarse = lines(ScreenGrid.plan(GridKind.UTM, grenoble, spacing = 2.0)).size
        assertTrue(fine > coarse, "fine $fine, coarse $coarse")
    }

    @Test
    fun `the lines cover the whole region`() {
        for (kind in listOf(GridKind.UTM, GridKind.LAMBERT93, GridKind.WGS84, GridKind.MGRS)) {
            val plan = ScreenGrid.plan(kind, grenoble)
            val all = lines(plan)
            val lons = all.flatMap { l -> l.points.map { it.lon } }
            val lats = all.flatMap { l -> l.points.map { it.lat } }
            assertTrue(lons.min() <= grenoble.minX && lons.max() >= grenoble.maxX, "$kind lon coverage")
            assertTrue(lats.min() <= grenoble.minY && lats.max() >= grenoble.maxY, "$kind lat coverage")
        }
    }

    @Test
    fun `Lambert 93 lines are straight in Lambert coordinates`() {
        val plan = ScreenGrid.plan(GridKind.LAMBERT93, grenoble)
        assertEquals("Lambert 93", plan.systemText)
        assertEquals(ScreenGridIssue.NONE, plan.issue)
        for (line in lines(plan)) {
            assertTrue(line.level == GridLineLevel.MINOR || line.level == GridLineLevel.MAJOR)
            val p = line.points.map { Lambert93.toLambert(it.lat, it.lon) }
            val e = p.maxOf { it.easting } - p.minOf { it.easting }
            val n = p.maxOf { it.northing } - p.minOf { it.northing }
            assertTrue(e < UNCUT_TOLERANCE || n < UNCUT_TOLERANCE, "e spread $e, n spread $n")
        }
    }

    @Test
    fun `Lambert 93 is drawn but flagged outside France`() {
        val plan = ScreenGrid.plan(GridKind.LAMBERT93, Bounds(-74.05, 40.68, -74.0, 40.72))
        assertEquals(ScreenGridIssue.OUTSIDE_VALIDITY, plan.issue)
        assertTrue(plan.geoJson.contains("LineString"))
    }

    @Test
    fun `MGRS lines are heavier on 10 and 100 km`() {
        // 60 km across: 10 km lines, two of which are 100 km lines at most.
        val area = Bounds(5.0, 45.0, 5.8, 45.5)
        val plan = ScreenGrid.plan(GridKind.MGRS, area)
        assertTrue(plan.systemText.startsWith("MGRS 31T"), plan.systemText)
        val all = lines(plan)
        assertTrue(all.isNotEmpty())
        for (line in all) {
            val v = gridValue(line, 31, true)
            val expected =
                when {
                    v.meters % 100_000 == 0L -> GridLineLevel.SQUARE
                    v.meters % 10_000 == 0L -> GridLineLevel.TEN_KM
                    else -> GridLineLevel.STEP
                }
            assertEquals(expected, line.level, "line at ${v.meters}")
        }
        // Finer than 1 km would not be an MGRS square size a map is read with.
        val tiny = ScreenGrid.plan(GridKind.MGRS, Bounds(5.7000, 45.1700, 5.7010, 45.1708))
        assertEquals("1 km", tiny.stepText)
    }

    @Test
    fun `UTM under 1 km keeps a heavier line every fifth step`() {
        val plan = ScreenGrid.plan(GridKind.UTM, Bounds(5.7000, 45.1700, 5.7100, 45.1780))
        val step = stepMeters(plan)
        assertTrue(step < 1000.0, plan.stepText)
        for (line in lines(plan)) {
            val v = gridValue(line, 31, true)
            val major = distanceToMultiple(v.meters.toDouble(), step * 5) < 1.0
            assertEquals(if (major) GridLineLevel.MAJOR else GridLineLevel.MINOR, line.level, "line at ${v.meters}")
        }
    }

    @Test
    fun `two zones on screen each draw their own grid, cut at the zone edge`() {
        val plan = ScreenGrid.plan(GridKind.MGRS, pontcharra)
        // The chip names the zone of the centre, east of 6° E.
        assertEquals("MGRS 32T", plan.systemText)
        val all = lines(plan)
        val edges = all.filter { it.isEdge }
        assertEquals(1, edges.size, "one zone edge: the 6° E meridian")
        val edge = edges.single()
        assertTrue(edge.points.all { it.lon == 6.0 }, "edge at ${edge.points}")
        assertEquals(pontcharra.minY, edge.points.minOf { it.lat }, 1e-7)
        assertEquals(pontcharra.maxY, edge.points.maxOf { it.lat }, 1e-7)
        val west = all.filter { !it.isEdge && it.midLon < 6.0 }
        val east = all.filter { !it.isEdge && it.midLon > 6.0 }
        assertTrue(west.size >= 2 && east.size >= 2, "${west.size} lines in 31T, ${east.size} in 32T")
        for (line in west) {
            assertTrue(line.points.all { it.lon <= 6.0 + 1e-7 }, "a 31T line past 6° E")
            gridValue(line, 31, true)
        }
        for (line in east) {
            assertTrue(line.points.all { it.lon >= 6.0 - 1e-7 }, "a 32T line before 6° E")
            gridValue(line, 32, true)
        }
        // The northing lines of each side stop at the edge.
        assertTrue(west.any { l -> abs(l.points.maxOf { it.lon } - 6.0) < 1e-7 })
        assertTrue(east.any { l -> abs(l.points.minOf { it.lon } - 6.0) < 1e-7 })
    }

    @Test
    fun `the zone edge is drawn at every step`() {
        val steps = mutableListOf<String>()
        for (half in listOf(0.002, 0.01, 0.05, 0.2, 0.8, 3.0, 10.0, 25.0, 38.0)) {
            val area = Bounds(6.0 - half, 42.0 - half, 6.0 + half, 42.0 + half)
            for (kind in listOf(GridKind.MGRS, GridKind.UTM)) {
                val plan = ScreenGrid.plan(kind, area)
                assertEquals(ScreenGridIssue.NONE, plan.issue, "$kind ±$half°")
                val atSixEast = lines(plan).filter { l -> l.isEdge && l.points.all { it.lon == 6.0 } }
                assertTrue(atSixEast.isNotEmpty(), "no edge at 6° E for $kind ±$half° (${plan.stepText})")
                if (kind == GridKind.MGRS) steps += plan.stepText
            }
        }
        // From 1 km to 100 km, then the edges alone; never 200 or 500 km.
        assertEquals("1 km", steps.first())
        assertTrue("100 km" in steps, "$steps")
        assertEquals("", steps.last())
        assertTrue(steps.none { it == "200 km" || it == "500 km" }, "$steps")
    }

    @Test
    fun `zooming out from 5 to 10 km keeps the 10 km lines where they were`() {
        // Variant A of the P24.0 preview, the user's images 2 and 3: the line near the centre stays.
        val lat = 45.43098
        val lon = 6.00589
        val near = ScreenGrid.plan(GridKind.MGRS, Bounds(lon - 0.15, lat - 0.11, lon + 0.15, lat + 0.11))
        val far = ScreenGrid.plan(GridKind.MGRS, Bounds(lon - 0.3, lat - 0.22, lon + 0.3, lat + 0.22))
        assertEquals("5 km", near.stepText)
        assertEquals("10 km", far.stepText)

        fun values(plan: ScreenGridPlan) =
            lines(plan).filter { !it.isEdge }.map { l ->
                val zone = if (l.midLon < 6.0) 31 else 32
                gridValue(l, zone, true).let { Triple(it.zone, it.vertical, it.meters) to it.level }
            }
        val farValues = values(far).toMap()
        val marked = values(near).filter { it.second != GridLineLevel.STEP }
        assertTrue(marked.size >= 4, "${marked.size} marked lines")
        for ((key, level) in marked) {
            assertEquals(level, farValues[key], "the line $key changed or went away")
        }
        assertTrue(farValues.values.none { it == GridLineLevel.STEP }, "at 10 km every line is a 10 km line")
    }

    @Test
    fun `a wide view draws the 100 km lines and the band edges`() {
        // About 1:5 000 000: the step would have been 200 km.
        val area = Bounds(2.0, 42.0, 10.0, 50.0)
        val plan = ScreenGrid.plan(GridKind.MGRS, area)
        assertEquals("100 km", plan.stepText)
        val all = lines(plan)
        assertTrue(all.filter { !it.isEdge }.all { it.level == GridLineLevel.SQUARE })
        val edges = all.filter { it.isEdge }
        assertTrue(edges.any { l -> l.points.all { it.lon == 6.0 } }, "the 31 / 32 edge")
        assertTrue(edges.any { l -> l.points.all { it.lat == 48.0 } }, "the T / U edge")
        // Each 100 km line of zone 31 stops at 6° E, each one of zone 32 starts there.
        for (line in all.filter { !it.isEdge }) {
            val zone = if (line.midLon < 6.0) 31 else 32
            val v = gridValue(line, zone, true, WIDE_TOLERANCE)
            assertEquals(0.0, distanceToMultiple(v.meters.toDouble(), 100_000.0), 1.0, "line at ${v.meters}")
        }
    }

    @Test
    fun `Norway draws 32V from 3 E`() {
        val area = Bounds(1.0, 54.0, 8.0, 58.0)
        val all = lines(ScreenGrid.plan(GridKind.UTM, area))
        val edges = all.filter { it.isEdge }
        val atThree = edges.filter { l -> l.points.all { it.lon == 3.0 } }
        assertEquals(listOf(56.0 to 58.0), atThree.map { l -> l.points.minOf { it.lat } to l.points.maxOf { it.lat } })
        val atSix = edges.filter { l -> l.points.all { it.lon == 6.0 } }
        assertEquals(listOf(54.0 to 56.0), atSix.map { l -> l.points.minOf { it.lat } to l.points.maxOf { it.lat } })
        // Between 3 and 6° E above 56° N, the lines are those of zone 32.
        val inV = all.filter { !it.isEdge && it.midLon > 3.0 && it.midLon < 6.0 && it.midLat > 56.0 }
        assertTrue(inV.isNotEmpty())
        for (line in inV) gridValue(line, 32, true, WIDE_TOLERANCE)
    }

    @Test
    fun `the grid runs on across the equator`() {
        val area = Bounds(6.40, -0.05, 6.52, 0.05)
        val plan = ScreenGrid.plan(GridKind.MGRS, area)
        val all = lines(plan)
        assertTrue(all.any { l -> l.isEdge && l.points.all { it.lat == 0.0 } }, "the M / N edge")
        val south = all.filter { !it.isEdge && it.midLat < -0.005 }
        assertTrue(south.isNotEmpty())
        val step = stepMeters(plan)
        for (line in south) {
            // In the northern hemisphere's grid: just below 0, and on the same lines as the southern one.
            val v = gridValue(line, 32, true)
            assertEquals(0.0, distanceToMultiple(v.meters.toDouble(), step), 1.0)
        }
        assertTrue(all.flatMap { it.points }.all { it.lat >= area.minY - 1e-7 && it.lat <= area.maxY + 1e-7 })
    }

    @Test
    fun `across the antimeridian zone 1 is drawn past 180`() {
        // Fiji, with the longitudes of a map that runs on east of 180°.
        val area = Bounds(179.6, -17.0, 180.4, -16.2)
        val plan = ScreenGrid.plan(GridKind.MGRS, area)
        assertEquals("MGRS 1K", plan.systemText)
        val all = lines(plan)
        assertTrue(all.any { l -> l.isEdge && l.points.all { it.lon == 180.0 } }, "the 60 / 1 edge")
        assertTrue(all.flatMap { it.points }.all { it.lon >= area.minX - 1e-7 && it.lon <= area.maxX + 1e-7 })
        val east = all.filter { !it.isEdge && it.midLon > 180.0 }
        val west = all.filter { !it.isEdge && it.midLon < 180.0 }
        assertTrue(east.isNotEmpty() && west.isNotEmpty())
        for (line in east) gridValue(line, 1, false)
        for (line in west) gridValue(line, 60, false)
    }

    private class Label(
        val text: String,
        val kind: String,
        val at: LonLat,
    )

    private fun labels(plan: ScreenGridPlan): List<Label> =
        Json.parseToJsonElement(plan.labelsGeoJson).jsonObject["features"]!!.jsonArray.map { f ->
            val o = f.jsonObject
            val p = o["properties"]!!.jsonObject
            val c = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            Label(
                p["text"]!!.jsonPrimitive.content,
                p["kind"]!!.jsonPrimitive.content,
                LonLat(c[0].jsonPrimitive.content.toDouble(), c[1].jsonPrimitive.content.toDouble()),
            )
        }

    @Test
    fun `a zone with one square in view is named with it, on its side of the edge`() {
        // Section 1 of the P24.0 preview: « 31T GL » and « 32T KR ».
        val all = labels(ScreenGrid.plan(GridKind.MGRS, pontcharra))
        assertEquals(setOf("31T GL", "32T KR"), all.map { it.text }.toSet())
        assertTrue(all.all { it.kind == GridLabelKind.NAME })
        val gl = all.single { it.text == "31T GL" }
        val kr = all.single { it.text == "32T KR" }
        assertTrue(gl.at.lon < 6.0 && kr.at.lon > 6.0, "${gl.at} ${kr.at}")
        for (label in all) {
            assertEquals(label.text, Mgrs.of(label.at.lat, label.at.lon)!!.format(0), "${label.text} at ${label.at}")
        }
        // UTM writes the figures of its lines at the edge of the screen instead of the zone names (2026-10-28).
        assertTrue(labels(ScreenGrid.plan(GridKind.UTM, pontcharra)).isEmpty())
        // On a view too wide for lines, it names the zones only.
        val wide = labels(ScreenGrid.plan(GridKind.UTM, Bounds(-20.0, 20.0, 40.0, 60.0)))
        assertTrue(wide.isNotEmpty() && wide.all { '0' <= it.text.first() && it.text.first() <= '9' })
        assertTrue(wide.none { ' ' in it.text }, "${wide.map { it.text }}")
    }

    @Test
    fun `a wide view names the zones and the squares that take enough of the screen`() {
        // Section 3 of the preview, about 1:5 000 000.
        val all = labels(ScreenGrid.plan(GridKind.MGRS, Bounds(2.0, 42.0, 10.0, 50.0)))
        val zones = all.filter { it.kind == GridLabelKind.ZONE }
        assertEquals(setOf("31T", "32T", "31U", "32U"), zones.map { it.text }.toSet())
        for (zone in zones) assertEquals(zone.text, GridZones.of(zone.at.lat, zone.at.lon)!!.name)
        val squares = all.filter { it.kind == GridLabelKind.SQUARE }
        assertTrue(squares.size >= 4, "${squares.size} squares named")
        for (square in squares) {
            val ref = Mgrs.of(square.at.lat, square.at.lon)!!
            assertEquals(square.text, "${ref.columnLetter}${ref.rowLetter}", "at ${square.at}")
        }
        assertTrue(all.none { it.kind == GridLabelKind.NAME })
    }

    @Test
    fun `a view too wide for lines keeps the zone names`() {
        val world = ScreenGrid.plan(GridKind.MGRS, Bounds(-20.0, 20.0, 40.0, 60.0))
        assertTrue(world.isEmpty)
        assertEquals(null, world.convergenceDegrees)
        val names = labels(world).filter { it.kind == GridLabelKind.ZONE }.map { it.text }
        assertTrue("32T" in names && "33U" in names, "$names")
    }

    @Test
    fun `UTM gives its kilometre lines for the figures at the edge, split before the last two`() {
        val plan = ScreenGrid.plan(GridKind.UTM, grenoble)
        val figures = plan.figureLines
        assertTrue(figures.any { it.easting } && figures.any { !it.easting }, "${figures.size} lines")
        for (line in figures) {
            assertEquals(0.0, distanceToMultiple(line.meters, 1000.0), 1e-6)
            val value = gridValue(Line(line.points, 0), 31, north = true)
            assertEquals(line.easting, value.vertical)
            assertEquals(line.meters.roundToLong(), value.meters)
            val (small, big) = line.figures
            assertEquals(2, big.length)
            assertEquals((line.meters / 1000).roundToLong().toString(), small + big)
        }
        // Each figure line is a line of the grid.
        assertTrue(figures.size <= lines(plan).count { !it.isEdge })
        // MGRS keeps its names, without figures.
        assertTrue(ScreenGrid.plan(GridKind.MGRS, grenoble).figureLines.isEmpty())
    }

    @Test
    fun `a close view under 1 km gives the kilometre lines alone, often none`() {
        // About 1:2 000, lines every 50 m: no kilometre line in view, nothing to write (zoom 18 or 19).
        val close = Bounds(5.7210, 45.1910, 5.7230, 45.1940)
        val plan = ScreenGrid.plan(GridKind.UTM, close)
        assertTrue(stepMeters(plan) < 1000.0, plan.stepText)
        for (line in plan.figureLines) assertEquals(0.0, distanceToMultiple(line.meters, 1000.0), 1e-6)
        // About 1:25 000, lines every 500 m: one in two is written.
        val mid = ScreenGrid.plan(GridKind.UTM, Bounds(5.70, 45.17, 5.73, 45.19))
        val grid = lines(mid).count { !it.isEdge }
        assertTrue(mid.figureLines.isNotEmpty() && mid.figureLines.size < grid, "${mid.figureLines.size} of $grid")
    }

    @Test
    fun `figures of a short value are not split`() {
        assertEquals("" to "25", GridFigureLine(true, 25_000.0, emptyList()).figures)
        assertEquals("7" to "25", GridFigureLine(true, 725_000.0, emptyList()).figures)
        assertEquals("50" to "45", GridFigureLine(false, 5_045_000.0, emptyList()).figures)
    }

    @Test
    fun `the convergence at the centre goes with the chip`() {
        // East of 6° E, in 32T: grid north about 2°08′ west of true north.
        val mgrs = ScreenGrid.plan(GridKind.MGRS, pontcharra).convergenceDegrees!!
        assertEquals(Utm.convergenceDegrees(45.43, 6.005), mgrs, 1e-9)
        assertTrue(mgrs < -2.0 && mgrs > -2.3, "$mgrs")
        // Lambert 93: n × (λ − 3°) in France, nothing elsewhere.
        val lambert = ScreenGrid.plan(GridKind.LAMBERT93, grenoble).convergenceDegrees!!
        assertEquals(0.7256077650532670 * (5.725 - 3.0), lambert, 1e-9)
        assertEquals(null, ScreenGrid.plan(GridKind.LAMBERT93, Bounds(-74.05, 40.68, -74.0, 40.72)).convergenceDegrees)
        assertEquals(null, ScreenGrid.plan(GridKind.WGS84, grenoble).convergenceDegrees)
    }

    @Test
    fun `clipping keeps the parts inside the area`() {
        val square = Bounds(0.0, 0.0, 1.0, 1.0)
        val zigzag = listOf(LonLat(-1.0, 0.5), LonLat(0.5, 0.5), LonLat(0.5, 2.0), LonLat(0.8, 0.5), LonLat(2.0, 0.5))
        val parts = ScreenGrid.clip(zigzag, square)
        assertEquals(2, parts.size)
        assertEquals(listOf(LonLat(0.0, 0.5), LonLat(0.5, 0.5), LonLat(0.5, 1.0)), parts[0])
        assertEquals(LonLat(0.8, 0.5), parts[1][1])
        assertEquals(LonLat(1.0, 0.5), parts[1].last())
        assertFalse(ScreenGrid.clip(listOf(LonLat(2.0, 2.0), LonLat(3.0, 3.0)), square).any())
    }

    @Test
    fun `the graticule uses degrees, minutes or seconds as the region shrinks`() {
        assertEquals("1°", ScreenGrid.plan(GridKind.WGS84, Bounds(5.0, 44.0, 11.0, 48.0)).stepText)
        assertEquals("1′", ScreenGrid.plan(GridKind.WGS84, Bounds(5.70, 45.17, 5.82, 45.21)).stepText)
        assertTrue(ScreenGrid.plan(GridKind.WGS84, Bounds(5.7000, 45.1700, 5.7040, 45.1730)).stepText.endsWith("″"))
        for (line in lines(ScreenGrid.plan(GridKind.WGS84, Bounds(5.70, 45.17, 5.82, 45.21)))) {
            // 2 points: straight lines in Mercator.
            assertEquals(2, line.points.size)
            assertTrue(line.points[0].lon == line.points[1].lon || line.points[0].lat == line.points[1].lat)
        }
    }

    @Test
    fun `polar regions have no UTM grid and the whole world its zone edges alone`() {
        assertEquals(ScreenGridIssue.POLAR, ScreenGrid.plan(GridKind.UTM, Bounds(10.0, 83.0, 12.0, 86.0)).issue)
        assertEquals(ScreenGridIssue.POLAR, ScreenGrid.plan(GridKind.MGRS, Bounds(10.0, -85.0, 12.0, -82.0)).issue)
        val world = ScreenGrid.plan(GridKind.UTM, Bounds(-20.0, 20.0, 40.0, 60.0))
        assertEquals(ScreenGridIssue.NONE, world.issue)
        assertTrue(world.isEmpty, "no chip")
        val all = lines(world)
        assertTrue(all.size > 10, "${all.size} edges")
        assertTrue(all.all { it.isEdge })
        // WGS84 and Lambert 93 still say the region is too wide.
        val wide = ScreenGrid.plan(GridKind.LAMBERT93, Bounds(-60.0, -40.0, 60.0, 60.0))
        assertEquals(ScreenGridIssue.TOO_WIDE, wide.issue)
    }

    @Test
    fun `an empty region gives nothing`() {
        assertTrue(ScreenGrid.plan(GridKind.UTM, Bounds(5.0, 45.0, 5.0, 45.0)).isEmpty)
    }

    @Test
    fun `nice steps are 1, 2 or 5 times a power of ten and respect the minimum`() {
        assertEquals(1000.0, ScreenGrid.niceStep(700.0, 10.0))
        assertEquals(2000.0, ScreenGrid.niceStep(1100.0, 10.0))
        assertEquals(5000.0, ScreenGrid.niceStep(2100.0, 10.0))
        assertEquals(10_000.0, ScreenGrid.niceStep(5100.0, 10.0))
        assertEquals(1000.0, ScreenGrid.niceStep(30.0, 1000.0))
        assertEquals(10.0, ScreenGrid.niceStep(1.0, 10.0))
    }
}
