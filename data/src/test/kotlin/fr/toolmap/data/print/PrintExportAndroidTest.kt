package fr.toolmap.data.print

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.print.AtlasMode
import fr.toolmap.core.print.Dressing
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.PrintArea
import fr.toolmap.core.print.PrintContent
import fr.toolmap.core.print.PrintExporter
import fr.toolmap.core.print.PrintPlanner
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTrack
import fr.toolmap.core.print.PrintWaypoint
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.TileId
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole export with Android's graphics: tiles drawn on a Bitmap, composed, JPEG-compressed, written as PDF. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PrintExportAndroidTest {
    private val sources = BuiltinSources.load().associateBy { it.id }
    private val stack = listOf(LayerConfig("l1", "ign-maps"), LayerConfig("l2", "ign-pentes", opacity = 0.5f))

    /** A tile with a checker colour, a black border and its coordinates, enough to see the geometry on the paper. */
    private fun tile(
        source: TileSource,
        id: TileId,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val overlay = source.id == "ign-pentes"
        canvas.drawColor(
            when {
                overlay -> Color.argb(120, 255, 140, 0)
                (id.x + id.y) % 2 == 0 -> Color.rgb(198, 224, 198)
                else -> Color.rgb(214, 230, 248)
            },
        )
        val paint =
            Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        canvas.drawRect(1f, 1f, 255f, 255f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 22f
        canvas.drawText("${id.z}/${id.x}/${id.y}", 12f, 132f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val content =
        PrintContent(
            tracks =
                listOf(
                    PrintTrack(
                        "Col de la Croix",
                        0xFFD32F2F.toInt(),
                        0.6,
                        listOf(List(40) { i -> LonLat(5.95 + i * 0.003, 44.97 + i * 0.0015) }),
                    ),
                ),
            waypoints = listOf(PrintWaypoint("Refuge", 45.0, 6.0, 0xFF1976D2.toInt())),
            attributions = listOf("© IGN-F/Géoportail"),
            layerNames = listOf("IGN Plan", "IGN Pentes"),
            declinationDegrees = 2.25,
            declinationYear = 2026,
            dateText = "02/10/2026",
        )

    private fun export(
        template: PrintTemplate,
        area: PrintArea,
        unavailable: (TileId) -> Boolean = { false },
    ): Triple<fr.toolmap.core.print.PrintResult, ByteArray, fr.toolmap.core.print.PrintPlan> {
        val plan = PrintPlanner.plan(template, area, stack, sources::get)
        assertTrue(plan.canExport, plan.issues.toString())
        val fetcher =
            PrintTileFetcher { source, id ->
                if (unavailable(id)) TileFetch.Unavailable else TileFetch.Found(tile(source, id))
            }
        val out = ByteArrayOutputStream()
        val result = AndroidBandRenderer(fetcher).use { PrintExporter(it).export(plan, content, out) }
        return Triple(result, out.toByteArray(), plan)
    }

    private fun save(
        name: String,
        bytes: ByteArray,
    ) {
        val dir = File("build/print-samples").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
    }

    @Test
    fun `an A4 page with grid and overlay is exported with Android graphics`() {
        val template =
            PrintTemplate(
                scale = 50_000.0,
                dpi = 150,
                title = "Belledonne",
                subtitle = "Sortie du 2 octobre",
                grid = GridKind.UTM,
                dressing = Dressing(cornerCoordinates = true),
            )
        val (result, bytes, plan) = export(template, PrintArea(45.0, 6.0)) { it.x % 7 == 0 }
        save("android-a4.pdf", bytes)
        assertEquals(1, result.pages)
        assertEquals(bytes.size.toLong(), result.bytes)
        assertTrue(String(bytes, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertTrue(result.missingTiles > 0)
        assertTrue(plan.pages.single().layers.layers.size == 2)
    }

    @Test
    fun `a grid atlas gives an overview page then the numbered pages`() {
        val template = PrintTemplate(scale = 50_000.0, dpi = 100, atlas = AtlasMode.GRID, title = "Atlas")
        val area = fr.toolmap.core.geo.Bounds(5.9, 44.9, 6.3, 45.15)
        val (result, bytes, plan) = export(template, PrintArea(45.0, 6.1, bounds = area))
        save("android-atlas.pdf", bytes)
        assertEquals(plan.pages.size, result.pages)
        assertTrue(result.pages > 2)
        assertEquals(0, result.missingTiles)
    }
}
