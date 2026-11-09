package fr.toolmap.core.xml

import fr.toolmap.core.problem.catchExpected
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.LexicalHandler
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.StringReader
import javax.xml.parsers.ParserConfigurationException
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
 *
 * The refusal happens before the parser reads a byte: the whole prolog (declaration, comments, processing
 * instructions) is scanned up to the root element, the only place where a DOCTYPE may stand. A prolog longer than
 * [HEADER_SCAN], and an encoding whose bytes are not those of ASCII (UTF-16, UTF-32, EBCDIC), are refused: the scan
 * would not see a DOCTYPE in them (P36 review: 9 KB of comment, or UTF-16, hid an entity bomb from the 8 KB scan).
 */
object SafeSax {
    private const val HEADER_SCAN = 64 * 1024
    private const val LEXICAL_HANDLER = "http://xml.org/sax/properties/lexical-handler"

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
            val prepared = neutralizeDoctype(head.copyOf(filled), wholeDocument = filled < head.size)
            val stream = SequenceInputStream(ByteArrayInputStream(prepared), input)
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            // Not every parser knows every feature (Android's does not know the Apache one): the DOCTYPE check
            // above and the entity resolver below guard the read without them.
            catchExpected(
                "xml.sax.feature_general_entities",
                SAXException::class,
                ParserConfigurationException::class,
            ) {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            catchExpected(
                "xml.sax.feature_parameter_entities",
                SAXException::class,
                ParserConfigurationException::class,
            ) {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            catchExpected(
                "xml.sax.feature_external_dtd",
                SAXException::class,
                ParserConfigurationException::class,
            ) {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            val reader = factory.newSAXParser().xmlReader
            reader.contentHandler = handler
            // A second guard, in the parser itself: any DOCTYPE the scan above let through stops the read.
            catchExpected("xml.sax.lexical_handler", SAXException::class) {
                reader.setProperty(LEXICAL_HANDLER, NoDoctype)
            }
            // Never fetch anything the document points at.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(stream))
        } catch (e: SAXException) {
            throw XmlReadException("Not a readable XML document: ${e.message}", e)
        } catch (e: IOException) {
            throw XmlReadException("Cannot read the document: ${e.message}", e)
        }
    }

    /**
     * [bytes], the start of the document, with its DOCTYPE blanked out. Throws when the document may not be read:
     * see the class comment. [wholeDocument]: [bytes] hold all of it (the document is shorter than the scan).
     */
    private fun neutralizeDoctype(
        bytes: ByteArray,
        wholeDocument: Boolean,
    ): ByteArray {
        val head = String(bytes, Charsets.ISO_8859_1)
        // The scan below reads ASCII bytes. UTF-16 and UTF-32 put a zero byte in their first characters, and the
        // other encodings whose bytes differ from ASCII have to be declared: all of them are refused.
        if (head.startsWith(UTF16_BE_BOM) || head.startsWith(UTF16_LE_BOM) || head.take(ENCODING_PROBE).contains(NUL)) {
            throw XmlReadException("Unsupported XML encoding (UTF-16 or UTF-32)")
        }
        var copy: ByteArray? = null
        var i = if (head.startsWith(UTF8_BOM)) UTF8_BOM.length else 0
        declaredEncoding(head, i)?.let { name ->
            if (!isAsciiCompatible(name)) {
                val shown = name.take(MAX_SHOWN_NAME).filter { it.isLetterOrDigit() || it in "._-" }
                throw XmlReadException("Unsupported XML encoding: $shown")
            }
        }
        while (true) {
            while (i < head.length && head[i].isXmlSpace()) i++
            if (i >= head.length) {
                // Nothing but a prolog: an empty or cut document is the parser's to refuse, a long prolog is ours.
                if (wholeDocument) return copy ?: bytes
                throw XmlReadException("The XML prolog is too long")
            }
            if (head[i] != '<') throw XmlReadException("Not a readable XML document: text before its first element")
            val end =
                when {
                    head.startsWith("<?", i) -> {
                        head.indexOf("?>", i + 2).let { if (it < 0) -1 else it + 2 }
                    }

                    head.startsWith("<!--", i) -> {
                        head.indexOf("-->", i + 4).let { if (it < 0) -1 else it + 3 }
                    }

                    head.startsWith("<!DOCTYPE", i, ignoreCase = true) -> {
                        if (copy != null) throw XmlReadException("Two DOCTYPE declarations")
                        val close = head.indexOf('>', i)
                        val subset = head.indexOf('[', i)
                        if (close < 0 || subset in i until close) {
                            if (head.indexOf("<!ENTITY", i, ignoreCase = true) >= 0) {
                                throw XmlReadException("Entity declarations are not allowed")
                            }
                            throw XmlReadException("DOCTYPE with an internal subset is not allowed")
                        }
                        copy = bytes.copyOf().also { for (k in i..close) it[k] = ' '.code.toByte() }
                        close + 1
                    }

                    // Any other declaration has no place before the root element.
                    head.startsWith("<!", i) -> {
                        throw XmlReadException("Not a readable XML document: bad prolog")
                    }

                    // The root element: no DOCTYPE may follow it.
                    else -> {
                        return copy ?: bytes
                    }
                }
            if (end < 0) {
                // A comment or an instruction that does not end in what was read.
                if (wholeDocument) return copy ?: bytes
                throw XmlReadException("The XML prolog is too long")
            }
            i = end
        }
    }

    private fun Char.isXmlSpace() = this == ' ' || this == '\t' || this == '\r' || this == '\n'

    /** The encoding named by the XML declaration at [start] of [head], if it names one. */
    private fun declaredEncoding(
        head: String,
        start: Int,
    ): String? {
        if (!head.startsWith(XML_DECLARATION, start) ||
            head.getOrNull(start + XML_DECLARATION.length)?.isXmlSpace() != true
        ) {
            return null
        }
        val end = head.indexOf("?>", start)
        if (end < 0) return null
        return ENCODING.find(head.substring(start, end))?.groupValues?.get(1)
    }

    /** Encodings that write the markup characters as ASCII does, the ones Android's parser reads (UTF-16 aside). */
    private fun isAsciiCompatible(name: String): Boolean {
        val upper = name.uppercase(java.util.Locale.ROOT)
        return upper in setOf("UTF-8", "UTF8", "US-ASCII", "ASCII") ||
            listOf("ISO-8859-", "ISO_8859-", "WINDOWS-125", "CP125").any(upper::startsWith)
    }

    private val ENCODING = Regex("""encoding\s*=\s*["']([^"']*)["']""")
    private const val XML_DECLARATION = "<?xml"
    private const val ENCODING_PROBE = 4
    private const val MAX_SHOWN_NAME = 20
    private const val NUL = '\u0000'

    /** Byte order marks, read as ISO-8859-1. */
    private const val UTF8_BOM = "\u00EF\u00BB\u00BF"
    private const val UTF16_BE_BOM = "\u00FE\u00FF"
    private const val UTF16_LE_BOM = "\u00FF\u00FE"

    /** Refuses any DOCTYPE the parser meets (the one of a document read by [parse] was blanked out before). */
    private object NoDoctype : LexicalHandler {
        override fun startDTD(
            name: String?,
            publicId: String?,
            systemId: String?,
        ): Unit = throw SAXException("DOCTYPE is not allowed")

        override fun endDTD() = Unit

        override fun startEntity(name: String?) = Unit

        override fun endEntity(name: String?) = Unit

        override fun startCDATA() = Unit

        override fun endCDATA() = Unit

        override fun comment(
            ch: CharArray?,
            start: Int,
            length: Int,
        ) = Unit
    }
}
