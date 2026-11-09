package fr.toolmap.core.track

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads a track file whichever its format: the content decides, the file name is only a hint for the error. */
object TrackImporter {
    /** Largest file we read; a 50 000-point GPX is about 5 MB. */
    const val MAX_BYTES = 64 * 1024 * 1024

    private const val MAX_UNZIPPED = 128 * 1024 * 1024
    private const val SNIFF_BYTES = 4096

    fun read(
        bytes: ByteArray,
        nameHint: String? = null,
    ): TrackDocument {
        if (bytes.size > MAX_BYTES) throw TrackFormatException("File too large: ${bytes.size} bytes")
        return when (detect(bytes)) {
            SourceFormat.GPX -> GpxReader.read(bytes)
            SourceFormat.KML -> KmlReader.read(bytes)
            SourceFormat.KMZ -> readKmz(bytes)
            SourceFormat.GEOJSON -> GeoJsonReader.read(String(bytes, Charsets.UTF_8).removePrefix(""))
            null -> throw TrackFormatException("Unknown file format${nameHint?.let { " ($it)" }.orEmpty()}")
        }
    }

    /** Looks at the first bytes: a ZIP, a JSON object, or the root element of an XML document. */
    fun detect(bytes: ByteArray): SourceFormat? {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() == 3
        ) {
            return SourceFormat.KMZ
        }
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.UTF_8).removePrefix("﻿").trimStart()
        if (head.startsWith("{")) return SourceFormat.GEOJSON
        val root = ROOT_ELEMENT.find(head)?.groupValues?.get(1)?.substringAfter(':') ?: return null
        return when (root) {
            "gpx" -> SourceFormat.GPX
            "kml" -> SourceFormat.KML
            else -> null
        }
    }

    /** First element that is not a declaration, comment or DOCTYPE. */
    private val ROOT_ELEMENT = Regex("""<([A-Za-z_][\w.:-]*)""")

    /**
     * The main KML of a KMZ, streamed to the parser and never held whole, at most [MAX_BYTES] like a KML file (P36
     * review: a small zip bomb made the import hold 128 MB of KML, then copy it, and the app ran out of memory).
     */
    private fun readKmz(bytes: ByteArray): TrackDocument {
        val index = kmlEntryIndex(bytes) ?: throw TrackFormatException("KMZ archive without a .kml file")
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            repeat(index + 1) { zip.nextEntry ?: throw TrackFormatException("KMZ archive without a .kml file") }
            return KmlReader.read(Bounded(zip, MAX_BYTES.toLong())).copy(format = SourceFormat.KMZ)
        }
    }

    /**
     * The index of the main KML of a KMZ: `doc.kml` if present, else the first `.kml` entry. The entries before it are
     * read through (how a ZIP is walked) with one small buffer, counted against [MAX_UNZIPPED], and kept nowhere.
     */
    private fun kmlEntryIndex(bytes: ByteArray): Int? {
        var chosen: Int? = null
        var total = 0L
        val buffer = ByteArray(UNZIP_BUFFER)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var index = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                val kml = !entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)
                if (kml && entry.name.equals("doc.kml", ignoreCase = true)) return index
                if (kml && chosen == null) chosen = index
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UNZIPPED) throw TrackFormatException("KMZ archive too large once unzipped")
                }
                index++
            }
        }
        return chosen
    }

    /** The first [limit] bytes of [input]; reading past them fails, as for a KML file too large to import. */
    private class Bounded(
        input: InputStream,
        private val limit: Long,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw IOException("KML too large once unzipped (more than $limit bytes)")
        }
    }

    private const val UNZIP_BUFFER = 16 * 1024
}
