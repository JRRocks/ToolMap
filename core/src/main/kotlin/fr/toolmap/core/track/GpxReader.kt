package fr.toolmap.core.track

import fr.toolmap.core.xml.SafeSax
import fr.toolmap.core.xml.XmlReadException
import fr.toolmap.core.xml.XmlText
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Reads GPX 1.0 and 1.1 (any namespace prefix, or none). Elements the model does not interpret (extensions of any
 * vendor, `magvar`, `hdop`...) are kept as [RawXml] so that [GpxWriter] writes them back.
 */
object GpxReader {
    const val NS_GPX_10 = "http://www.topografix.com/GPX/1/0"
    const val NS_GPX_11 = "http://www.topografix.com/GPX/1/1"

    fun read(bytes: ByteArray): TrackDocument = read(bytes.inputStream())

    fun read(input: InputStream): TrackDocument {
        val handler = Handler()
        try {
            SafeSax.parse(input, handler)
        } catch (e: XmlReadException) {
            throw TrackFormatException(e.message ?: "Not a readable GPX file", e)
        }
        return handler.document()
    }

    private const val MAX_TEXT = 100_000

    private val POINT_FIELDS =
        setOf("ele", "time", "name", "cmt", "desc", "src", "link", "url", "urlname", "sym", "type")
    private val ITEM_FIELDS = setOf("name", "cmt", "desc", "src", "link", "url", "urlname", "number", "type")

    /** Children each element understands; anything else under it is kept (or dropped) as a raw fragment. */
    private val KNOWN_CHILDREN: Map<String, Set<String>> =
        mapOf(
            "gpx" to
                setOf(
                    "metadata",
                    "wpt",
                    "rte",
                    "trk",
                    "name",
                    "desc",
                    "author",
                    "email",
                    "url",
                    "urlname",
                    "time",
                    "keywords",
                    "bounds",
                ),
            "metadata" to setOf("name", "desc", "author", "link", "time", "keywords", "bounds"),
            "wpt" to POINT_FIELDS,
            "rtept" to POINT_FIELDS,
            "trkpt" to setOf("ele", "time"),
            "rte" to ITEM_FIELDS + "rtept",
            "trk" to ITEM_FIELDS + "trkseg",
            "trkseg" to setOf("trkpt"),
            "link" to setOf("text", "type"),
            "author" to setOf("name"),
        )

    /** Elements that have a list to put unknown children in. */
    private val KEEPS_EXTRAS = setOf("gpx", "metadata", "wpt", "rtept", "trkpt", "rte", "trk")

    /** GPX 1.0 point fields with no GPX 1.1 equivalent: dropped (speed and course can be computed from the points). */
    private val V10_DROPPED_POINT_FIELDS = setOf("speed", "course")

    private class PointBuilder(
        val lat: Double?,
        val lon: Double?,
    ) {
        var ele: Double? = null
        var time: Long? = null
        var name: String? = null
        var cmt: String? = null
        var desc: String? = null
        var src: String? = null
        var sym: String? = null
        var type: String? = null
        val links = ArrayList<GpxLink>()
        val extras = ArrayList<RawXml>()

        val isValid: Boolean
            get() =
                lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
                    lat in -90.0..90.0 && lon in -180.0..180.0
    }

    private class ItemBuilder {
        var name: String? = null
        var cmt: String? = null
        var desc: String? = null
        var src: String? = null
        var number: Int? = null
        var type: String? = null
        val links = ArrayList<GpxLink>()
        val extras = ArrayList<RawXml>()
        val points = ArrayList<Waypoint>()
        val segments = ArrayList<List<TrackPoint>>()
    }

    /** Re-serialises one unknown element, with the namespace declarations it makes itself. */
    private class Capture(
        val discard: Boolean,
    ) {
        val xml = StringBuilder()
        var depth = 0
        private val text = StringBuilder()

        fun start(
            qName: String,
            attributes: Attributes,
            declarations: List<Pair<String, String>>,
        ) {
            if (discard) {
                depth++
                return
            }
            flushText()
            xml.append('<').append(qName)
            for ((prefix, uri) in declarations) {
                xml.append(" xmlns").append(if (prefix.isEmpty()) "" else ":$prefix")
                xml.append("=\"").append(XmlText.attribute(uri)).append('"')
            }
            for (i in 0 until attributes.length) {
                xml.append(' ').append(attributes.getQName(i)).append("=\"")
                xml.append(XmlText.attribute(attributes.getValue(i))).append('"')
            }
            xml.append('>')
            depth++
        }

        fun characters(
            chars: CharArray,
            from: Int,
            length: Int,
        ) {
            if (!discard && text.length < MAX_TEXT) text.append(chars, from, length)
        }

        fun end(qName: String) {
            if (!discard) {
                flushText()
                xml.append("</").append(qName).append('>')
            }
            depth--
        }

        private fun flushText() {
            if (text.isNotBlank()) xml.append(XmlText.text(text.toString()))
            text.setLength(0)
        }
    }

    private class Handler : DefaultHandler() {
        private val issues = IssueLog()
        private val namespaces = LinkedHashMap<String, String>()
        private val pending = ArrayList<Pair<String, String>>()
        private val stack = ArrayList<String>()
        private val text = StringBuilder()
        private var capture: Capture? = null

        private var version: String? = null
        private var creator: String? = null
        private var docName: String? = null
        private var docDesc: String? = null
        private var docAuthor: String? = null
        private var docKeywords: String? = null
        private var docTime: Long? = null
        private val docLinks = ArrayList<GpxLink>()
        private val metadataExtras = ArrayList<RawXml>()
        private val rootExtras = ArrayList<RawXml>()
        private val waypoints = ArrayList<Waypoint>()
        private val routes = ArrayList<Route>()
        private val tracks = ArrayList<Track>()

        private var point: PointBuilder? = null
        private var item: ItemBuilder? = null
        private var segment: ArrayList<TrackPoint>? = null
        private var linkHref: String? = null
        private var linkText: String? = null
        private var linkType: String? = null
        private var rootSeen = false

        fun document(): TrackDocument {
            if (!rootSeen) throw TrackFormatException("Empty document: no <gpx> element")
            return TrackDocument(
                name = docName,
                description = docDesc,
                author = docAuthor,
                keywords = docKeywords,
                timeMillis = docTime,
                creator = creator,
                version = version,
                links = docLinks,
                waypoints = waypoints,
                routes = routes,
                tracks = tracks,
                metadataExtras = metadataExtras,
                extras = rootExtras,
                namespaces = namespaces,
                issues = issues.toList(),
                format = SourceFormat.GPX,
            )
        }

        override fun startPrefixMapping(
            prefix: String?,
            uri: String?,
        ) {
            val p = prefix.orEmpty()
            val u = uri.orEmpty()
            pending += p to u
            if (capture == null && p.isNotEmpty() && p != "xml" && p !in namespaces) namespaces[p] = u
        }

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes,
        ) {
            val declarations = pending.toList()
            pending.clear()
            val q = qName.orEmpty()
            val local = localName.takeUnless { it.isNullOrEmpty() } ?: q.substringAfter(':')
            val active = capture
            if (active != null) {
                active.start(q, attributes, declarations)
                return
            }
            val ns = uri.orEmpty()
            if (stack.isEmpty()) {
                startRoot(local, ns, attributes)
                return
            }
            val parent = stack.last()
            val inGpx = ns.isEmpty() || ns == NS_GPX_10 || ns == NS_GPX_11
            if (!inGpx || local !in KNOWN_CHILDREN[parent].orEmpty()) {
                startUnknown(parent, local, q, attributes, declarations)
                return
            }
            stack += local
            text.setLength(0)
            when (local) {
                "wpt", "rtept", "trkpt" -> {
                    val lat = attributes.getValue("lat")?.trim()?.toDoubleOrNull()
                    val lon = attributes.getValue("lon")?.trim()?.toDoubleOrNull()
                    point = PointBuilder(lat, lon)
                }

                "rte", "trk" -> {
                    item = ItemBuilder()
                }

                "trkseg" -> {
                    segment = ArrayList()
                }

                "link" -> {
                    linkHref = attributes.getValue("href")
                    linkText = null
                    linkType = null
                }
            }
        }

        private fun startUnknown(
            parent: String,
            local: String,
            qName: String,
            attributes: Attributes,
            declarations: List<Pair<String, String>>,
        ) {
            val discard = parent !in KEEPS_EXTRAS || (version == "1.0" && local in V10_DROPPED_POINT_FIELDS)
            if (discard) issues.add(TrackIssue.Kind.FIELD_NOT_KEPT, "$parent/$local")
            capture = Capture(discard).also { it.start(qName, attributes, declarations) }
        }

        private fun startRoot(
            local: String,
            ns: String,
            attributes: Attributes,
        ) {
            if (local != "gpx") throw TrackFormatException("Not a GPX file: root element is <$local>")
            rootSeen = true
            version =
                when {
                    ns == NS_GPX_10 -> "1.0"
                    else -> attributes.getValue("version")?.trim() ?: "1.1"
                }
            creator = attributes.getValue("creator")
            stack += "gpx"
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            val active = capture
            if (active != null) {
                active.characters(ch, start, length)
            } else if (text.length < MAX_TEXT) {
                text.append(ch, start, length)
            }
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qName: String?,
        ) {
            val active = capture
            if (active != null) {
                active.end(qName.orEmpty())
                if (active.depth == 0) {
                    capture = null
                    if (!active.discard) attachExtra(RawXml(active.xml.toString()))
                }
                return
            }
            val local = stack.last()
            val parent = stack.getOrNull(stack.size - 2).orEmpty()
            val value = text.toString().trim()
            text.setLength(0)
            endKnown(local, parent, value)
            stack.removeAt(stack.size - 1)
        }

        private fun endKnown(
            local: String,
            parent: String,
            value: String,
        ) {
            when (local) {
                "ele" -> {
                    point?.ele = value.toDoubleOrNull()?.takeIf { it.isFinite() }
                }

                "time" -> {
                    setTime(parent, value)
                }

                "name" -> {
                    setName(parent, value)
                }

                "cmt" -> {
                    if (isPoint(parent)) point?.cmt = value else item?.cmt = value
                }

                "desc" -> {
                    setDesc(parent, value)
                }

                "src" -> {
                    if (isPoint(parent)) point?.src = value else item?.src = value
                }

                "sym" -> {
                    point?.sym = value
                }

                "type" -> {
                    setType(parent, value)
                }

                "number" -> {
                    item?.number = value.toIntOrNull()
                }

                "keywords" -> {
                    docKeywords = value
                }

                "author" -> {
                    if (parent == "gpx" && value.isNotEmpty()) docAuthor = value
                }

                "email" -> {
                    issues.add(TrackIssue.Kind.FIELD_NOT_KEPT, "gpx/email")
                }

                "url" -> {
                    linksOf(parent)?.add(GpxLink(value))
                }

                "urlname" -> {
                    linksOf(parent)?.let {
                        if (it.isNotEmpty()) it[it.size - 1] = it.last().copy(text = value)
                    }
                }

                "text" -> {
                    if (parent == "link") linkText = value
                }

                "link" -> {
                    linkHref?.let { linksOf(parent)?.add(GpxLink(it, linkText, linkType)) }
                }

                "wpt", "rtept", "trkpt" -> {
                    endPoint(local)
                }

                "trkseg" -> {
                    endSegment()
                }

                "rte" -> {
                    endRoute()
                }

                "trk" -> {
                    endTrack()
                }
            }
        }

        private fun isPoint(parent: String) = parent == "wpt" || parent == "rtept"

        private fun linksOf(owner: String): MutableList<GpxLink>? =
            when (owner) {
                "gpx", "metadata" -> docLinks
                "wpt", "rtept" -> point?.links
                "rte", "trk" -> item?.links
                else -> null
            }

        private fun setTime(
            parent: String,
            value: String,
        ) {
            val millis = TrackTime.parse(value)
            if (millis == null && value.isNotEmpty()) issues.add(TrackIssue.Kind.INVALID_TIME, value)
            if (parent == "gpx" || parent == "metadata") docTime = millis else point?.time = millis
        }

        private fun setName(
            parent: String,
            value: String,
        ) {
            when (parent) {
                "gpx", "metadata" -> docName = value
                "author" -> docAuthor = value
                "wpt", "rtept" -> point?.name = value
                "rte", "trk" -> item?.name = value
            }
        }

        private fun setDesc(
            parent: String,
            value: String,
        ) {
            when (parent) {
                "gpx", "metadata" -> docDesc = value
                "wpt", "rtept" -> point?.desc = value
                "rte", "trk" -> item?.desc = value
            }
        }

        private fun setType(
            parent: String,
            value: String,
        ) {
            when (parent) {
                "wpt", "rtept" -> point?.type = value
                "rte", "trk" -> item?.type = value
                "link" -> linkType = value
            }
        }

        private fun attachExtra(raw: RawXml) {
            when (stack.last()) {
                "gpx" -> rootExtras += raw
                "metadata" -> metadataExtras += raw
                "wpt", "rtept", "trkpt" -> point?.extras?.add(raw)
                "rte", "trk" -> item?.extras?.add(raw)
            }
        }

        private fun endPoint(kind: String) {
            val p = point ?: return
            point = null
            if (!p.isValid) {
                issues.add(TrackIssue.Kind.INVALID_COORDINATE, "$kind lat=${p.lat} lon=${p.lon}")
                return
            }
            when (kind) {
                "trkpt" -> segment?.add(TrackPoint(p.lat!!, p.lon!!, p.ele, p.time, p.extras))
                "rtept" -> item?.points?.add(waypointOf(p))
                else -> waypoints += waypointOf(p)
            }
        }

        private fun waypointOf(p: PointBuilder) =
            Waypoint(
                lat = p.lat!!,
                lon = p.lon!!,
                ele = p.ele,
                timeMillis = p.time,
                name = p.name,
                cmt = p.cmt,
                desc = p.desc,
                src = p.src,
                links = p.links,
                sym = p.sym,
                type = p.type,
                extras = p.extras,
            )

        private fun endSegment() {
            val s = segment ?: return
            segment = null
            if (s.isEmpty()) issues.add(TrackIssue.Kind.EMPTY_ITEM_DROPPED, "trkseg") else item?.segments?.add(s)
        }

        private fun endRoute() {
            val b = item ?: return
            item = null
            if (b.points.isEmpty()) {
                issues.add(TrackIssue.Kind.EMPTY_ITEM_DROPPED, "rte ${b.name.orEmpty()}")
                return
            }
            routes +=
                Route(
                    b.name,
                    b.cmt,
                    b.desc,
                    b.src,
                    b.links,
                    b.number,
                    b.type,
                    b.points,
                    b.extras,
                    style = ExtensionStyle.fromExtras(b.extras),
                )
        }

        private fun endTrack() {
            val b = item ?: return
            item = null
            if (b.segments.isEmpty()) {
                issues.add(TrackIssue.Kind.EMPTY_ITEM_DROPPED, "trk ${b.name.orEmpty()}")
                return
            }
            tracks +=
                Track(
                    b.name,
                    b.cmt,
                    b.desc,
                    b.src,
                    b.links,
                    b.number,
                    b.type,
                    b.segments,
                    b.extras,
                    style = ExtensionStyle.fromExtras(b.extras),
                )
        }
    }
}
