package fr.toolmap.core.compute

/**
 * A faster engine for the heavy computations of this module (ADR-119, `docs/specs/native-engine.md`): the native
 * library of `:engine`, when the app has loaded it. Each function stands for one piece of Kotlin code and gives exactly
 * what that code gives, bit for bit; it may also decline (`false`, `null`), and the Kotlin code then does the work.
 * A function that declines has changed nothing.
 */
interface Accelerator {
    /** [fr.toolmap.core.layers.Blend.compositeReference], [mode] being the ordinal of the blend mode. */
    fun composite(
        below: IntArray,
        above: IntArray,
        mode: Int,
        opacity: Float,
    ): Boolean

    /** [fr.toolmap.core.layers.Blend.tintReference]. */
    fun tint(
        pixels: IntArray,
        tint: Int,
        intensity: Float,
    ): Boolean

    /** [fr.toolmap.core.layers.Blend.adjustReference]. */
    fun adjust(
        pixels: IntArray,
        matrix: FloatArray,
    ): Boolean

    /**
     * [fr.toolmap.core.elevation.MaskRaster.alphas] of [fr.toolmap.core.elevation.MaskRaster.elevations], for the cell
     * ([col], [row]) of a 2^[depth] grid of the elevation tile. [low] and [high] are the bounds of the band in metres,
     * NaN for none.
     */
    fun maskAlphas(
        dem: FloatArray,
        demSize: Int,
        depth: Int,
        col: Int,
        row: Int,
        size: Int,
        low: Float,
        high: Float,
    ): ByteArray?

    /** [fr.toolmap.core.elevation.MaskRaster.applyAlphasReference]. */
    fun applyAlphas(
        pixels: IntArray,
        alphas: ByteArray,
    ): Boolean

    /**
     * The PNG of [pixels] ([width] × [height] ARGB ints with straight colours), written fast rather than small: for an
     * image that is shown and never stored. The Kotlin code has no such writer (`null` without an accelerator).
     */
    fun encodePng(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ByteArray?

    /** [fr.toolmap.core.track.PathSimplifier.keptIndicesReference]. */
    fun keptIndices(
        xs: DoubleArray,
        ys: DoubleArray,
        tolerance: Double,
    ): BooleanArray?
}

/** The engine in use for the heavy computations: the Kotlin code alone unless an [Accelerator] was installed. */
object Compute {
    /** Set once at start by the app (`NativeEngine.install`), `null` in the JVM tests of this module. */
    @Volatile
    var accelerator: Accelerator? = null

    /**
     * The PNG of an image that is shown and never stored (a tile drawn here), or `null` without an accelerator: the
     * caller then writes it with the platform's own encoder.
     */
    fun fastPng(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): ByteArray? = accelerator?.encodePng(pixels, width, height)
}
