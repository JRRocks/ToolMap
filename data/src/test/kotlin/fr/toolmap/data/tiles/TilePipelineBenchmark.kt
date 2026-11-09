package fr.toolmap.data.tiles

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileRecipe
import fr.toolmap.core.track.PathSimplifier
import fr.toolmap.engine.NativeEngine
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.sin

/**
 * Times the Kotlin code against the native engine on this machine (docs/specs/native-engine.md § 4): each step of a
 * drawn tile, then whole tiles. Not a test: it runs only when asked,
 * `./gradlew :data:testDebugUnitTest --tests '*TilePipelineBenchmark*' -Ptoolmap.engine.bench=true`, and prints its
 * lines (also in `data/build/engine-bench.txt`). The JVM of a build machine compiles Kotlin better than a phone does:
 * the gains printed here are a floor for the per-pixel steps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TilePipelineBenchmark {
    private val lines = mutableListOf<String>()

    private fun say(line: String) {
        println(line)
        lines += line
    }

    /** Median time of [work], in milliseconds, after a warm-up. */
    private fun time(
        rounds: Int = 31,
        warmup: Int = 12,
        work: () -> Unit,
    ): Double {
        repeat(warmup) { work() }
        val times =
            DoubleArray(rounds) {
                val start = System.nanoTime()
                work()
                (System.nanoTime() - start) / 1e6
            }
        times.sort()
        return times[rounds / 2]
    }

    private fun row(
        name: String,
        kotlinMs: Double,
        nativeMs: Double,
    ) = say(
        String.format(
            Locale.ROOT,
            "%-46s kotlin %8.3f ms   native %8.3f ms   x%.1f",
            name,
            kotlinMs,
            nativeMs,
            kotlinMs / nativeMs,
        ),
    )

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** 256 px tiles cut from the map pictures of the repository (IGN SCAN 25 and IGN Plan), or drawn when absent. */
    private fun tiles(name: String): List<Bitmap> {
        val file = File(System.getProperty("toolmap.repo.dir").orEmpty(), "docs/previews/p26/$name")
        val picture = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        if (picture == null) return List(4) { drawn(it) }
        val out = mutableListOf<Bitmap>()
        var y = 60
        while (y + 256 <= picture.height) {
            var x = 0
            while (x + 256 <= picture.width) {
                out += Bitmap.createBitmap(picture, x, y, 256, 256)
                x += 256
            }
            y += 256
        }
        return out
    }

    private fun drawn(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(230, 236, 215))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(seed.toLong())
        repeat(60) {
            paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            paint.strokeWidth = 1f + random.nextInt(5)
            canvas.drawLine(
                random.nextInt(256).toFloat(),
                random.nextInt(256).toFloat(),
                random.nextInt(256).toFloat(),
                random.nextInt(256).toFloat(),
                paint,
            )
        }
        return bitmap
    }

    /** A slope overlay: transparent, with partly transparent coloured patches. */
    private fun overlay(): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(3)
        repeat(25) {
            paint.color = Color.argb(90 + random.nextInt(120), 255, random.nextInt(200), random.nextInt(60))
            canvas.drawCircle(
                random.nextInt(256).toFloat(),
                random.nextInt(256).toFloat(),
                10f + random.nextInt(45),
                paint,
            )
        }
        return bitmap
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    /** What a Kotlin PNG writer with the fastest zlib would cost: the part of the gain that is not the language. */
    private fun kotlinPng(
        argb: IntArray,
        size: Int,
    ): ByteArray {
        val raw = ByteArray(size * (size * 3 + 1))
        var o = 0
        for (y in 0 until size) {
            raw[o++] = 0
            for (x in 0 until size) {
                val p = argb[y * size + x]
                raw[o++] = (p shr 16).toByte()
                raw[o++] = (p shr 8).toByte()
                raw[o++] = p.toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(raw.size + 1024)
        val n = deflater.deflate(buffer)
        deflater.end()
        val out = ByteArrayOutputStream(n + 64)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(
            type: String,
            data: ByteArray,
            length: Int,
        ) {
            val crc = CRC32()
            crc.update(type.toByteArray(Charsets.ISO_8859_1))
            crc.update(data, 0, length)
            for (shift in intArrayOf(24, 16, 8, 0)) out.write(length ushr shift)
            out.write(type.toByteArray(Charsets.ISO_8859_1))
            out.write(data, 0, length)
            for (shift in intArrayOf(24, 16, 8, 0)) out.write((crc.value ushr shift).toInt())
        }
        val header = ByteArray(13)
        header[2] = (size ushr 8).toByte()
        header[3] = size.toByte()
        header[6] = (size ushr 8).toByte()
        header[7] = size.toByte()
        header[8] = 8
        header[9] = 2
        chunk("IHDR", header, 13)
        chunk("IDAT", buffer, n)
        chunk("IEND", ByteArray(0), 0)
        return out.toByteArray()
    }

    @Test
    fun measure() {
        assumeTrue("asked for with -Ptoolmap.engine.bench=true", System.getProperty("toolmap.engine.bench") == "true")
        assumeTrue("no native engine on this machine", NativeEngine.install() == NativeEngine.State.ACTIVE)
        val accelerator = checkNotNull(fr.toolmap.core.compute.Compute.accelerator)
        val scan = tiles("map-ign-scan25.jpg")
        val plan = tiles("map-ign-plan.jpg")
        say(
            "tiles: ${scan.size} of IGN SCAN 25, ${plan.size} of IGN Plan, 256 px; JVM ${System.getProperty(
                "java.vm.version",
            )}",
        )
        val below = pixels(scan[0])
        val above = pixels(plan[0])
        val slopes = pixels(overlay())

        say("--- one step, one 256 px tile ---")
        for ((name, source, mode, opacity) in listOf(
            Quad("blend multiply, opaque, full opacity", above, BlendMode.MULTIPLY, 1f),
            Quad("blend multiply, opaque, opacity 0.6", above, BlendMode.MULTIPLY, 0.6f),
            Quad("blend soft-light, opaque, full opacity", above, BlendMode.SOFT_LIGHT, 1f),
            Quad("blend normal, partly transparent overlay", slopes, BlendMode.NORMAL, 1f),
            Quad("blend overlay, partly transparent, 0.8", slopes, BlendMode.OVERLAY, 0.8f),
            Quad("blend colour (whole-colour mode)", above, BlendMode.COLOR, 1f),
        )) {
            val scratch = IntArray(below.size)
            val kotlin =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    Blend.compositeReference(scratch, source, mode, opacity)
                }
            val native =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    accelerator.composite(scratch, source, mode.ordinal, opacity)
                }
            row(name, kotlin, native)
        }
        run {
            val scratch = IntArray(below.size)
            val kotlin =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    Blend.tintReference(scratch, 0x3366CC, 0.5f)
                }
            val native =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    accelerator.tint(scratch, 0x3366CC, 0.5f)
                }
            row("tint", kotlin, native)
            val matrix = RasterAdjust.colorMatrix(LayerConfig("l", "s", contrast = 0.3f, saturation = -0.5f))!!
            val kotlinAdjust =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    Blend.adjustReference(scratch, matrix)
                }
            val nativeAdjust =
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    accelerator.adjust(scratch, matrix)
                }
            row("colour matrix (contrast, saturation)", kotlinAdjust, nativeAdjust)
        }
        val dem =
            FloatArray(256 * 256) { i -> (1200.0 + 600.0 * sin((i % 256) / 37.0) * sin((i / 256) / 23.0)).toFloat() }
        val window = DemWindow.of(15, 16803, 11601)
        val band = ElevationMask(1300, null)
        row(
            "elevation mask (heights + opacities)",
            time { MaskRaster.alphas(MaskRaster.elevations(dem, 256, window, 256), 256, band) },
            time { accelerator.maskAlphas(dem, 256, window.depth, window.col, window.row, 256, 1300f, Float.NaN) },
        )
        run {
            val alphas = MaskRaster.alphas(MaskRaster.elevations(dem, 256, window, 256), 256, band)
            val scratch = IntArray(below.size)
            row(
                "opacity of the mask applied",
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    MaskRaster.applyAlphasReference(scratch, alphas)
                },
                time {
                    System.arraycopy(below, 0, scratch, 0, below.size)
                    accelerator.applyAlphas(scratch, alphas)
                },
            )
        }

        say("--- PNG of one 256 px tile (median over the tiles) ---")
        val all = scan + plan
        var androidMs = 0.0
        var kotlinFastMs = 0.0
        var nativeMs = 0.0
        var androidBytes = 0L
        var kotlinFastBytes = 0L
        var nativeBytes = 0L
        for (tile in all) {
            val argb = pixels(tile)
            androidMs += time(rounds = 15, warmup = 4) { png(tile) }
            kotlinFastMs += time(rounds = 15, warmup = 6) { kotlinPng(argb, 256) }
            nativeMs += time(rounds = 15, warmup = 6) { accelerator.encodePng(argb, 256, 256) }
            androidBytes += png(tile).size
            kotlinFastBytes += kotlinPng(argb, 256).size
            nativeBytes += accelerator.encodePng(argb, 256, 256)!!.size
        }
        val n = all.size
        say(
            String.format(
                Locale.ROOT,
                "Bitmap.compress (Android, today)         %8.3f ms  %7d bytes",
                androidMs / n,
                androidBytes / n,
            ),
        )
        say(
            String.format(
                Locale.ROOT,
                "Kotlin writer, zlib level 1, no filter   %8.3f ms  %7d bytes",
                kotlinFastMs / n,
                kotlinFastBytes / n,
            ),
        )
        say(
            String.format(
                Locale.ROOT,
                "native engine (Up filter, fdeflate)      %8.3f ms  %7d bytes",
                nativeMs / n,
                nativeBytes / n,
            ),
        )

        say("--- whole tiles, bytes in to PNG out ---")
        val scanBytes = scan.map(::png)
        val planBytes = plan.map(::png)
        val slopeBytes = png(overlay())
        val layers =
            listOf(
                LayerConfig("a", "scan") to scanBytes[0],
                LayerConfig("b", "slopes", opacity = 0.7f) to slopeBytes,
                LayerConfig("c", "plan", blend = BlendMode.MULTIPLY) to planBytes[0],
            )
        val blender = BitmapTileBlender()
        val masker = BitmapElevationMasker()
        val grid = DemGrid(256, dem)
        val level = LevelDef(ZoomSet.range(0, 18), listOf("https://t/{\$z}/{\$x}/{\$y}.png"))
        val source = TileSource(id = "s", name = "s", levels = listOf(level))
        val enlarged =
            TileRecipe.Enlarge(
                TileRecipe.Direct(source, level, TileId(10, 5, 5)),
                depth = 1,
                col = 1,
                row = 0,
            )
        val composer = BitmapTileComposer()
        val nativeBlend = time(rounds = 21, warmup = 8) { blender.blend(layers) }
        val nativeMask = time(rounds = 21, warmup = 8) { masker.mask(scanBytes[1], grid, window, band) }
        val nativeCompose = time(rounds = 21, warmup = 8) { composer.compose(enlarged) { scanBytes[2] } }
        val xs = DoubleArray(50_000)
        val ys = DoubleArray(50_000)
        val walk = Random(11)
        var heading = 0.2
        for (i in 1 until xs.size) {
            if (walk.nextInt(40) == 0) heading += walk.nextGaussian()
            xs[i] = xs[i - 1] + Math.cos(heading) * 8.0 + walk.nextGaussian() * 0.6
            ys[i] = ys[i - 1] + Math.sin(heading) * 8.0 + walk.nextGaussian() * 0.6
        }
        val nativePath = time(rounds = 21, warmup = 8) { accelerator.keptIndices(xs, ys, 2.0) }
        NativeEngine.uninstall()
        row(
            "blended tile (3 layers, the top one multiplied)",
            time(rounds = 21, warmup = 8) {
                blender.blend(layers)
            },
            nativeBlend,
        )
        row(
            "tile cut out along a contour",
            time(rounds = 21, warmup = 8) {
                masker.mask(scanBytes[1], grid, window, band)
            },
            nativeMask,
        )
        row(
            "tile made of a coarser one (enlarged)",
            time(rounds = 21, warmup = 8) {
                composer.compose(enlarged) { scanBytes[2] }
            },
            nativeCompose,
        )
        say("--- paths ---")
        row(
            "Douglas-Peucker, 50 000 points, 2 m",
            time(rounds = 21, warmup = 8) {
                PathSimplifier.keptIndicesReference(xs, ys, 2.0)
            },
            nativePath,
        )

        File("build/engine-bench.txt").apply {
            parentFile?.mkdirs()
        }.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private data class Quad(
        val name: String,
        val source: IntArray,
        val mode: BlendMode,
        val opacity: Float,
    )

    // Unused here, kept for a look at a tile when a number surprises: `Rect` and `Canvas` draw the pictures above.
    @Suppress("unused")
    private val whole = Rect(0, 0, 256, 256)
}
