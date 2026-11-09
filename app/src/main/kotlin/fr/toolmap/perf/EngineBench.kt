package fr.toolmap.perf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.Log
import fr.toolmap.BuildConfig
import fr.toolmap.core.compute.Accelerator
import fr.toolmap.core.compute.Compute
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.LayerConfig
import fr.toolmap.core.layers.RasterAdjust
import fr.toolmap.core.track.PathSimplifier
import fr.toolmap.data.tiles.BitmapElevationMasker
import fr.toolmap.data.tiles.BitmapTileBlender
import fr.toolmap.data.tiles.DemGrid
import fr.toolmap.engine.NativeEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Times the Kotlin code against the native engine on the device itself (ADR-119, `docs/specs/native-engine.md` § 4):
 * each step of a drawn tile, then whole tiles, on tiles drawn here (no network, no stored map needed). Lines go to
 * logcat under [TAG], `bench …`, for `tools/emulator/smoke.sh` and `tools/perf/E-engine.sh`.
 *
 * Runs only when asked, off the main thread: in a debug APK by a marker file that only `adb` can leave
 * (`adb shell run-as fr.toolmap touch files/engine-bench`, then a start of the app), in a benchmark APK by
 * `perf.engine=bench` ([PerfIntent]). Never in a release, and never by itself.
 */
object EngineBench {
    const val TAG = "ToolMapEngine"
    private const val MARKER = "engine-bench"
    private const val SIZE = 256
    private const val WARMUP = 4
    private const val ROUNDS = 9
    private const val PATH_POINTS = 20_000

    /** Runs the measure if `adb` left the marker file, and removes it: one start, one measure. */
    fun runIfAsked(context: Context) {
        val marker = File(context.filesDir, MARKER)
        if (!marker.isFile) return
        marker.delete()
        run()
    }

    /** Starts the measure on a thread of its own (the benchmark build's `perf.engine=bench`). */
    fun start() {
        Thread(::run, "engine-bench").apply { priority = Thread.NORM_PRIORITY }.start()
    }

    private fun say(line: String) {
        Log.i(TAG, "bench $line")
    }

    /** Median time of [work], in milliseconds, after a warm-up (the first runs are interpreted, then compiled). */
    private fun time(work: () -> Unit): Double {
        repeat(WARMUP) { work() }
        val times =
            DoubleArray(ROUNDS) {
                val start = SystemClock.elapsedRealtimeNanos()
                work()
                (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            }
        times.sort()
        return times[ROUNDS / 2]
    }

    private fun ms(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun step(
        name: String,
        kotlin: () -> Unit,
        native: () -> Unit,
    ) {
        val kotlinMs = time(kotlin)
        val nativeMs = time(native)
        say("step=$name kotlin_ms=${ms(kotlinMs)} native_ms=${ms(nativeMs)} gain=${ms(kotlinMs / nativeMs)}")
    }

    private fun run() {
        val started = SystemClock.elapsedRealtimeNanos()
        // Asked for at launch, the measure may come before the engine is in charge: wait for it (install is locked).
        if (BuildConfig.NATIVE_ENGINE) NativeEngine.install()
        val accelerator = Compute.accelerator
        if (accelerator == null) {
            say("skipped engine=${NativeEngine.state.name.lowercase()}")
            return
        }
        say(
            "start device=${Build.MODEL.replace(
                ' ',
                '_',
            )} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()} " +
                "cores=${Runtime.getRuntime().availableProcessors()} tile=$SIZE",
        )
        val plan = mapTile(seed = 1, photo = false)
        val photo = mapTile(seed = 2, photo = true)
        val slopes = overlayTile()
        steps(accelerator, pixels(photo), pixels(plan), pixels(slopes))
        pngs(accelerator, plan, photo)
        wholeTiles(plan, photo, slopes)
        say("done ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000}")
    }

    /** Each computation alone, on one tile: the Kotlin reference, then the same call through the engine. */
    private fun steps(
        accelerator: Accelerator,
        below: IntArray,
        above: IntArray,
        slopes: IntArray,
    ) {
        val scratch = IntArray(below.size)

        fun fresh() = System.arraycopy(below, 0, scratch, 0, below.size)
        val blends =
            listOf(
                Blending("blend_multiply_opaque", above, BlendMode.MULTIPLY, 1f),
                Blending("blend_normal_opaque_60", above, BlendMode.NORMAL, 0.6f),
                Blending("blend_softlight_opaque", above, BlendMode.SOFT_LIGHT, 1f),
                Blending("blend_normal_overlay", slopes, BlendMode.NORMAL, 1f),
                Blending("blend_overlay_overlay_80", slopes, BlendMode.OVERLAY, 0.8f),
                Blending("blend_colour_opaque", above, BlendMode.COLOR, 1f),
            )
        for (b in blends) {
            step(
                b.name,
                {
                    fresh()
                    Blend.compositeReference(scratch, b.source, b.mode, b.opacity)
                },
                {
                    fresh()
                    accelerator.composite(scratch, b.source, b.mode.ordinal, b.opacity)
                },
            )
        }
        step(
            "tint",
            {
                fresh()
                Blend.tintReference(scratch, TINT, 0.5f)
            },
            {
                fresh()
                accelerator.tint(scratch, TINT, 0.5f)
            },
        )
        RasterAdjust.colorMatrix(LayerConfig("l", "s", contrast = 0.3f, saturation = -0.5f))?.let { matrix ->
            step(
                "colour_matrix",
                {
                    fresh()
                    Blend.adjustReference(scratch, matrix)
                },
                {
                    fresh()
                    accelerator.adjust(scratch, matrix)
                },
            )
        }
        val dem = dem()
        val window = DemWindow.of(DemWindow.MAX_DEM_ZOOM + 2, 3, 1)
        val band = ElevationMask(BAND_LOW, null)
        step(
            "elevation_mask",
            { MaskRaster.alphas(MaskRaster.elevations(dem, SIZE, window, SIZE), SIZE, band) },
            {
                accelerator.maskAlphas(
                    dem,
                    SIZE,
                    window.depth,
                    window.col,
                    window.row,
                    SIZE,
                    BAND_LOW.toFloat(),
                    Float.NaN,
                )
            },
        )
        val xs = DoubleArray(PATH_POINTS)
        val ys = DoubleArray(PATH_POINTS)
        val walk = Random(11)
        var heading = 0.2
        for (i in 1 until PATH_POINTS) {
            if (walk.nextInt(40) == 0) heading += walk.nextGaussian()
            xs[i] = xs[i - 1] + cos(heading) * 8.0 + walk.nextGaussian() * 0.6
            ys[i] = ys[i - 1] + sin(heading) * 8.0 + walk.nextGaussian() * 0.6
        }
        step(
            "simplify_${PATH_POINTS}_points",
            { PathSimplifier.keptIndicesReference(xs, ys, 2.0) },
            { accelerator.keptIndices(xs, ys, 2.0) },
        )
    }

    /** The PNG of a tile: Android's own writer (what a drawn tile cost before) against the engine's. */
    private fun pngs(
        accelerator: Accelerator,
        plan: Bitmap,
        photo: Bitmap,
    ) {
        for ((name, tile) in listOf("plan" to plan, "photo" to photo)) {
            val argb = pixels(tile)
            val androidMs = time { androidPng(tile) }
            val nativeMs = time { accelerator.encodePng(argb, SIZE, SIZE) }
            say(
                "png=$name android_ms=${ms(androidMs)} native_ms=${ms(nativeMs)} gain=${ms(androidMs / nativeMs)} " +
                    "android_bytes=${androidPng(
                        tile,
                    ).size} native_bytes=${accelerator.encodePng(argb, SIZE, SIZE)?.size}",
            )
        }
    }

    /**
     * Whole tiles, image bytes in, PNG out, as the map asks for them: with the engine, then with the Kotlin code alone
     * (the engine is put back at the end; tiles the map asks for meanwhile are drawn by the Kotlin code).
     */
    private fun wholeTiles(
        plan: Bitmap,
        photo: Bitmap,
        slopes: Bitmap,
    ) {
        val layers =
            listOf(
                LayerConfig("a", "photo") to androidPng(photo),
                LayerConfig("b", "slopes", opacity = 0.7f) to androidPng(slopes),
                LayerConfig("c", "plan", blend = BlendMode.MULTIPLY) to androidPng(plan),
            )
        val blender = BitmapTileBlender()
        val masker = BitmapElevationMasker()
        val grid = DemGrid(SIZE, dem())
        val window = DemWindow.of(DemWindow.MAX_DEM_ZOOM + 2, 3, 1)
        val band = ElevationMask(BAND_LOW, null)
        val photoBytes = layers[0].second
        val nativeBlend = time { blender.blend(layers) }
        val nativeMask = time { masker.mask(photoBytes, grid, window, band) }
        NativeEngine.uninstall()
        try {
            val kotlinBlend = time { blender.blend(layers) }
            val kotlinMask = time { masker.mask(photoBytes, grid, window, band) }
            say(
                "tile=blended_3_layers kotlin_ms=${ms(
                    kotlinBlend,
                )} native_ms=${ms(nativeBlend)} gain=${ms(kotlinBlend / nativeBlend)}",
            )
            say(
                "tile=elevation_band kotlin_ms=${ms(
                    kotlinMask,
                )} native_ms=${ms(nativeMask)} gain=${ms(kotlinMask / nativeMask)}",
            )
        } finally {
            NativeEngine.install()
        }
    }

    private fun androidPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream(64 * 1024).also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(SIZE * SIZE).also {
            bitmap.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE)
        }

    /** A tile that looks like a map: flat areas and lines (a plan), with a grain over it for a [photo] or a scan. */
    private fun mapTile(
        seed: Int,
        photo: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(232, 238, 216))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(seed.toLong())
        repeat(18) {
            paint.color = Color.rgb(170 + random.nextInt(80), 190 + random.nextInt(60), 150 + random.nextInt(90))
            canvas.drawCircle(
                random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(),
                20f + random.nextInt(70),
                paint,
            )
        }
        repeat(70) {
            paint.color = Color.rgb(random.nextInt(200), random.nextInt(200), random.nextInt(200))
            paint.strokeWidth = 1f + random.nextInt(4)
            canvas.drawLine(
                random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(),
                paint,
            )
        }
        if (photo) {
            val argb = pixels(bitmap)
            for (i in argb.indices) {
                val grain = random.nextInt(GRAIN) - GRAIN / 2
                val p = argb[i]
                val r = (((p shr 16) and 0xFF) + grain).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + grain).coerceIn(0, 255)
                val b = ((p and 0xFF) + grain).coerceIn(0, 255)
                argb[i] = OPAQUE or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(argb, 0, SIZE, 0, 0, SIZE, SIZE)
        }
        return bitmap
    }

    /** A slope overlay: transparent, with partly transparent coloured patches. */
    private fun overlayTile(): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val random = Random(3)
        repeat(25) {
            paint.color = Color.argb(90 + random.nextInt(120), 255, random.nextInt(200), random.nextInt(60))
            canvas.drawCircle(
                random.nextInt(SIZE).toFloat(),
                random.nextInt(SIZE).toFloat(),
                10f + random.nextInt(45),
                paint,
            )
        }
        return bitmap
    }

    /** Elevations with slopes and hollows, in metres, as a decoded elevation tile holds them. */
    private fun dem(): FloatArray =
        FloatArray(SIZE * SIZE) { i -> (1200.0 + 600.0 * sin((i % SIZE) / 37.0) * sin((i / SIZE) / 23.0)).toFloat() }

    private class Blending(
        val name: String,
        val source: IntArray,
        val mode: BlendMode,
        val opacity: Float,
    )

    private const val TINT = 0x3366CC
    private const val BAND_LOW = 1300
    private const val GRAIN = 24
    private const val OPAQUE = -0x1000000
}
