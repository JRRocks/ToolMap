package fr.toolmap.core.track

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    private fun readKmz(bytes: ByteArray): TrackDocument {
        val kml = findKml(bytes) ?: throw TrackFormatException("KMZ archive without a .kml file")
        return KmlReader.read(kml).copy(format = SourceFormat.KMZ)
    }

    /** The main KML of a KMZ: `doc.kml` if present, else the first `.kml` entry. Reading stops at [MAX_UNZIPPED]. */
    private fun findKml(bytes: ByteArray): ByteArray? {
        var chosen: ByteArray? = null
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".kml", ignoreCase = true)) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_UNZIPPED) throw TrackFormatException("KMZ archive too large once unzipped")
                    out.write(buffer, 0, n)
                }
                if (entry.name.equals("doc.kml", ignoreCase = true)) return out.toByteArray()
                if (chosen == null) chosen = out.toByteArray()
            }
        }
        return chosen
    }
}
