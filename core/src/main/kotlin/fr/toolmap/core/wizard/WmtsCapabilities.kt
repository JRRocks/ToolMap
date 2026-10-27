package fr.toolmap.core.wizard

import org.xml.sax.Attributes
import java.net.URLEncoder

data class WmtsStyle(
    val id: String,
    val isDefault: Boolean,
)

/** A layer's link to a tile matrix set, with the zooms the layer actually has in it (from its limits, if given). */
data class WmtsMatrixLink(
    val matrixSetId: String,
    val minZoom: Int?,
    val maxZoom: Int?,
)

data class WmtsResourceUrl(
    val format: String,
    val resourceType: String,
    val template: String,
)

data class WmtsLayer(
    val id: String,
    val title: String,
    val formats: List<String>,
    val styles: List<WmtsStyle>,
    val links: List<WmtsMatrixLink>,
    val resourceUrls: List<WmtsResourceUrl>,
)

data class WmtsMatrixSet(
    val id: String,
    val crs: String,
    val tileSize: Int,
    /** Zooms read from the numeric matrix identifiers (`0`..`19`), or `null` when they are not numeric. */
    val zooms: IntRange?,
) {
    val isWebMercator: Boolean get() = WmtsCapabilities.isWebMercator(crs)
}

/** What a WMTS server offers, reduced to what the add-a-map wizard needs. */
data class WmtsCapabilities(
    val title: String?,
    val provider: String?,
    /** Base URL of GetTile requests (KVP), when the server declares one. */
    val getTileHref: String?,
    val layers: List<WmtsLayer>,
    val matrixSets: Map<String, WmtsMatrixSet>,
) {
    /** Links of [layer] to matrix sets ToolMap can display (Web Mercator). */
    fun usableLinks(layer: WmtsLayer): List<WmtsMatrixLink> =
        layer.links.filter { matrixSets[it.matrixSetId]?.isWebMercator == true }

    /** Layers that have at least one usable matrix set. */
    val usableLayers: List<WmtsLayer> get() = layers.filter { usableLinks(it).isNotEmpty() }

    companion object {
        private val MERCATOR_CODES = listOf("3857", "900913", "3785", "102100", "102113")

        fun isWebMercator(crs: String): Boolean = MERCATOR_CODES.any { crs.endsWith(it) }

        fun parse(bytes: ByteArray): WmtsCapabilities {
            val handler = Handler()
            OgcXml.parse(bytes, handler)
            return handler.result()
        }

        /** The zoom a matrix identifier stands for: `12`, `EPSG:3857:12`, `PM_12` -> 12. */
        internal fun zoomOf(identifier: String): Int? =
            identifier.toIntOrNull() ?: identifier.substringAfterLast(':').substringAfterLast('_').toIntOrNull()
    }

    private class LayerBuilder {
        var id = ""
        var title = ""
        val formats = mutableListOf<String>()
        val styles = mutableListOf<WmtsStyle>()
        val links = mutableListOf<WmtsMatrixLink>()
        val resourceUrls = mutableListOf<WmtsResourceUrl>()
        var styleId = ""
        var styleDefault = false
        var linkId = ""
        val linkZooms = mutableListOf<Int>()
        var limitsSeen = false

        fun build() =
            WmtsLayer(
                id,
                title.ifEmpty {
                    id
                },
                formats.toList(),
                styles.toList(),
                links.toList(),
                resourceUrls.toList(),
            )
    }

    private class SetBuilder {
        var id = ""
        var crs = ""
        var tileSize = 0
        val zooms = mutableListOf<Int?>()
        var matrixId = ""

        fun build() =
            WmtsMatrixSet(
                id,
                crs,
                tileSize.takeIf { it > 0 } ?: DEFAULT_TILE_SIZE,
                zooms.takeIf { z ->
                    z.isNotEmpty() && z.all { it != null }
                }?.filterNotNull()?.let { it.min()..it.max() },
            )
    }

    private class Handler : PathHandler() {
        private var title: String? = null
        private var provider: String? = null
        private var getTileHref: String? = null
        private var operation: String? = null
        private val layers = mutableListOf<WmtsLayer>()
        private val sets = LinkedHashMap<String, WmtsMatrixSet>()
        private var layer: LayerBuilder? = null
        private var set: SetBuilder? = null

        fun result(): WmtsCapabilities {
            if (layers.isEmpty() && sets.isEmpty()) throw CapabilitiesException("No WMTS layer found in the document")
            return WmtsCapabilities(title, provider, getTileHref, layers.toList(), sets)
        }

        override fun start(
            name: String,
            attributes: Attributes,
        ) {
            when (name) {
                "Operation" -> {
                    operation = attributes.getValue("name")
                }

                "Get" -> {
                    if (operation == "GetTile" && getTileHref == null) getTileHref = OgcXml.href(attributes)
                }

                "Layer" -> {
                    if (parentIs("Contents")) layer = LayerBuilder()
                }

                "Style" -> {
                    layer?.let { it.styleDefault = attributes.getValue("isDefault") == "true" }
                }

                "TileMatrixSet" -> {
                    if (parentIs("Contents")) set = SetBuilder()
                }

                "TileMatrixSetLink" -> {
                    layer?.let {
                        it.linkId = ""
                        it.linkZooms.clear()
                    }
                }

                "ResourceURL" -> {
                    layer?.resourceUrls?.add(
                        WmtsResourceUrl(
                            attributes.getValue("format").orEmpty(),
                            attributes.getValue("resourceType").orEmpty(),
                            attributes.getValue("template").orEmpty(),
                        ),
                    )
                }
            }
        }

        override fun end(
            name: String,
            text: String,
        ) {
            val l = layer
            val s = set
            when {
                name == "Operation" -> {
                    operation = null
                }

                name == "Title" && parentIs("ServiceIdentification") -> {
                    title = text.ifEmpty { null }
                }

                name == "ProviderName" -> {
                    provider = text.ifEmpty { null }
                }

                l != null -> {
                    endInLayer(l, name, text)
                }

                s != null -> {
                    endInSet(s, name, text)
                }
            }
        }

        private fun endInLayer(
            l: LayerBuilder,
            name: String,
            text: String,
        ) {
            when {
                name == "Identifier" && parentIs("Layer") -> {
                    l.id = text
                }

                name == "Title" && parentIs("Layer") -> {
                    l.title = text
                }

                name == "Format" && parentIs("Layer") -> {
                    if (text.isNotEmpty()) l.formats += text
                }

                name == "Identifier" && parentIs("Style") -> {
                    l.styleId = text
                }

                name == "Style" -> {
                    if (l.styleId.isNotEmpty()) {
                        l.styles +=
                            WmtsStyle(l.styleId, l.styleDefault).also { l.styleId = "" }
                    }
                }

                name == "TileMatrixSet" && parentIs("TileMatrixSetLink") -> {
                    l.linkId = text
                }

                name == "TileMatrix" && parentIs("TileMatrixLimits") -> {
                    WmtsCapabilities.zoomOf(text)?.let {
                        l.linkZooms +=
                            it
                    }
                }

                name == "TileMatrixSetLink" -> {
                    if (l.linkId.isNotEmpty()) {
                        l.links += WmtsMatrixLink(l.linkId, l.linkZooms.minOrNull(), l.linkZooms.maxOrNull())
                    }
                }

                name == "Layer" && parentIs("Contents") -> {
                    if (l.id.isNotEmpty()) layers += l.build()
                    layer = null
                }
            }
        }

        private fun endInSet(
            s: SetBuilder,
            name: String,
            text: String,
        ) {
            when {
                name == "Identifier" && parentIs("TileMatrixSet") -> {
                    s.id = text
                }

                name == "SupportedCRS" -> {
                    s.crs = text
                }

                name == "Identifier" && parentIs("TileMatrix") -> {
                    s.zooms += WmtsCapabilities.zoomOf(text)
                }

                name == "TileWidth" -> {
                    if (s.tileSize == 0) s.tileSize = text.toIntOrNull() ?: 0
                }

                name == "TileMatrixSet" && parentIs("Contents") -> {
                    if (s.id.isNotEmpty()) sets[s.id] = s.build()
                    set = null
                }
            }
        }
    }
}

private const val DEFAULT_TILE_SIZE = 256

/** The user's choices among what a [WmtsCapabilities] offers. */
data class WmtsChoice(
    val layerId: String,
    val styleId: String,
    val matrixSetId: String,
    val format: String,
)

/** What a [WmtsChoice] turns into: a URL template and the settings that go with it. */
data class WmtsSourceSpec(
    val template: String,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int,
    val name: String,
    val copyright: String?,
    /** True when the template came from a REST `ResourceURL` rather than a KVP query. */
    val rest: Boolean,
)

object WmtsSourceBuilder {
    /** A sensible starting choice for [layer]: default style, a Web Mercator set (`PM` first), first format. */
    fun defaultChoice(
        caps: WmtsCapabilities,
        layer: WmtsLayer,
    ): WmtsChoice? {
        val links = caps.usableLinks(layer)
        val link =
            links.firstOrNull { it.matrixSetId == "PM" }
                ?: links.firstOrNull { it.matrixSetId == "GoogleMapsCompatible" }
                ?: links.firstOrNull()
                ?: return null
        val style = layer.styles.firstOrNull { it.isDefault } ?: layer.styles.firstOrNull()
        return WmtsChoice(layer.id, style?.id.orEmpty(), link.matrixSetId, layer.formats.firstOrNull() ?: "image/png")
    }

    /**
     * Builds the URL template for [choice]. [capabilitiesUrl] is the address the document was read from; it is the
     * fallback base for KVP requests when the document does not declare one.
     */
    fun build(
        caps: WmtsCapabilities,
        choice: WmtsChoice,
        capabilitiesUrl: String,
    ): WmtsSourceSpec {
        val layer =
            caps.layers.firstOrNull { it.id == choice.layerId }
                ?: throw IllegalArgumentException("Unknown layer ${choice.layerId}")
        val set =
            caps.matrixSets[choice.matrixSetId]
                ?: throw IllegalArgumentException("Unknown matrix set ${choice.matrixSetId}")
        require(set.isWebMercator) { "Matrix set ${set.id} is not Web Mercator (${set.crs})" }
        val link = layer.links.firstOrNull { it.matrixSetId == set.id }
        val setZooms = set.zooms
        val minZoom = maxOf(link?.minZoom ?: setZooms?.first ?: 0, setZooms?.first ?: 0)
        val maxZoom = minOf(link?.maxZoom ?: setZooms?.last ?: DEFAULT_MAX_ZOOM, setZooms?.last ?: DEFAULT_MAX_ZOOM)

        val rest = layer.resourceUrls.firstOrNull { it.resourceType == "tile" && it.format == choice.format }
        val template =
            if (rest != null) {
                restTemplate(rest.template, choice)
            } else {
                kvpTemplate(caps.getTileHref ?: capabilitiesUrl, choice)
            }
        return WmtsSourceSpec(
            template,
            minZoom,
            maxOf(minZoom, maxZoom),
            set.tileSize,
            layer.title,
            caps.provider,
            rest != null,
        )
    }

    private fun restTemplate(
        template: String,
        choice: WmtsChoice,
    ): String =
        template
            .replace("{Layer}", choice.layerId, ignoreCase = true)
            .replace("{Style}", choice.styleId, ignoreCase = true)
            .replace("{TileMatrixSet}", choice.matrixSetId, ignoreCase = true)
            .replace("{TileMatrix}", "{\$z}", ignoreCase = true)
            .replace("{TileRow}", "{\$y}", ignoreCase = true)
            .replace("{TileCol}", "{\$x}", ignoreCase = true)

    private fun kvpTemplate(
        base: String,
        choice: WmtsChoice,
    ): String {
        val path = base.substringBefore('?')
        val existing = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val present = existing.map { it.substringBefore('=').lowercase() }.toSet()
        val wanted =
            listOf(
                "SERVICE" to "WMTS",
                "REQUEST" to "GetTile",
                "VERSION" to "1.0.0",
                "LAYER" to encode(choice.layerId),
                "STYLE" to encode(choice.styleId),
                "TILEMATRIXSET" to encode(choice.matrixSetId),
                "TILEMATRIX" to "{\$z}",
                "TILEROW" to "{\$y}",
                "TILECOL" to "{\$x}",
                "FORMAT" to encode(choice.format),
            )
        val query = existing + wanted.filter { it.first.lowercase() !in present }.map { "${it.first}=${it.second}" }
        return "$path?${query.joinToString("&")}"
    }

    // Keeps '/', ':' and '.' readable (image/png, EPSG:3857, GEOGRAPHICALGRIDSYSTEMS.MAPS): servers accept both.
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("%2F", "/").replace("%3A", ":")

    private const val DEFAULT_MAX_ZOOM = 19
}
