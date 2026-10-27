package fr.toolmap.core.wizard

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.UpdateDelay
import fr.toolmap.core.tiles.TileUrlBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WmtsCapabilitiesTest {
    private val capabilitiesUrl = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"

    private fun ignCaps(): WmtsCapabilities {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        return WmtsCapabilities.parse(File(dir, "wmts/ign-capabilities-excerpt.xml").readBytes())
    }

    @Test
    fun `real IGN capabilities list their layers`() {
        val caps = ignCaps()
        assertEquals("IGN", caps.provider)
        assertEquals("https://data.geopf.fr/wmts?SERVICE=WMTS&", caps.getTileHref)
        assertEquals(
            listOf(
                "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2",
                "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN",
                "ORTHOIMAGERY.ORTHOPHOTOS",
                "CADASTRALPARCELS.PARCELLAIRE_EXPRESS.L93",
            ),
            caps.layers.map { it.id },
        )
        val plan = caps.layers.first()
        assertEquals("Plan IGN", plan.title)
        assertEquals(listOf("image/png"), plan.formats)
        assertEquals(listOf(WmtsStyle("normal", isDefault = true)), plan.styles)
        assertEquals(listOf(WmtsMatrixLink("PM_0_19", 0, 19)), plan.links)
    }

    @Test
    fun `only web mercator layers are usable`() {
        val caps = ignCaps()
        assertEquals(
            listOf(
                "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2",
                "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN",
                "ORTHOIMAGERY.ORTHOPHOTOS",
            ),
            caps.usableLayers.map { it.id },
        )
        assertFalse(caps.matrixSets.getValue("2154_10cm_12_20").isWebMercator)
        val set = caps.matrixSets.getValue("PM_0_19")
        assertTrue(set.isWebMercator)
        assertEquals(256, set.tileSize)
        assertEquals(0..19, set.zooms)
    }

    @Test
    fun `layer is turned into a tile url template`() {
        val caps = ignCaps()
        val layer = caps.layers.first { it.id == "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN" }
        val choice = assertNotNull(WmtsSourceBuilder.defaultChoice(caps, layer))
        assertEquals(WmtsChoice(layer.id, "normal", "PM_0_17", "image/png"), choice)
        val spec = WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
        assertEquals(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN&STYLE=normal&TILEMATRIXSET=PM_0_17" +
                "&TILEMATRIX={\$z}&TILEROW={\$y}&TILECOL={\$x}&FORMAT=image/png",
            spec.template,
        )
        assertEquals(0, spec.minZoom)
        assertEquals(17, spec.maxZoom)
        assertEquals(256, spec.tileSize)
        assertEquals("Carte des pentes", spec.name)
        assertEquals("IGN", spec.copyright)
        assertFalse(spec.rest)
    }

    @Test
    fun `template built from capabilities expands to the url that the server answered 200 to`() {
        val caps = ignCaps()
        val layer = caps.layers.first { it.id == "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2" }
        val spec = WmtsSourceBuilder.build(caps, WmtsSourceBuilder.defaultChoice(caps, layer)!!, capabilitiesUrl)
        val draft =
            SourceDraft(name = spec.name, urls = listOf(spec.template), zoomSpec = "${spec.minZoom}-${spec.maxZoom}")
        assertTrue(draft.isValid, draft.validate().toString())
        // Same request that returned HTTP 200 image/png during the session (Paris, zoom 12).
        assertEquals(
            "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&TILEMATRIXSET=PM_0_19" +
                "&TILEMATRIX=12&TILEROW=1409&TILECOL=2074&FORMAT=image/png",
            TileUrlBuilder(draft.toSource()).build(12, 2074, 1409),
        )
    }

    @Test
    fun `non mercator matrix sets are refused`() {
        val caps = ignCaps()
        val layer = caps.layers.first { it.id.startsWith("CADASTRAL") }
        assertNull(WmtsSourceBuilder.defaultChoice(caps, layer))
        val choice = WmtsChoice(layer.id, "normal", "2154_10cm_12_20", "image/png")
        runCatching { WmtsSourceBuilder.build(caps, choice, capabilitiesUrl) }
            .let { assertTrue(it.exceptionOrNull() is IllegalArgumentException) }
    }

    @Test
    fun `existing query parameters of the base url are not repeated`() {
        val caps = ignCaps().copy(getTileHref = "https://example.org/tiles?apikey={\$param:apikey}&service=wmts")
        val layer = caps.layers.first()
        val spec = WmtsSourceBuilder.build(caps, WmtsSourceBuilder.defaultChoice(caps, layer)!!, capabilitiesUrl)
        assertEquals(1, Regex("(?i)service=").findAll(spec.template).count(), spec.template)
        assertTrue(
            spec.template.startsWith("https://example.org/tiles?apikey={\$param:apikey}&service=wmts&REQUEST=GetTile"),
        )
    }

    @Test
    fun `rest resource url is preferred when the server declares one`() {
        val xml =
            """
            <Capabilities xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1"
                          xmlns:xlink="http://www.w3.org/1999/xlink">
              <Contents>
                <Layer>
                  <ows:Title>Ortho</ows:Title><ows:Identifier>ortho</ows:Identifier>
                  <Style isDefault="true"><ows:Identifier>default</ows:Identifier></Style>
                  <Format>image/jpeg</Format>
                  <TileMatrixSetLink><TileMatrixSet>webmercator</TileMatrixSet></TileMatrixSetLink>
                  <ResourceURL format="image/jpeg" resourceType="tile"
                    template="https://t.example/wmts/{Layer}/{Style}/{TileMatrixSet}/{TileMatrix}/{TileCol}/{TileRow}.jpg"/>
                </Layer>
                <TileMatrixSet>
                  <ows:Identifier>webmercator</ows:Identifier>
                  <ows:SupportedCRS>urn:ogc:def:crs:EPSG::3857</ows:SupportedCRS>
                  <TileMatrix><ows:Identifier>EPSG:3857:0</ows:Identifier><TileWidth>256</TileWidth><TileHeight>256</TileHeight></TileMatrix>
                  <TileMatrix><ows:Identifier>EPSG:3857:1</ows:Identifier><TileWidth>256</TileWidth><TileHeight>256</TileHeight></TileMatrix>
                  <TileMatrix><ows:Identifier>EPSG:3857:2</ows:Identifier><TileWidth>256</TileWidth><TileHeight>256</TileHeight></TileMatrix>
                </TileMatrixSet>
              </Contents>
            </Capabilities>
            """.trimIndent()
        val caps = WmtsCapabilities.parse(xml.toByteArray())
        assertNull(caps.getTileHref)
        assertEquals(0..2, caps.matrixSets.getValue("webmercator").zooms)
        val spec =
            WmtsSourceBuilder.build(
                caps,
                WmtsSourceBuilder.defaultChoice(caps, caps.layers.first())!!,
                "https://t.example/wmts",
            )
        assertTrue(spec.rest)
        assertEquals("https://t.example/wmts/ortho/default/webmercator/{\$z}/{\$x}/{\$y}.jpg", spec.template)
        assertEquals(0, spec.minZoom)
        assertEquals(2, spec.maxZoom)
    }

    @Test
    fun `the url pasted by the user is the fallback base when the document has no GetTile address`() {
        val xml =
            """<Capabilities xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1">
              <Contents><Layer><ows:Identifier>l</ows:Identifier><Format>image/png</Format>
              <TileMatrixSetLink><TileMatrixSet>GoogleMapsCompatible</TileMatrixSet></TileMatrixSetLink></Layer>
              <TileMatrixSet><ows:Identifier>GoogleMapsCompatible</ows:Identifier><ows:SupportedCRS>EPSG:900913</ows:SupportedCRS>
              <TileMatrix><ows:Identifier>0</ows:Identifier><TileWidth>256</TileWidth></TileMatrix></TileMatrixSet></Contents></Capabilities>"""
        val caps = WmtsCapabilities.parse(xml.toByteArray())
        val spec =
            WmtsSourceBuilder.build(
                caps,
                WmtsSourceBuilder.defaultChoice(caps, caps.layers.first())!!,
                "https://x.example/wmts?token=1",
            )
        assertTrue(
            spec.template.startsWith("https://x.example/wmts?token=1&SERVICE=WMTS&REQUEST=GetTile"),
            spec.template,
        )
        assertEquals("l", spec.name)
    }

    @Test
    fun `matrix identifiers give zooms`() {
        assertEquals(12, WmtsCapabilities.zoomOf("12"))
        assertEquals(12, WmtsCapabilities.zoomOf("EPSG:3857:12"))
        assertEquals(7, WmtsCapabilities.zoomOf("PM_7"))
        assertNull(WmtsCapabilities.zoomOf("LEVEL"))
        assertTrue(UpdateDelay.toMillis("1Y") != null)
    }
}
