package fr.toolmap.core.wizard

import fr.toolmap.core.xml.SafeSax
import fr.toolmap.core.xml.XmlReadException
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** The document is not usable XML, or is one we refuse to read. */
class CapabilitiesException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Streaming read of OGC capabilities documents (WMTS, WMS): they can be several megabytes (the IGN WMTS one is
 * 3 MB for 720 layers), so a SAX parser keeps only the few fields we need instead of a whole DOM.
 */
internal object OgcXml {
    /** XLink namespace, for `xlink:href`. */
    const val XLINK = "http://www.w3.org/1999/xlink"

    fun parse(
        bytes: ByteArray,
        handler: DefaultHandler,
    ) {
        try {
            SafeSax.parse(bytes, handler)
        } catch (e: XmlReadException) {
            throw CapabilitiesException(e.message ?: "Not a readable XML document", e)
        }
    }

    /** `xlink:href` of an element, whichever way the document declares the namespace. */
    fun href(attributes: Attributes): String? = attributes.getValue(XLINK, "href") ?: attributes.getValue("xlink:href")
}

/** Keeps track of the element path while a SAX parse runs, and collects character data of the current element. */
internal abstract class PathHandler : DefaultHandler() {
    protected val path = ArrayList<String>()
    private val text = StringBuilder()

    /** Name of the element [up] levels above the current one (0 = current). */
    protected fun ancestor(up: Int): String? = path.getOrNull(path.size - 1 - up)

    protected fun parentIs(name: String): Boolean = ancestor(1) == name

    protected abstract fun start(
        name: String,
        attributes: Attributes,
    )

    protected abstract fun end(
        name: String,
        text: String,
    )

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: Attributes,
    ) {
        val name = localName.takeUnless { it.isNullOrEmpty() } ?: qName.orEmpty().substringAfter(':')
        path += name
        text.setLength(0)
        start(name, attributes)
    }

    override fun characters(
        ch: CharArray,
        start: Int,
        length: Int,
    ) {
        if (text.length < MAX_TEXT) text.append(ch, start, length)
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        val name = path.last()
        end(name, text.toString().trim())
        text.setLength(0)
        path.removeAt(path.size - 1)
    }

    private companion object {
        const val MAX_TEXT = 20_000
    }
}
