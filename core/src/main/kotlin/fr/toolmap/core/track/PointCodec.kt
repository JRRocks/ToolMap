package fr.toolmap.core.track

import java.io.ByteArrayOutputStream

/**
 * Compact binary form of a list of [TrackPoint]s, for the database: a 50 000-point track is about 10 bytes per point
 * instead of 40, and loads as one blob instead of 50 000 rows.
 *
 * Coordinates are stored as integers of 1e-9 degree (0.1 mm), elevations in micrometres, times in milliseconds, each
 * as the difference with the previous point (zig-zag varint), so a point costs 7 to 11 bytes. Unmodelled XML fragments
 * ([RawXml]) are kept as UTF-8. Anything finer than those units is rounded; GPS data is not that precise.
 */
object PointCodec {
    private const val VERSION = 1
    private const val FLAG_ELE = 1
    private const val FLAG_TIME = 2
    private const val FLAG_EXTRAS = 4
    private const val COORD_SCALE = 1e9
    private const val ELE_SCALE = 1e6
    private const val MAX_EXTRAS = 10_000

    fun encode(points: List<TrackPoint>): ByteArray {
        val out = ByteArrayOutputStream(points.size * 10 + 8)
        out.write(VERSION)
        writeVarint(out, points.size.toLong())
        var lat = 0L
        var lon = 0L
        var ele = 0L
        var time = 0L
        for (p in points) {
            var flags = 0
            if (p.ele != null) flags = flags or FLAG_ELE
            if (p.timeMillis != null) flags = flags or FLAG_TIME
            if (p.extras.isNotEmpty()) flags = flags or FLAG_EXTRAS
            out.write(flags)
            val la = Math.round(p.lat * COORD_SCALE)
            val lo = Math.round(p.lon * COORD_SCALE)
            writeVarint(out, zigzag(la - lat))
            writeVarint(out, zigzag(lo - lon))
            lat = la
            lon = lo
            p.ele?.let {
                val e = Math.round(it * ELE_SCALE)
                writeVarint(out, zigzag(e - ele))
                ele = e
            }
            p.timeMillis?.let {
                writeVarint(out, zigzag(it - time))
                time = it
            }
            if (p.extras.isNotEmpty()) {
                writeVarint(out, p.extras.size.toLong())
                for (raw in p.extras) {
                    val bytes = raw.xml.toByteArray(Charsets.UTF_8)
                    writeVarint(out, bytes.size.toLong())
                    out.write(bytes)
                }
            }
        }
        return out.toByteArray()
    }

    /** @throws IllegalArgumentException when [bytes] is not something [encode] wrote (a damaged database). */
    fun decode(bytes: ByteArray): List<TrackPoint> {
        val input = Reader(bytes)
        require(input.byte() == VERSION) { "Unknown point format version" }
        val count = input.varint().toInt()
        require(count in 0..bytes.size) { "Corrupt point count" }
        val points = ArrayList<TrackPoint>(count)
        var lat = 0L
        var lon = 0L
        var ele = 0L
        var time = 0L
        repeat(count) {
            val flags = input.byte()
            lat += unzigzag(input.varint())
            lon += unzigzag(input.varint())
            var elevation: Double? = null
            var millis: Long? = null
            if (flags and FLAG_ELE != 0) {
                ele += unzigzag(input.varint())
                elevation = ele / ELE_SCALE
            }
            if (flags and FLAG_TIME != 0) {
                time += unzigzag(input.varint())
                millis = time
            }
            val extras =
                if (flags and FLAG_EXTRAS != 0) {
                    val n = input.varint().toInt()
                    require(n in 0..MAX_EXTRAS) { "Corrupt extras count" }
                    List(n) { RawXml(String(input.bytes(input.varint().toInt()), Charsets.UTF_8)) }
                } else {
                    emptyList()
                }
            points += TrackPoint(lat / COORD_SCALE, lon / COORD_SCALE, elevation, millis, extras)
        }
        return points
    }

    private fun zigzag(n: Long): Long = (n shl 1) xor (n shr 63)

    private fun unzigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private class Reader(
        private val data: ByteArray,
    ) {
        private var pos = 0

        fun byte(): Int {
            require(pos < data.size) { "Truncated point data" }
            return data[pos++].toInt() and 0xFF
        }

        fun bytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= data.size) { "Truncated point data" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "Corrupt varint" }
            }
        }
    }
}
