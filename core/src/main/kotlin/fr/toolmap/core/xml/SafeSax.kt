package fr.toolmap.core.xml

import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** The document is not usable XML, or is one we refuse to read. */
class XmlReadException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Namespace-aware SAX read of untrusted XML (capabilities, GPX, KML...). External entities and DTDs are never
 * fetched; a `<!DOCTYPE ... SYSTEM "...">` line is blanked out and a DOCTYPE with an internal subset or any entity
 * declaration is refused (XXE, entity expansion). `javax.xml.parsers` exists on the JVM and on Android alike.
 */
object SafeSax {
    private const val HEADER_SCAN = 8192

    fun parse(
        bytes: ByteArray,
        handler: DefaultHandler,
    ) = parse(ByteArrayInputStream(bytes), handler)

    /** Streams [input]: only the first [HEADER_SCAN] bytes are held, so large GPX files are not copied into memory. */
    fun parse(
        input: InputStream,
        handler: DefaultHandler,
    ) {
        try {
            val head = ByteArray(HEADER_SCAN)
            var filled = 0
            while (filled < head.size) {
                val n = input.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
            val prepared = neutralizeDoctype(head.copyOf(filled))
            val stream = SequenceInputStream(ByteArrayInputStream(prepared), input)
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            // Never fetch anything the document points at.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(stream))
        } catch (e: SAXException) {
            throw XmlReadException("Not a readable XML document: ${e.message}", e)
        } catch (e: IOException) {
            throw XmlReadException("Cannot read the document: ${e.message}", e)
        }
    }

    private fun neutralizeDoctype(bytes: ByteArray): ByteArray {
        val head = String(bytes, Charsets.ISO_8859_1)
        if (head.contains("<!ENTITY", ignoreCase = true)) throw XmlReadException("Entity declarations are not allowed")
        val start = head.indexOf("<!DOCTYPE", ignoreCase = true)
        if (start < 0) return bytes
        val end = head.indexOf('>', start)
        val subset = head.indexOf('[', start)
        if (end < 0 || subset in start until end) {
            throw XmlReadException("DOCTYPE with an internal subset is not allowed")
        }
        val copy = bytes.copyOf()
        for (i in start..end) copy[i] = ' '.code.toByte()
        return copy
    }
}
