package fr.toolmap.core.wizard

import org.xml.sax.Attributes
import java.net.URLEncoder

data class WmsLayer(
    val name: String,
    val title: String,
    /** CRS codes the layer can be drawn in (own and inherited from parent layers). */
    val crs: Set<String>,
) {
    /** ToolMap asks WMS servers for Web Mercator images: the one CRS whose axis order and units never surprise. */
    val supportsWebMercator: Boolean get() = crs.any { WmtsCapabilities.isWebMercator(it) }
}

/** What a WMS server offers, reduced to what the add-a-map wizard needs. */
data class WmsCapabilities(
    val version: String,
    val title: String?,
    val formats: List<String>,
    val getMapHref: String?,
    val layers: List<WmsLayer>,
) {
    val usableLayers: List<WmsLayer> get() = layers.filter { it.supportsWebMercator }

    companion object {
        fun parse(bytes: ByteArray): WmsCapabilities {
            val handler = Handler()
            OgcXml.parse(bytes, handler)
            return handler.result()
        }
    }

    private class LayerFrame(
        val inheritedCrs: Set<String>,
    ) {
        var name = ""
        var title = ""
        val crs = LinkedHashSet<String>()
    }

    private class Handler : PathHandler() {
        private var version = ""
        private var title: String? = null
        private val formats = mutableListOf<String>()
        private var getMapHref: String? = null
        private val layers = mutableListOf<WmsLayer>()
        private val frames = ArrayList<LayerFrame>()
        private var inGetMap = false

        fun result(): WmsCapabilities {
            if (layers.isEmpty()) throw CapabilitiesException("No WMS layer found in the document")
            return WmsCapabilities(version.ifEmpty { "1.3.0" }, title, formats.toList(), getMapHref, layers.toList())
        }

        override fun start(
            name: String,
            attributes: Attributes,
        ) {
            when (name) {
                "WMS_Capabilities", "WMT_MS_Capabilities" -> {
                    version = attributes.getValue("version").orEmpty()
                }

                "GetMap" -> {
                    inGetMap = true
                }

                "OnlineResource" -> {
                    if (inGetMap && getMapHref == null) getMapHref = OgcXml.href(attributes)
                }

                "Layer" -> {
                    frames += LayerFrame(frames.lastOrNull()?.let { it.inheritedCrs + it.crs } ?: emptySet())
                }
            }
        }

        override fun end(
            name: String,
            text: String,
        ) {
            val frame = frames.lastOrNull()
            when {
                name == "GetMap" -> {
                    inGetMap = false
                }

                name == "Format" && inGetMap -> {
                    if (text.isNotEmpty()) formats += text
                }

                name == "Title" && parentIs("Service") -> {
                    title = text.ifEmpty { null }
                }

                frame != null && name == "Name" && parentIs("Layer") -> {
                    frame.name = text
                }

                frame != null && name == "Title" && parentIs("Layer") -> {
                    frame.title = text
                }

                frame != null && (name == "CRS" || name == "SRS") && parentIs("Layer") -> {
                    // WMS 1.1.1 may list several space-separated codes in one SRS element.
                    text.split(' ', '\n', '\t').filter { it.isNotEmpty() }.forEach { frame.crs += it.uppercase() }
                }

                frame != null && name == "Layer" -> {
                    if (frame.name.isNotEmpty()) {
                        layers +=
                            WmsLayer(frame.name, frame.title.ifEmpty { frame.name }, frame.inheritedCrs + frame.crs)
                    }
                    frames.removeAt(frames.size - 1)
                }
            }
        }
    }
}

/** What a WMS layer choice turns into: a `{$bbox}` URL template in Web Mercator. */
data class WmsSourceSpec(
    val template: String,
    val name: String,
    val bboxCrs: String,
)

object WmsSourceBuilder {
    /** The format to pick first: PNG (transparent) when offered, else the first one. */
    fun defaultFormat(caps: WmsCapabilities): String =
        caps.formats.firstOrNull { it.equals("image/png", ignoreCase = true) } ?: caps.formats.firstOrNull()
            ?: "image/png"

    fun build(
        caps: WmsCapabilities,
        layer: WmsLayer,
        format: String,
        capabilitiesUrl: String,
        tileSize: Int = 256,
    ): WmsSourceSpec {
        require(layer.supportsWebMercator) { "Layer ${layer.name} cannot be drawn in Web Mercator" }
        val base = caps.getMapHref ?: capabilitiesUrl
        val path = base.substringBefore('?')
        val existing = base.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val present = existing.map { it.substringBefore('=').lowercase() }.toSet()
        val crsKey = if (caps.version.startsWith("1.3")) "CRS" else "SRS"
        val wanted =
            mutableListOf(
                "SERVICE" to "WMS",
                "VERSION" to caps.version,
                "REQUEST" to "GetMap",
                "LAYERS" to encode(layer.name),
                "STYLES" to "",
                crsKey to "EPSG:3857",
                "BBOX" to "{\$bbox}",
                "WIDTH" to tileSize.toString(),
                "HEIGHT" to tileSize.toString(),
                "FORMAT" to encode(format),
            )
        if (format.equals("image/png", ignoreCase = true)) wanted += "TRANSPARENT" to "TRUE"
        val query = existing + wanted.filter { it.first.lowercase() !in present }.map { "${it.first}=${it.second}" }
        return WmsSourceSpec("$path?${query.joinToString("&")}", layer.title, "EPSG:3857")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("%2F", "/").replace("%3A", ":")
}
