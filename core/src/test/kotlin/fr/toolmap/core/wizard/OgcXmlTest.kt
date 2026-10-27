package fr.toolmap.core.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OgcXmlTest {
    private val wmts =
        """
        <Capabilities xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1">
          <Contents>
            <Layer><ows:Identifier>a</ows:Identifier><Format>image/png</Format></Layer>
          </Contents>
        </Capabilities>
        """.trimIndent()

    @Test
    fun `entity declarations are refused`() {
        val xxe =
            """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><Capabilities>&e;</Capabilities>"""
        assertFailsWith<CapabilitiesException> { WmtsCapabilities.parse(xxe.toByteArray()) }
    }

    @Test
    fun `a doctype with an internal subset is refused`() {
        val doc = """<?xml version="1.0"?><!DOCTYPE x [<!ELEMENT x ANY>]><Capabilities/>"""
        assertFailsWith<CapabilitiesException> { WmtsCapabilities.parse(doc.toByteArray()) }
    }

    @Test
    fun `a simple external doctype is blanked, not fetched`() {
        val doc =
            """<?xml version="1.0"?><!DOCTYPE Capabilities SYSTEM "http://127.0.0.1:1/never.dtd">""" + wmts
        // Would throw a connection error if the DTD were fetched.
        assertEquals(listOf("a"), WmtsCapabilities.parse(doc.toByteArray()).layers.map { it.id })
    }

    @Test
    fun `text that is not xml gives a clear error`() {
        val html = "<html><body>Forbidden</body>"
        val error = assertFailsWith<CapabilitiesException> { WmtsCapabilities.parse(html.toByteArray()) }
        assertEquals(true, error.message?.isNotBlank())
        assertFailsWith<CapabilitiesException> { WmtsCapabilities.parse(ByteArray(0)) }
    }

    @Test
    fun `a well formed document with no layer is refused`() {
        assertFailsWith<CapabilitiesException> { WmtsCapabilities.parse("<Capabilities/>".toByteArray()) }
        assertFailsWith<CapabilitiesException> { WmsCapabilities.parse("<WMS_Capabilities/>".toByteArray()) }
    }
}
