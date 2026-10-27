package fr.toolmap.core.aqx

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.ClearColor
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for AlpineQuest `.aqx` map definition files (see `docs/specs/aqx-format.md`).
 *
 * Tolerant by design: unknown elements are reported as [AqxWarning]s, never as failures. Only a file
 * that is not XML, has the wrong root, or declares a DOCTYPE/entities (XXE) is rejected.
 */
object AqxParser {
    const val MAX_FILE_CHARS = 5_000_000

    /** Simultaneous downloads a file may ask for per source (the wizard offers the same range, ADR-058). */
    const val MAX_THREADS = 16

    private val projectionTags =
        setOf(
            "projection-name",
            "projection-geoid",
            "projection-true-origin-x",
            "projection-true-origin-y",
            "projection-false-origin-x",
            "projection-false-origin-y",
            "projection-resolution",
            "projection-factor-x",
            "projection-factor-y",
            "projection-scale-denominator",
            "tiles-bounds",
            "import-crs-txt-def",
        )
    private val webMercatorCodes = setOf("EPSG:3857", "EPSG:900913", "EPSG:3785", "EPSG:102100", "EPSG:102113")
    private val zoomLevelTags = setOf("level", "zoom-levels", "zoom-level")
    private val webMercatorWms =
        Regex("[?&](crs|srs)=(epsg:)?(3857|900913|102100|102113)(&|$)", RegexOption.IGNORE_CASE)

    // Built from the code point on purpose: an invisible literal is easy to lose when a file is edited
    // (the reference fixture is UTF-8 with a BOM, and the JVM UTF-8 decoder keeps it).
    private val BYTE_ORDER_MARK = 0xFEFF.toChar().toString()

    @Throws(AqxParseException::class)
    fun parse(text: String): AqxParseResult {
        if (text.length > MAX_FILE_CHARS) throw AqxParseException("AQX file too large (${text.length} characters)")
        // Entities and DOCTYPEs are never needed by AQX: refuse them outright rather than rely on parser flags
        // (not every Android XML parser supports every hardening feature).
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) {
            throw AqxParseException("DOCTYPE and entity declarations are not allowed in AQX files")
        }
        val root =
            try {
                newDocumentBuilderFactory().newDocumentBuilder()
                    .parse(InputSource(StringReader(text.removePrefix(BYTE_ORDER_MARK))))
                    .documentElement
            } catch (e: SAXException) {
                throw AqxParseException("Invalid XML: ${e.message}", e)
            }
        if (!root.tagName.equals("aqx", ignoreCase = true)) {
            throw AqxParseException("Root element is <${root.tagName}>, expected <aqx>")
        }
        return Reader().read(root)
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        factory.isCoalescing = true // CDATA and text are merged
        factory.isIgnoringComments = true
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory
    }

    private class Reader {
        private val warnings = mutableListOf<AqxWarning>()

        fun read(root: Element): AqxParseResult {
            var name: String? = null
            var description: String? = null
            val params = linkedMapOf<String, String>()
            val sourceElements = mutableListOf<Element>()
            for (child in root.elements()) {
                when (child.tagName.lowercase()) {
                    "name" -> name = child.text()
                    "description" -> description = child.text()
                    "param" -> readParam(child, params, "file")
                    "source" -> sourceElements += child
                    else -> warn("file", "unknown element <${child.tagName}> ignored")
                }
            }
            val sources = mutableListOf<TileSource>()
            for (element in sourceElements) {
                val source = readSource(element, params) ?: continue
                if (sources.any { it.id == source.id }) {
                    warn("source ${source.id}", "duplicate source id: later definition ignored")
                } else {
                    sources += source
                }
            }
            val file = AqxFile(root.getAttribute("version").trim().toIntOrNull(), name, description, params, sources)
            return AqxParseResult(file, warnings)
        }

        private fun readParam(
            element: Element,
            into: MutableMap<String, String>,
            context: String,
        ) {
            val key = element.getAttribute("name").trim()
            if (key.isEmpty()) {
                warn(context, "<param> without a name ignored")
                return
            }
            val value = element.getAttribute("value").ifEmpty { element.text() }
            into[key] = value
        }

        private fun readSource(
            element: Element,
            fileParams: Map<String, String>,
        ): TileSource? {
            val id = element.getAttribute("id").trim()
            if (id.isEmpty()) {
                warn("file", "<source> without an id ignored")
                return null
            }
            val context = "source $id"
            var name: String? = null
            var type: String? = element.getAttribute("type").trim().lowercase().ifEmpty { null }
            var copyright: String? = null
            var dataSource: String? = null
            var region: String? = null
            var outline: List<LonLat> = emptyList()
            var preview: LonLatZoom? = null
            var default: LonLat? = null
            val params = LinkedHashMap(fileParams)
            val levels = mutableListOf<LevelDef>()
            val unsupported = mutableListOf<String>()

            for (child in element.elements()) {
                val tag = child.tagName.lowercase()
                when {
                    tag == "name" -> name = child.text()
                    tag == "type" -> type = child.text().lowercase().ifEmpty { null }
                    tag == "copyright" -> copyright = child.text().ifEmpty { null }
                    tag == "data-source" -> dataSource = child.text().ifEmpty { null }
                    tag == "region" -> region = child.text().ifEmpty { null }
                    tag == "outline" -> outline = parseOutline(child.text(), context)
                    tag == "preview-location" -> preview = parsePreview(child.text(), context)
                    tag == "default-location" -> default = parseLonLat(child.text(), context)
                    tag == "param" -> readParam(child, params, context)
                    tag in zoomLevelTags -> readLevel(child, context, unsupported)?.let { levels += it }
                    else -> warn(context, "unknown element <${child.tagName}> ignored")
                }
            }
            if (levels.isEmpty()) unsupported += "no zoom level definition"
            return TileSource(
                id = id,
                name = name?.ifEmpty { null } ?: id,
                type = type,
                isLayer = element.getAttribute("layer").trim().equals("true", ignoreCase = true),
                opacity = element.getAttribute("opacity").trim().toIntOrNull()?.coerceIn(0, 100) ?: 100,
                copyright = copyright,
                dataSource = dataSource,
                region = region,
                outline = outline,
                previewLocation = preview,
                defaultLocation = default,
                params = params,
                levels = levels,
                unsupportedReason = unsupported.distinct().joinToString("; ").ifEmpty { null },
            )
        }

        private fun readLevel(
            element: Element,
            context: String,
            unsupported: MutableList<String>,
        ): LevelDef? {
            var zoomSpec: String? = element.getAttribute("z").trim().ifEmpty { null }
            val servers = mutableListOf<String>()
            val headers = mutableListOf<Pair<String, String>>()
            var referer: String? = null
            var userAgent: String? = null
            var cookie: String? = null
            var updateDelay: String? = null
            var maxThreads = LevelDef.DEFAULT_MAX_THREADS
            var tileSize = LevelDef.DEFAULT_TILE_SIZE
            var tileSizeX: Int? = null
            var tileSizeY: Int? = null
            var clearColor: ClearColor? = null
            var keyUrl: String? = null
            var bboxCrs = LevelDef.CRS_WGS84
            var bboxCrsWritten = false

            // Some files (OpenSnowMap) wrap the servers and their HTTP settings in <servers>: read them as if inline.
            val children =
                element.elements().flatMap {
                    if (it.tagName.equals("servers", ignoreCase = true)) it.elements() else listOf(it)
                }
            for (child in children) {
                val tag = child.tagName.lowercase()
                when {
                    tag == "zoom-values" || tag == "zoom-levels" -> {
                        zoomSpec = child.text()
                    }

                    tag == "server" -> {
                        child.text().takeIf { it.isNotEmpty() }?.let { servers += it }
                    }

                    tag == "referer" -> {
                        referer = child.text().ifEmpty { null }
                    }

                    tag == "user-agent" -> {
                        userAgent = child.text().ifEmpty { null }
                    }

                    tag == "cookie" -> {
                        cookie = child.text().ifEmpty { null }
                    }

                    tag == "header" -> {
                        parseHeader(child.text(), context)?.let { headers += it }
                    }

                    tag == "update-delay" -> {
                        updateDelay = child.text().ifEmpty { null }
                    }

                    tag == "max-threads" -> {
                        maxThreads = (child.text().toIntOrNull() ?: maxThreads).coerceIn(1, MAX_THREADS)
                    }

                    tag == "tiles-size" -> {
                        tileSize = child.text().toIntOrNull() ?: tileSize
                    }

                    tag == "tiles-size-x" -> {
                        tileSizeX = child.text().toIntOrNull()
                    }

                    tag == "tiles-size-y" -> {
                        tileSizeY = child.text().toIntOrNull()
                    }

                    tag == "clear-color" -> {
                        clearColor = parseClearColor(child, context)
                    }

                    tag == "key-url" -> {
                        keyUrl = child.text().ifEmpty { null }
                    }

                    tag == "bbox-crs-code" -> {
                        bboxCrsWritten = true
                        bboxCrs = normalizeCrs(child.text())
                        if (bboxCrs != LevelDef.CRS_WGS84 && bboxCrs != LevelDef.CRS_WEB_MERCATOR) {
                            unsupported += "unsupported {\$bbox} CRS $bboxCrs"
                        }
                    }

                    tag == "projection-code" -> {
                        val code = normalizeCrs(child.text())
                        if (code !in webMercatorCodes && code != "") unsupported += "unsupported projection $code"
                    }

                    tag in projectionTags -> {
                        unsupported += "custom projection (<${child.tagName}>)"
                    }

                    tag == "variable" || tag == "expression" -> {
                        warn(context, "<${child.tagName}> read but not evaluated: URLs using it may not work")
                    }

                    else -> {
                        warn(context, "unknown element <${child.tagName}> in level ignored")
                    }
                }
            }
            if (tileSizeX != null || tileSizeY != null) {
                val x = tileSizeX ?: tileSize
                val y = tileSizeY ?: tileSize
                if (x == y) {
                    tileSize = x
                } else {
                    unsupported += "non-square tiles (${x}x$y)"
                }
            }
            val zooms =
                try {
                    ZoomSet.parse(zoomSpec ?: throw IllegalArgumentException("missing zoom levels"))
                } catch (e: IllegalArgumentException) {
                    warn(context, "level ignored: ${e.message}")
                    return null
                }
            if (servers.isEmpty()) {
                warn(context, "level ${zooms.toSpec()} ignored: no <server> URL")
                return null
            }
            if (!bboxCrsWritten && servers.any { it.contains("bbox}") && webMercatorWms.containsMatchIn(it) }) {
                // A WMS URL asking for CRS=EPSG:3857 without <bbox-crs-code>: its {$bbox} must be in metres too.
                bboxCrs = LevelDef.CRS_WEB_MERCATOR
            }
            return LevelDef(
                zooms = zooms,
                servers = servers,
                referer = referer,
                userAgent = userAgent,
                headers = headers,
                cookie = cookie,
                updateDelay = updateDelay,
                maxThreads = maxThreads,
                insecure = element.getAttribute("insecure").trim().equals("true", ignoreCase = true),
                tileSize = tileSize,
                clearColor = clearColor,
                keyUrl = keyUrl,
                bboxCrs = bboxCrs,
            )
        }

        private fun parseHeader(
            text: String,
            context: String,
        ): Pair<String, String>? {
            val separator = text.indexOf(':')
            if (separator <= 0) {
                warn(context, "malformed <header> '$text' ignored (expected 'Name: value')")
                return null
            }
            return text.substring(0, separator).trim() to text.substring(separator + 1).trim()
        }

        private fun parseClearColor(
            element: Element,
            context: String,
        ): ClearColor? {
            fun channel(name: String): IntRange? {
                val raw = element.getAttribute(name).trim()
                if (raw.isEmpty()) return 0..255
                val parts = raw.split('-').map { it.trim().toIntOrNull() }
                val from = parts.firstOrNull()
                val to = if (parts.size == 2) parts[1] else from
                return if (parts.size <= 2 && from != null && to != null) from..to else null
            }
            val r = channel("r")
            val g = channel("g")
            val b = channel("b")
            if (r == null || g == null || b == null) {
                warn(context, "malformed <clear-color> ignored")
                return null
            }
            return ClearColor(r, g, b)
        }

        private fun normalizeCrs(raw: String): String {
            val text = raw.trim().uppercase()
            return when {
                text.isEmpty() -> ""
                text.startsWith("EPSG:") -> text
                text.all { it.isDigit() } -> "EPSG:$text"
                else -> text
            }
        }

        private fun parseLonLat(
            text: String,
            context: String,
        ): LonLat? {
            val parts = text.split(',').map { it.trim().toDoubleOrNull() }
            if (parts.size < 2 || parts[0] == null || parts[1] == null) {
                warn(context, "malformed coordinates '$text' ignored")
                return null
            }
            return LonLat(parts[0]!!, parts[1]!!)
        }

        private fun parsePreview(
            text: String,
            context: String,
        ): LonLatZoom? {
            val parts = text.split(',').map { it.trim() }
            val lon = parts.getOrNull(0)?.toDoubleOrNull()
            val lat = parts.getOrNull(1)?.toDoubleOrNull()
            val zoom = parts.getOrNull(2)?.toDoubleOrNull()?.toInt()
            if (lon == null || lat == null || zoom == null) {
                warn(context, "malformed <preview-location> '$text' ignored (expected lon,lat,zoom)")
                return null
            }
            return LonLatZoom(lon, lat, zoom)
        }

        private fun parseOutline(
            text: String,
            context: String,
        ): List<LonLat> {
            val points = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { parseLonLat(it, context) }
            return if (points.any { it == null }) emptyList() else points.filterNotNull()
        }

        private fun warn(
            context: String,
            message: String,
        ) {
            warnings += AqxWarning(context, message)
        }

        private fun Element.text(): String = textContent.trim()

        private fun Element.elements(): List<Element> {
            val result = mutableListOf<Element>()
            var node: Node? = firstChild
            while (node != null) {
                if (node is Element) result += node
                node = node.nextSibling
            }
            return result
        }
    }
}
