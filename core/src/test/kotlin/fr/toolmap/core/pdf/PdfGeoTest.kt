package fr.toolmap.core.pdf

import fr.toolmap.core.geo.LonLat
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The geospatial PDF of P37: the dictionaries GDAL writes itself (ISO 32000, `GEO_ENCODING=ISO32000`). */
class PdfGeoTest {
    private val viewport =
        PdfGeoViewport(
            leftMm = 10.0,
            topMm = 20.0,
            widthMm = 100.0,
            heightMm = 50.0,
            topLeft = LonLat(6.0, 45.01),
            bottomLeft = LonLat(6.0, 45.0),
            bottomRight = LonLat(6.02, 45.0),
            topRight = LonLat(6.02, 45.01),
            name = "Tour du lac",
        )

    private fun write(block: PdfWriter.() -> Unit): PdfProbe {
        val out = ByteArrayOutputStream()
        PdfWriter(out).use(block)
        return PdfProbe(out.toByteArray())
    }

    private fun ref(
        body: String,
        key: String,
    ): Int = Regex("/$key (\\d+) 0 R").find(body)!!.groupValues[1].toInt()

    @Test
    fun `a georeferenced page carries its viewport, measure and coordinate system`() {
        val pdf =
            write {
                beginPage(210.0, 297.0).geoViewport(viewport)
            }
        val page = pdf.body(pdf.pageNumbers[0])
        val vpNumber = Regex("/VP \\[(\\d+) 0 R]").find(page)!!.groupValues[1].toInt()
        val vp = pdf.body(vpNumber)
        assertTrue("/Type /Viewport" in vp)
        // 10 mm from the left, 20 mm from the top of a 297 mm page: the box in points, origin at the bottom left.
        val box = Regex("/BBox \\[([^\\]]*)]").find(vp)!!.groupValues[1].split(' ').map { it.toDouble() }
        val pt = 72 / 25.4
        assertEquals(4, box.size)
        box.zip(listOf(10 * pt, (297 - 20 - 50) * pt, 110 * pt, (297 - 20) * pt)).forEach { (a, b) ->
            assertEquals(b, a, 0.001)
        }
        val measure = pdf.body(ref(vp, "Measure"))
        assertTrue("/Subtype /GEO" in measure)
        assertTrue("/LPTS [0 1 0 0 1 0 1 1]" in measure)
        // Latitude first, corners in the order of the unit square: top left, bottom left, bottom right, top right.
        assertTrue("/GPTS [45.01 6 45 6 45 6.02 45.01 6.02]" in measure, measure)
        val crs = pdf.body(ref(measure, "GCS"))
        assertTrue("/Type /PROJCS /EPSG 3857" in crs)
        assertTrue("Mercator_Auxiliary_Sphere" in crs)
    }

    @Test
    fun `a page without viewport has no geospatial entry`() {
        val pdf = write { beginPage(210.0, 297.0) }
        assertFalse("/VP" in pdf.body(pdf.pageNumbers[0]))
        assertFalse("/Measure" in pdf.text)
    }
}
