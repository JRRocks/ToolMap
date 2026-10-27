package fr.toolmap.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlyphRangeTest {
    /** A square of ink of [side] pixels, with the margin MapLibre expects around it. */
    private fun square(side: Int): RasterGlyph {
        val size = side + 2 * SignedDistance.BUFFER
        val alpha = ByteArray(size * size)
        for (y in 0 until side) {
            for (x in 0 until side) {
                alpha[(y + SignedDistance.BUFFER) * size + x + SignedDistance.BUFFER] = 0xFF.toByte()
            }
        }
        return RasterGlyph(side, side, left = 1, top = side, advance = side + 2, alpha = alpha)
    }

    @Test
    fun `the field is high inside, 191 near the edge and low far outside`() {
        val side = 10
        val size = side + 2 * SignedDistance.BUFFER
        val field = SignedDistance.of(square(side).alpha, size, size)

        fun at(
            x: Int,
            y: Int,
        ) = field[y * size + x].toInt() and 0xFF
        val centre = at(size / 2, size / 2)
        val inkEdge = at(SignedDistance.BUFFER, size / 2)
        val justOutside = at(SignedDistance.BUFFER - 1, size / 2)
        val corner = at(0, 0)
        assertTrue(centre > inkEdge, "centre $centre, edge $inkEdge")
        // The edge (191) lies between the first inked pixel and the empty one before it.
        assertTrue(inkEdge in 192..230, "edge pixel $inkEdge")
        assertTrue(justOutside in 150..190, "outside pixel $justOutside")
        assertTrue(corner < justOutside, "corner $corner")
    }

    @Test
    fun `an empty image is far from any edge everywhere`() {
        val field = SignedDistance.of(ByteArray(36), 6, 6)
        assertTrue(field.all { it.toInt() and 0xFF == 0 })
    }

    @Test
    fun `ranges are read only when MapLibre could ask for them`() {
        assertEquals(0, GlyphRange.startOf("0-255"))
        assertEquals(256, GlyphRange.startOf("256-511"))
        assertNull(GlyphRange.startOf("1-256"))
        assertNull(GlyphRange.startOf("0-511"))
        assertNull(GlyphRange.startOf("abc"))
        assertNull(GlyphRange.startOf("65536-65791"))
    }

    @Test
    fun `bold faces are recognised from the font stack`() {
        assertTrue(GlyphRange.isBold("ToolMap Sans Bold"))
        assertTrue(GlyphRange.isBold("Roboto Medium"))
        assertFalse(GlyphRange.isBold("ToolMap Sans Regular"))
    }

    @Test
    fun `the file holds one stack with every glyph the font draws`() {
        val file =
            GlyphRange.encode("ToolMap Sans Bold", 0) { code, bold ->
                assertTrue(bold)
                when (code) {
                    'A'.code -> square(4)
                    ' '.code -> RasterGlyph(0, 0, 0, 0, advance = 6, alpha = ByteArray(0))
                    else -> null
                }
            }
        val stacks = Proto(file).fields()
        assertEquals(listOf(1), stacks.map { it.first })
        val stack = Proto(stacks.single().second as ByteArray).fields()
        assertEquals("ToolMap Sans Bold", String(stack.first { it.first == 1 }.second as ByteArray))
        assertEquals("0-255", String(stack.first { it.first == 2 }.second as ByteArray))
        val glyphs = stack.filter { it.first == 3 }.map { Proto(it.second as ByteArray).fields().toMap() }
        assertEquals(listOf(' '.code.toLong(), 'A'.code.toLong()), glyphs.map { it[1] })
        val space = glyphs[0]
        assertNull(space[2], "a blank glyph has no bitmap")
        assertEquals(6L, space[7])
        val a = glyphs[1]
        assertEquals(10 * 10, (a[2] as ByteArray).size, "4 px of ink and a 3 px margin on each side")
        assertEquals(4L, a[3])
        assertEquals(4L, a[4])
        assertEquals(1L, unzig(a[5] as Long))
        assertEquals(4L - GlyphRange.ASCENT, unzig(a[6] as Long))
        assertEquals(6L, a[7])
    }

    private fun unzig(v: Long): Long = (v ushr 1) xor -(v and 1)

    /** Just enough protocol buffer reading to check the file: varints and length-delimited fields. */
    private class Proto(
        val bytes: ByteArray,
    ) {
        private var pos = 0

        private fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fields(): List<Pair<Int, Any>> {
            val out = ArrayList<Pair<Int, Any>>()
            while (pos < bytes.size) {
                val key = varint().toInt()
                val field = key ushr 3
                when (key and 7) {
                    0 -> {
                        out += field to varint()
                    }

                    2 -> {
                        val length = varint().toInt()
                        out += field to bytes.copyOfRange(pos, pos + length)
                        pos += length
                    }

                    else -> {
                        error("unexpected wire type")
                    }
                }
            }
            return out
        }
    }
}
