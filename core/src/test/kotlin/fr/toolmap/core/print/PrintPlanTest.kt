package fr.toolmap.core.print

import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.LayerElevation
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.layers.Workspaces
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.tiles.TileMath
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintPlanTest {
    private val sources = BuiltinSources.load().associateBy { it.id }
    private val lat45 = 45.0

    private fun stack(vararg layers: LayerConfig) = layers.toList()

    private fun layer(
        source: String,
        min: Int? = null,
        max: Int? = null,
        opacity: Float = 1f,
        visible: Boolean = true,
    ) = LayerConfig(
        id = "l-$source",
        sourceId = source,
        visible = visible,
        opacity = opacity,
        displayMinZoom = min,
        displayMaxZoom = max,
    )

    private fun plan(
        stack: List<LayerConfig>,
        scale: Double = 25_000.0,
        dpi: Int = 300,
        respect: Boolean = true,
        lighter: Boolean = false,
    ) = PrintLayerPlanner.plan(stack, sources::get, scale, dpi, lat45, respect, lighter)

    @Test
    fun `SCAN 25 stops at zoom 16 and triggers the oversampling warning at 1 to 6 250 and 300 dpi`() {
        val p = plan(stack(layer("ign-scan25")), scale = 6_250.0)
        assertEquals(18, p.idealZoom)
        val used = p.layers.single()
        assertEquals(16, used.tileZoom)
        assertEquals(3.2, used.oversampling, 1e-9)
        val warning = assertIs<PrintWarning.Oversampled>(p.warnings.single())
        assertEquals(3.2, warning.factor, 1e-9)
        assertEquals(16, warning.usedZoom)
        assertEquals(18, warning.wantedZoom)
        // At 1:25,000 it now goes deep enough.
        assertTrue(plan(stack(layer("ign-scan25"))).warnings.isEmpty())
    }

    @Test
    fun `a source that goes deep enough is read at the ideal zoom without warning`() {
        val p = plan(stack(layer("ign-maps")))
        assertEquals(16, p.layers.single().tileZoom)
        assertTrue(p.warnings.isEmpty())
        assertEquals(1.0, p.layers.single().oversampling)
    }

    @Test
    fun `hidden and transparent layers are left out and the order of the stack is kept`() {
        val p =
            plan(
                stack(
                    layer("ign-maps"),
                    layer("osm", visible = false),
                    layer("opentopomap", opacity = 0f),
                    layer("ign-pentes", opacity = 0.5f),
                ),
            )
        assertEquals(listOf("ign-maps", "ign-pentes"), p.layers.map { it.source.id })
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `display ranges decide with the zoom the page is equivalent to`() {
        // 1:25 000 at 300 dpi and 45 degrees: equivalent zoom 15.67, as the screen would show at that zoom.
        val range = plan(stack(layer("ign-maps", min = 8, max = 15), layer("ign-plan", min = 16, max = 19)))
        assertEquals(15.67, range.equivalentZoom, 0.01)
        // 15.67 < 15 + 1: the first stays; 15.67 < 16: the second is not yet visible.
        assertEquals(listOf("ign-maps"), range.layers.map { it.source.id })
        val outside = assertIs<PrintWarning.OutsideRange>(range.warnings.single())
        assertEquals("ign-plan", outside.sourceId)
        // The same stack at 1:10 000 (equivalent zoom 17.0): the roles are swapped.
        val closer =
            plan(stack(layer("ign-maps", min = 8, max = 15), layer("ign-plan", min = 16, max = 19)), scale = 10_000.0)
        assertEquals(listOf("ign-plan"), closer.layers.map { it.source.id })
    }

    @Test
    fun `ranges can be ignored`() {
        val p = plan(stack(layer("ign-maps", min = 1, max = 5)), respect = false)
        assertEquals(listOf("ign-maps"), p.layers.map { it.source.id })
        assertTrue(p.warnings.isEmpty())
    }

    @Test
    fun `a layer in an elevation band is printed in that band, read from the elevation map, as on the screen`() {
        // Sortie Ski: IGN SCAN 25 under the snow map above 1550 m; the IGN layer stays whole.
        val ski = Workspaces.sortieSki.toStack { true }.stack.layers
        val p = plan(ski)
        val scan = p.layers.single { it.source.id == "ign-scan25" }
        val snow = p.layers.single { it.source.id == "OSMSNOWM" }
        assertNull(scan.band)
        assertEquals(ElevationMask(1550, null), assertNotNull(snow.band).mask)
        assertEquals(LayerElevation.DEM_SOURCE_ID, snow.band?.dem?.id)
        // Ignoring the zoom ranges does not drop the elevation band.
        assertNotNull(plan(ski, respect = false).layers.single { it.source.id == "OSMSNOWM" }.band)
    }

    @Test
    fun `with OU the band is printed outside the zoom range and the layer whole inside it`() {
        val either =
            layer("ign-maps", min = 8, max = 15).copy(displayMinElevation = 1500, rangeLogic = RangeLogic.OR)
        // Equivalent zoom 15.67: inside 8–15, the layer is drawn whole.
        assertNull(plan(stack(either)).layers.single().band)
        // Equivalent zoom 17.0: outside the range, the screen still draws the band, and so does the page.
        val closer = plan(stack(either), scale = 10_000.0)
        assertEquals(1500, closer.layers.single().band?.mask?.min)
        assertTrue(closer.warnings.none { it is PrintWarning.OutsideRange })
        // With ET, outside the range, it is left out as before.
        val both = either.copy(rangeLogic = RangeLogic.AND)
        assertTrue(plan(stack(both), scale = 10_000.0).layers.isEmpty())
    }

    @Test
    fun `a layer of an unknown source is reported`() {
        val p = plan(stack(layer("nowhere")))
        assertTrue(p.layers.isEmpty())
        assertEquals("nowhere", assertIs<PrintWarning.Unavailable>(p.warnings.single()).sourceId)
    }

    @Test
    fun `lighter takes one level less`() {
        assertEquals(15, plan(stack(layer("ign-maps")), lighter = true).layers.single().tileZoom)
    }

    @Test
    fun `the raster of an A4 page at 300 dpi is cut in bands and the bands cover it exactly`() {
        val frame = FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        val raster = PrintRaster(frame, 300)
        assertEquals(3_272, raster.widthPx)
        assertEquals(2_244, raster.heightPx)
        assertEquals(5, raster.bandCount)
        assertEquals(0, raster.band(0).y0)
        assertEquals(512, raster.band(0).heightPx)
        assertEquals(2_048, raster.band(4).y0)
        assertEquals(196, raster.band(4).heightPx)
        assertEquals(raster.heightPx, (0 until raster.bandCount).sumOf { raster.band(it).heightPx })
    }

    @Test
    fun `a wide page is cut in pieces no wider than 8 192 px that cover it edge to edge`() {
        // A 2 m wide frame at 300 dpi: ≈ 23 600 px wide, three pieces in every band.
        val raster = PrintRaster(FrameProjection(lat45, 6.0, 25_000.0, 2_000.0, 300.0), 300)
        assertEquals(512, raster.bandHeightPx)
        assertEquals(3, raster.pieceCount)
        val band = raster.band(0)
        val pieces = raster.pieces(band)
        assertEquals(listOf(0, 8_192, 16_384), pieces.map { it.x0 })
        assertEquals(raster.widthPx, pieces.sumOf { it.widthPx })
        assertTrue(pieces.all { it.widthPx <= PrintRaster.MAX_PIECE_WIDTH && it.heightPx == band.heightPx })
        // The tiles of a piece are placed in its own bitmap, the ones cut by its edges reaching beyond it.
        val z = 16
        val middle = raster.placements(z, pieces[1])
        assertTrue(middle.minOf { it.left } <= 0 && middle.maxOf { it.right } >= pieces[1].widthPx)
        // A tile lands at the same place of the page whichever piece draws it.
        val inBand = raster.placements(z, band).associateBy { it.tile }
        for ((k, piece) in pieces.withIndex()) {
            for (p in raster.placements(z, piece)) {
                val whole = inBand.getValue(p.tile)
                assertEquals(whole.left, p.left + piece.x0, "piece $k, ${p.tile}")
                assertEquals(whole.right, p.right + piece.x0, "piece $k, ${p.tile}")
            }
        }
        // Every tile of the band is drawn by one piece or two (when cut by an edge), never left out.
        assertEquals(inBand.keys, pieces.flatMap { p -> raster.placements(z, p).map { it.tile } }.toSet())
        // An A4 page is one piece, the whole band.
        val a4 = PrintRaster(FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0), 300)
        assertEquals(listOf(a4.band(0)), a4.pieces(a4.band(0)))
    }

    @Test
    fun `tile placements abut without a gap and cover the band`() {
        val frame = FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        val raster = PrintRaster(frame, 300)
        val z = 16
        for (i in 0 until raster.bandCount) {
            val band = raster.band(i)
            val placements = raster.placements(z, band)
            assertTrue(placements.isNotEmpty())
            // Every pixel of the band is covered by at least one tile: check the corners and the middle.
            val probes =
                listOf(
                    0 to 0,
                    raster.widthPx - 1 to 0,
                    0 to band.heightPx - 1,
                    raster.widthPx - 1 to band.heightPx - 1,
                    raster.widthPx / 2 to band.heightPx / 2,
                )
            for ((x, y) in probes) {
                assertTrue(
                    placements.any {
                        x >= it.left && x < it.right && y >= it.top && y < it.bottom
                    },
                    "band $i pixel $x,$y",
                )
            }
            // Neighbours in a row share their edge exactly.
            val byRow = placements.groupBy { it.tile.y }
            for (row in byRow.values) {
                val sorted = row.sortedBy { it.tile.x }
                for (k in 1 until sorted.size) assertEquals(sorted[k - 1].right, sorted[k].left)
            }
            val byColumn = placements.groupBy { it.tile.x }
            for (column in byColumn.values) {
                val sorted = column.sortedBy { it.tile.y }
                for (k in 1 until sorted.size) assertEquals(sorted[k - 1].bottom, sorted[k].top)
            }
        }
    }

    @Test
    fun `a tile is placed at the size the scale gives it`() {
        val frame = FrameProjection(lat45, 6.0, 25_000.0, 277.0, 190.0)
        val raster = PrintRaster(frame, 300)
        val z = 16
        val placement = raster.placements(z, raster.band(1)).first()
        val tileMeters = 2 * TileMath.ORIGIN_SHIFT_M / (1 shl z)
        // A tile is tileMeters of the Mercator plane; the paper shows the plane at 1:25 000 x 1/cos(lat).
        val expectedMm = tileMeters / frame.mercatorPerMm
        val widthMm = (placement.right - placement.left) / 300.0 * 25.4
        assertEquals(expectedMm, widthMm, 0.1)
        assertTrue(abs((placement.right - placement.left) - (placement.bottom - placement.top)) <= 1)
    }

    @Test
    fun `the number of tiles of a page matches the count`() {
        val frame = FrameProjection(lat45, 6.0, 50_000.0, 277.0, 190.0)
        val raster = PrintRaster(frame, 300)
        val tiles = raster.tiles(15)
        assertEquals(raster.tileCount(15), tiles.size.toLong())
        assertTrue(tiles.size in 170..260, "${tiles.size}")
        assertTrue(tiles.all { it.z == 15 })
        assertTrue(tiles.contains(fr.toolmap.core.tiles.TileId(15, raster.centerTile(15).x, raster.centerTile(15).y)))
    }

    @Test
    fun `a frame at the edge of the map keeps its tile ranges inside the world`() {
        val frame = FrameProjection(84.9, 179.99, 25_000.0, 277.0, 190.0)
        val raster = PrintRaster(frame, 150)
        val (xs, ys) = raster.tileRange(10)
        assertTrue(xs.first >= 0 && xs.last < 1024 && ys.first >= 0 && ys.last < 1024)
    }

    @Test
    fun `the scale bar of 1 to 25 000 is a round length that fits`() {
        val bar = ScaleBarPlanner.plan(25_000.0, maxWidthMm = 80.0, locale = Locale.FRANCE)
        // 80 mm is 2 km at 1:25 000: exactly 2 km, 4 blocks of 500 m.
        assertEquals(2_000.0, bar.totalMeters, 1e-9)
        assertEquals(80.0, bar.widthMm, 1e-9)
        assertEquals(4, bar.blocks.size)
        assertEquals(listOf(true, false, true, false), bar.blocks.map { it.black })
        assertEquals(listOf("0", "0,5", "1", "1,5", "2 km"), bar.labels.map { it.text })
        assertEquals(0.0, bar.blocks.first().startMm, 1e-9)
        assertEquals(bar.widthMm, bar.blocks.last().endMm, 1e-9)
    }

    @Test
    fun `short scale bars are in meters and never wider than allowed`() {
        val bar = ScaleBarPlanner.plan(5_000.0, maxWidthMm = 60.0, locale = Locale.FRANCE)
        assertTrue(bar.widthMm <= 60.0)
        assertEquals(200.0, bar.totalMeters, 1e-9)
        assertEquals("200 m", bar.labels.last().text)
        for (scale in listOf(1_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 250_000.0)) {
            for (max in listOf(40.0, 60.0, 100.0)) {
                val b = ScaleBarPlanner.plan(scale, max)
                assertTrue(b.widthMm <= max + 1e-9 && b.widthMm > max / 2.6, "1:$scale max $max -> ${b.widthMm}")
            }
        }
        assertEquals(ScaleBarPlan.EMPTY, ScaleBarPlanner.plan(0.0, 10.0))
    }
}
