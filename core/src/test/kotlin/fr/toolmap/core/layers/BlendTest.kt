package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlendTest {
    private fun near(
        expected: Float,
        actual: Float,
        mode: Any = "",
    ) = assertEquals(expected, actual, 1e-4f, "$mode")

    @Test
    fun separableModesGiveTheReferenceValues() {
        // Hand-computed from W3C Compositing and Blending Level 1, § 10.1.
        val cases =
            listOf(
                Triple(BlendMode.NORMAL, 0.2f to 0.7f, 0.7f),
                Triple(BlendMode.MULTIPLY, 0.5f to 0.5f, 0.25f),
                Triple(BlendMode.SCREEN, 0.5f to 0.5f, 0.75f),
                Triple(BlendMode.OVERLAY, 0.25f to 0.5f, 0.25f),
                Triple(BlendMode.OVERLAY, 0.75f to 0.5f, 0.75f),
                Triple(BlendMode.DARKEN, 0.2f to 0.7f, 0.2f),
                Triple(BlendMode.LIGHTEN, 0.2f to 0.7f, 0.7f),
                Triple(BlendMode.COLOR_DODGE, 0.5f to 0.5f, 1f),
                Triple(BlendMode.COLOR_DODGE, 0.2f to 0.5f, 0.4f),
                Triple(BlendMode.COLOR_DODGE, 0f to 1f, 0f),
                Triple(BlendMode.COLOR_BURN, 0.5f to 0.5f, 0f),
                Triple(BlendMode.COLOR_BURN, 0.8f to 0.5f, 0.6f),
                Triple(BlendMode.COLOR_BURN, 1f to 0f, 1f),
                Triple(BlendMode.HARD_LIGHT, 0.5f to 0.25f, 0.25f),
                Triple(BlendMode.HARD_LIGHT, 0.5f to 0.75f, 0.75f),
                Triple(BlendMode.SOFT_LIGHT, 0.25f to 0.75f, 0.375f),
                Triple(BlendMode.SOFT_LIGHT, 0.5f to 0.25f, 0.375f),
                Triple(BlendMode.SOFT_LIGHT, 0.64f to 1f, 0.8f),
                Triple(BlendMode.DIFFERENCE, 0.2f to 0.7f, 0.5f),
                Triple(BlendMode.EXCLUSION, 0.5f to 0.5f, 0.5f),
                Triple(BlendMode.EXCLUSION, 0.2f to 1f, 0.8f),
            )
        for ((mode, colors, expected) in cases) near(expected, Blend.channel(mode, colors.first, colors.second), mode)
    }

    @Test
    fun nonSeparableModesMixHueSaturationAndLuminosity() {
        val grey = floatArrayOf(0.5f, 0.5f, 0.5f)
        val red = floatArrayOf(1f, 0f, 0f)
        val out = FloatArray(3)

        // Luminosity: the backdrop's colour at the source's luminosity (0.3 for pure red).
        Blend.color(BlendMode.LUMINOSITY, grey, red, out)
        out.forEach { near(0.3f, it, "luminosity") }
        // Color: red brought to the grey's luminosity 0.5, then clipped back into 0..1.
        Blend.color(BlendMode.COLOR, grey, red, out)
        near(1f, out[0], "color r")
        near(0.5f - 0.3f * 0.5f / 0.7f, out[1], "color g")
        near(out[1], out[2], "color b")
        assertEquals(0.5f, Blend.lum(out), 1e-4f)
        // Hue over a grey: no saturation to give it, the grey stays.
        Blend.color(BlendMode.HUE, grey, red, out)
        out.forEach { near(0.5f, it, "hue") }
        // Saturation of a grey over red: red loses its colour and keeps its luminosity.
        Blend.color(BlendMode.SATURATION, red, grey, out)
        out.forEach { near(0.3f, it, "saturation") }
        assertTrue(BlendMode.EXCLUSION.isSeparable)
        assertFalse(BlendMode.HUE.isSeparable)
    }

    @Test
    fun compositeBlendsOpaquePixelsAndKeepsTransparentOnes() {
        val below = intArrayOf(0xFF808080.toInt(), 0xFF808080.toInt(), 0x00000000, 0xFF102030.toInt())
        val above = intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0x80FF0000.toInt(), 0x00FFFFFF)
        val half = below.copyOf()
        Blend.composite(below, above, BlendMode.MULTIPLY)
        assertEquals(0xFF800000.toInt(), below[0])
        // Over nothing the source shows as it is, with its own alpha; a transparent source leaves the backdrop.
        assertEquals(0x80FF0000.toInt(), below[2])
        assertEquals(0xFF102030.toInt(), below[3])

        // Half opacity: half the product, half the backdrop.
        Blend.composite(half, above, BlendMode.MULTIPLY, opacity = 0.5f)
        assertEquals(0xFF804040.toInt(), half[0])
    }

    @Test
    fun normalModeIsPlainSourceOver() {
        val below = intArrayOf(0xFF0000FF.toInt())
        Blend.composite(below, intArrayOf(0x80FF0000.toInt()), BlendMode.NORMAL)
        // 0.502 red over blue.
        assertEquals(0xFF80007F.toInt(), below[0])
    }

    @Test
    fun tintGivesTheColourAndKeepsLuminosityAndAlpha() {
        val pixels = intArrayOf(0xFF808080.toInt(), 0x40808080)
        Blend.tint(pixels, 0xFF0000, 1f)
        assertEquals(0xFF, (pixels[0] shr 16) and 0xFF)
        assertEquals(74, (pixels[0] shr 8) and 0xFF)
        assertEquals(74, pixels[0] and 0xFF)
        assertEquals(0x40, pixels[1] ushr 24)

        val untouched = intArrayOf(0xFF123456.toInt())
        Blend.tint(untouched, 0xFF0000, 0f)
        assertEquals(0xFF123456.toInt(), untouched[0])
        // Half strength: halfway between the grey and its tinted colour.
        val half = intArrayOf(0xFF808080.toInt())
        Blend.tint(half, 0xFF0000, 0.5f)
        assertEquals(0xC0, (half[0] shr 16) and 0xFF)
    }

    @Test
    fun adjustAppliesTheColourMatrixOfTheLayer() {
        val pixels = intArrayOf(0xFF336699.toInt(), 0x80000000.toInt())
        val identity = FloatArray(20).also { for (i in 0 until 4) it[i * 6] = 1f }
        val copy = pixels.copyOf()
        Blend.adjust(copy, identity)
        assertContentEquals(pixels, copy)

        // Inverted colours: the matrix of RasterAdjust flips each channel.
        val inverted = RasterAdjust.colorMatrix(LayerConfig("l", "s", inverted = true))!!
        Blend.adjust(pixels, inverted)
        assertEquals(0xFFCC9966.toInt(), pixels[0])
        assertEquals(0x80FFFFFF.toInt(), pixels[1])
    }
}
