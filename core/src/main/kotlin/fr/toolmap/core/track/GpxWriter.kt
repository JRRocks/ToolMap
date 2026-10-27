package fr.toolmap.core.track

import fr.toolmap.core.xml.XmlText
import java.io.StringWriter
import java.io.Writer
import java.math.BigDecimal

/**
 * Writes GPX 1.1, children in the order of the schema. Imported GPX 1.0 documents are written as 1.1. Coordinates,
 * elevations and times round-trip exactly (milliseconds); the unmodelled fragments of [RawXml] are written back as
 * read and their namespace prefixes are declared on the root.
 */
object GpxWriter {
    private val POINT_ORDER =
        listOf(
            "ele",
            "time",
            "magvar",
            "geoidheight",
            "name",
            "cmt",
            "desc",
            "src",
            "link",
            "sym",
            "type",
            "fix",
            "sat",
            "hdop",
            "vdop",
            "pdop",
            "ageofdgpsdata",
            "dgpsid",
            "extensions",
        )
    private val ITEM_ORDER = listOf("name", "cmt", "desc", "src", "link", "number", "type", "extensions")
    private val METADATA_ORDER =
        listOf("name", "desc", "author", "copyright", "link", "time", "keywords", "bounds", "extensions")

    fun toString(
        document: TrackDocument,
        creator: String = DEFAULT_CREATOR,
    ): String = StringWriter().also { write(document, it, creator) }.toString()

    fun toBytes(
        document: TrackDocument,
        creator: String = DEFAULT_CREATOR,
    ): ByteArray = toString(document, creator).toByteArray(Charsets.UTF_8)

    fun write(
        document: TrackDocument,
        out: Writer,
        creator: String = DEFAULT_CREATOR,
    ) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<gpx version=\"1.1\" creator=\"").append(XmlText.attribute(creator)).append("\"")
        out.append(" xmlns=\"").append(GpxReader.NS_GPX_11).append('"')
        for ((prefix, uri) in document.namespaces) {
            if (uri == GpxReader.NS_GPX_11 || prefix == "xmlns") continue
            out.append(" xmlns:").append(prefix).append("=\"").append(XmlText.attribute(uri)).append('"')
        }
        out.append(">\n")
        writeMetadata(document, out)
        for (w in document.waypoints) writePoint(out, "wpt", w.lat, w.lon, pointFields(w), 1)
        for (r in document.routes) writeRoute(out, r)
        for (t in document.tracks) writeTrack(out, t)
        for (raw in document.extras) out.append("  ").append(raw.xml).append('\n')
        out.append("</gpx>\n")
    }

    private fun writeMetadata(
        d: TrackDocument,
        out: Writer,
    ) {
        val fields = ArrayList<Pair<String, String>>()
        d.name?.let { fields += "name" to leaf("name", it) }
        d.description?.let { fields += "desc" to leaf("desc", it) }
        d.author?.let { fields += "author" to "<author>${leaf("name", it)}</author>" }
        for (l in d.links) fields += "link" to link(l)
        d.timeMillis?.let { fields += "time" to leaf("time", TrackTime.format(it)) }
        d.keywords?.let { fields += "keywords" to leaf("keywords", it) }
        bounds(d)?.let { fields += "bounds" to it }
        for (raw in d.metadataExtras) fields += raw.localName to raw.xml
        if (fields.isEmpty()) return
        out.append("  <metadata>\n")
        for ((_, xml) in sortByOrder(fields, METADATA_ORDER)) out.append("    ").append(xml).append('\n')
        out.append("  </metadata>\n")
    }

    private fun bounds(d: TrackDocument): String? {
        var minLat = Double.POSITIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY

        fun see(
            lat: Double,
            lon: Double,
        ) {
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
        }
        for (w in d.waypoints) see(w.lat, w.lon)
        for (r in d.routes) for (p in r.points) see(p.lat, p.lon)
        for (t in d.tracks) for (s in t.segments) for (p in s) see(p.lat, p.lon)
        if (minLat > maxLat) return null
        return "<bounds minlat=\"${num(
            minLat,
        )}\" minlon=\"${num(minLon)}\" maxlat=\"${num(maxLat)}\" maxlon=\"${num(maxLon)}\"/>"
    }

    private fun pointFields(w: Waypoint): List<Pair<String, String>> {
        val f = ArrayList<Pair<String, String>>()
        w.ele?.let { f += "ele" to leaf("ele", num(it)) }
        w.timeMillis?.let { f += "time" to leaf("time", TrackTime.format(it)) }
        w.name?.let { f += "name" to leaf("name", it) }
        w.cmt?.let { f += "cmt" to leaf("cmt", it) }
        w.desc?.let { f += "desc" to leaf("desc", it) }
        w.src?.let { f += "src" to leaf("src", it) }
        for (l in w.links) f += "link" to link(l)
        w.sym?.let { f += "sym" to leaf("sym", it) }
        w.type?.let { f += "type" to leaf("type", it) }
        for (raw in w.extras) f += raw.localName to raw.xml
        return f
    }

    private fun writePoint(
        out: Writer,
        tag: String,
        lat: Double,
        lon: Double,
        fields: List<Pair<String, String>>,
        level: Int,
    ) {
        val pad = "  ".repeat(level)
        out.append(pad).append('<').append(tag).append(" lat=\"").append(num(lat)).append("\" lon=\"").append(num(lon))
        if (fields.isEmpty()) {
            out.append("\"/>\n")
            return
        }
        out.append("\">\n")
        for ((_, xml) in sortByOrder(fields, POINT_ORDER)) out.append(pad).append("  ").append(xml).append('\n')
        out.append(pad).append("</").append(tag).append(">\n")
    }

    private fun writeRoute(
        out: Writer,
        r: Route,
    ) {
        out.append("  <rte>\n")
        val fields = ArrayList<Pair<String, String>>()
        itemFields(fields, r.name, r.cmt, r.desc, r.src, r.links, r.number, r.type, r.extras)
        for ((_, xml) in sortByOrder(fields, ITEM_ORDER)) out.append("    ").append(xml).append('\n')
        for (p in r.points) writePoint(out, "rtept", p.lat, p.lon, pointFields(p), 2)
        out.append("  </rte>\n")
    }

    private fun writeTrack(
        out: Writer,
        t: Track,
    ) {
        out.append("  <trk>\n")
        val fields = ArrayList<Pair<String, String>>()
        itemFields(fields, t.name, t.cmt, t.desc, t.src, t.links, t.number, t.type, t.extras)
        for ((_, xml) in sortByOrder(fields, ITEM_ORDER)) out.append("    ").append(xml).append('\n')
        for (segment in t.segments) {
            out.append("    <trkseg>\n")
            for (p in segment) {
                val f = ArrayList<Pair<String, String>>()
                p.ele?.let { f += "ele" to leaf("ele", num(it)) }
                p.timeMillis?.let { f += "time" to leaf("time", TrackTime.format(it)) }
                for (raw in p.extras) f += raw.localName to raw.xml
                writePoint(out, "trkpt", p.lat, p.lon, f, 3)
            }
            out.append("    </trkseg>\n")
        }
        out.append("  </trk>\n")
    }

    @Suppress("LongParameterList")
    private fun itemFields(
        f: MutableList<Pair<String, String>>,
        name: String?,
        cmt: String?,
        desc: String?,
        src: String?,
        links: List<GpxLink>,
        number: Int?,
        type: String?,
        extras: List<RawXml>,
    ) {
        name?.let { f += "name" to leaf("name", it) }
        cmt?.let { f += "cmt" to leaf("cmt", it) }
        desc?.let { f += "desc" to leaf("desc", it) }
        src?.let { f += "src" to leaf("src", it) }
        for (l in links) f += "link" to link(l)
        number?.let { f += "number" to leaf("number", it.toString()) }
        type?.let { f += "type" to leaf("type", it) }
        for (raw in extras) f += raw.localName to raw.xml
    }

    /** Stable sort by position in the schema; names the schema does not list go with `extensions`, at the end. */
    private fun sortByOrder(
        fields: List<Pair<String, String>>,
        order: List<String>,
    ): List<Pair<String, String>> =
        fields.sortedBy { (name, _) -> order.indexOf(name).let { if (it < 0) order.size - 1 else it } }

    private fun leaf(
        name: String,
        value: String,
    ) = "<$name>${XmlText.text(value)}</$name>"

    private fun link(l: GpxLink): String {
        val inner =
            buildString {
                l.text?.let { append(leaf("text", it)) }
                l.type?.let { append(leaf("type", it)) }
            }
        val href = XmlText.attribute(l.href)
        return if (inner.isEmpty()) "<link href=\"$href\"/>" else "<link href=\"$href\">$inner</link>"
    }

    /** Shortest decimal that reads back as the same double, never in scientific notation. */
    private fun num(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    const val DEFAULT_CREATOR = "ToolMap"
}
