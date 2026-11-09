package fr.toolmap.core.track

import fr.toolmap.core.xml.SafeSax
import fr.toolmap.core.xml.XmlReadException
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream

/**
 * Reads the part of KML that is about paths and places: `Placemark` with `Point`, `LineString`, `Polygon`
 * (outer ring), `MultiGeometry` and Google's `gx:Track`; `Folder` names, inline and shared `Style` line colours and
 * widths (also through `StyleMap`). Overlays, tours, network links and icons are ignored.
 */
object KmlReader {
    fun read(bytes: ByteArray): TrackDocument = read(bytes.inputStream())

    fun read(input: InputStream): TrackDocument {
        val handler = Handler()
        try {
            SafeSax.parse(input, handler)
        } catch (e: XmlReadException) {
            throw TrackFormatException(e.message ?: "Not a readable KML file", e)
        }
        return handler.document()
    }

    private const val MAX_TEXT = 1_000_000

    /** One `lon,lat,alt`: far more characters than any number needs. */
    private const val MAX_TUPLE = 200

    private class StyleBuilder(
        val id: String?,
    ) {
        var color: Int? = null
        var width: Float? = null

        fun toStyle(): PathStyle? =
            if (color == null && width == null) {
                null
            } else {
                PathStyle(colorArgb = color ?: PathStyle.DEFAULT_COLOR, widthDp = width ?: PathStyle.DEFAULT_WIDTH)
            }
    }

    private class PlacemarkBuilder {
        var name: String? = null
        var desc: String? = null
        var styleUrl: String? = null
        var inlineStyle: StyleBuilder? = null
        var timeMillis: Long? = null
        val points = ArrayList<TrackPoint>()
        val lines = ArrayList<List<TrackPoint>>()
        val rings = ArrayList<List<TrackPoint>>()
    }

    private class Handler : DefaultHandler() {
        private val issues = IssueLog()
        private val stack = ArrayList<String>()
        private val text = StringBuilder()
        private val folders = ArrayList<String>()
        private var docName: String? = null
        private var docDesc: String? = null

        private val waypoints = ArrayList<Waypoint>()
        private val routes = ArrayList<Route>()
        private val tracks = ArrayList<Track>()
        private val trackStyleRefs = ArrayList<Pair<String?, StyleBuilder?>>()
        private val routeStyleRefs = ArrayList<Pair<String?, StyleBuilder?>>()

        private val styles = HashMap<String, StyleBuilder>()
        private val styleMaps = HashMap<String, String>()
        private var style: StyleBuilder? = null
        private var styleMapId: String? = null
        private var pairKey: String? = null
        private var pairUrl: String? = null

        private var placemark: PlacemarkBuilder? = null
        private var gxTimes = ArrayList<Long?>()
        private var gxCoords = ArrayList<TrackPoint?>()

        // The points of the <coordinates> being read, taken as its text arrives: kept whole, the text of a long line
        // was cut at [MAX_TEXT] characters, and a track of more than about 25,000 points lost its end, without a
        // word (P36 audit). `null` outside <coordinates>.
        private var coordinates: ArrayList<TrackPoint>? = null
        private val tuple = StringBuilder()
        private var tupleTooLong = false
        private var badTuples = 0

        /** Points read so far in the whole file, against [TrackImporter.MAX_POINTS]. */
        private var pointCount = 0
        private var rootSeen = false

        fun document(): TrackDocument {
            if (!rootSeen) throw TrackFormatException("Empty document: no <kml> element")
            return TrackDocument(
                name = docName,
                description = docDesc,
                waypoints = waypoints,
                routes = routes.mapIndexed { i, r -> r.copy(style = resolve(routeStyleRefs[i])) },
                tracks = tracks.mapIndexed { i, t -> t.copy(style = resolve(trackStyleRefs[i])) },
                issues = issues.toList(),
                format = SourceFormat.KML,
            )
        }

        private fun resolve(ref: Pair<String?, StyleBuilder?>): PathStyle? {
            ref.second?.toStyle()?.let { return it }
            var url = ref.first?.removePrefix("#") ?: return null
            styleMaps[url]?.let { url = it.removePrefix("#") }
            return styles[url]?.toStyle()
        }

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes,
        ) {
            val local = localName.takeUnless { it.isNullOrEmpty() } ?: qName.orEmpty().substringAfter(':')
            if (stack.isEmpty()) {
                if (local != "kml") throw TrackFormatException("Not a KML file: root element is <$local>")
                rootSeen = true
            }
            val parent = stack.lastOrNull()
            stack += local
            text.setLength(0)
            if (local == "coordinates") {
                coordinates = ArrayList()
                tuple.setLength(0)
                tupleTooLong = false
                badTuples = 0
            }
            when (local) {
                "Folder" -> {
                    folders += ""
                }

                "Placemark" -> {
                    placemark = PlacemarkBuilder()
                }

                "Style" -> {
                    style =
                        StyleBuilder(attributes.getValue("id")).also {
                            if (parent == "Placemark") placemark?.inlineStyle = it
                        }
                }

                "StyleMap" -> {
                    styleMapId = attributes.getValue("id")
                }

                "Pair" -> {
                    pairKey = null
                    pairUrl = null
                }

                "Track", "MultiTrack" -> {
                    gxTimes = ArrayList()
                    gxCoords = ArrayList()
                }
            }
        }

        override fun characters(
            ch: CharArray,
            start: Int,
            length: Int,
        ) {
            val points = coordinates
            if (points == null) {
                if (text.length < MAX_TEXT) text.append(ch, start, length)
                return
            }
            for (i in start until start + length) {
                val c = ch[i]
                when {
                    c.isWhitespace() -> endTuple(points)
                    tuple.length < MAX_TUPLE -> tuple.append(c)
                    else -> tupleTooLong = true
                }
            }
        }

        /** The tuple that just ended, if any, becomes a point of [points]. */
        private fun endTuple(points: ArrayList<TrackPoint>) {
            if (tuple.isEmpty()) return
            val point = if (tupleTooLong) null else parseTuple(tuple.split(','))
            tuple.setLength(0)
            tupleTooLong = false
            if (point == null) badTuples++ else points += counted(point)
        }

        /** [point], counted against what one file may hold. */
        private fun counted(point: TrackPoint): TrackPoint {
            if (++pointCount > TrackImporter.MAX_POINTS) {
                throw TrackFormatException("File too large: more than ${TrackImporter.MAX_POINTS} points")
            }
            return point
        }

        override fun endElement(
            uri: String?,
            localName: String?,
            qName: String?,
        ) {
            val local = stack.last()
            val parent = stack.getOrNull(stack.size - 2).orEmpty()
            val value = text.toString().trim()
            text.setLength(0)
            end(local, parent, value)
            stack.removeAt(stack.size - 1)
        }

        private fun end(
            local: String,
            parent: String,
            value: String,
        ) {
            when (local) {
                "name" -> {
                    setName(parent, value)
                }

                "description" -> {
                    if (parent ==
                        "Placemark"
                    ) {
                        placemark?.desc = value
                    } else if (parent == "Document") {
                        docDesc = value
                    }
                }

                "styleUrl" -> {
                    if (parent ==
                        "Placemark"
                    ) {
                        placemark?.styleUrl = value
                    } else if (parent == "Pair") {
                        pairUrl = value
                    }
                }

                "key" -> {
                    if (parent == "Pair") pairKey = value
                }

                "color" -> {
                    if (parent == "LineStyle") style?.color = parseColor(value)
                }

                "width" -> {
                    if (parent == "LineStyle") style?.width = value.toFloatOrNull()
                }

                "when" -> {
                    endWhen(parent, value)
                }

                "coord" -> {
                    gxCoords += parseTuple(value.split(Regex("\\s+")))?.let(::counted)
                }

                "coordinates" -> {
                    val points = coordinates ?: ArrayList()
                    endTuple(points)
                    coordinates = null
                    endCoordinates(parent, points)
                }

                "Pair" -> {
                    if (pairKey == "normal" ||
                        (pairKey == null && pairUrl != null)
                    ) {
                        styleMapId?.let { styleMaps[it] = pairUrl.orEmpty() }
                    }
                }

                "Style" -> {
                    style?.id?.let { styles[it] = style!! }
                }

                "StyleMap" -> {
                    styleMapId = null
                }

                "Track" -> {
                    endGxTrack()
                }

                "Folder" -> {
                    folders.removeAt(folders.size - 1)
                }

                "Placemark" -> {
                    endPlacemark()
                }
            }
        }

        private fun setName(
            parent: String,
            value: String,
        ) {
            when (parent) {
                "Placemark" -> placemark?.name = value
                "Folder" -> folders[folders.size - 1] = value
                "Document" -> docName = value
            }
        }

        private fun endWhen(
            parent: String,
            value: String,
        ) {
            when (parent) {
                "TimeStamp" -> placemark?.timeMillis = TrackTime.parse(value)
                "Track" -> gxTimes += TrackTime.parse(value)
            }
        }

        private fun endCoordinates(
            parent: String,
            points: List<TrackPoint>,
        ) {
            val pm = placemark ?: return
            if (badTuples > 0) issues.add(TrackIssue.Kind.INVALID_COORDINATE, "coordinates")
            when (parent) {
                "Point" -> pm.points += points
                "LineString" -> if (points.isNotEmpty()) pm.lines += points
                "LinearRing" -> if (stack.contains("outerBoundaryIs") && points.isNotEmpty()) pm.rings += points
            }
        }

        private fun endGxTrack() {
            val pm = placemark ?: return
            val points =
                gxCoords.mapIndexedNotNull { i, p -> p?.copy(timeMillis = gxTimes.getOrNull(i)) }
            if (points.isNotEmpty()) pm.lines += points
            gxCoords = ArrayList()
            gxTimes = ArrayList()
        }

        private fun endPlacemark() {
            val pm = placemark ?: return
            placemark = null
            val folder = folders.filter { it.isNotEmpty() }.joinToString(" / ").ifEmpty { null }
            for (p in pm.points) {
                waypoints +=
                    Waypoint(
                        lat = p.lat,
                        lon = p.lon,
                        ele = p.ele,
                        timeMillis = pm.timeMillis,
                        name = pm.name,
                        desc = pm.desc,
                        folder = folder,
                    )
            }
            if (pm.lines.isNotEmpty()) {
                tracks += Track(name = pm.name, desc = pm.desc, segments = pm.lines.toList(), folder = folder)
                trackStyleRefs += pm.styleUrl to pm.inlineStyle
            }
            for (ring in pm.rings) {
                issues.add(TrackIssue.Kind.POLYGON_AS_ROUTE, pm.name.orEmpty())
                val points = ring.map { Waypoint(it.lat, it.lon, it.ele) }
                routes += Route(name = pm.name, desc = pm.desc, points = points, folder = folder)
                routeStyleRefs += pm.styleUrl to pm.inlineStyle
            }
        }

        /** KML writes `lon,lat[,alt]`; `gx:coord` writes `lon lat [alt]`. */
        private fun parseTuple(parts: List<String>): TrackPoint? {
            if (parts.size < 2) return null
            val lon = parts[0].trim().toDoubleOrNull() ?: return null
            val lat = parts[1].trim().toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val ele = parts.getOrNull(2)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
            return TrackPoint(lat, lon, ele)
        }

        /** KML colours are `aabbggrr`. */
        private fun parseColor(value: String): Int? {
            val hex = value.trim()
            if (hex.length != 8) return null
            val n = hex.toLongOrNull(16) ?: return null
            val a = (n shr 24) and 0xFF
            val b = (n shr 16) and 0xFF
            val g = (n shr 8) and 0xFF
            val r = n and 0xFF
            return ((a shl 24) or (r shl 16) or (g shl 8) or b).toInt()
        }
    }
}
