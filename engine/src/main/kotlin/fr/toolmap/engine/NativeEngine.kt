package fr.toolmap.engine

import fr.toolmap.core.compute.Accelerator
import fr.toolmap.core.compute.Compute
import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.layers.Blend
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.track.PathSimplifier
import java.io.File

/**
 * The native compute engine (Rust, ADR-119, `docs/specs/native-engine.md`): loads the library, checks that it is the
 * one this code was written for and that it gives what the Kotlin code gives, then puts it in charge of the heavy
 * computations of `:core` ([Compute.accelerator]). Whatever goes wrong, the Kotlin code keeps doing the work and the
 * problem is reported: the engine only ever makes things faster.
 */
object NativeEngine {
    /** What became of the native library in this process. */
    enum class State {
        /** [install] has not run yet. */
        IDLE,

        /** Loaded, checked, in charge. */
        ACTIVE,

        /** Not asked for (this build keeps the Kotlin code in charge). */
        OFF,

        /** This build has no native library (built without Rust), or this is a JVM that was not given one. */
        ABSENT,

        /** The library did not load, is of another version, or disagreed with the Kotlin code: reported. */
        FAILED,
    }

    @Volatile
    var state: State = State.IDLE
        private set

    val isActive: Boolean get() = state == State.ACTIVE

    /** Set by [keepOff]: the Kotlin code stays in charge whatever [install] is asked afterwards. */
    private var keptOff = false

    /**
     * Loads the library and puts it in charge, unless [enabled] is false. Safe to call again; cheap (a few
     * milliseconds), but meant to run off the main thread.
     */
    @Synchronized
    fun install(enabled: Boolean = true): State {
        if (state == State.ACTIVE && enabled && !keptOff) return state
        Compute.accelerator = null
        state =
            when {
                !enabled || keptOff -> State.OFF
                !expected() -> State.ABSENT
                else -> loadAndCheck()
            }
        if (state == State.ACTIVE) Compute.accelerator = NativeAccelerator
        return state
    }

    /** Gives the work back to the Kotlin code until the next [install] (tests, a measure of one against the other). */
    @Synchronized
    fun uninstall() {
        Compute.accelerator = null
        if (state == State.ACTIVE) state = State.OFF
    }

    /**
     * Keeps the Kotlin code in charge for the rest of the process, also if [install] is still on its way on another
     * thread: a measure of the app without the native engine (`perf.engine=kotlin`).
     */
    @Synchronized
    fun keepOff() {
        keptOff = true
        uninstall()
        state = State.OFF
    }

    /** One line for the logs: the state, and the architecture of the library when it is in charge. */
    fun describe(): String =
        "native=${if (isActive) 1 else 0} state=${state.name.lowercase()} abi=${NativeBridge.ABI_VERSION} " +
            "arch=${System.getProperty("os.arch").orEmpty()}"

    /**
     * True where a library is to be found: in an app whose build made one, or on a JVM that was given the folder of
     * the build machine's own ([DIR_PROPERTY], the tests) and finds it there.
     */
    private fun expected(): Boolean = if (isAndroid()) BuildConfig.NATIVE_LIBRARY else hostLibrary() != null

    /** Android's runtime calls itself « Dalvik » (ART included) and « Android Runtime »; a JVM does neither. */
    private fun isAndroid(): Boolean =
        System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true) ||
            System.getProperty("java.runtime.name").orEmpty().contains("Android", ignoreCase = true)

    /** The library built for this machine, when the tests were given its folder and it was built. */
    private fun hostLibrary(): File? =
        System.getProperty(DIR_PROPERTY)?.let { File(it, System.mapLibraryName(LIBRARY)) }?.takeIf { it.isFile }

    private fun loadAndCheck(): State =
        try {
            val host = if (isAndroid()) null else hostLibrary()
            if (host != null) System.load(host.absolutePath) else System.loadLibrary(LIBRARY)
            val version = NativeBridge.abiVersion()
            when {
                version != NativeBridge.ABI_VERSION -> {
                    Problems.unexpected(
                        "engine.version",
                        "the native engine is of another version than this code",
                        "found" to version,
                        "expected" to NativeBridge.ABI_VERSION,
                    )
                    State.FAILED
                }

                !agreesWithKotlin() -> {
                    State.FAILED
                }

                else -> {
                    State.ACTIVE
                }
            }
        } catch (e: LinkageError) {
            // The library is missing from the APK, built for another architecture, or lacks a function: a bug of the
            // build, not of the phone. The Kotlin code does the work.
            Problems.report("engine.load", e)
            State.FAILED
        }

    /**
     * A few computations done by both engines, on values that take every path of the native code (opaque and partly
     * transparent pixels, the table of opaque pixels, a whole-colour mode, an elevation band, a path): the library is
     * put in charge only if it gives the same results, bit for bit.
     */
    private fun agreesWithKotlin(): Boolean {
        val wrong = firstDisagreement() ?: return true
        Problems.unexpected("engine.selftest", "the native engine disagrees with the Kotlin code", "check" to wrong)
        return false
    }

    private fun firstDisagreement(): String? {
        val below = IntArray(CHECK_PIXELS) { i -> sample(i, 0x9E3779B1.toInt()) }
        val above = IntArray(CHECK_PIXELS) { i -> sample(i, 0x85EBCA6B.toInt()) }
        for (mode in CHECK_MODES) {
            for (opacity in CHECK_OPACITIES) {
                val expected = below.copyOf().also { Blend.compositeReference(it, above, mode, opacity) }
                val actual = below.copyOf()
                val code = NativeBridge.composite(actual, above, mode.ordinal, opacity)
                if (code != NativeBridge.OK || !actual.contentEquals(expected)) return "composite ${mode.key} $opacity"
            }
        }
        val tinted = below.copyOf().also { Blend.tintReference(it, CHECK_TINT, CHECK_INTENSITY) }
        val nativeTinted = below.copyOf()
        if (NativeBridge.tint(nativeTinted, CHECK_TINT, CHECK_INTENSITY) != NativeBridge.OK ||
            !nativeTinted.contentEquals(tinted)
        ) {
            return "tint"
        }
        val dem = FloatArray(CHECK_DEM * CHECK_DEM) { i -> 900f + (i % CHECK_DEM) * 37.5f + (i / CHECK_DEM) * 11.25f }
        val window = DemWindow.of(DemWindow.MAX_DEM_ZOOM + 1, 1, 0)
        val mask = ElevationMask(CHECK_LOW, CHECK_HIGH)
        val alphas = MaskRaster.alphas(MaskRaster.elevations(dem, CHECK_DEM, window, CHECK_TILE), CHECK_TILE, mask)
        val nativeAlphas =
            NativeBridge.maskAlphas(
                dem,
                CHECK_DEM,
                window.depth,
                window.col,
                window.row,
                CHECK_TILE,
                CHECK_LOW.toFloat(),
                CHECK_HIGH.toFloat(),
            )
        if (nativeAlphas == null || !nativeAlphas.contentEquals(alphas)) return "mask"
        val png =
            NativeBridge.encodePng(
                below.copyOf(CHECK_TILE * CHECK_TILE),
                CHECK_TILE,
                CHECK_TILE,
                NativeBridge.PNG_FASTEST,
            )
        if (png == null || png.size < PNG_SIGNATURE.size ||
            !png.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            return "png"
        }
        val xs = DoubleArray(CHECK_PATH) { i -> i * 10.0 }
        val ys = DoubleArray(CHECK_PATH) { i -> ((i * 7919) % 13) * 1.5 }
        val kept = PathSimplifier.keptIndicesReference(xs, ys, CHECK_TOLERANCE)
        val nativeKept = NativeBridge.keptIndices(xs, ys, CHECK_TOLERANCE)
        if (nativeKept == null || !nativeKept.contentEquals(kept)) return "simplify"
        return null
    }

    /** A pixel of the check: every alpha and colour comes up, a quarter of them opaque (the table's own path). */
    private fun sample(
        i: Int,
        salt: Int,
    ): Int {
        val mixed = (i + 1) * salt
        val argb = mixed xor (mixed ushr 15)
        return if (i % 4 == 0) argb or OPAQUE else argb
    }

    /** The native library behind the door of `:core`: a call it declines is reported, and the Kotlin code works. */
    private object NativeAccelerator : Accelerator {
        override fun composite(
            below: IntArray,
            above: IntArray,
            mode: Int,
            opacity: Float,
        ): Boolean = done("composite", NativeBridge.composite(below, above, mode, opacity))

        override fun tint(
            pixels: IntArray,
            tint: Int,
            intensity: Float,
        ): Boolean = done("tint", NativeBridge.tint(pixels, tint, intensity))

        override fun adjust(
            pixels: IntArray,
            matrix: FloatArray,
        ): Boolean = done("adjust", NativeBridge.adjust(pixels, matrix))

        override fun maskAlphas(
            dem: FloatArray,
            demSize: Int,
            depth: Int,
            col: Int,
            row: Int,
            size: Int,
            low: Float,
            high: Float,
        ): ByteArray? =
            NativeBridge.maskAlphas(dem, demSize, depth, col, row, size, low, high) ?: declined("maskAlphas", null)

        override fun applyAlphas(
            pixels: IntArray,
            alphas: ByteArray,
        ): Boolean = done("applyAlphas", NativeBridge.applyAlphas(pixels, alphas))

        override fun encodePng(
            pixels: IntArray,
            width: Int,
            height: Int,
        ): ByteArray? {
            // Larger than any tile: not the engine's to write, the caller uses the platform's own encoder.
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PNG_PIXELS) return null
            return NativeBridge.encodePng(pixels, width, height, NativeBridge.PNG_FASTEST)
                ?: declined("encodePng", null)
        }

        override fun keptIndices(
            xs: DoubleArray,
            ys: DoubleArray,
            tolerance: Double,
        ): BooleanArray? = NativeBridge.keptIndices(xs, ys, tolerance) ?: declined("keptIndices", null)

        private fun done(
            function: String,
            code: Int,
        ): Boolean {
            if (code == NativeBridge.OK) return true
            // Broken off after a part was written: the array holds neither the old image nor the new one.
            check(code != NativeBridge.BROKEN) { "the native engine broke off in $function" }
            declined<Unit>(function, code)
            return false
        }

        /** Says that [function] declined (the Kotlin code takes over) and gives `null`. */
        private fun <T> declined(
            function: String,
            code: Int?,
        ): T? {
            Problems.unexpected(
                "engine.declined",
                "the native engine declined a call",
                "function" to function,
                "code" to code,
            )
            return null
        }
    }

    /** The system property that names the folder of the build machine's own library (JVM tests). */
    const val DIR_PROPERTY = "toolmap.engine.dir"

    /** The largest image the engine writes as a PNG (`MAX_PIXELS` in `tile_png.rs`): 2048 × 2048, tiles are smaller. */
    private const val MAX_PNG_PIXELS = 2048L * 2048L

    private const val LIBRARY = "toolmap_engine"
    private const val OPAQUE = -0x1000000

    /** Enough pixels for the native code to use its table of opaque pixels (16 384 and more). */
    private const val CHECK_PIXELS = 16 * 1024 + 7
    private val CHECK_MODES = listOf(BlendMode.MULTIPLY, BlendMode.SOFT_LIGHT, BlendMode.COLOR_BURN, BlendMode.HUE)
    private val CHECK_OPACITIES = floatArrayOf(1f, 0.35f)
    private const val CHECK_TINT = 0x3366CC
    private const val CHECK_INTENSITY = 0.5f
    private const val CHECK_DEM = 16
    private const val CHECK_TILE = 32
    private const val CHECK_LOW = 1000
    private const val CHECK_HIGH = 1300
    private const val CHECK_PATH = 300
    private const val CHECK_TOLERANCE = 4.0
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
