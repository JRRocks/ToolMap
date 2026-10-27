package fr.toolmap.core.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfWriterTest {
    private fun write(block: PdfWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        PdfWriter(out, PdfInfo(title = "Carte é", creationMillis = 0L)).use(block)
        return out.toByteArray()
    }

    @Test
    fun `the file is well formed with exact page boxes and every object at its offset`() {
        val bytes =
            write {
                beginPage(297.0, 210.0).rect(10.0, 10.0, 277.0, 190.0)
                beginPage(210.0, 297.0).rect(10.0, 10.0, 190.0, 277.0)
            }
        assertTrue(String(bytes, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        val probe = PdfProbe(bytes)
        assertEquals(2, probe.pageNumbers.size)
        // A4 landscape is 841.890 x 595.276 pt: not the rounded 842 x 595.
        assertEquals(listOf(0.0, 0.0, 841.89, 595.276), probe.mediaBox(0))
        assertEquals(listOf(0.0, 0.0, 595.276, 841.89), probe.mediaBox(1))
        for (number in probe.offsets.keys) probe.body(number)
        assertTrue(probe.text.trimEnd().endsWith("%%EOF"))
        assertTrue("/Title <FEFF00430061007200740065002000E9>" in probe.text)
    }

    @Test
    fun `user space is millimeters from the top-left corner`() {
        val bytes =
            write {
                val page = beginPage(100.0, 50.0)
                page.rect(1.0, 2.0, 3.0, 4.5)
                page.fill()
            }
        val content = PdfProbe(bytes).contentOfPage(0)
        // 72 / 25.4 pt per mm, y flipped, origin moved to the top of the page (50 mm = 141.732283 pt).
        assertTrue(content.startsWith("2.834645669 0 0 -2.834645669 0 141.732283465 cm\n"), content.take(80))
        assertTrue("1 2 3 4.5 re\nf\n" in content)
    }

    @Test
    fun `numbers are short and never use an exponent`() {
        assertEquals("0", PdfPage.num(0.00001))
        assertEquals("12.3457", PdfPage.num(12.345678))
        assertEquals("-0.5", PdfPage.num(-0.5))
        assertEquals("100", PdfPage.num(100.0))
        assertEquals("0.00001", PdfPage.num(0.00001, 5))
        assertEquals("0", PdfPage.num(Double.NaN))
        assertEquals("123456789.1235", PdfPage.num(123_456_789.123456))
    }

    @Test
    fun `text is measured with the Helvetica metrics and encoded in Windows-1252`() {
        // H 722 + e 556 + l 222 + l 222 + o 556 = 2278 thousandths.
        val widthMm = PdfPage.measure("Hello", PdfFont.REGULAR, 10.0)
        assertEquals(2.278 * 10.0 / PdfPage.PT_PER_MM, widthMm, 1e-9)
        assertTrue(PdfPage.measure("Hello", PdfFont.BOLD, 10.0) > widthMm)
        assertContentEquals(byteArrayOf(0xE9.toByte(), 0xA9.toByte(), 0xB0.toByte()), PdfPage.encode("é©°"))
        // Primes become quotes, the narrow no-break space a no-break space, the unknown a question mark.
        assertContentEquals(
            byteArrayOf('\''.code.toByte(), '"'.code.toByte(), 0xA0.toByte(), '?'.code.toByte()),
            PdfPage.encode("′″ 中"),
        )
        assertEquals(0.0, PdfPage.measure("", PdfFont.REGULAR, 10.0))
    }

    @Test
    fun `text is placed by its baseline and aligned by measured width`() {
        val bytes =
            write {
                val page = beginPage(100.0, 50.0)
                page.text("Hi", 50.0, 20.0, PdfFont.BOLD, 12.0, PdfColor(255, 0, 0), TextAlign.CENTER)
            }
        val content = PdfProbe(bytes).contentOfPage(0)
        val width = PdfPage.measure("Hi", PdfFont.BOLD, 12.0)
        val expected = PdfPage.num(50.0 - width / 2)
        assertTrue("/F2 4.23333 Tf 1 0 0 -1 $expected 20 Tm <4869> Tj ET" in content, content)
        assertTrue("1 0 0 rg" in content)
    }

    @Test
    fun `a jpeg goes through untouched and raw pixels come back identical`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val pixels = ByteArray(2 * 2 * 3) { (it * 7).toByte() }
        val bytes =
            write {
                val page = beginPage(100.0, 50.0)
                page.image(PdfImage.jpeg(2, 2, jpeg), 0.0, 0.0, 10.0, 10.0)
                page.image(PdfImage.rgb(2, 2, pixels), 10.0, 0.0, 10.0, 10.0)
            }
        val probe = PdfProbe(bytes)
        val images = probe.offsets.keys.filter { "/Subtype /Image" in probe.body(it) }.sorted()
        assertEquals(2, images.size)
        assertTrue("/DCTDecode" in probe.body(images[0]))
        assertContentEquals(jpeg, probe.stream(images[0]))
        assertTrue("/FlateDecode" in probe.body(images[1]))
        assertContentEquals(pixels, probe.stream(images[1]))
        val content = probe.contentOfPage(0)
        // The image is flipped to be upright in the y-down space: bottom edge at y + h.
        assertTrue("10 0 0 -10 0 10 cm\n/Im1 Do" in content, content)
        assertTrue("/Im1" in probe.body(probe.pageNumbers[0]) && "/Im2" in probe.body(probe.pageNumbers[0]))
    }

    @Test
    fun `opacity levels become named graphics states used by the page`() {
        val bytes =
            write {
                val page = beginPage(100.0, 50.0)
                page.alpha(0.5)
                page.alpha(0.25)
                page.alpha(0.5)
            }
        val probe = PdfProbe(bytes)
        val content = probe.contentOfPage(0)
        assertTrue("/GS1 gs" in content && "/GS2 gs" in content)
        assertEquals(3, Regex("gs\n").findAll(content).count())
        val page = probe.body(probe.pageNumbers[0])
        assertTrue("/GS1 << /CA 0.5 /ca 0.5 >>" in page && "/GS2 << /CA 0.25 /ca 0.25 >>" in page)
    }

    @Test
    fun `a polyline skips points that are too close but keeps the last one`() {
        val xs = doubleArrayOf(0.0, 0.01, 0.02, 5.0, 5.01)
        val ys = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)
        val bytes = write { beginPage(100.0, 50.0).polyline(xs, ys, minStepMm = 0.1) }
        val content = PdfProbe(bytes).contentOfPage(0)
        assertTrue("0 0 m\n5 0 l\n5.01 0 l\nS\n" in content, content)
    }

    @Test
    fun `a page that is never ended is ended on close and objects are never skipped`() {
        val bytes = write { beginPage(10.0, 10.0) }
        val probe = PdfProbe(bytes)
        assertEquals(1, probe.pageNumbers.size)
        assertEquals((1 until probe.offsets.size + 1).toSet(), probe.offsets.keys)
    }

    @Test
    fun `sample for visual checks is written to the build directory`() {
        val dir = File("build/print-samples").apply { mkdirs() }
        val out = File(dir, "writer-sample.pdf")
        PdfWriter(out.outputStream(), PdfInfo(title = "Sample")).use { writer ->
            val page = writer.beginPage(210.0, 297.0)
            // A 100 mm square and diagonals, a ruler from 0 to 100 mm with ticks every 10 mm.
            page.strokeColor(PdfColor.BLACK)
            page.lineWidth(0.2)
            page.rect(55.0, 20.0, 100.0, 100.0)
            page.stroke()
            for (i in 0..10) {
                page.moveTo(55.0 + i * 10.0, 130.0)
                page.lineTo(55.0 + i * 10.0, if (i % 5 == 0) 138.0 else 134.0)
                page.stroke()
            }
            page.text("Échelle de contrôle — 100 mm", 105.0, 150.0, PdfFont.BOLD, 12.0, align = TextAlign.CENTER)
            page.fillColor(PdfColor(200, 30, 30))
            page.circle(105.0, 70.0, 10.0)
            page.fill()
        }
        assertTrue(abs(out.length().toDouble()) > 500)
    }
}
