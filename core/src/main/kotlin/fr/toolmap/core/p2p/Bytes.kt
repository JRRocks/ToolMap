package fr.toolmap.core.p2p

import java.io.ByteArrayOutputStream

/** Thrown when bytes received from a peer or read from a code do not follow the format. */
class WireException(
    message: String,
) : Exception(message)

/** Big-endian writer for the binary formats of the pairing codes and of the messages. */
internal class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }

    fun u16(v: Int) = apply { u8(v shr 8).u8(v) }

    fun u32(v: Long) = apply { u16((v shr 16).toInt()).u16(v.toInt()) }

    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun i16(v: Int) = u16(v)

    fun u64(v: Long) = apply { u32(v ushr 32).u32(v and 0xFFFFFFFFL) }

    fun bytes(b: ByteArray) = apply { out.write(b) }

    /** One-byte length then UTF-8, cut on a character boundary when longer than [max] bytes. */
    fun str(
        s: String,
        max: Int = MAX_SHORT_STRING,
    ) = apply {
        val b = truncateUtf8(s, max)
        u8(b.size)
        bytes(b)
    }

    /** Two-byte length then UTF-8. */
    fun longStr(
        s: String,
        max: Int,
    ) = apply {
        val b = truncateUtf8(s, max)
        u16(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        const val MAX_SHORT_STRING = 255

        fun truncateUtf8(
            s: String,
            max: Int,
        ): ByteArray {
            val all = s.toByteArray(Charsets.UTF_8)
            if (all.size <= max) return all
            var end = max
            // Do not cut a multi-byte sequence in the middle: back up to a lead byte.
            while (end > 0 && (all[end].toInt() and 0xC0) == 0x80) end--
            return all.copyOf(end)
        }
    }
}

internal class ByteReader(
    private val data: ByteArray,
    private var pos: Int = 0,
) {
    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw WireException("truncated data")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun i32(): Int = u32().toInt()

    fun u64(): Long = (u32() shl 32) or u32()

    fun bytes(n: Int): ByteArray {
        need(n)
        val r = data.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun rest(): ByteArray = bytes(remaining)

    fun str(): String = String(bytes(u8()), Charsets.UTF_8)

    fun longStr(): String = String(bytes(u16()), Charsets.UTF_8)
}
