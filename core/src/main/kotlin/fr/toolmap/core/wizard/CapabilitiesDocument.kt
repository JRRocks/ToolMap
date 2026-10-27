package fr.toolmap.core.wizard

import org.xml.sax.Attributes

/** Which OGC tile service a capabilities document describes. */
enum class OgcService(
    val keyword: String,
) {
    WMTS("WMTS"),
    WMS("WMS"),
}

/** A capabilities document read from a server: the wizard shows its layers and builds a URL from the choice. */
sealed interface CapabilitiesDocument {
    data class Wmts(
        val capabilities: WmtsCapabilities,
    ) : CapabilitiesDocument

    data class Wms(
        val capabilities: WmsCapabilities,
    ) : CapabilitiesDocument
}

object CapabilitiesReader {
    private val ROOT = Regex("""<\s*(?:[A-Za-z0-9_.-]+:)?([A-Za-z_][A-Za-z0-9_.-]*)""")
    private const val HEAD = 4096

    /**
     * Reads a WMTS or WMS capabilities document, whichever the root element says.
     * @throws CapabilitiesException when it is neither; a server's own error report (`ExceptionReport`) is turned
     * into the exception message so the user sees why the request was refused.
     */
    fun read(bytes: ByteArray): CapabilitiesDocument =
        when (val root = rootName(bytes)) {
            "Capabilities" -> CapabilitiesDocument.Wmts(WmtsCapabilities.parse(bytes))
            "WMS_Capabilities", "WMT_MS_Capabilities" -> CapabilitiesDocument.Wms(WmsCapabilities.parse(bytes))
            "ExceptionReport", "ServiceExceptionReport" -> throw CapabilitiesException(serviceError(bytes))
            null -> throw CapabilitiesException("The answer is not an XML document")
            else -> throw CapabilitiesException("Not a WMTS or WMS capabilities document (root element <$root>)")
        }

    /** Name (without prefix) of the first element, skipping the XML declaration, comments and DOCTYPE. */
    internal fun rootName(bytes: ByteArray): String? {
        var head = String(bytes, 0, minOf(bytes.size, HEAD), Charsets.UTF_8)
        head = head.replace(Regex("""<\?.*?\?>""", RegexOption.DOT_MATCHES_ALL), " ")
        head = head.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        head = head.replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), " ")
        return ROOT.find(head)?.groupValues?.get(1)
    }

    private fun serviceError(bytes: ByteArray): String {
        val texts = mutableListOf<String>()
        val handler =
            object : PathHandler() {
                override fun start(
                    name: String,
                    attributes: Attributes,
                ) = Unit

                override fun end(
                    name: String,
                    text: String,
                ) {
                    if ((name == "ExceptionText" || name == "ServiceException") && text.isNotEmpty()) texts += text
                }
            }
        runCatching { OgcXml.parse(bytes, handler) }
        return texts.joinToString(" / ").ifEmpty { "The server answered with an error report" }.take(MAX_ERROR_TEXT)
    }

    private const val MAX_ERROR_TEXT = 300
}

/** Why an address typed by the user cannot be used to read capabilities. */
enum class CapabilitiesUrlProblem {
    EMPTY,
    NOT_HTTP,

    /** It is already a tile (`GetTile`) or map (`GetMap`) request, or a `{$z}` template: use it as a plain URL. */
    TILE_REQUEST,
}

class CapabilitiesUrlException(
    val problem: CapabilitiesUrlProblem,
) : IllegalArgumentException(problem.name)

object CapabilitiesUrl {
    /**
     * The address to download the capabilities from. A bare service address (`https://host/wmts`) gets
     * `SERVICE=WMTS&REQUEST=GetCapabilities` appended after its own parameters (an `apikey=` stays); an address that
     * already asks for `GetCapabilities`, or points at a `.xml` document (REST WMTS), is kept as typed.
     */
    fun normalize(
        input: String,
        service: OgcService,
    ): String {
        val text = input.trim()
        if (text.isEmpty()) throw CapabilitiesUrlException(CapabilitiesUrlProblem.EMPTY)
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) {
            throw CapabilitiesUrlException(CapabilitiesUrlProblem.NOT_HTTP)
        }
        val withoutFragment = text.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val pairs = withoutFragment.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        val request = pairs.firstOrNull { it.substringBefore('=').equals("request", ignoreCase = true) }
        val requestValue = request?.substringAfter('=', "")
        if (withoutFragment.contains("{\$") ||
            requestValue.equals("GetTile", ignoreCase = true) ||
            requestValue.equals("GetMap", ignoreCase = true)
        ) {
            throw CapabilitiesUrlException(CapabilitiesUrlProblem.TILE_REQUEST)
        }
        if (requestValue != null || path.endsWith(".xml", ignoreCase = true)) return withoutFragment
        val present = pairs.map { it.substringBefore('=').lowercase() }.toSet()
        val added =
            buildList {
                if ("service" !in present) add("SERVICE=${service.keyword}")
                add("REQUEST=GetCapabilities")
                if (service == OgcService.WMS && "version" !in present) add("VERSION=1.3.0")
            }
        return "$path?${(pairs + added).joinToString("&")}"
    }
}

/** Recognises the image formats a tile server can answer with, from the first bytes. */
object ImageSniffer {
    enum class Format(
        val mime: String,
    ) {
        PNG("image/png"),
        JPEG("image/jpeg"),
        WEBP("image/webp"),
        GIF("image/gif"),
    }

    fun formatOf(bytes: ByteArray): Format? =
        when {
            bytes.size >= 8 && bytes.hasPrefix(0x89, 0x50, 0x4E, 0x47) -> {
                Format.PNG
            }

            bytes.size >= 3 && bytes.hasPrefix(0xFF, 0xD8, 0xFF) -> {
                Format.JPEG
            }

            bytes.size >= 12 &&
                bytes.hasPrefix(
                    0x52,
                    0x49,
                    0x46,
                    0x46,
                ) && bytes.hasBytesAt(8, 0x57, 0x45, 0x42, 0x50) -> {
                Format.WEBP
            }

            bytes.size >= 6 && bytes.hasPrefix(0x47, 0x49, 0x46, 0x38) -> {
                Format.GIF
            }

            else -> {
                null
            }
        }

    private fun ByteArray.hasPrefix(vararg prefix: Int): Boolean = hasBytesAt(0, *prefix)

    private fun ByteArray.hasBytesAt(
        offset: Int,
        vararg expected: Int,
    ): Boolean = expected.indices.all { this[offset + it] == expected[it].toByte() }
}
