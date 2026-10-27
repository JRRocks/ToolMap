package fr.toolmap.core.track

import fr.toolmap.core.xml.XmlText
import kotlinx.serialization.Serializable

/** `<prefix:name` and ` prefix:attribute=`. */
private val PREFIX_USE = Regex("""[<\s]/?([A-Za-z_][\w.-]*):[A-Za-z_]""")

/**
 * A fragment of XML the model does not interpret (Garmin `gpxtpx` heart rate, OsmAnd extensions, `magvar`, `hdop`...),
 * kept as it was read so that exporting writes it back. Namespace prefixes are those of the source document: the
 * writer declares them on the root element.
 */
@Serializable
@JvmInline
value class RawXml(
    val xml: String,
) {
    /** Qualified name of the fragment's root element, e.g. `extensions` or `gpxtpx:hr`. */
    val name: String
        get() = xml.drop(1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }

    /** Local name, without prefix. */
    val localName: String get() = name.substringAfter(':')

    /**
     * The same fragment with an `xmlns:prefix` declaration on its root for every prefix of [namespaces] it uses and
     * does not declare itself, so that it stays valid wherever it is written.
     */
    fun declaring(namespaces: Map<String, String>): RawXml {
        val used = PREFIX_USE.findAll(xml).map { it.groupValues[1] }.toSet()
        val missing = used.filter { it in namespaces && " xmlns:$it=" !in xml }
        if (missing.isEmpty()) return this
        val rootEnd = name.length + 1
        val declarations = missing.joinToString("") { " xmlns:$it=\"${XmlText.attribute(namespaces.getValue(it))}\"" }
        return RawXml(xml.substring(0, rootEnd) + declarations + xml.substring(rootEnd))
    }
}

/** A `<link>` of GPX (or a GPX 1.0 `<url>` / `<urlname>` pair). */
@Serializable
data class GpxLink(
    val href: String,
    val text: String? = null,
    val type: String? = null,
)

/** A recorded or drawn position. [timeMillis] is milliseconds since the epoch (UTC). */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMillis: Long? = null,
    val extras: List<RawXml> = emptyList(),
)

/** A named place. Also used for the points of a [Route], whose points may carry a name (turn instructions). */
@Serializable
data class Waypoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMillis: Long? = null,
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val links: List<GpxLink> = emptyList(),
    val sym: String? = null,
    val type: String? = null,
    val extras: List<RawXml> = emptyList(),
    /** Name of the KML folder it came from; GPX has no such notion. */
    val folder: String? = null,
)

/** How a line is drawn. */
data class PathStyle(
    val colorArgb: Int = DEFAULT_COLOR,
    val widthDp: Float = DEFAULT_WIDTH,
    val dashed: Boolean = false,
    /** Direction arrows along the line. */
    val arrows: Boolean = false,
) {
    companion object {
        const val DEFAULT_COLOR: Int = 0xFFE53935.toInt()
        const val DEFAULT_WIDTH: Float = 4f

        /** Colours given in turn to the tracks of an import, and offered by the style picker. */
        val PALETTE: List<Int> =
            listOf(
                0xFFE53935.toInt(),
                0xFF1E88E5.toInt(),
                0xFF43A047.toInt(),
                0xFFFB8C00.toInt(),
                0xFF8E24AA.toInt(),
                0xFF00ACC1.toInt(),
                0xFF6D4C41.toInt(),
                0xFF212121.toInt(),
            )
    }
}

/** An ordered list of points to follow (GPX `<rte>`; a KML polygon outline is imported as one too). */
data class Route(
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val links: List<GpxLink> = emptyList(),
    val number: Int? = null,
    val type: String? = null,
    val points: List<Waypoint> = emptyList(),
    val extras: List<RawXml> = emptyList(),
    val style: PathStyle? = null,
    val folder: String? = null,
)

/** A recorded path made of one or more segments (a new segment starts after a GPS loss or a pause). */
data class Track(
    val name: String? = null,
    val cmt: String? = null,
    val desc: String? = null,
    val src: String? = null,
    val links: List<GpxLink> = emptyList(),
    val number: Int? = null,
    val type: String? = null,
    val segments: List<List<TrackPoint>> = emptyList(),
    val extras: List<RawXml> = emptyList(),
    /** What the file itself says about colour and width (KML `LineStyle`, Garmin `DisplayColor`), if anything. */
    val style: PathStyle? = null,
    val folder: String? = null,
) {
    val pointCount: Int get() = segments.sumOf { it.size }
}

enum class SourceFormat { GPX, KML, KMZ, GEOJSON }

/** What was odd in a file that was nevertheless imported. [detail] is for display, in the file's own words. */
data class TrackIssue(
    val kind: Kind,
    val detail: String = "",
) {
    enum class Kind {
        /** A point with a missing or out-of-range latitude / longitude was skipped. */
        INVALID_COORDINATE,

        /** A time that could not be read was ignored. */
        INVALID_TIME,

        /** A track segment or route without any usable point was dropped. */
        EMPTY_ITEM_DROPPED,

        /** A KML polygon was imported as a closed route. */
        POLYGON_AS_ROUTE,

        /** GPX 1.0 `speed`, `course` and `email`, or the e-mail and link of a GPX 1.1 author, are not kept. */
        FIELD_NOT_KEPT,

        /** A geometry or element type that is not supported was skipped. */
        UNSUPPORTED_ELEMENT,
    }
}

/** Everything a GPX / KML / GeoJSON file holds, in one format-neutral shape. */
data class TrackDocument(
    val name: String? = null,
    val description: String? = null,
    val author: String? = null,
    val keywords: String? = null,
    val timeMillis: Long? = null,
    val creator: String? = null,
    /** `1.0` or `1.1` for GPX; `null` for other formats. */
    val version: String? = null,
    val links: List<GpxLink> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    val routes: List<Route> = emptyList(),
    val tracks: List<Track> = emptyList(),
    /** Unmodelled children of `<metadata>` (e.g. `copyright`). */
    val metadataExtras: List<RawXml> = emptyList(),
    /** Unmodelled children of the root element (the document's own `<extensions>`). */
    val extras: List<RawXml> = emptyList(),
    /** Namespace prefixes (without the default one) declared in the source, needed by the raw fragments. */
    val namespaces: Map<String, String> = emptyMap(),
    val issues: List<TrackIssue> = emptyList(),
    val format: SourceFormat? = null,
) {
    val isEmpty: Boolean get() = waypoints.isEmpty() && routes.isEmpty() && tracks.isEmpty()
    val pointCount: Int get() = tracks.sumOf { it.pointCount } + routes.sumOf { it.points.size } + waypoints.size
}

/** The file is not a track file we can read, or is one we refuse to read. */
class TrackFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
