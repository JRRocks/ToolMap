package fr.toolmap.core.p2p

/**
 * Base45 (RFC 9285). Its 45 characters are exactly the alphanumeric mode of a QR code, which stores 5.5 bits per
 * character, so a payload costs about 1.5 characters per byte instead of the 8 bits of the byte mode.
 */
object Base45 {
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
    private const val BASE = 45

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size * 3 / 2 + 2)
        var i = 0
        while (i + 1 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[n % BASE]).append(ALPHABET[(n / BASE) % BASE]).append(ALPHABET[n / (BASE * BASE)])
            i += 2
        }
        if (i < data.size) {
            val n = data[i].toInt() and 0xFF
            sb.append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE])
        }
        return sb.toString()
    }

    /** Decodes [text]; any character outside the alphabet or an impossible group is a [WireException]. */
    fun decode(text: String): ByteArray {
        if (text.length % 3 == 1) throw WireException("bad base45 length")
        val out = java.io.ByteArrayOutputStream(text.length * 2 / 3)
        var i = 0
        while (i < text.length) {
            val c = value(text[i])
            val d = value(text[i + 1])
            if (i + 2 < text.length) {
                val n = c + d * BASE + value(text[i + 2]) * BASE * BASE
                if (n > 0xFFFF) throw WireException("bad base45 group")
                out.write(n shr 8)
                out.write(n and 0xFF)
                i += 3
            } else {
                val n = c + d * BASE
                if (n > 0xFF) throw WireException("bad base45 group")
                out.write(n)
                i += 2
            }
        }
        return out.toByteArray()
    }

    private fun value(c: Char): Int {
        val v = ALPHABET.indexOf(c)
        if (v < 0) throw WireException("bad base45 character")
        return v
    }
}
