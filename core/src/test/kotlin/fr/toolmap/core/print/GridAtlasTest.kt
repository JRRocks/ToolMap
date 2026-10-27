package fr.toolmap.core.print

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.Utm
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GridAtlasTest {
    private val france = Locale.FRANCE

    private fun frame(
        lat: Double = 45.19,
        lon: Double = 5.72,
        scale: Double = 25_000.0,
    ) = FrameProjection(lat, lon, scale, 277.0, 190.0)

    private fun List<GridLabel>.on(side: FrameSide) = filter { it.side == side }

    @Test
    fun `an UTM grid at 1 to 25 000 has a line every kilometer, about 40 mm apart`() {
        val f = frame()
        val plan = GridPlanner.plan(f, GridKind.UTM, locale = france)
        assertEquals(GridIssue.NONE, plan.issue)
        assertEquals("1 km", plan.stepText)
        assertEquals("UTM 31N · WGS84", plan.systemText)
        // 277 mm / 40 mm: six or seven vertical lines, and 190 / 40: four or five horizontal ones.
        val top = plan.labels.on(FrameSide.TOP).sortedBy { it.positionMm }
        val left = plan.labels.on(FrameSide.LEFT).sortedBy { it.positionMm }
        assertTrue(top.size in 6..8, "${top.size} labels on the top edge")
        assertTrue(left.size in 4..6, "${left.size} labels on the left edge")
        for (k in 1 until top.size) assertEquals(40.0, top[k].positionMm - top[k - 1].positionMm, 0.8)
        // The label is the kilometer figure: the easting of the frame centre is 5xx km in zone 31.
        val expected = Utm.toUtm(45.19, 5.72, 31)
        assertTrue(
            top.any {
                it.text.toInt() == (expected.easting / 1000).toInt()
            },
            "labels ${top.map { it.text }} for ${expected.easting}",
        )
        assertTrue(left.any { it.text.toInt() == (expected.northing / 1000).toInt() })
        // Lines stay inside the frame.
        for (line in plan.lines) {
            for (p in line.points) {
                assertTrue(p.xMm in -1e-6..277.0 + 1e-6 && p.yMm in -1e-6..190.0 + 1e-6)
            }
        }
    }

    @Test
    fun `grid lines are nearly vertical or horizontal near the central meridian and keep their step in millimeters`() {
        val f = frame(lon = 3.0) // central meridian of zone 31
        val plan = GridPlanner.plan(f, GridKind.UTM, locale = france)
        val vertical = plan.lines.filter { l -> abs(l.points.first().xMm - l.points.last().xMm) < 2.0 }
        assertTrue(vertical.size >= 5, "${vertical.size}")
        // A line of constant easting is 190 mm long in a 190 mm frame, give or take the convergence.
        assertTrue(
            vertical.all { l ->
                l.points.last().yMm - l.points.first().yMm in 185.0..191.0 ||
                    l.points.first().yMm - l.points.last().yMm in 185.0..191.0
            },
        )
    }

    @Test
    fun `a Lambert 93 grid labels kilometers and a WGS84 graticule labels degrees, minutes and seconds`() {
        val lambert = GridPlanner.plan(frame(48.85, 2.35), GridKind.LAMBERT93, locale = france)
        assertEquals("Lambert-93 · RGF93 (EPSG:2154)", lambert.systemText)
        val top = lambert.labels.on(FrameSide.TOP).map { it.text.toInt() }
        assertTrue(top.all { it in 640..660 }, "$top")
        assertTrue(lambert.labels.on(FrameSide.LEFT).map { it.text.toInt() }.all { it in 6850..6870 })

        val wgs = GridPlanner.plan(frame(45.19, 5.72), GridKind.WGS84, locale = france)
        assertEquals("30″", wgs.stepText)
        assertTrue(
            wgs.labels.any {
                it.side == FrameSide.TOP &&
                    it.text.matches(Regex("5°4\\d′\\d\\d″E|5°4\\d′00″E|5°4\\d′30″E"))
            },
            wgs.labels.on(FrameSide.TOP).map { it.text }.toString(),
        )
        assertTrue(wgs.labels.any { it.side == FrameSide.LEFT && it.text.endsWith("N") })
        val west = GridPlanner.plan(frame(40.0, -74.0, 100_000.0), GridKind.WGS84)
        assertTrue(west.labels.on(FrameSide.TOP).all { it.text.endsWith("W") })
        assertTrue(west.labels.on(FrameSide.LEFT).all { it.text.endsWith("N") })
    }

    @Test
    fun `an MGRS grid labels the two digits of the kilometer inside the 100 km square`() {
        val f = frame()
        val plan = GridPlanner.plan(f, GridKind.MGRS, locale = france)
        assertTrue(plan.systemText.startsWith("MGRS 31T"), plan.systemText)
        assertTrue(
            plan.labels.all {
                it.text.length == 2 && it.text.all(Char::isDigit)
            },
            plan.labels.map { it.text }.toString(),
        )
        assertNotNull(GridPlanner.mgrsSquare(f))
        assertTrue(GridPlanner.mgrsSquare(f)!!.startsWith("31T "))
    }

    @Test
    fun `the step follows the scale`() {
        // The spacings of printed maps (P17, Q12): a square of 2 to 5 cm on the paper.
        assertEquals("1 km", GridPlanner.plan(frame(scale = 50_000.0), GridKind.UTM).stepText)
        assertEquals("1 km", GridPlanner.plan(frame(scale = 25_000.0), GridKind.UTM).stepText)
        assertEquals("500 m", GridPlanner.plan(frame(scale = 10_000.0), GridKind.UTM).stepText)
        assertEquals("2 km", GridPlanner.plan(frame(scale = 100_000.0), GridKind.UTM).stepText)
        assertEquals(
            "1 km",
            GridPlanner.plan(frame(scale = 100_000.0), GridKind.UTM, style = MapStyle.NATO).stepText,
        )
        assertEquals("30″", GridPlanner.plan(frame(scale = 25_000.0), GridKind.WGS84).stepText)
        val decimal = GridPlanner.plan(frame(scale = 25_000.0), GridKind.WGS84_DECIMAL, locale = Locale.ROOT)
        assertEquals("0.01°", decimal.stepText)
        assertEquals(1_000.0, GridPlanner.nearestNice(1_300.0))
        assertEquals(5_000.0, GridPlanner.nearestNice(3_900.0))
        assertEquals(2_000.0, GridPlanner.nearestNice(2_500.0))
        // A forced step wins.
        assertEquals("500 m", GridPlanner.plan(frame(), GridKind.UTM, step = 500.0).stepText)
    }

    @Test
    fun `no grid, and grids that do not exist at the place`() {
        assertTrue(GridPlanner.plan(frame(), GridKind.NONE).lines.isEmpty())
        val polar = GridPlanner.plan(frame(86.0, 10.0), GridKind.UTM)
        assertEquals(GridIssue.OUTSIDE_VALIDITY, polar.issue)
        assertTrue(polar.lines.isEmpty())
        val abroad = GridPlanner.plan(frame(35.0, -100.0), GridKind.LAMBERT93)
        assertEquals(GridIssue.OUTSIDE_VALIDITY, abroad.issue)
    }

    @Test
    fun `a page across zones 31 and 32 has each grid on its side of 6 E, the edge, the neighbour in the margin`() {
        // Pontcharra, the sheet of the P24.0 preview: 6 E crosses it a little east of its middle.
        val f = frame(45.4274, 6.004)
        val plan = GridPlanner.plan(f, GridKind.MGRS, locale = france)
        assertEquals(listOf("31T", "32T"), plan.zones)
        assertEquals("MGRS 32T · WGS84", plan.systemText)
        val edge = plan.zoneEdges.single()
        assertTrue(edge.meridian)
        assertEquals(6.0, edge.degrees)
        assertEquals("31" to "32", edge.sides)
        val xEdge = f.toFrame(6.0, 45.4274).xMm
        assertEquals(xEdge, edge.line.points.first().xMm, 1e-6)
        assertEquals(0.0, edge.line.points.minOf { it.yMm }, 1e-6)
        assertEquals(190.0, edge.line.points.maxOf { it.yMm }, 1e-6)
        // No grid line crosses the edge: each zone's grid stops there.
        for (line in plan.lines) {
            val west = line.points.all { it.xMm <= xEdge + 1e-6 }
            val east = line.points.all { it.xMm >= xEdge - 1e-6 }
            assertTrue(west || east, "a line crosses 6 E: ${line.points.first()} .. ${line.points.last()}")
        }

        // Each figure on the top edge is the easting of its own zone there: 31 west of the edge, 32 east of it.
        fun easting(
            label: GridLabel,
            zone: Int,
        ): Double = f.toLonLat(label.positionMm, 0.0).let { Utm.toUtm(it.lat, it.lon, zone).easting }
        val top = plan.labels.filter { it.side == FrameSide.TOP }
        val own = top.filter { !it.neighbour }
        assertTrue(own.any { it.positionMm < xEdge } && own.any { it.positionMm > xEdge })
        for (label in own) {
            val zone = if (label.positionMm < xEdge) 31 else 32
            assertEquals(label.value, easting(label, zone), 1.0, "own label ${label.text} at ${label.positionMm}")
        }
        // The neighbour's figures are written where the other zone is: 31 east of the edge, 32 west of it.
        val neighbours = top.filter { it.neighbour }
        assertTrue(neighbours.any { it.positionMm > xEdge } && neighbours.any { it.positionMm < xEdge })
        for (label in neighbours) {
            val zone = if (label.positionMm > xEdge) 31 else 32
            assertEquals(label.value, easting(label, zone), 1.0, "neighbour ${label.text} at ${label.positionMm}")
        }

        // On the sides: zone 31 on the left with zone 32 beyond it, the other way round on the right.
        fun northing(
            label: GridLabel,
            x: Double,
            zone: Int,
        ): Double = f.toLonLat(x, label.positionMm).let { Utm.toUtm(it.lat, it.lon, zone).northing }
        for (label in plan.labels.filter { it.side == FrameSide.LEFT }) {
            assertEquals(label.value, northing(label, 0.0, if (label.neighbour) 32 else 31), 1.0)
        }
        for (label in plan.labels.filter { it.side == FrameSide.RIGHT }) {
            assertEquals(label.value, northing(label, 277.0, if (label.neighbour) 31 else 32), 1.0)
        }
        assertTrue(plan.labels.any { it.side == FrameSide.LEFT && it.neighbour })
        assertTrue(plan.labels.any { it.side == FrameSide.RIGHT && it.neighbour })
        // The squares with their zone, and their names beside the edge, as in the preview.
        assertEquals(listOf("31T GL", "32T KR"), plan.squares)
        assertEquals(mapOf("31T" to CompassSide.WEST, "32T" to CompassSide.EAST), plan.zoneSides)
        val gl = plan.squareTags.single { it.letters == "31T GL" }
        val kr = plan.squareTags.single { it.letters == "32T KR" }
        assertEquals(TagAlign.END, gl.align)
        assertEquals(TagAlign.START, kr.align)
        assertTrue(gl.xMm < xEdge && kr.xMm > xEdge)
        assertEquals(95.0, gl.yMm, 1.0)
        // UTM: the same grid, zones named by hemisphere, no squares.
        val utm = GridPlanner.plan(f, GridKind.UTM, locale = france)
        assertEquals(listOf("31N", "32N"), utm.zones)
        assertTrue(utm.squares.isEmpty())
        assertEquals(1, utm.zoneEdges.size)
    }

    @Test
    fun `a page inside one zone has no edge and no neighbour, and a band edge is drawn without neighbours`() {
        val inside = GridPlanner.plan(frame(), GridKind.MGRS, locale = france)
        assertEquals(listOf("31T"), inside.zones)
        assertTrue(inside.zoneEdges.isEmpty())
        assertTrue(inside.labels.none { it.neighbour })
        assertTrue(inside.squares.all { it.length == 2 }, inside.squares.toString())
        // 48 N between bands T and U in zone 32: one grid, the parallel drawn, the squares named with their band.
        val band = GridPlanner.plan(frame(48.0, 9.0), GridKind.MGRS, locale = france)
        assertEquals(listOf("32T", "32U"), band.zones)
        val edge = band.zoneEdges.single()
        assertTrue(!edge.meridian)
        assertEquals(48.0, edge.degrees)
        assertEquals("T" to "U", edge.sides)
        assertTrue(band.labels.none { it.neighbour })
        assertTrue(band.squares.all { it.startsWith("32T ") || it.startsWith("32U ") }, band.squares.toString())
        assertEquals(mapOf("32T" to CompassSide.SOUTH, "32U" to CompassSide.NORTH), band.zoneSides)
    }

    @Test
    fun `the graduated band keeps the ticks of each zone and leaves the neighbour's out`() {
        val graduation = GridPlanner.graduation(frame(45.4274, 6.004), GridKind.WGS84, france)
        assertEquals(GridKind.UTM, graduation.kind)
        val utm = GridPlanner.plan(frame(45.4274, 6.004), GridKind.UTM, locale = france)
        val own = utm.labels.count { it.side == FrameSide.TOP && !it.neighbour }
        assertEquals(own + 1, graduation.bars.count { it.side == FrameSide.TOP })
    }

    // ---- atlas ----------------------------------------------------------------------------------------------------

    private val a4 = PageSetup(Paper.A4, landscape = true, marginMm = 10.0)

    @Test
    fun `a zone smaller than a page is one page, centred`() {
        val plan = AtlasPlanner.grid(Bounds(5.70, 45.18, 5.72, 45.19), 50_000.0, a4, 10.0)
        assertEquals(1, plan.pages.size)
        assertEquals(5.71, plan.pages.single().centerLon, 1e-9)
    }

    @Test
    fun `a grid of pages covers the whole zone with the requested overlap`() {
        // About 40 x 25 km at 1:50 000: pages cover 13.85 x 9.5 km.
        val area = Bounds(5.0, 45.0, 5.5, 45.22)
        val plan = AtlasPlanner.grid(area, 50_000.0, a4, 10.0)
        assertTrue(plan.columns in 3..5 && plan.rows in 2..4, "${plan.columns} x ${plan.rows}")
        assertEquals(plan.columns * plan.rows, plan.pages.size)
        assertEquals((1..plan.pages.size).toList(), plan.pages.map { it.number })
        // Every corner and the centre of the area fall in at least one page.
        val probes =
            listOf(
                LonLat(5.0, 45.0),
                LonLat(5.5, 45.0),
                LonLat(5.0, 45.22),
                LonLat(5.5, 45.22),
                LonLat(5.25, 45.11),
            )
        val frames =
            plan.pages.map {
                FrameProjection(it.centerLat, it.centerLon, 50_000.0, a4.frameWidthMm, a4.frameHeightMm)
            }
        for (p in probes) {
            assertTrue(frames.any { it.contains(it.toFrame(p.lon, p.lat)) }, "uncovered $p")
        }
        // Neighbouring pages overlap: the next page's left edge falls inside the previous page.
        val first = frames[0]
        val second = frames[1]
        val overlapMm = first.toFrame(second.toLonLat(0.0, 95.0).lon, 45.0).xMm.let { 277.0 - it }
        assertTrue(overlapMm > 20.0, "overlap $overlapMm mm")
    }

    @Test
    fun `more overlap needs at least as many pages`() {
        val area = Bounds(5.0, 45.0, 5.5, 45.22)
        val few = AtlasPlanner.grid(area, 50_000.0, a4, 0.0).pages.size
        val many = AtlasPlanner.grid(area, 50_000.0, a4, 30.0).pages.size
        assertTrue(many >= few)
    }

    @Test
    fun `pages along a track follow it from the start and the track stays inside the useful part of a page`() {
        // A 30 km track going east along a parallel, at 1:25 000 (pages of 6.9 km).
        val path = List(301) { i -> LonLat(5.0 + i * 0.001, 45.0) }
        val plan = AtlasPlanner.alongTrack(path, 25_000.0, a4, 10.0)
        val km30 = 300 * 0.001 * 111.195 * Math.cos(Math.toRadians(45.0))
        assertTrue(plan.pages.size in 5..10, "${plan.pages.size} pages for ${"%.1f".format(km30)} km")
        assertEquals(path.first().lon, plan.pages.first().centerLon, 1e-9)
        assertEquals((1..plan.pages.size).toList(), plan.pages.map { it.number })
        // Every point of the track is in some page.
        val frames =
            plan.pages.map {
                FrameProjection(it.centerLat, it.centerLon, 25_000.0, a4.frameWidthMm, a4.frameHeightMm)
            }
        for (p in path) assertTrue(frames.any { it.contains(it.toFrame(p.lon, p.lat)) }, "uncovered ${p.lon}")
        // Centres move eastwards only.
        assertTrue(plan.pages.zipWithNext().all { (a, b) -> b.centerLon > a.centerLon })
    }

    @Test
    fun `a track that ends inside the first page gives one page and an empty track gives none`() {
        val path = List(10) { i -> LonLat(5.0 + i * 0.0005, 45.0) }
        assertEquals(1, AtlasPlanner.alongTrack(path, 25_000.0, a4, 10.0).pages.size)
        assertTrue(AtlasPlanner.alongTrack(emptyList(), 25_000.0, a4, 10.0).pages.isEmpty())
    }

    @Test
    fun `a wrong scale cannot make an unbounded atlas`() {
        val path = List(5_000) { i -> LonLat(i * 0.01, 45.0) }
        assertTrue(AtlasPlanner.alongTrack(path, 1_000.0, a4, 10.0).pages.size <= AtlasPlanner.MAX_PAGES)
    }

    @Test
    fun `the overview fits every footprint in the sheet`() {
        val area = Bounds(5.0, 45.0, 5.5, 45.22)
        val plan = AtlasPlanner.grid(area, 50_000.0, a4, 10.0)
        val overview = AtlasPlanner.overview(plan, 50_000.0, a4, 277.0, 190.0)
        assertEquals(plan.pages.size, overview.footprints.size)
        for (footprint in overview.footprints) {
            for (corner in footprint.corners) {
                val p = overview.toSheet(corner.lon, corner.lat)
                assertTrue(p.xMm in -0.01..277.01 && p.yMm in -0.01..190.01, "$p outside the sheet")
            }
        }
    }
}
