package fr.toolmap.core.pdf

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The three standard fonts every PDF reader has; their glyph widths are known, so text can be measured here. */
enum class PdfFont(
    internal val resource: String,
    internal val baseFont: String,
    internal val widths: IntArray,
) {
    REGULAR("F1", "Helvetica", HelveticaMetrics.regular),
    BOLD("F2", "Helvetica-Bold", HelveticaMetrics.bold),
    ITALIC("F3", "Helvetica-Oblique", HelveticaMetrics.regular),
}

data class PdfColor(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    companion object {
        val BLACK = PdfColor(0, 0, 0)
        val WHITE = PdfColor(255, 255, 255)

        /** The colour of an `0xAARRGGBB` integer (the alpha is ignored: see [PdfPage.alpha]). */
        fun fromArgb(argb: Int) = PdfColor((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

enum class TextAlign { LEFT, CENTER, RIGHT }

enum class LineCap { BUTT, ROUND, SQUARE }

enum class LineJoin { MITER, ROUND, BEVEL }

/** Document properties shown by PDF readers. */
data class PdfInfo(
    val title: String = "",
    val subject: String = "",
    val creator: String = "ToolMap",
    val creationMillis: Long = System.currentTimeMillis(),
)

/** An image to place on a page: a JPEG as it comes from an encoder, or raw RGB pixels compressed on writing. */
class PdfImage private constructor(
    val widthPx: Int,
    val heightPx: Int,
    val data: ByteArray,
    val jpeg: Boolean,
    /** Opacity of each pixel (0 transparent, 255 opaque), row after row; `null` for an opaque image. */
    val alpha: ByteArray? = null,
) {
    companion object {
        /** A baseline JPEG with three colour components. */
        fun jpeg(
            widthPx: Int,
            heightPx: Int,
            bytes: ByteArray,
        ) = PdfImage(widthPx, heightPx, bytes, jpeg = true)

        /** Pixels as red, green, blue bytes, row after row, [widthPx] × [heightPx] × 3 of them. */
        fun rgb(
            widthPx: Int,
            heightPx: Int,
            pixels: ByteArray,
        ): PdfImage {
            require(pixels.size == widthPx * heightPx * 3) { "pixel data does not match $widthPx x $heightPx" }
            return PdfImage(widthPx, heightPx, pixels, jpeg = false)
        }

        /** Like [rgb], with an opacity byte per pixel: an icon drawn over the map. */
        fun rgba(
            widthPx: Int,
            heightPx: Int,
            pixels: ByteArray,
            alpha: ByteArray,
        ): PdfImage {
            require(pixels.size == widthPx * heightPx * 3) { "pixel data does not match $widthPx x $heightPx" }
            require(alpha.size == widthPx * heightPx) { "alpha data does not match $widthPx x $heightPx" }
            return PdfImage(widthPx, heightPx, pixels, jpeg = false, alpha = alpha)
        }
    }
}

/**
 * Writes a PDF 1.4 file page by page, to a stream, without a graphics stack: images go out as soon as they are added
 * (memory stays bounded whatever the paper), the vector content of a page is kept until it ends. All the page API is in
 * millimeters from the top-left corner, y downwards, so a layout in millimeters is a layout on the paper to the
 * thousandth: what the print is about (docs/specs/print-pdf.md).
 */
class PdfWriter(
    output: OutputStream,
    private val info: PdfInfo = PdfInfo(),
) : Closeable {
    private val out = CountingStream(output)
    private val offsets = HashMap<Int, Long>()
    private val pages = ArrayList<Int>()
    private var nextObject = FIRST_FREE_OBJECT
    private var current: PdfPage? = null
    private var closed = false

    /** Images with transparency already written, by identity: see [PdfPage.image]. */
    internal val icons = java.util.IdentityHashMap<PdfImage, Int>()

    init {
        out.write("%PDF-1.4\n%".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    val pageCount: Int get() = pages.size

    /** Starts a page of [widthMm] × [heightMm] (the previous one, if still open, ends first). */
    fun beginPage(
        widthMm: Double,
        heightMm: Double,
    ): PdfPage {
        check(!closed) { "document already closed" }
        endPage()
        require(widthMm > 0 && heightMm > 0) { "empty page" }
        return PdfPage(this, widthMm, heightMm).also { current = it }
    }

    fun endPage() {
        current?.finish()
        current = null
    }

    override fun close() {
        if (closed) return
        endPage()
        closed = true
        writeObject(PAGES_OBJECT) {
            append("<< /Type /Pages /Count ${pages.size} /Kids [")
            pages.forEach { append("$it 0 R ") }
            append("] >>")
        }
        writeObject(CATALOG_OBJECT) { append("<< /Type /Catalog /Pages $PAGES_OBJECT 0 R >>") }
        writeObject(INFO_OBJECT) {
            append("<< /Title ${hexString(info.title)} /Subject ${hexString(info.subject)}")
            append(" /Creator ${hexString(info.creator)} /Producer ${hexString(info.creator)}")
            append(" /CreationDate (${pdfDate(info.creationMillis)}) >>")
        }
        PdfFont.entries.forEachIndexed { index, font ->
            writeObject(FONT_OBJECT + index) {
                append("<< /Type /Font /Subtype /Type1 /BaseFont /${font.baseFont} /Encoding /WinAnsiEncoding >>")
            }
        }
        val xrefAt = out.count
        val total = nextObject
        out.write("xref\n0 $total\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (number in 1 until total) {
            val offset = offsets[number] ?: error("object $number was never written")
            out.write("${offset.toString().padStart(OFFSET_DIGITS, '0')} 00000 n \n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(
            "trailer\n<< /Size $total /Root $CATALOG_OBJECT 0 R /Info $INFO_OBJECT 0 R >>\nstartxref\n$xrefAt\n%%EOF\n"
                .toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()
    }

    internal fun allocate(): Int = nextObject++

    internal fun addPage(number: Int) {
        pages += number
    }

    /** Writes the dictionary [body] as object [number]. */
    internal fun writeObject(
        number: Int,
        body: StringBuilder.() -> Unit,
    ) {
        offsets[number] = out.count
        out.write("$number 0 obj\n${StringBuilder().apply(body)}\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    /** Writes a stream object: [dictionary] entries (without the length) and the [data] as they are. */
    internal fun writeStream(
        number: Int,
        dictionary: String,
        data: ByteArray,
    ) {
        offsets[number] = out.count
        out.write("$number 0 obj\n<< $dictionary /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    private class CountingStream(
        private val target: OutputStream,
    ) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
    }

    companion object {
        internal const val CATALOG_OBJECT = 1
        internal const val PAGES_OBJECT = 2
        internal const val INFO_OBJECT = 3
        internal const val FONT_OBJECT = 4
        private const val FIRST_FREE_OBJECT = 7
        private const val OFFSET_DIGITS = 10

        private fun pdfDate(millis: Long): String {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.timeInMillis = millis
            return String.format(
                java.util.Locale.ROOT,
                "D:%04d%02d%02d%02d%02d%02dZ",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND),
            )
        }

        /** A text string as UTF-16 hex with its byte order mark: any character, in every reader. */
        internal fun hexString(text: String): String {
            val bytes = text.toByteArray(Charsets.UTF_16BE)
            return buildString {
                append("<FEFF")
                bytes.forEach { append(String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF)) }
                append('>')
            }
        }
    }
}

/**
 * One page being written. Coordinates are millimeters from the top-left corner (y down); font sizes are in points, as
 * everywhere in typography. Methods draw straight into the page's content stream.
 */
class PdfPage internal constructor(
    private val writer: PdfWriter,
    val widthMm: Double,
    val heightMm: Double,
) {
    private val ops = StringBuilder(INITIAL_CONTENT)
    private val images = LinkedHashMap<String, Int>()
    private val alphas = LinkedHashMap<String, String>()
    private var finished = false

    init {
        // User space becomes millimeters, y downwards.
        ops.append(exact(PT_PER_MM)).append(" 0 0 -").append(exact(PT_PER_MM)).append(" 0 ")
            .append(exact(heightMm * PT_PER_MM)).append(" cm\n")
    }

    fun save() {
        ops.append("q\n")
    }

    fun restore() {
        ops.append("Q\n")
    }

    /** Moves the origin. */
    fun translate(
        xMm: Double,
        yMm: Double,
    ) {
        ops.append("1 0 0 1 ").append(num(xMm)).append(' ').append(num(yMm)).append(" cm\n")
    }

    /** Turns what follows by [degrees], clockwise on the paper, around the origin. */
    fun rotate(degrees: Double) {
        val r = Math.toRadians(degrees)
        val c = cos(r)
        val s = sin(r)
        ops.append(exact(c)).append(' ').append(exact(s)).append(' ').append(exact(-s)).append(' ')
            .append(exact(c)).append(" 0 0 cm\n")
    }

    fun clipRect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        rect(x, y, w, h)
        ops.append("W n\n")
    }

    fun fillColor(color: PdfColor) {
        ops.append(component(color.red)).append(' ').append(component(color.green)).append(' ')
            .append(component(color.blue)).append(" rg\n")
    }

    fun strokeColor(color: PdfColor) {
        ops.append(component(color.red)).append(' ').append(component(color.green)).append(' ')
            .append(component(color.blue)).append(" RG\n")
    }

    fun lineWidth(mm: Double) {
        ops.append(num(mm)).append(" w\n")
    }

    fun lineStyle(
        cap: LineCap = LineCap.BUTT,
        join: LineJoin = LineJoin.MITER,
    ) {
        ops.append(cap.ordinal).append(" J ").append(join.ordinal).append(" j\n")
    }

    /** A dash pattern in millimeters; empty for a solid line. */
    fun dash(vararg pattern: Double) {
        ops.append('[')
        pattern.forEachIndexed { i, v ->
            if (i > 0) ops.append(' ')
            ops.append(num(v))
        }
        ops.append("] 0 d\n")
    }

    /** Opacity of what is stroked and filled from now on, 0 (clear) to 1 (opaque). */
    fun alpha(opacity: Double) {
        val value = opacity.coerceIn(0.0, 1.0)
        val key = num(value)
        val name = alphas.getOrPut(key) { "GS${alphas.size + 1}" }
        ops.append('/').append(name).append(" gs\n")
    }

    fun moveTo(
        x: Double,
        y: Double,
    ) {
        ops.append(num(x)).append(' ').append(num(y)).append(" m\n")
    }

    fun lineTo(
        x: Double,
        y: Double,
    ) {
        ops.append(num(x)).append(' ').append(num(y)).append(" l\n")
    }

    fun curveTo(
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        x3: Double,
        y3: Double,
    ) {
        ops.append(num(x1)).append(' ').append(num(y1)).append(' ').append(num(x2)).append(' ').append(num(y2))
            .append(' ').append(num(x3)).append(' ').append(num(y3)).append(" c\n")
    }

    fun rect(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        ops.append(num(x)).append(' ').append(num(y)).append(' ').append(num(w)).append(' ').append(num(h))
            .append(" re\n")
    }

    /** A circle as four Bézier arcs. */
    fun circle(
        cx: Double,
        cy: Double,
        r: Double,
    ) {
        val k = r * KAPPA
        moveTo(cx + r, cy)
        curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        closePath()
    }

    fun closePath() {
        ops.append("h\n")
    }

    fun stroke() {
        ops.append("S\n")
    }

    fun fill(evenOdd: Boolean = false) {
        ops.append(if (evenOdd) "f*\n" else "f\n")
    }

    fun fillAndStroke() {
        ops.append("B\n")
    }

    /** Strokes a polyline through [xs] / [ys] (parallel arrays), skipping points closer than [minStepMm] to the last. */
    fun polyline(
        xs: DoubleArray,
        ys: DoubleArray,
        count: Int = minOf(xs.size, ys.size),
        minStepMm: Double = 0.0,
    ) {
        if (count < 2) return
        moveTo(xs[0], ys[0])
        var lastX = xs[0]
        var lastY = ys[0]
        var drawn = 1
        for (i in 1 until count) {
            val last = i == count - 1
            if (!last && abs(xs[i] - lastX) < minStepMm && abs(ys[i] - lastY) < minStepMm) continue
            lineTo(xs[i], ys[i])
            lastX = xs[i]
            lastY = ys[i]
            drawn++
        }
        if (drawn > 1) stroke() else ops.append("n\n")
    }

    /** The width of [text] on the paper, in millimeters. */
    fun textWidth(
        text: String,
        font: PdfFont,
        sizePt: Double,
    ): Double = measure(text, font, sizePt)

    /**
     * Writes [text] with its baseline at [y]; [x] is the left end, the middle or the right end according to [align].
     * Characters outside Windows-1252 become `?`.
     */
    fun text(
        text: String,
        x: Double,
        y: Double,
        font: PdfFont,
        sizePt: Double,
        color: PdfColor = PdfColor.BLACK,
        align: TextAlign = TextAlign.LEFT,
    ) {
        if (text.isEmpty() || sizePt <= 0) return
        val width = measure(text, font, sizePt)
        val left =
            when (align) {
                TextAlign.LEFT -> x
                TextAlign.CENTER -> x - width / 2
                TextAlign.RIGHT -> x - width
            }
        save()
        fillColor(color)
        ops.append("BT /").append(font.resource).append(' ').append(num(sizePt / PT_PER_MM, TEXT_DECIMALS))
            .append(" Tf 1 0 0 -1 ").append(num(left)).append(' ').append(num(y)).append(" Tm <")
        encode(text).forEach { ops.append(HEX[(it.toInt() shr 4) and 0xF]).append(HEX[it.toInt() and 0xF]) }
        ops.append("> Tj ET\n")
        restore()
    }

    /** Draws [image] in the rectangle at ([x], [y]) of [w] × [h] millimeters. */
    fun image(
        image: PdfImage,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) {
        val number = writer.icons[image] ?: writeImage(image)
        val name = images.entries.firstOrNull { it.value == number }?.key ?: "Im${images.size + 1}"
        images[name] = number
        save()
        // The unit square of an image is y-up: flip it into our y-down space.
        ops.append(num(w)).append(" 0 0 ").append(num(-h)).append(' ').append(num(x)).append(' ')
            .append(num(y + h)).append(" cm\n/").append(name).append(" Do\n")
        restore()
    }

    /**
     * Writes [image] (and its mask) and returns its object number. An image with transparency is an icon: small and
     * drawn many times, it is written once for the whole document.
     */
    private fun writeImage(image: PdfImage): Int {
        val mask =
            image.alpha?.let { alpha ->
                writer.allocate().also { maskNumber ->
                    writer.writeStream(
                        maskNumber,
                        "/Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                            "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode",
                        deflate(alpha),
                    )
                }
            }
        val number = writer.allocate()
        val dictionary =
            "/Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                (if (image.jpeg) "/Filter /DCTDecode" else "/Filter /FlateDecode") +
                (mask?.let { " /SMask $it 0 R" } ?: "")
        writer.writeStream(number, dictionary, if (image.jpeg) image.data else deflate(image.data))
        if (image.alpha != null) writer.icons[image] = number
        return number
    }

    internal fun finish() {
        if (finished) return
        finished = true
        val contentNumber = writer.allocate()
        val pageNumber = writer.allocate()
        writer.writeStream(
            contentNumber,
            "/Filter /FlateDecode",
            deflate(ops.toString().toByteArray(Charsets.ISO_8859_1)),
        )
        writer.writeObject(pageNumber) {
            append("<< /Type /Page /Parent ${PdfWriter.PAGES_OBJECT} 0 R")
            append(" /MediaBox [0 0 ${num(widthMm * PT_PER_MM, MEDIABOX_DECIMALS)} ")
            append("${num(heightMm * PT_PER_MM, MEDIABOX_DECIMALS)}]")
            append(" /Contents $contentNumber 0 R /Resources << /Font <<")
            PdfFont.entries.forEachIndexed { i, f -> append(" /${f.resource} ${PdfWriter.FONT_OBJECT + i} 0 R") }
            append(" >>")
            if (images.isNotEmpty()) {
                append(" /XObject <<")
                images.forEach { (name, number) -> append(" /$name $number 0 R") }
                append(" >>")
            }
            if (alphas.isNotEmpty()) {
                append(" /ExtGState <<")
                alphas.forEach { (key, name) -> append(" /$name << /CA $key /ca $key >>") }
                append(" >>")
            }
            append(" >> >>")
        }
        writer.addPage(pageNumber)
    }

    companion object {
        /** Points per millimeter: 72 / 25.4. */
        const val PT_PER_MM = 72.0 / 25.4

        private const val INITIAL_CONTENT = 8_192
        private const val KAPPA = 0.5522847498
        private const val TEXT_DECIMALS = 5
        private const val MEDIABOX_DECIMALS = 3
        private val HEX = "0123456789ABCDEF".toCharArray()
        private val WIN_ANSI: Charset = Charset.forName("windows-1252")

        private val SUBSTITUTES =
            mapOf(
                ' ' to ' ',
                ' ' to ' ',
                ' ' to ' ',
                '′' to '\'',
                '″' to '"',
                '−' to '-',
                '‑' to '-',
            )

        /** The bytes of [text] in Windows-1252. */
        internal fun encode(text: String): ByteArray {
            val mapped = text.map { SUBSTITUTES[it] ?: it }.joinToString("")
            return mapped.toByteArray(WIN_ANSI)
        }

        internal fun measure(
            text: String,
            font: PdfFont,
            sizePt: Double,
        ): Double {
            var units = 0
            for (b in encode(text)) units += font.widths[b.toInt() and 0xFF]
            return units / 1000.0 * sizePt / PT_PER_MM
        }

        /** A decimal with at most [decimals] digits, no exponent and no trailing zeros. */
        internal fun num(
            value: Double,
            decimals: Int = 4,
        ): String {
            if (!value.isFinite()) return "0"
            var scale = 1L
            repeat(decimals) { scale *= 10 }
            val rounded = Math.round(value * scale)
            if (rounded == 0L) return "0"
            val negative = rounded < 0
            val magnitude = abs(rounded)
            val whole = magnitude / scale
            val fraction = (magnitude % scale).toString().padStart(decimals, '0').trimEnd('0')
            return buildString {
                if (negative) append('-')
                append(whole)
                if (fraction.isNotEmpty()) append('.').append(fraction)
            }
        }

        private fun exact(value: Double): String = num(value, EXACT_DECIMALS)

        private const val EXACT_DECIMALS = 9

        private fun component(value: Int): String = num(value.coerceIn(0, 255) / 255.0, 3)

        internal fun deflate(data: ByteArray): ByteArray {
            val buffer = ByteArrayOutputStream(maxOf(64, data.size / 3))
            DeflaterOutputStream(buffer, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(data) }
            return buffer.toByteArray()
        }
    }
}
