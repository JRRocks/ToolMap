package fr.toolmap.core.print

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.pdf.PdfImage
import fr.toolmap.core.pdf.PdfPage
import fr.toolmap.core.pdf.PdfProbe
import fr.toolmap.core.source.BuiltinSources
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Draws tiles as plain squares with a one-pixel black border, enough to see where the maths puts them. */
private class SyntheticRenderer(
    private val missingPerBand: Int = 0,
) : BandRenderer {
    val calls = ArrayList<Pair<PageKind, Band>>()

    override fun render(
        page: PrintPagePlan,
        band: Band,
    ): RenderedBand {
        calls += page.kind to band
        val width = page.raster.widthPx
        val image = BufferedImage(width, band.heightPx, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(238, 238, 230)
        g.fillRect(0, 0, width, band.heightPx)
        for (layer in page.layers.layers) {
            for (p in page.raster.placements(layer.tileZoom, band)) {
                g.color = if ((p.tile.x + p.tile.y) % 2 == 0) Color(198, 224, 198) else Color(214, 230, 248)
                g.fillRect(p.left, p.top, p.right - p.left, p.bottom - p.top)
                g.color = Color.BLACK
                g.drawRect(p.left, p.top, p.right - p.left - 1, p.bottom - p.top - 1)
            }
        }
        g.dispose()
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.9f
            }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return RenderedBand(PdfImage.jpeg(width, band.heightPx, out.toByteArray()), missingPerBand)
    }
}

class PrintExportTest {
    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "ign-maps"))
    private val track =
        PrintTrack(
            "Col de la Croix",
            0xFFD32F2F.toInt(),
            0.6,
            listOf(List(40) { i -> LonLat(5.95 + i * 0.003, 44.97 + i * 0.0015) }),
        )
    private val content =
        PrintContent(
            tracks = listOf(track),
            waypoints = listOf(PrintWaypoint("Refuge", 45.0, 6.0, 0xFF1976D2.toInt())),
            attributions = listOf("© IGN-F/Géoportail", "© les contributeurs d'OpenStreetMap"),
            declinationDegrees = 2.25,
            declinationYear = 2026,
            dateText = "02/10/2026",
        )

    private fun plan(
        template: PrintTemplate,
        area: PrintArea = PrintArea(45.0, 6.0),
    ) = PrintPlanner.plan(template, area, stack, sources::get)

    private fun export(
        plan: PrintPlan,
        renderer: BandRenderer = SyntheticRenderer(),
        content: PrintContent = this.content,
    ): Pair<PrintResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = PrintExporter(renderer).export(plan, content, out)
        return result to out.toByteArray()
    }

    /** How a text is written in a content stream: `<hex> Tj`. */
    private fun shown(text: String): String =
        "<" + PdfPage.encode(text).joinToString("") { "%02X".format(it.toInt() and 0xFF) } + ">"

    /** Every text of a content stream, one per line. */
    private fun written(content: String): String =
        Regex("<([0-9A-F]*)> Tj").findAll(content).joinToString("\n") { m ->
            val hex = m.groupValues[1]
            val bytes = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            String(bytes, charset("windows-1252"))
        }

    @Test
    fun `an A4 page at 1 to 50 000 comes out as one valid page with its strips and furniture`() {
        val template =
            PrintTemplate(
                scale = 50_000.0,
                dpi = 150,
                title = "Belledonne",
                subtitle = "Sortie du 2 octobre",
                grid = GridKind.UTM,
                dressing = Dressing(cornerCoordinates = true),
            )
        val p = plan(template)
        assertTrue(p.canExport, p.issues.toString())
        val renderer = SyntheticRenderer()
        val (result, bytes) = export(p, renderer)
        assertEquals(1, result.pages)
        assertEquals(bytes.size.toLong(), result.bytes)
        val probe = PdfProbe(bytes)
        assertEquals(1, probe.pageNumbers.size)
        assertEquals(listOf(0.0, 0.0, 841.89, 595.276), probe.mediaBox(0))
        for (number in probe.offsets.keys) probe.body(number)

        val raster = p.pages.single().raster
        // One call per strip, each reaching two rows into the next; no situation map any more (P17).
        val strips = renderer.calls.filter { it.first == PageKind.MAP }
        assertEquals(raster.bandCount, strips.size)
        assertEquals(raster.heightPx, strips.last().second.y0 + strips.last().second.heightPx)
        assertTrue(strips.dropLast(1).all { it.second.heightPx == raster.bandHeightPx + PrintExporter.SEAM_ROWS })
        assertEquals(0, renderer.calls.count { it.first == PageKind.MINIMAP })

        val content = probe.contentOfPage(0)
        assertEquals(raster.bandCount, Regex("/Im\\d+ Do").findAll(content).count())
        // The scale text "1:50 000" (with a no-break space) and the title are written, the credits too.
        assertTrue(shown("1:50 000") in content, "scale missing")
        assertTrue(shown("Belledonne") in content, "title missing")
        assertTrue(content.contains("A9"), "© missing")
    }

    @Test
    fun `strips are placed edge to edge over the exact frame`() {
        val template = PrintTemplate(scale = 50_000.0, dpi = 300, dressing = Dressing())
        val setup = template.page
        val p = plan(template)
        val (_, bytes) = export(p)
        val content = PdfProbe(bytes).contentOfPage(0)
        // Every strip is "w 0 0 -h left y cm", the frame starting inside the margin, the label strip and the band.
        val placements =
            Regex(
                "\n([\\d.]+) 0 0 (-[\\d.]+) ([\\d.]+) ([\\d.]+) cm\n/Im\\d+ Do",
            ).findAll(content).toList()
        val raster = p.pages.single().raster
        assertEquals(raster.bandCount, placements.size)
        val mmPerRow = setup.frameHeightMm / raster.heightPx
        var expectedTop = setup.frameTopMm
        for ((i, m) in placements.withIndex()) {
            val width = m.groupValues[1].toDouble()
            val height = -m.groupValues[2].toDouble()
            val bottom = m.groupValues[4].toDouble()
            assertEquals(setup.frameWidthMm, width, 1e-3)
            assertEquals(setup.frameLeftMm, m.groupValues[3].toDouble(), 1e-9)
            assertEquals(expectedTop, bottom - height, 1e-3, "strip $i top")
            expectedTop += raster.band(i).heightPx * mmPerRow
        }
        assertEquals(setup.frameTopMm + setup.frameHeightMm, expectedTop, 1e-2)
    }

    @Test
    fun `the sheet of an A4 landscape keeps the map frame, the label strips and a footer of one row`() {
        val setup = PageSetup()
        // 8 mm margin, 7 mm of labels, 1.6 mm of graduated band on each side.
        assertEquals(16.6, setup.frameLeftMm, 1e-9)
        assertEquals(297.0 - 2 * 16.6, setup.frameWidthMm, 1e-9)
        assertEquals(1, SheetLayout.rows(297.0 - 16.0, MapStyle.TOP25))
        assertEquals(1, SheetLayout.rows(297.0 - 16.0, MapStyle.NATO))
        assertEquals(setup.footerTopMm + setup.footerHeightMm, 210.0 - 8.0, 1e-9)
        assertTrue(setup.frameHeightMm > 140.0, "${setup.frameHeightMm}")
        // A narrow sheet takes a second row of boxes rather than smaller letters.
        assertEquals(2, SheetLayout.rows(210.0 - 16.0, MapStyle.NATO))
        val portrait = PageSetup(landscape = false, style = MapStyle.NATO)
        assertTrue(portrait.footerHeightMm > setup.footerHeightMm)
        val blocks = SheetLayout.place(297.0 - 16.0, MapStyle.NATO)
        assertEquals(FooterBlock.entries.toList(), blocks.map { it.block })
        for ((a, b) in blocks.zipWithNext()) assertTrue(a.xMm + a.widthMm < b.xMm, "$a overlaps $b")
        assertTrue(blocks.last().xMm + blocks.last().widthMm <= 297.0 - 16.0 - SheetLayout.PAD_MM + 1e-9)
    }

    @Test
    fun `a NATO sheet adds the mils and the grid reference box, a TOP 25 sheet does not`() {
        val base =
            PrintTemplate(
                scale = 25_000.0,
                dpi = 100,
                title = "Chartreuse",
                grid = GridKind.MGRS,
            )
        val nato = base.copy(page = PageSetup(style = MapStyle.NATO))
        val natoContent = PdfProbe(export(plan(nato, PrintArea(45.4, 5.85))).second).contentOfPage(0)
        val top25Content = PdfProbe(export(plan(base, PrintArea(45.4, 5.85))).second).contentOfPage(0)
        val texts = PrintTexts()
        assertTrue(shown(texts.referenceTitle) in natoContent, "reference box missing")
        assertTrue(shown(texts.referenceTitle) !in top25Content)
        assertTrue(" mils" in written(natoContent), written(natoContent))
        assertTrue(" mils" !in written(top25Content))
        // The worked example is the MGRS reference of the centre, at 100 m.
        val example =
            assertNotNull(GridPlanner.exampleReference(plan(nato, PrintArea(45.4, 5.85)).pages.single().frame))
        assertTrue(Regex("31T [A-Z]{2} \\d{3} \\d{3}").matches(example), example)
        assertTrue(String.format(texts.referenceExample, example) in written(natoContent))
    }

    @Test
    fun `a sheet carries the chosen grid only, never the degrees around a projected grid`() {
        // The user's UTM and MGRS sheets of 2026-10-28 had the corners and the band in degrees, and MGRS the « 7 » of
        // the hundreds of km before its two figures (ADR-087).
        // Corners are written to the second; the declination of the norths box (« 2°15' E ») is not a corner.
        val corner = Regex("""\d+°\d{2}\D\d{2}""")
        val base = PrintTemplate(scale = 25_000.0, dpi = 100, dressing = Dressing(cornerCoordinates = true))
        val pontcharra = PrintArea(45.4274, 6.004)

        fun text(template: PrintTemplate): String =
            written(PdfProbe(export(plan(template, pontcharra)).second).contentOfPage(0))
        val utm = text(base.copy(grid = GridKind.UTM))
        assertTrue(!corner.containsMatchIn(utm), utm)
        assertTrue(" m E" in utm && " m N" in utm, utm)
        val mgrs = text(base.copy(grid = GridKind.MGRS))
        assertTrue(!corner.containsMatchIn(mgrs), mgrs)
        assertTrue(" m E" !in mgrs && " m N" !in mgrs, mgrs)
        // No small figure before the two of a line: the hundreds of km (« 7 », « 50 ») are not written.
        assertTrue(mgrs.lines().none { it == "7" || it == "2" || it == "50" }, mgrs)
        val lambert = text(base.copy(grid = GridKind.LAMBERT93))
        assertTrue(!corner.containsMatchIn(lambert), lambert)
        // The degrees keep their corners; the decimal ones in decimal degrees.
        assertTrue(corner.containsMatchIn(text(base.copy(grid = GridKind.WGS84))))
        val decimal = text(base.copy(grid = GridKind.WGS84_DECIMAL))
        assertTrue(!corner.containsMatchIn(decimal), decimal)
        assertTrue(Regex("""\d+[.,]\d{5}° [NE]""").containsMatchIn(decimal), decimal)
    }

    @Test
    fun `the NATO reference box speaks the grid of the sheet`() {
        val nato = PrintTemplate(scale = 25_000.0, dpi = 100, page = PageSetup(style = MapStyle.NATO))
        val area = PrintArea(45.4, 5.85)

        fun text(grid: GridKind): String =
            written(PdfProbe(export(plan(nato.copy(grid = grid), area)).second).contentOfPage(0))
        val texts = PrintTexts()
        val utm = text(GridKind.UTM)
        assertTrue("MGRS" !in utm, utm)
        assertTrue(String.format(texts.referenceUtmStep1, "31T") in utm, utm)
        assertTrue(Regex("""31T \d{3} \d{3} E \d \d{3} \d{3} N""").containsMatchIn(utm), utm)
        val lambert = text(GridKind.LAMBERT93)
        assertTrue("MGRS" !in lambert && "31T" !in lambert, lambert)
        assertTrue(Regex("""\d{3} \d{3} E \d \d{3} \d{3} N""").containsMatchIn(lambert), lambert)
        val degrees = text(GridKind.WGS84)
        assertTrue("MGRS" !in degrees, degrees)
    }

    @Test
    fun `the norths box always shows the three angles with their values, in both styles`() {
        val sheet =
            PrintContent(
                declinationDegrees = 3.2,
                declinationYear = 2026,
                declinationAnnualDegrees = 0.13,
            )
        val texts = PrintTexts()
        for (style in MapStyle.entries) {
            val template =
                PrintTemplate(
                    scale = 25_000.0,
                    dpi = 100,
                    grid = GridKind.UTM,
                    page = PageSetup(style = style),
                )
            val p = plan(template, PrintArea(45.6, 5.95))
            val shownTexts = written(PdfProbe(export(p, content = sheet).second).contentOfPage(0)).lines()
            val convergence = assertNotNull(GridPlanner.convergenceDegrees(p.pages.single().frame, GridKind.UTM))
            // As written on the page: the prime of the minutes becomes an apostrophe in Windows-1252.
            val angle = { d: Double ->
                String(PdfPage.encode(PrintFormat.declination(d, texts.east, texts.west)), charset("windows-1252"))
            }
            for ((label, degrees) in listOf(
                texts.declination to 3.2,
                texts.gridConvergence to convergence,
                texts.gridMagneticAngle to 3.2 - convergence,
            )) {
                assertTrue(shownTexts.any { it.startsWith(label) }, "$style: $label missing in $shownTexts")
                assertTrue(shownTexts.any { it.startsWith(angle(degrees)) }, "$style: ${angle(degrees)} missing")
            }
        }
    }

    @Test
    fun `waypoint icons are written once however many waypoints use them`() {
        val glyph = PdfImage.rgba(2, 2, ByteArray(12) { -1 }, byteArrayOf(0, -1, -1, 0))
        val waypoints = List(5) { i -> PrintWaypoint("P$i", 45.0 + i * 0.002, 6.0, 0xFF1976D2.toInt(), "peak") }
        val withIcons = PrintContent(waypoints = waypoints, glyphs = mapOf("peak" to glyph))
        val p = plan(PrintTemplate(scale = 25_000.0, dpi = 100))
        val probe = PdfProbe(export(p, content = withIcons).second)
        val masks = probe.offsets.keys.count { "/ColorSpace /DeviceGray" in probe.body(it) }
        assertEquals(1, masks)
        val raster = p.pages.single().raster
        assertEquals(raster.bandCount + 5, Regex("/Im\\d+ Do").findAll(probe.contentOfPage(0)).count())
    }

    @Test
    fun `an atlas gives an overview page first then one page per sheet`() {
        val area = Bounds(5.95, 44.95, 6.2, 45.1)
        val template = PrintTemplate(scale = 50_000.0, dpi = 100, atlas = AtlasMode.GRID)
        val p = plan(template, PrintArea(45.0, 6.0, bounds = area))
        val renderer = SyntheticRenderer(missingPerBand = 1)
        val (result, bytes) = export(p, renderer)
        assertEquals(p.pages.size, result.pages)
        assertTrue(result.pages >= 3)
        assertEquals(result.pages, PdfProbe(bytes).pageNumbers.size)
        assertEquals(PageKind.OVERVIEW, renderer.calls.first().first)
        assertTrue(result.missingTiles > 0)
        val firstMapPage = PdfProbe(bytes).contentOfPage(1)
        assertTrue(String.format(PrintTexts().page, 1, p.mapPages.size) in written(firstMapPage))
    }

    @Test
    fun `a plan with blocking issues is refused and cancellation stops the export`() {
        val bad = plan(PrintTemplate(scale = 50_000.0, atlas = AtlasMode.GRID))
        assertFailsWith<IllegalArgumentException> { export(bad) }
        val good = plan(PrintTemplate(scale = 50_000.0, dpi = 150))
        var calls = 0
        val exporter =
            PrintExporter(SyntheticRenderer(), ensureActive = {
                if (++calls >
                    2
                ) {
                    throw CancellationException("stop")
                }
            })
        assertFailsWith<CancellationException> { exporter.export(good, content, ByteArrayOutputStream()) }
    }

    @Test
    fun `progress goes from zero to one over the pages`() {
        val p = plan(PrintTemplate(scale = 50_000.0, dpi = 150))
        val seen = ArrayList<Double>()
        PrintExporter(SyntheticRenderer()).export(p, content, ByteArrayOutputStream()) { seen += it.fraction }
        assertEquals(0.0, seen.first())
        assertEquals(1.0, seen.last())
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun `samples for visual checks are written to the build directory`() {
        val dir = File("build/print-samples").apply { mkdirs() }
        val sheet =
            content.let {
                PrintContent(
                    tracks = it.tracks,
                    waypoints = it.waypoints,
                    attributions = it.attributions,
                    layerNames = it.layerNames,
                    declinationDegrees = it.declinationDegrees,
                    declinationYear = it.declinationYear,
                    declinationAnnualDegrees = 0.13,
                    dateText = it.dateText,
                )
            }
        val top25 =
            PrintTemplate(
                scale = 25_000.0,
                dpi = 200,
                title = "Belledonne — traversée",
                subtitle = "Étape 2 : col de la Croix",
                grid = GridKind.UTM,
            )
        val p = plan(top25)
        File(dir, "top25-utm-25000.pdf").outputStream().use { PrintExporter(SyntheticRenderer()).export(p, sheet, it) }
        val nato = top25.copy(scale = 50_000.0, page = PageSetup(style = MapStyle.NATO), grid = GridKind.MGRS)
        File(dir, "nato-mgrs-50000.pdf").outputStream().use {
            PrintExporter(SyntheticRenderer()).export(plan(nato), sheet, it)
        }
        // Across zones 31 and 32 at Pontcharra (P24.4): the NATO sheet of the P24.0 preview, the TOP 25 of the user.
        val pontcharra = PrintArea(45.4274, 6.004)
        val straddling = nato.copy(scale = 25_000.0, page = PageSetup(landscape = false, style = MapStyle.NATO))
        File(dir, "nato-mgrs-pontcharra.pdf").outputStream().use {
            PrintExporter(SyntheticRenderer()).export(plan(straddling, pontcharra), sheet, it)
        }
        // The user's sheet, with the French words of the grid box, the longest it gets.
        val french =
            PrintTexts(
                gridHeading = "QUADRILLAGE",
                gridSquares = "Carreaux de %s",
                zoneEdgeMeridian = "Limite des zones %1\$s / %2\$s : méridien %3\$s (trait épais)",
                neighbourGrid = "En bleu dans la marge : quadrillage de la zone voisine",
                and = "et",
                squaresOfZones = "Carrés de 100 km : %s",
                westSide = "ouest",
                eastSide = "est",
                west = "O",
            )
        val frenchSheet = PrintContent(attributions = sheet.attributions, dateText = sheet.dateText, texts = french)
        File(dir, "top25-mgrs-pontcharra.pdf").outputStream().use {
            val top25Mgrs = top25.copy(grid = GridKind.MGRS)
            PrintExporter(SyntheticRenderer()).export(plan(top25Mgrs, PrintArea(45.48625, 5.99694)), frenchSheet, it)
        }
        val portrait = nato.copy(page = PageSetup(landscape = false, style = MapStyle.NATO), grid = GridKind.WGS84)
        File(dir, "nato-wgs84-portrait.pdf").outputStream().use {
            PrintExporter(SyntheticRenderer()).export(plan(portrait), sheet, it)
        }
        val single = p.pages.single()
        val frame = single.frame
        val geometry =
            listOf(
                "\"lat\": ${frame.centerLat}",
                "\"lon\": ${frame.centerLon}",
                "\"scale\": ${frame.scale}",
                "\"frameLeft\": ${top25.page.frameLeftMm}",
                "\"frameTop\": ${top25.page.frameTopMm}",
                "\"frameW\": ${frame.frameWidthMm}",
                "\"frameH\": ${frame.frameHeightMm}",
                "\"zoom\": ${single.layers.layers.single().tileZoom}",
                "\"dpi\": ${single.dpi}",
            )
        File(dir, "top25-utm-25000.json").writeText(geometry.joinToString(", ", "{", "}"))
        val atlas =
            plan(
                top25.copy(scale = 50_000.0, atlas = AtlasMode.GRID, dpi = 100, grid = GridKind.WGS84),
                PrintArea(45.0, 6.0, bounds = Bounds(5.95, 44.95, 6.2, 45.1)),
            )
        File(dir, "atlas.pdf").outputStream().use { PrintExporter(SyntheticRenderer()).export(atlas, sheet, it) }
        assertTrue(File(dir, "top25-utm-25000.pdf").length() > 1_000)
    }
}
