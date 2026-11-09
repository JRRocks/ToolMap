package fr.toolmap.engine

/**
 * The JNI door of the native engine (`engine/src/main/rust/src/lib.rs`): one function per function of the Rust
 * library, with the same arguments. Nothing here is called before [NativeEngine] has loaded the library and checked
 * its [abiVersion].
 *
 * The functions that work in place return 0 when done and a negative code when they declined ([REFUSED],
 * [JNI_FAILED], [PANICKED]: nothing was written) or broke off after writing a part ([BROKEN]); the others return
 * `null` when they declined.
 */
internal object NativeBridge {
    /** The version of the door this code was written for (`ABI_VERSION` in `lib.rs`). */
    const val ABI_VERSION = 1

    const val OK = 0
    const val REFUSED = -1
    const val JNI_FAILED = -2
    const val PANICKED = -3
    const val BROKEN = -4

    /** `encodePng` effort: rows filtered « Up », the fastest that still compresses. */
    const val PNG_FASTEST = 0

    @JvmStatic
    external fun abiVersion(): Int

    @JvmStatic
    external fun composite(
        below: IntArray,
        above: IntArray,
        mode: Int,
        opacity: Float,
    ): Int

    @JvmStatic
    external fun tint(
        pixels: IntArray,
        tint: Int,
        intensity: Float,
    ): Int

    @JvmStatic
    external fun adjust(
        pixels: IntArray,
        matrix: FloatArray,
    ): Int

    @JvmStatic
    external fun maskAlphas(
        dem: FloatArray,
        demSize: Int,
        depth: Int,
        col: Int,
        row: Int,
        size: Int,
        low: Float,
        high: Float,
    ): ByteArray?

    @JvmStatic
    external fun applyAlphas(
        pixels: IntArray,
        alphas: ByteArray,
    ): Int

    @JvmStatic
    external fun encodePng(
        pixels: IntArray,
        width: Int,
        height: Int,
        effort: Int,
    ): ByteArray?

    @JvmStatic
    external fun keptIndices(
        xs: DoubleArray,
        ys: DoubleArray,
        tolerance: Double,
    ): BooleanArray?
}
