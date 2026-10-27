package fr.toolmap.core.pdf

import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/** A small reader of the files [PdfWriter] makes, just enough for tests to look inside them. */
internal class PdfProbe(
    val bytes: ByteArray,
) {
    val text: String = String(bytes, Charsets.ISO_8859_1)

    /** Offsets listed by the cross-reference table, by object number. */
    val offsets: Map<Int, Long>

    init {
        val startXref = Regex("startxref\\s+(\\d+)\\s+%%EOF\\s*$").find(text)?.groupValues?.get(1)?.toInt()
        requireNotNull(startXref) { "no startxref" }
        require(text.startsWith("xref\n", startXref)) { "startxref does not point at the table" }
        val lines = text.substring(startXref).lines()
        val count = lines[1].substringAfter(' ').trim().toInt()
        val map = HashMap<Int, Long>()
        for (number in 1 until count) {
            val line = lines[2 + number]
            map[number] = line.substring(0, 10).toLong()
        }
        offsets = map
    }

    fun body(number: Int): String {
        val at = offsets.getValue(number).toInt()
        require(text.startsWith("$number 0 obj", at)) { "object $number is not at its offset" }
        return text.substring(at, text.indexOf("endobj", at))
    }

    /** The decompressed (or raw, for a JPEG) bytes of a stream object. */
    fun stream(number: Int): ByteArray {
        val at = offsets.getValue(number).toInt()
        val header = body(number)
        val length = Regex("/Length (\\d+)").find(header)!!.groupValues[1].toInt()
        val start = text.indexOf("stream\n", at) + "stream\n".length
        val raw = bytes.copyOfRange(start, start + length)
        return if ("/FlateDecode" in header) InflaterInputStream(ByteArrayInputStream(raw)).readBytes() else raw
    }

    fun content(number: Int): String = String(stream(number), Charsets.ISO_8859_1)

    val pageNumbers: List<Int>
        get() = Regex("(\\d+) 0 R").findAll(body(2).substringAfter("/Kids")).map { it.groupValues[1].toInt() }.toList()

    fun contentOfPage(page: Int): String {
        val contents = Regex("/Contents (\\d+) 0 R").find(body(pageNumbers[page]))!!.groupValues[1].toInt()
        return content(contents)
    }

    fun mediaBox(page: Int): List<Double> =
        Regex("/MediaBox \\[([^\\]]*)]").find(body(pageNumbers[page]))!!.groupValues[1].trim().split(' ')
            .map { it.toDouble() }
}
