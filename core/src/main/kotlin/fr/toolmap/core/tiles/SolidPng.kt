package fr.toolmap.core.tiles

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Minimal PNG encoder for flat-colour tiles (the stored-coverage overlay): an RGBA square filled with one
 * colour and an optional one-colour frame. No Android classes, so it is unit-tested on the JVM.
 */
object SolidPng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Colours are `0xAARRGGBB`. [frame] is `null` for no frame; [frameWidth] is in pixels. */
    fun create(
        size: Int,
        fill: Int,
        frame: Int? = null,
        frameWidth: Int = 1,
    ): ByteArray {
        require(size in 1..MAX_SIZE) { "size out of range: $size" }
        require(frameWidth >= 0) { "negative frame width" }
        val rowBytes = size * BYTES_PER_PIXEL
        val raw = ByteArray((rowBytes + 1) * size)
        for (y in 0 until size) {
            val rowStart = y * (rowBytes + 1)
            // Filter type 0 (none) is already the zero byte at rowStart.
            for (x in 0 until size) {
                val onFrame =
                    frame != null &&
                        (x < frameWidth || y < frameWidth || x >= size - frameWidth || y >= size - frameWidth)
                val argb = if (onFrame) frame!! else fill
                val offset = rowStart + 1 + x * BYTES_PER_PIXEL
                raw[offset] = (argb shr 16).toByte()
                raw[offset + 1] = (argb shr 8).toByte()
                raw[offset + 2] = argb.toByte()
                raw[offset + 3] = (argb ushr 24).toByte()
            }
        }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        chunk(out, "IHDR", header(size))
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun header(size: Int): ByteArray {
        val data = ByteArray(HEADER_SIZE)
        writeInt(data, 0, size)
        writeInt(data, 4, size)
        data[8] = 8 // bit depth
        data[9] = 6 // colour type: RGBA
        return data // compression, filter and interlace methods are 0
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val lengthBytes = ByteArray(Int.SIZE_BYTES)
        writeInt(lengthBytes, 0, data.size)
        out.write(lengthBytes)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(Int.SIZE_BYTES)
        writeInt(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun writeInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private const val BYTES_PER_PIXEL = 4
    private const val HEADER_SIZE = 13
    private const val BUFFER_SIZE = 4096
    private const val MAX_SIZE = 2048
}
