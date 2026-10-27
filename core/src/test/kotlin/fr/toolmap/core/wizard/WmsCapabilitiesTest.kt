package fr.toolmap.core.wizard

import fr.toolmap.core.tiles.TileUrlBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WmsCapabilitiesTest {
    private val wms130 =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <WMS_Capabilities version="1.3.0" xmlns="http://www.opengis.net/wms" xmlns:xlink="http://www.w3.org/1999/xlink">
          <Service><Name>WMS</Name><Title>Demo server</Title></Service>
          <Capability>
            <Request>
              <GetCapabilities><Format>text/xml</Format></GetCapabilities>
              <GetMap>
                <Format>image/jpeg</Format>
                <Format>image/png</Format>
                <DCPType><HTTP><Get><OnlineResource xlink:href="https://wms.example/ows?map=demo&amp;"/></Get></HTTP></DCPType>
              </GetMap>
            </Request>
            <Layer>
              <Title>Root</Title>
              <CRS>EPSG:4326</CRS>
              <CRS>EPSG:3857</CRS>
              <Layer><Name>roads</Name><Title>Roads</Title></Layer>
              <Layer>
                <Name>lambert</Name><Title>Lambert only</Title>
                <CRS>EPSG:2154</CRS>
              </Layer>
            </Layer>
          </Capability>
        </WMS_Capabilities>
        """.trimIndent()

    @Test
    fun `layers inherit the crs of their parent`() {
        val caps = WmsCapabilities.parse(wms130.toByteArray())
        assertEquals("1.3.0", caps.version)
        assertEquals("Demo server", caps.title)
        assertEquals(listOf("image/jpeg", "image/png"), caps.formats)
        assertEquals("https://wms.example/ows?map=demo&", caps.getMapHref)
        assertEquals(listOf("roads", "lambert"), caps.layers.map { it.name })
        assertEquals(listOf("roads", "lambert"), caps.usableLayers.map { it.name }, "both inherit EPSG:3857")
        assertTrue(caps.layers.first().supportsWebMercator)
    }

    @Test
    fun `png is the default format and the template uses a mercator bbox`() {
        val caps = WmsCapabilities.parse(wms130.toByteArray())
        val format = WmsSourceBuilder.defaultFormat(caps)
        assertEquals("image/png", format)
        val spec =
            WmsSourceBuilder.build(
                caps,
                caps.layers.first(),
                format,
                "https://wms.example/ows?SERVICE=WMS&REQUEST=GetCapabilities",
            )
        assertEquals(
            "https://wms.example/ows?map=demo&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=roads&STYLES=" +
                "&CRS=EPSG:3857&BBOX={\$bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE",
            spec.template,
        )
        assertEquals("EPSG:3857", spec.bboxCrs)
        val draft = SourceDraft(name = "Roads", urls = listOf(spec.template), bboxCrs = spec.bboxCrs)
        assertTrue(draft.isValid, draft.validate().toString())
        val url = TileUrlBuilder(draft.toSource()).build(0, 0, 0)
        assertTrue(url!!.contains("BBOX=-20037508.3428,-20037508.3428,20037508.3428,20037508.3428"), url)
    }

    @Test
    fun `wms 1_1_1 with a doctype uses srs and a plain jpeg request`() {
        val wms111 =
            """
            <?xml version="1.0"?>
            <!DOCTYPE WMT_MS_Capabilities SYSTEM "http://schemas.opengis.net/wms/1.1.1/WMS_MS_Capabilities.dtd">
            <WMT_MS_Capabilities version="1.1.1">
              <Service><Name>OGC:WMS</Name><Title>Old server</Title></Service>
              <Capability>
                <Request><GetMap><Format>image/jpeg</Format>
                  <DCPType><HTTP><Get><OnlineResource xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="http://old.example/wms?"/></Get></HTTP></DCPType>
                </GetMap></Request>
                <Layer><Title>Root</Title><SRS>EPSG:4326 EPSG:900913</SRS>
                  <Layer><Name>ortho</Name><Title>Ortho</Title></Layer>
                </Layer>
              </Capability>
            </WMT_MS_Capabilities>
            """.trimIndent()
        val caps = WmsCapabilities.parse(wms111.toByteArray())
        assertEquals("1.1.1", caps.version)
        assertEquals(listOf("ortho"), caps.usableLayers.map { it.name })
        val spec =
            WmsSourceBuilder.build(
                caps,
                caps.layers.first(),
                WmsSourceBuilder.defaultFormat(caps),
                "http://old.example/wms",
            )
        assertEquals(
            "http://old.example/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=ortho&STYLES=&SRS=EPSG:3857" +
                "&BBOX={\$bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg",
            spec.template,
        )
        assertFalse(spec.template.contains("TRANSPARENT"))
    }

    @Test
    fun `a layer that cannot be drawn in web mercator is refused`() {
        val xml =
            """<WMS_Capabilities version="1.3.0"><Capability><Layer><Name>l</Name><Title>L</Title><CRS>EPSG:2154</CRS></Layer></Capability></WMS_Capabilities>"""
        val caps = WmsCapabilities.parse(xml.toByteArray())
        assertTrue(caps.usableLayers.isEmpty())
        val result = runCatching { WmsSourceBuilder.build(caps, caps.layers.first(), "image/png", "https://x/wms") }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
