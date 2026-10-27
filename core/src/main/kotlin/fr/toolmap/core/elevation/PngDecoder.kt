package fr.toolmap.core.elevation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.math.abs

/** A decoded 8-bit image: [channels] bytes per pixel, rows top to bottom. */
class PngImage(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: ByteArray,
) {
    /** Unsigned value of [channel] of the pixel at ([x], [y]). */
    fun sample(
        x: Int,
        y: Int,
        channel: Int,
    ): Int = data[(y * width + x) * channels + channel].toInt() and 0xFF
}

class PngException(
    message: String,
) : Exception(message)

/**
 * Small PNG reader for elevation tiles (Terrarium is 8-bit RGB), with no `android.graphics` or `javax.imageio`, so
 * that it runs identically in unit tests, in a background thread and without allocating a Bitmap. Supports 8-bit
 * grey, grey+alpha, RGB, RGBA and palette images, all five scanline filters, not interlaced (Adam7).
 */
object PngDecoder {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_PIXELS = 4096L * 4096L
    private const val MAX_CHUNK = 64L * 1024 * 1024

    fun decode(bytes: ByteArray): PngImage {
        if (bytes.size < SIGNATURE.size || !bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
            throw PngException("Not a PNG")
        }
        var pos = SIGNATURE.size
        var width = 0
        var height = 0
        var colorType = -1
        var palette: ByteArray? = null
        val idat = ByteArrayOutputStream()
        var seenHeader = false
        while (pos + 8 <= bytes.size) {
            val length = readInt(bytes, pos).toLong() and 0xFFFFFFFFL
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val start = pos + 8
            if (length > MAX_CHUNK || start + length + 4 > bytes.size) throw PngException("Truncated PNG chunk $type")
            when (type) {
                "IHDR" -> {
                    if (length < 13) throw PngException("Bad IHDR")
                    width = readInt(bytes, start)
                    height = readInt(bytes, start + 4)
                    val depth = bytes[start + 8].toInt()
                    colorType = bytes[start + 9].toInt()
                    val interlace = bytes[start + 12].toInt()
                    if (depth != 8) throw PngException("Only 8-bit PNG is supported (depth $depth)")
                    if (interlace != 0) throw PngException("Interlaced PNG is not supported")
                    if (width <= 0 || height <= 0 ||
                        width.toLong() * height > MAX_PIXELS
                    ) {
                        throw PngException("Bad size")
                    }
                    seenHeader = true
                }

                "PLTE" -> {
                    palette = bytes.copyOfRange(start, start + length.toInt())
                }

                "IDAT" -> {
                    idat.write(bytes, start, length.toInt())
                }

                "IEND" -> {
                    break
                }
            }
            pos = start + length.toInt() + 4
        }
        if (!seenHeader) throw PngException("Missing IHDR")
        val inputChannels =
            when (colorType) {
                0, 3 -> 1
                2 -> 3
                4 -> 2
                6 -> 4
                else -> throw PngException("Unsupported colour type $colorType")
            }
        val raw =
            unfilter(inflate(idat.toByteArray(), (width * inputChannels + 1) * height), width, height, inputChannels)
        if (colorType != 3) return PngImage(width, height, inputChannels, raw)
        val table = palette ?: throw PngException("Palette image without PLTE")
        val rgb = ByteArray(width * height * 3)
        for (i in 0 until width * height) {
            val index = raw[i].toInt() and 0xFF
            if (index * 3 + 2 >= table.size) throw PngException("Palette index out of range")
            rgb[i * 3] = table[index * 3]
            rgb[i * 3 + 1] = table[index * 3 + 1]
            rgb[i * 3 + 2] = table[index * 3 + 2]
        }
        return PngImage(width, height, 3, rgb)
    }

    private fun inflate(
        compressed: ByteArray,
        expected: Int,
    ): ByteArray {
        val out = ByteArray(expected)
        try {
            InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                var filled = 0
                while (filled < expected) {
                    val n = input.read(out, filled, expected - filled)
                    if (n < 0) throw PngException("PNG pixel data is too short")
                    filled += n
                }
            }
        } catch (e: ZipException) {
            throw PngException("Corrupt PNG data: ${e.message}")
        }
        return out
    }

    private fun unfilter(
        raw: ByteArray,
        width: Int,
        height: Int,
        bpp: Int,
    ): ByteArray {
        val stride = width * bpp
        val out = ByteArray(stride * height)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val src = y * (stride + 1) + 1
            val dst = y * stride
            for (i in 0 until stride) {
                val x = raw[src + i].toInt() and 0xFF
                val a = if (i >= bpp) out[dst + i - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) out[dst - stride + i].toInt() and 0xFF else 0
                val c = if (y > 0 && i >= bpp) out[dst - stride + i - bpp].toInt() and 0xFF else 0
                val value =
                    when (filter) {
                        0 -> x
                        1 -> x + a
                        2 -> x + b
                        3 -> x + (a + b) / 2
                        4 -> x + paeth(a, b, c)
                        else -> throw PngException("Bad PNG filter $filter")
                    }
                out[dst + i] = value.toByte()
            }
        }
        return out
    }

    private fun paeth(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val p = a + b - c
        val pa = abs(p - a)
        val pb = abs(p - b)
        val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) {
            a
        } else if (pb <= pc) {
            b
        } else {
            c
        }
    }

    private fun readInt(
        b: ByteArray,
        at: Int,
    ): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
