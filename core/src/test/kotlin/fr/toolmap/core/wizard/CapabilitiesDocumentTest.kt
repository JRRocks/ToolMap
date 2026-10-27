package fr.toolmap.core.wizard

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilitiesDocumentTest {
    private fun norm(
        url: String,
        service: OgcService = OgcService.WMTS,
    ) = CapabilitiesUrl.normalize(url, service)

    @Test
    fun `a bare service address gets the capabilities request`() {
        assertEquals(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetCapabilities",
            norm("  https://data.geopf.fr/wmts "),
        )
        assertEquals(
            "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&SERVICE=WMTS&REQUEST=GetCapabilities",
            norm("https://data.geopf.fr/private/wmts?apikey=ign_scan_ws"),
        )
        assertEquals(
            "https://x.example/ows?map=demo&SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0",
            norm("https://x.example/ows?map=demo", OgcService.WMS),
        )
    }

    @Test
    fun `an address that already is a capabilities request is kept`() {
        val full = "https://x.example/wmts?service=WMTS&Request=GetCapabilities&version=1.0.0"
        assertEquals(full, norm(full))
        val rest = "https://x.example/wmts/1.0.0/WMTSCapabilities.xml"
        assertEquals(rest, norm(rest))
        assertEquals(rest, norm("$rest#top"))
    }

    @Test
    fun `tile requests and templates are not capabilities addresses`() {
        for (url in listOf(
            "https://x/wmts?REQUEST=GetTile&LAYER=a",
            "https://x/wms?request=getmap&layers=a",
            "https://x/{\$z}/{\$x}/{\$y}.png",
        )) {
            val e = assertFailsWith<CapabilitiesUrlException> { norm(url) }
            assertEquals(CapabilitiesUrlProblem.TILE_REQUEST, e.problem, url)
        }
        assertEquals(CapabilitiesUrlProblem.EMPTY, assertFailsWith<CapabilitiesUrlException> { norm("  ") }.problem)
        assertEquals(
            CapabilitiesUrlProblem.NOT_HTTP,
            assertFailsWith<CapabilitiesUrlException> {
                norm("ftp://x")
            }.problem,
        )
        assertEquals(
            CapabilitiesUrlProblem.NOT_HTTP,
            assertFailsWith<CapabilitiesUrlException> {
                norm("data.geopf.fr/wmts")
            }.problem,
        )
    }

    @Test
    fun `reader picks the parser from the root element`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val wmts = CapabilitiesReader.read(File(dir, "wmts/ign-capabilities-excerpt.xml").readBytes())
        assertEquals(4, assertIs<CapabilitiesDocument.Wmts>(wmts).capabilities.layers.size)
        val wms =
            CapabilitiesReader.read(
                """<?xml version="1.0"?><!-- c --><wms:WMS_Capabilities xmlns:wms="http://www.opengis.net/wms" version="1.3.0">
                   <wms:Capability><wms:Layer><wms:Name>a</wms:Name><wms:CRS>EPSG:3857</wms:CRS></wms:Layer></wms:Capability>
                   </wms:WMS_Capabilities>""".toByteArray(),
            )
        assertEquals(listOf("a"), assertIs<CapabilitiesDocument.Wms>(wms).capabilities.layers.map { it.name })
    }

    @Test
    fun `server error reports and other documents give readable errors`() {
        val report =
            """<?xml version="1.0"?><ExceptionReport xmlns="http://www.opengis.net/ows/1.1" version="1.1.0">
               <Exception exceptionCode="InvalidParameterValue"><ExceptionText>Invalid apikey</ExceptionText></Exception></ExceptionReport>"""
        assertEquals(
            "Invalid apikey",
            assertFailsWith<CapabilitiesException> {
                CapabilitiesReader.read(report.toByteArray())
            }.message,
        )
        val wmsError =
            """<ServiceExceptionReport version="1.1.1"><ServiceException code="X">Unknown layer</ServiceException></ServiceExceptionReport>"""
        assertEquals(
            "Unknown layer",
            assertFailsWith<CapabilitiesException> {
                CapabilitiesReader.read(wmsError.toByteArray())
            }.message,
        )
        val html =
            assertFailsWith<CapabilitiesException> {
                CapabilitiesReader.read("<html><body>x</body></html>".toByteArray())
            }
        assertTrue(html.message!!.contains("<html>"))
        assertFailsWith<CapabilitiesException> { CapabilitiesReader.read("plain text".toByteArray()) }
        assertFailsWith<CapabilitiesException> { CapabilitiesReader.read(ByteArray(0)) }
    }

    @Test
    fun `image formats are recognised from the first bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = "RIFF....WEBPVP8 ".toByteArray()
        assertEquals(ImageSniffer.Format.PNG, ImageSniffer.formatOf(png))
        assertEquals(ImageSniffer.Format.JPEG, ImageSniffer.formatOf(jpeg))
        assertEquals(ImageSniffer.Format.WEBP, ImageSniffer.formatOf(webp))
        assertEquals(ImageSniffer.Format.GIF, ImageSniffer.formatOf("GIF89a".toByteArray()))
        assertNull(ImageSniffer.formatOf("<html>".toByteArray()))
        assertNull(ImageSniffer.formatOf(ByteArray(0)))
        assertNull(ImageSniffer.formatOf(byteArrayOf(0x89.toByte(), 0x50)))
    }
}
