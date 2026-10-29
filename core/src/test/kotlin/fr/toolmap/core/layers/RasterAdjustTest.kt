package fr.toolmap.core.layers

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RasterAdjustTest {
    private fun layer(
        contrast: Float = 0f,
        brightness: Float = 0f,
        saturation: Float = 0f,
        hue: Float = 0f,
    ) = LayerConfig("l", "s", contrast = contrast, brightness = brightness, saturation = saturation, hue = hue)

    /** Applies a 4 x 5 matrix to a colour in 0..1 (the offset column is in 0..255). */
    private fun apply(
        m: FloatArray,
        rgb: FloatArray,
    ): FloatArray =
        FloatArray(3) { row ->
            (m[row * 5] * rgb[0] + m[row * 5 + 1] * rgb[1] + m[row * 5 + 2] * rgb[2] + m[row * 5 + 4] / 255f)
        }

    /** The raster shader of the map, written straight from its description. */
    private fun shader(
        rgb: FloatArray,
        contrast: Float,
        brightness: Float,
        saturation: Float,
        hue: Float = 0f,
    ): FloatArray {
        val sat = if (saturation >= 0f) 1f - 1f / (1.001f - saturation) else -saturation
        val con = if (contrast > 0f) 1f / (1f - contrast) else 1f + contrast
        val range = RasterAdjust.brightnessRange(brightness)
        // Hue first: MapLibre's spin weights, dot(rgb, w.xyz), dot(rgb, w.zxy), dot(rgb, w.yzx).
        val a = Math.toRadians(hue.toDouble())
        val w =
            doubleArrayOf(
                (2 * cos(a) + 1) / 3,
                (-sqrt(3.0) * sin(a) - cos(a) + 1) / 3,
                (sqrt(3.0) * sin(a) - cos(a) + 1) / 3,
            ).map { it.toFloat() }
        val spun =
            floatArrayOf(
                rgb[0] * w[0] + rgb[1] * w[1] + rgb[2] * w[2],
                rgb[0] * w[2] + rgb[1] * w[0] + rgb[2] * w[1],
                rgb[0] * w[1] + rgb[1] * w[2] + rgb[2] * w[0],
            )
        val average = (rgb[0] + rgb[1] + rgb[2]) / 3f
        return FloatArray(3) { i ->
            val s = spun[i] + (average - spun[i]) * sat
            val c = (s - 0.5f) * con + 0.5f
            range.min + (range.max - range.min) * c
        }
    }

    private val samples =
        listOf(
            floatArrayOf(0.2f, 0.5f, 0.8f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(0.9f, 0.85f, 0.1f),
        )

    @Test
    fun `a layer without adjustments needs no filter`() {
        assertNull(RasterAdjust.colorMatrix(layer()))
    }

    @Test
    fun `the matrix does what the shader does for any setting`() {
        val colors =
            listOf(
                floatArrayOf(0.2f, 0.5f, 0.8f),
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.5f, 0.5f, 0.5f),
                floatArrayOf(0.9f, 0.85f, 0.1f),
            )
        for (contrast in listOf(-0.6f, 0f, 0.3f)) {
            for (brightness in listOf(-0.4f, 0f, 0.5f)) {
                for (saturation in listOf(-1f, -0.3f, 0f, 0.4f)) {
                    if (contrast == 0f && brightness == 0f && saturation == 0f) continue
                    val m = assertNotNull(RasterAdjust.colorMatrix(layer(contrast, brightness, saturation)))
                    for (c in colors) {
                        val expected = shader(c, contrast, brightness, saturation)
                        val actual = apply(m, c)
                        for (i in 0..2) {
                            assertEquals(expected[i], actual[i], 1e-4f, "c=$contrast b=$brightness s=$saturation")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `full desaturation gives grey and the alpha row is left alone`() {
        val m = assertNotNull(RasterAdjust.colorMatrix(layer(saturation = -1f)))
        val grey = apply(m, floatArrayOf(0.9f, 0.3f, 0.0f))
        assertEquals(0.4f, grey[0], 1e-5f)
        assertEquals(grey[0], grey[1], 1e-6f)
        assertEquals(grey[1], grey[2], 1e-6f)
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), m.drop(15).take(5))
    }

    @Test
    fun `contrast and brightness extremes stay finite`() {
        val m = assertNotNull(RasterAdjust.colorMatrix(layer(contrast = 1f, brightness = 1f, saturation = 1f)))
        assertEquals(true, m.all { it.isFinite() })
    }

    @Test
    fun `an inverted layer turns black into white and white into black`() {
        val inverted = LayerConfig("l", "s", inverted = true)
        assertEquals(true, inverted.hasAdjustments)
        val m = assertNotNull(RasterAdjust.colorMatrix(inverted))
        val black = apply(m, floatArrayOf(0f, 0f, 0f))
        val white = apply(m, floatArrayOf(1f, 1f, 1f))
        val colour = apply(m, floatArrayOf(0.2f, 0.5f, 0.9f))
        // The shader's neutral saturation is 0.001, not 0 exactly, hence the small tolerance.
        for (i in 0..2) {
            assertEquals(1f, black[i], 1e-3f)
            assertEquals(0f, white[i], 1e-3f)
        }
        assertEquals(0.8f, colour[0], 1e-3f)
        assertEquals(0.5f, colour[1], 1e-3f)
        assertEquals(0.1f, colour[2], 1e-3f)
    }

    @Test
    fun `the inverted range is the normal one read backwards`() {
        val normal = RasterAdjust.brightnessRange(0.3f)
        val flipped = RasterAdjust.brightnessRange(0.3f, inverted = true)
        assertEquals(normal.min, flipped.max)
        assertEquals(normal.max, flipped.min)
        assertEquals(BrightnessRange(1f, 0f), RasterAdjust.brightnessRange(0f, inverted = true))
    }

    @Test
    fun `the style gets the swapped brightness range for an inverted layer`() {
        val stack = LayerStack().add("osm").let { it.setInverted("l1", true) }
        val props = StackToStyle.desired(stack) { true }.single().props
        assertEquals(1f, props.brightnessMin)
        assertEquals(0f, props.brightnessMax)
    }

    @Test
    fun `the night mode darkens every map, inverted or not`() {
        val stack = LayerStack().add("osm")
        val night = StackToStyle.desired(stack, darkening = 0.6f) { true }.single().props
        assertEquals(0f, night.brightnessMin)
        assertEquals(0.4f, night.brightnessMax, 1e-6f)
        val inverted = StackToStyle.desired(stack.setInverted("l1", true), darkening = 0.6f) { true }.single().props
        assertEquals(0.4f, inverted.brightnessMin, 1e-6f)
        assertEquals(0f, inverted.brightnessMax)
        assertEquals(StackToStyle.desired(stack) { true }, StackToStyle.desired(stack, darkening = 0f) { true })
    }

    @Test
    fun `the matrix does what the shader does with a hue, alone or with the other settings`() {
        for (hue in listOf(-150f, -45f, 30f, 120f, 180f)) {
            for ((contrast, brightness, saturation) in listOf(
                Triple(0f, 0f, 0f),
                Triple(0.3f, -0.4f, -0.3f),
                Triple(-0.6f, 0.5f, 0.4f),
                Triple(0f, 0f, -1f),
            )) {
                val m = assertNotNull(RasterAdjust.colorMatrix(layer(contrast, brightness, saturation, hue)))
                for (c in samples) {
                    val expected = shader(c, contrast, brightness, saturation, hue)
                    val actual = apply(m, c)
                    for (i in 0..2) assertEquals(expected[i], actual[i], 1e-4f, "h=$hue c=$contrast s=$saturation")
                }
            }
        }
    }

    @Test
    fun `a third of a turn sends red to green, green to blue and blue to red, and keeps grey`() {
        val m = assertNotNull(RasterAdjust.colorMatrix(layer(hue = 120f)))
        val red = apply(m, floatArrayOf(1f, 0f, 0f))
        val green = apply(m, floatArrayOf(0f, 1f, 0f))
        val grey = apply(m, floatArrayOf(0.4f, 0.4f, 0.4f))
        // The shader's neutral saturation is 0.001, not 0 exactly, hence the small tolerance.
        for ((i, v) in listOf(0f, 1f, 0f).withIndex()) assertEquals(v, red[i], 1e-3f)
        for ((i, v) in listOf(0f, 0f, 1f).withIndex()) assertEquals(v, green[i], 1e-3f)
        for (v in grey) assertEquals(0.4f, v, 1e-5f)
        // A full turn either way is the same.
        val back = assertNotNull(RasterAdjust.colorMatrix(layer(hue = -180f)))
        val forth = assertNotNull(RasterAdjust.colorMatrix(layer(hue = 180f)))
        for (i in back.indices) assertEquals(back[i], forth[i], 1e-5f)
    }

    @Test
    fun `a hue alone is an adjustment, and the style gets it clamped to half a turn`() {
        assertEquals(true, layer(hue = 15f).hasAdjustments)
        val stack = LayerStack().add("osm").setHue("l1", 400f)
        assertEquals(RasterAdjust.MAX_HUE, stack["l1"]!!.hue)
        assertEquals(180f, StackToStyle.desired(stack) { true }.single().props.hueRotate)
        val turned = LayerStack().add("osm").setHue("l1", -75f)
        assertEquals(-75f, StackToStyle.desired(turned) { true }.single().props.hueRotate)
        assertEquals(0f, StackToStyle.desired(LayerStack().add("osm")) { true }.single().props.hueRotate)
    }
}
