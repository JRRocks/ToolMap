package fr.toolmap.core.text

import java.io.ByteArrayOutputStream

/**
 * A glyph drawn by the device's font at [GlyphRange.FONT_SIZE] pixels: its coverage [alpha] (0 = empty, 255 = ink),
 * [width] × [height] pixels of ink, placed [left] pixels right of the pen and [top] pixels above the baseline, and the
 * [advance] of the pen. A blank glyph (a space) has no pixel and only an advance.
 */
class RasterGlyph(
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val advance: Int,
    val alpha: ByteArray,
)

/** Draws one character of a font stack, or answers null when the font has nothing for it. */
fun interface GlyphRasterizer {
    fun draw(
        codePoint: Int,
        bold: Boolean,
    ): RasterGlyph?
}

/**
 * The glyph file MapLibre asks for (`{fontstack}/{start}-{end}.pbf`, ADR-062): the 256 characters of a range, each as
 * a signed distance field with a [SignedDistance.BUFFER] margin, in the protocol buffer format of the
 * `glyphs` schema (`glyphs > stacks > glyphs`). The map's base style has no hosted font: this file is made on the
 * device, from the system font, so that names show on the map offline.
 */
object GlyphRange {
    /** The size MapLibre's glyph metrics are expressed at. */
    const val FONT_SIZE = 24

    /** Characters per range file. */
    const val RANGE = 256

    /** The highest character a range may hold (end of the Unicode planes MapLibre asks for). */
    private const val MAX_CODE_POINT = 0xFFFF

    /**
     * Distance from the top of MapLibre's line box to the baseline at [FONT_SIZE] (hosted fonts give `top` relative to
     * that point; 24 px text has its baseline about 24 px down, MapLibre adds its own −17 px shaping offset).
     */
    const val ASCENT = 24

    /** Reads `0-255` (the `{range}` of the URL, without `.pbf`): the first character, or null when it is not a range. */
    fun startOf(range: String): Int? {
        val parts = range.split('-')
        if (parts.size != 2) return null
        val start = parts[0].toIntOrNull() ?: return null
        val end = parts[1].toIntOrNull() ?: return null
        if (start < 0 || start % RANGE != 0 || end != start + RANGE - 1 || end > MAX_CODE_POINT) return null
        return start
    }

    /** Whether the font stack asks for the bold face (`… Bold`, `… Medium`, `… Semibold`). */
    fun isBold(fontStack: String): Boolean {
        val name = fontStack.lowercase()
        return "bold" in name || "medium" in name
    }

    /** The glyph file of the 256 characters from [start], drawn by [rasterizer]. */
    fun encode(
        fontStack: String,
        start: Int,
        rasterizer: GlyphRasterizer,
    ): ByteArray {
        val bold = isBold(fontStack)
        val stack = ByteArrayOutputStream()
        stack.string(FIELD_STACK_NAME, fontStack)
        stack.string(FIELD_STACK_RANGE, "$start-${start + RANGE - 1}")
        for (code in start until start + RANGE) {
            val glyph = rasterizer.draw(code, bold) ?: continue
            stack.message(FIELD_STACK_GLYPHS, glyphMessage(code, glyph))
        }
        val file = ByteArrayOutputStream()
        file.message(FIELD_STACKS, stack.toByteArray())
        return file.toByteArray()
    }

    private fun glyphMessage(
        code: Int,
        glyph: RasterGlyph,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.uint(FIELD_ID, code.toLong())
        if (glyph.width > 0 && glyph.height > 0) {
            val w = glyph.width + 2 * SignedDistance.BUFFER
            val h = glyph.height + 2 * SignedDistance.BUFFER
            require(glyph.alpha.size == w * h) { "glyph $code: ${glyph.alpha.size} bytes, expected $w × $h" }
            out.bytes(FIELD_BITMAP, SignedDistance.of(glyph.alpha, w, h))
        }
        out.uint(FIELD_WIDTH, glyph.width.toLong())
        out.uint(FIELD_HEIGHT, glyph.height.toLong())
        out.sint(FIELD_LEFT, glyph.left)
        out.sint(FIELD_TOP, glyph.top - ASCENT)
        out.uint(FIELD_ADVANCE, glyph.advance.toLong())
        return out.toByteArray()
    }

    // Protocol buffer writing: varints, zigzag for sint32, length-delimited strings and messages.

    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.tag(
        field: Int,
        wireType: Int,
    ) = varint(((field shl 3) or wireType).toLong())

    private fun ByteArrayOutputStream.uint(
        field: Int,
        value: Long,
    ) {
        tag(field, WIRE_VARINT)
        varint(value)
    }

    private fun ByteArrayOutputStream.sint(
        field: Int,
        value: Int,
    ) {
        tag(field, WIRE_VARINT)
        varint(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)
    }

    private fun ByteArrayOutputStream.bytes(
        field: Int,
        value: ByteArray,
    ) {
        tag(field, WIRE_LENGTH)
        varint(value.size.toLong())
        write(value)
    }

    private fun ByteArrayOutputStream.string(
        field: Int,
        value: String,
    ) = bytes(field, value.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.message(
        field: Int,
        value: ByteArray,
    ) = bytes(field, value)

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH = 2
    private const val FIELD_STACKS = 1
    private const val FIELD_STACK_NAME = 1
    private const val FIELD_STACK_RANGE = 2
    private const val FIELD_STACK_GLYPHS = 3
    private const val FIELD_ID = 1
    private const val FIELD_BITMAP = 2
    private const val FIELD_WIDTH = 3
    private const val FIELD_HEIGHT = 4
    private const val FIELD_LEFT = 5
    private const val FIELD_TOP = 6
    private const val FIELD_ADVANCE = 7
}
