package fr.toolmap.engine

import fr.toolmap.core.compute.Compute
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.elevation.PngDecoder
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.core.track.PathSimplifier
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.util.Random
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The native engine against the Kotlin code it stands for (ADR-119): the same results, bit for bit, on every blend
 * mode, opacity and kind of pixel, on elevation bands and on paths. Runs on the library built for this machine
 * (`cargoBuildHost`); skipped where Rust is not installed, unless the engine is required (CI).
 */
class EngineParityTest {
    @Before
    fun load() {
        val state = NativeEngine.install()
        if (System.getProperty("toolmap.engine.required") == "true") {
            assertEquals(NativeEngine.State.ACTIVE, state, "the native engine is required here")
        }
        assumeTrue("no native engine on this machine (Rust not installed)", state == NativeEngine.State.ACTIVE)
    }

    private val random = Random(20261109)

    /** Pixels of every kind: any colour, alpha any value, with runs of opaque and of fully transparent ones. */
    private fun pixels(count: Int): IntArray =
        IntArray(count) {
            val argb = random.nextInt()
            when (random.nextInt(8)) {
                0, 1, 2 -> argb or OPAQUE
                3 -> argb and RGB
                else -> argb
            }
        }

    /** Every pair of channel values, opaque: what the native table of opaque pixels is read for. */
    private fun everyPair(): Pair<IntArray, IntArray> {
        val below = IntArray(65536) { i -> OPAQUE or ((i shr 8) * 0x010101) }
        val above = IntArray(65536) { i -> OPAQUE or ((i and 0xFF) * 0x010101) }
        return below to above
    }

    @Test
    fun `every blend mode gives the pixels of the Kotlin code`() {
        val below = pixels(40_000)
        val above = pixels(40_000)
        for (mode in BlendMode.entries) {
            for (opacity in floatArrayOf(1f, 0.5f, 0.35f, 0f, 1.5f, -1f)) {
                val expected = below.copyOf().also { Blend.compositeReference(it, above, mode, opacity) }
                val actual = below.copyOf()
                assertEquals(NativeBridge.OK, NativeBridge.composite(actual, above, mode.ordinal, opacity))
                assertContentEquals(expected, actual, "$mode at $opacity")
            }
        }
    }

    @Test
    fun `opaque pixels give the pixels of the Kotlin code for every pair of values`() {
        // More opacities than the engine keeps tables for: the oldest ones are made again.
        val (below, above) = everyPair()
        for (mode in BlendMode.entries) {
            for (opacity in floatArrayOf(1f, 0.6f, 0.35f, 0.004f)) {
                val expected = below.copyOf().also { Blend.compositeReference(it, above, mode, opacity) }
                val actual = below.copyOf()
                assertEquals(NativeBridge.OK, NativeBridge.composite(actual, above, mode.ordinal, opacity))
                assertContentEquals(expected, actual, "$mode at $opacity")
            }
        }
    }

    @Test
    fun `an image larger than one piece is blended whole`() {
        // The door reads and writes 65 536 pixels at a time: more than two pieces, the last one partial.
        val below = pixels(150_001)
        val above = pixels(150_001)
        val expected = below.copyOf().also { Blend.compositeReference(it, above, BlendMode.OVERLAY, 0.8f) }
        assertEquals(NativeBridge.OK, NativeBridge.composite(below, above, BlendMode.OVERLAY.ordinal, 0.8f))
        assertContentEquals(expected, below)
    }

    @Test
    fun `tint and colour matrix give the pixels of the Kotlin code`() {
        val source = pixels(30_000)
        for (tint in intArrayOf(0xFF0000, 0x3366CC, 0x000000, 0xFFFFFF, 0x808080)) {
            for (intensity in floatArrayOf(1f, 0.5f, 0.01f, 0f, 2f)) {
                val expected = source.copyOf().also { Blend.tintReference(it, tint, intensity) }
                val actual = source.copyOf()
                assertEquals(NativeBridge.OK, NativeBridge.tint(actual, tint, intensity))
                assertContentEquals(expected, actual, "tint $tint at $intensity")
            }
        }
        val layers =
            listOf(
                LayerConfig("l", "s", inverted = true),
                LayerConfig("l", "s", contrast = 0.6f, brightness = -0.3f, saturation = -1f),
                LayerConfig("l", "s", contrast = -0.8f, brightness = 0.9f, saturation = 1f, hue = 137f),
            )
        for (layer in layers) {
            val matrix = assertNotNull(RasterAdjust.colorMatrix(layer))
            val expected = source.copyOf().also { Blend.adjustReference(it, matrix) }
            val actual = source.copyOf()
            assertEquals(NativeBridge.OK, NativeBridge.adjust(actual, matrix))
            assertContentEquals(expected, actual, "$layer")
        }
    }

    /** A 256 px elevation tile with slopes, a ridge and a flat: metres as a Terrarium tile gives them. */
    private fun dem(size: Int = 256): FloatArray =
        FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            (1200.0 + 600.0 * sin(x / 37.0) * sin(y / 23.0) + (x - y) * 1.7).toFloat().let {
                if (x in
                    90..110
                ) {
                    1500f
                } else {
                    it
                }
            }
        }

    @Test
    fun `elevation bands give the opacities of the Kotlin code`() {
        val dem = dem()
        val masks =
            listOf(
                ElevationMask(1500, null),
                ElevationMask(null, 1200),
                ElevationMask(1000, 1600),
                ElevationMask(-50, 9000),
                ElevationMask(1500, 1500),
            )
        // A tile as fine as the elevations, finer ones (a part of the elevation tile), and other tile sizes.
        val cases =
            listOf(
                Triple(DemWindow.of(13, 4200, 2900), 256, "same zoom"),
                Triple(DemWindow.of(15, 16803, 11601), 256, "two zooms finer"),
                Triple(DemWindow.of(18, 134431, 92815), 256, "five zooms finer"),
                Triple(DemWindow.of(14, 8401, 5800), 512, "512 px tile"),
                Triple(DemWindow.of(9, 262, 181), 64, "64 px tile"),
            )
        for ((window, size, name) in cases) {
            for (mask in masks) {
                val expected = MaskRaster.alphas(MaskRaster.elevations(dem, 256, window, size), size, mask)
                val actual =
                    NativeBridge.maskAlphas(
                        dem,
                        256,
                        window.depth,
                        window.col,
                        window.row,
                        size,
                        mask.min?.toFloat() ?: Float.NaN,
                        mask.max?.toFloat() ?: Float.NaN,
                    )
                assertContentEquals(expected, actual, "$name, $mask")
            }
        }
    }

    @Test
    fun `the mask scales the opacity as the Kotlin code does`() {
        val source = pixels(70_000)
        val alphas = ByteArray(source.size).also { random.nextBytes(it) }
        val expected = source.copyOf().also { MaskRaster.applyAlphasReference(it, alphas) }
        assertEquals(NativeBridge.OK, NativeBridge.applyAlphas(source, alphas))
        assertContentEquals(expected, source)
    }

    @Test
    fun `the fast PNG holds the pixels it was given`() {
        // Opaque: written without alpha. With transparency: a fully transparent pixel loses its hidden colour.
        val opaque = IntArray(256 * 256) { random.nextInt() or OPAQUE }
        val decoded =
            PngDecoder.decode(
                assertNotNull(NativeBridge.encodePng(opaque, 256, 256, NativeBridge.PNG_FASTEST)),
            )
        assertEquals(3, decoded.channels)
        for (i in opaque.indices) {
            val rgb =
                (decoded.sample(i % 256, i / 256, 0) shl 16) or (decoded.sample(i % 256, i / 256, 1) shl 8) or
                    decoded.sample(i % 256, i / 256, 2)
            assertEquals(opaque[i] and RGB, rgb, "pixel $i")
        }
        val mixed = pixels(96 * 64)
        val image = PngDecoder.decode(assertNotNull(NativeBridge.encodePng(mixed, 96, 64, NativeBridge.PNG_FASTEST)))
        assertEquals(4, image.channels)
        assertEquals(96 to 64, image.width to image.height)
        for (i in mixed.indices) {
            val x = i % 96
            val y = i / 96
            val argb =
                (image.sample(x, y, 3) shl 24) or (image.sample(x, y, 0) shl 16) or
                    (image.sample(x, y, 1) shl 8) or image.sample(x, y, 2)
            val expected = if (mixed[i] ushr 24 == 0) 0 else mixed[i]
            assertEquals(expected, argb, "pixel $i")
        }
    }

    @Test
    fun `paths keep the points the Kotlin code keeps`() {
        for (count in intArrayOf(0, 1, 2, 3, 300, 5_000, 60_000)) {
            // A walk with straight stretches, bends, repeated points and a loop back to the start.
            var x = 0.0
            var y = 0.0
            var heading = 0.3
            val xs = DoubleArray(count)
            val ys = DoubleArray(count)
            for (i in 0 until count) {
                if (random.nextInt(40) == 0) heading += random.nextGaussian()
                if (random.nextInt(25) != 0) {
                    x += Math.cos(heading) * 8.0 + random.nextGaussian() * 0.6
                    y += Math.sin(heading) * 8.0 + random.nextGaussian() * 0.6
                }
                xs[i] = x
                ys[i] = y
            }
            if (count > 2) {
                xs[count - 1] = xs[0]
                ys[count - 1] = ys[0]
            }
            for (tolerance in doubleArrayOf(0.5, 5.0, 50.0, 0.0, Double.NaN)) {
                val expected = PathSimplifier.keptIndicesReference(xs, ys, tolerance)
                assertContentEquals(
                    expected,
                    NativeBridge.keptIndices(xs, ys, tolerance),
                    "$count points, $tolerance m",
                )
            }
        }
    }

    @Test
    fun `arguments the engine does not work on are declined and nothing is written`() {
        val below = pixels(10)
        val copy = below.copyOf()
        assertEquals(NativeBridge.REFUSED, NativeBridge.composite(below, pixels(9), BlendMode.MULTIPLY.ordinal, 1f))
        assertEquals(NativeBridge.REFUSED, NativeBridge.composite(below, pixels(10), 99, 1f))
        assertEquals(NativeBridge.REFUSED, NativeBridge.adjust(below, FloatArray(19)))
        assertEquals(NativeBridge.REFUSED, NativeBridge.applyAlphas(below, ByteArray(11)))
        assertContentEquals(copy, below)
        assertEquals(null, NativeBridge.encodePng(below, 5, 3, NativeBridge.PNG_FASTEST))
        assertEquals(null, NativeBridge.encodePng(below, 0, 10, NativeBridge.PNG_FASTEST))
        assertEquals(null, NativeBridge.maskAlphas(FloatArray(15), 4, 0, 0, 0, 8, Float.NaN, 1000f))
        assertEquals(null, NativeBridge.maskAlphas(FloatArray(16), 4, 31, 0, 0, 8, Float.NaN, 1000f))
        assertEquals(null, NativeBridge.keptIndices(DoubleArray(3), DoubleArray(4), 1.0))
    }

    @Test
    fun `once installed the engine does the work of the core functions`() {
        assertTrue(NativeEngine.isActive)
        assertNotNull(Compute.accelerator)
        val below = pixels(20_000)
        val above = pixels(20_000)
        val expected = below.copyOf().also { Blend.compositeReference(it, above, BlendMode.SCREEN, 0.7f) }
        Blend.composite(below, above, BlendMode.SCREEN, 0.7f)
        assertContentEquals(expected, below)
        assertNotNull(Compute.fastPng(below, 200, 100))
        NativeEngine.uninstall()
        assertEquals(null, Compute.accelerator)
        assertEquals(null, Compute.fastPng(below, 200, 100))
    }

    private companion object {
        const val OPAQUE = -0x1000000
        const val RGB = 0x00FFFFFF
    }
}
