package fr.toolmap.core.print

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.pdf.PdfImage
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.core.track.GpxReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Draws the tiles of the page as a checkerboard with black borders: the tile grid of Web Mercator, visible in a GIS. */
private class TileGridRenderer : BandRenderer {
    override fun render(
        page: PrintPagePlan,
        band: Band,
    ): RenderedBand {
        val image = BufferedImage(band.widthPx, band.heightPx, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(238, 238, 230)
        g.fillRect(0, 0, band.widthPx, band.heightPx)
        for (layer in page.layers.layers) {
            for (p in page.raster.placements(layer.tileZoom, band)) {
                g.color = if ((p.tile.x + p.tile.y) % 2 == 0) Color(214, 232, 206) else Color(222, 232, 246)
                g.fillRect(p.left, p.top, p.right - p.left, p.bottom - p.top)
                g.color = Color(60, 60, 60)
                g.drawRect(p.left, p.top, p.right - p.left - 1, p.bottom - p.top - 1)
            }
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpeg", out)
        return RenderedBand(PdfImage.jpeg(band.widthPx, band.heightPx, out.toByteArray()), 0)
    }
}

/** P37: the PDF of a print says where its map is on the ground, as GDAL (so QGIS) and Global Mapper read it. */
class PrintGeoTest {
    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "osm"))

    private fun export(
        template: PrintTemplate,
        area: PrintArea,
        content: PrintContent = PrintContent(),
    ): ByteArray {
        val plan = PrintPlanner.plan(template, area, stack, sources::get)
        val out = ByteArrayOutputStream()
        PrintExporter(TileGridRenderer()).export(plan, content, out)
        return out.toByteArray()
    }

    @Test
    fun `the corners of the viewport are the corners of the frame, exactly on the Web Mercator plane`() {
        val setup = PageSetup()
        val frame = FrameProjection(45.0, 6.0, 25_000.0, setup.frameWidthMm, setup.frameHeightMm)
        val vp = PrintGeo.viewport(setup, frame)
        assertEquals(setup.frameLeftMm, vp.leftMm)
        assertEquals(setup.frameTopMm, vp.topMm)
        val b = frame.mercatorBounds

        fun x(p: LonLat) = TileMath.mercatorX(p.lon)

        fun y(p: LonLat) = TileMath.mercatorY(p.lat)
        // A millimeter of mercator rounding at most: the corners place the map to a fraction of a pixel.
        for ((corner, expected) in listOf(
            vp.topLeft to (b.minX to b.maxY),
            vp.bottomLeft to (b.minX to b.minY),
            vp.bottomRight to (b.maxX to b.minY),
            vp.topRight to (b.maxX to b.maxY),
        )) {
            assertEquals(expected.first, x(corner), 0.001)
            assertEquals(expected.second, y(corner), 0.001)
        }
    }

    @Test
    fun `off by default, nothing changes in the file`() {
        val pdf = String(export(PrintTemplate(dpi = 72), PrintArea(45.0, 6.0)), Charsets.ISO_8859_1)
        assertFalse("/VP" in pdf)
    }

    @Test
    fun `every page of an atlas is georeferenced with its own frame`() {
        val path = List(60) { i -> LonLat(6.0 + i * 0.004, 45.0 + i * 0.002) }
        val template = PrintTemplate(dpi = 72, scale = 25_000.0, atlas = AtlasMode.TRACK, geoReferenced = true)
        val pdf = String(export(template, PrintArea(45.06, 6.12, path = path)), Charsets.ISO_8859_1)
        val pages = Regex("/Type /Page ").findAll(pdf).count()
        val viewports = Regex("/VP \\[").findAll(pdf).count()
        assertTrue(pages > 1, "an atlas of several pages: $pages")
        assertEquals(pages, viewports)
        assertEquals(pages, Regex("/GPTS \\[").findAll(pdf).count())
    }

    /**
     * End to end, when GDAL is installed (`apt install gdal-bin`, or the OSGeo4W shell of QGIS on Windows): GDAL reads
     * the PDF of a 1:25 000 print of a real GPX track as EPSG:3857, and the pixel it finds at each point of the track is
     * the red of the printed track. The PDF stays in `core/build/geopdf/` to be opened in QGIS with the same GPX.
     */
    @Test
    fun `GDAL finds the printed track at the coordinates of its points`() {
        Assume.assumeTrue("gdalinfo is not installed", run("gdalinfo", "--version") != null)
        val fixtures = File(requireNotNull(System.getProperty("fixtures.dir")))
        val gpx = GpxReader.read(File(fixtures, "gpx/korita-zbevnica.gpx").readBytes())
        // The page is centred on the first of the three tracks of the file (they are 10 km apart).
        val points = gpx.tracks.first().segments.flatten().map { LonLat(it.lon, it.lat) }
        val center =
            LonLat(
                (points.minOf { it.lon } + points.maxOf { it.lon }) / 2,
                (points.minOf { it.lat } + points.maxOf { it.lat }) / 2,
            )
        val template =
            PrintTemplate(
                scale = SCALE,
                dpi = GDAL_DPI,
                title = "Korita – Zbevnica",
                grid = GridKind.UTM,
                geoReferenced = true,
            )
        val segments = gpx.tracks.flatMap { it.segments }.map { s -> s.map { LonLat(it.lon, it.lat) } }
        val track = PrintTrack("Korita – Zbevnica", TRACK_RED, TRACK_WIDTH_MM, segments)
        val bytes = export(template, PrintArea(center.lat, center.lon), PrintContent(tracks = listOf(track)))
        val file = File("build/geopdf/korita-zbevnica-25k.pdf").apply { parentFile.mkdirs() }
        file.writeBytes(bytes)

        val info = Json.parseToJsonElement(requireNotNull(run("gdalinfo", "-json", file.path))).jsonObject
        val wkt = info["coordinateSystem"]!!.jsonObject["wkt"]!!.jsonPrimitive.content
        assertTrue("Pseudo-Mercator" in wkt || "3857" in wkt, wkt.take(200))
        val gt = info["geoTransform"]!!.jsonArray.map { it.jsonPrimitive.double }
        val size = info["size"]!!.jsonArray.map { it.jsonPrimitive.int }
        // GDAL draws the page at 150 dpi: the frame starts at its margin, in pixels of that page.
        val page = template.page
        val frame = FrameProjection(center.lat, center.lon, SCALE, page.frameWidthMm, page.frameHeightMm)
        val pxPerMm = GDAL_DPI / 25.4
        assertEquals(frame.mercatorBounds.minX, gt[0] + page.frameLeftMm * pxPerMm * gt[1], gt[1])
        assertEquals(frame.mercatorBounds.maxY, gt[3] + page.frameTopMm * pxPerMm * gt[5], -gt[5])
        assertEquals(Math.round(page.widthMm * pxPerMm).toInt(), size[0], "page width in pixels")

        val inside =
            points.filter {
                frame.contains(frame.toFrame(it.lon, it.lat))
            }.filterIndexed { i, _ -> i % SAMPLE_EVERY == 0 }
        assertTrue(inside.size >= 10, "points of the track on the page: ${inside.size}")
        val bands = info["bands"]!!.jsonArray.size
        val colors = sample(file, inside, bands)
        val red = colors.count { (r, g, b) -> r > 150 && g < 120 && b < 120 }
        assertTrue(red >= inside.size * 8 / 10, "track pixels found where GDAL puts the track: $red of ${inside.size}")
        // Control: 300 m to the east, the same points are mostly not on the track.
        val shifted = sample(file, inside.map { LonLat(it.lon + 0.004, it.lat) }, bands)
        val shiftedRed = shifted.count { (r, g, b) -> r > 150 && g < 120 && b < 120 }
        assertTrue(shiftedRed < inside.size / 2, "control: $shiftedRed of ${inside.size} red 300 m away")
    }

    /** The RGB of the page at each WGS84 point, as GDAL rasterizes the PDF (`gdallocationinfo -wgs84`). */
    private fun sample(
        pdf: File,
        points: List<LonLat>,
        bands: Int,
    ): List<Triple<Int, Int, Int>> {
        val input = points.joinToString("\n") { "${it.lon} ${it.lat}" } + "\n"
        val out = requireNotNull(run("gdallocationinfo", "-valonly", "-wgs84", pdf.path, input = input))
        val values = out.lines().filter { it.isNotBlank() }.map { it.trim().toDouble().toInt() }
        return values.chunked(bands).filter { it.size >= RGB }.map { Triple(it[0], it[1], it[2]) }
    }

    private fun run(
        vararg command: String,
        input: String? = null,
    ): String? =
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            if (input != null) {
                process.outputStream.bufferedWriter().use { it.write(input) }
            } else {
                process.outputStream.close()
            }
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS) || process.exitValue() != 0) null else text
        } catch (e: IOException) {
            // GDAL not installed: the test is skipped, not failed.
            println("GDAL not available: ${e.message}")
            null
        }

    private companion object {
        const val GDAL_DPI = 150
        const val SCALE = 25_000.0
        const val SAMPLE_EVERY = 12
        const val RGB = 3
        const val TRACK_WIDTH_MM = 0.6
        const val TIMEOUT_S = 120L
        val TRACK_RED = 0xFFD32F2F.toInt()
    }
}
