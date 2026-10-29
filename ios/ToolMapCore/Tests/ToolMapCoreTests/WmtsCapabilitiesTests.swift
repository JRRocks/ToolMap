// Port of core/src/test/kotlin/fr/toolmap/core/wizard/WmtsCapabilitiesTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct WmtsCapabilitiesTests {
    private let capabilitiesUrl = "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetCapabilities"

    private func ignCaps() throws -> WmtsCapabilities {
        let data = try Fixtures.data("wmts/ign-capabilities-excerpt.xml")
        return try WmtsCapabilities.parse(data)
    }

    @Test("real IGN capabilities list their layers")
    func ignLayers() throws {
        let caps = try ignCaps()
        #expect(caps.provider == "IGN")
        #expect(caps.getTileHref == "https://data.geopf.fr/wmts?SERVICE=WMTS&")
        #expect(
            caps.layers.map(\.id) == [
                "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2",
                "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN",
                "ORTHOIMAGERY.ORTHOPHOTOS",
                "CADASTRALPARCELS.PARCELLAIRE_EXPRESS.L93",
            ]
        )
        let plan = try #require(caps.layers.first)
        #expect(plan.title == "Plan IGN")
        #expect(plan.formats == ["image/png"])
        #expect(plan.styles == [WmtsStyle(id: "normal", isDefault: true)])
        #expect(plan.links == [WmtsMatrixLink(matrixSetId: "PM_0_19", minZoom: 0, maxZoom: 19)])
    }

    @Test("only web mercator layers are usable")
    func usableLayers() throws {
        let caps = try ignCaps()
        #expect(
            caps.usableLayers.map(\.id) == [
                "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2",
                "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN",
                "ORTHOIMAGERY.ORTHOPHOTOS",
            ]
        )
        let lambert = try #require(caps.matrixSets["2154_10cm_12_20"])
        #expect(!lambert.isWebMercator)
        let mercator = try #require(caps.matrixSets["PM_0_19"])
        #expect(mercator.isWebMercator)
        #expect(mercator.tileSize == 256)
        #expect(mercator.zooms == 0 ... 19)
    }

    @Test("layer is turned into a tile url template")
    func layerTemplate() throws {
        let caps = try ignCaps()
        let layer = try #require(caps.layers.first(where: { $0.id == "GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN" }))
        let choice = try #require(WmtsSourceBuilder.defaultChoice(caps, layer))
        #expect(choice == WmtsChoice(layerId: layer.id, styleId: "normal", matrixSetId: "PM_0_17", format: "image/png"))
        let spec = try WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
        #expect(
            spec.template == "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=GEOGRAPHICALGRIDSYSTEMS.SLOPES.MOUNTAIN&STYLE=normal&TILEMATRIXSET=PM_0_17" +
                "&TILEMATRIX={$z}&TILEROW={$y}&TILECOL={$x}&FORMAT=image/png"
        )
        #expect(spec.minZoom == 0)
        #expect(spec.maxZoom == 17)
        #expect(spec.tileSize == 256)
        #expect(spec.name == "Carte des pentes")
        #expect(spec.copyright == "IGN")
        #expect(!spec.rest)
    }

    @Test("template built from capabilities expands to the url that the server answered 200 to")
    func templateExpands() throws {
        let caps = try ignCaps()
        let layer = try #require(caps.layers.first(where: { $0.id == "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2" }))
        let choice = try #require(WmtsSourceBuilder.defaultChoice(caps, layer))
        let spec = try WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
        let draft = SourceDraft(name: spec.name, urls: [spec.template], zoomSpec: "\(spec.minZoom)-\(spec.maxZoom)")
        #expect(draft.isValid, "\(draft.validate())")
        // Same request that returned HTTP 200 image/png during the session (Paris, zoom 12).
        let url = try TileUrlBuilder(draft.toSource()).build(12, 2074, 1409)
        #expect(
            url == "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
                "&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2&STYLE=normal&TILEMATRIXSET=PM_0_19" +
                "&TILEMATRIX=12&TILEROW=1409&TILECOL=2074&FORMAT=image/png"
        )
    }

    @Test("non mercator matrix sets are refused")
    func nonMercatorRefused() throws {
        let caps = try ignCaps()
        let layer = try #require(caps.layers.first(where: { $0.id.hasPrefix("CADASTRAL") }))
        #expect(WmtsSourceBuilder.defaultChoice(caps, layer) == nil)
        let choice = WmtsChoice(layerId: layer.id, styleId: "normal", matrixSetId: "2154_10cm_12_20", format: "image/png")
        #expect(throws: ToolMapError.self) {
            _ = try WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
        }
    }

    @Test("existing query parameters of the base url are not repeated")
    func noRepeatedParameters() throws {
        let ign = try ignCaps()
        let caps = WmtsCapabilities(
            title: ign.title,
            provider: ign.provider,
            getTileHref: "https://example.org/tiles?apikey={$param:apikey}&service=wmts",
            layers: ign.layers,
            matrixSets: ign.matrixSets
        )
        let layer = try #require(caps.layers.first)
        let choice = try #require(WmtsSourceBuilder.defaultChoice(caps, layer))
        let spec = try WmtsSourceBuilder.build(caps, choice, capabilitiesUrl)
        let services = spec.template.lowercased().components(separatedBy: "service=").count - 1
        #expect(services == 1, "\(spec.template)")
        #expect(spec.template.hasPrefix("https://example.org/tiles?apikey={$param:apikey}&service=wmts&REQUEST=GetTile"))
    }

    @Test("rest resource url is preferred when the server declares one")
    func restPreferred() throws {
        let xml = """
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
            """
        let caps = try WmtsCapabilities.parse(Data(xml.utf8))
        #expect(caps.getTileHref == nil)
        #expect(caps.matrixSets["webmercator"]?.zooms == 0 ... 2)
        let layer = try #require(caps.layers.first)
        let choice = try #require(WmtsSourceBuilder.defaultChoice(caps, layer))
        let spec = try WmtsSourceBuilder.build(caps, choice, "https://t.example/wmts")
        #expect(spec.rest)
        #expect(spec.template == "https://t.example/wmts/ortho/default/webmercator/{$z}/{$x}/{$y}.jpg")
        #expect(spec.minZoom == 0)
        #expect(spec.maxZoom == 2)
    }

    @Test("the url pasted by the user is the fallback base when the document has no GetTile address")
    func pastedUrlFallback() throws {
        let xml = """
            <Capabilities xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1">
              <Contents><Layer><ows:Identifier>l</ows:Identifier><Format>image/png</Format>
              <TileMatrixSetLink><TileMatrixSet>GoogleMapsCompatible</TileMatrixSet></TileMatrixSetLink></Layer>
              <TileMatrixSet><ows:Identifier>GoogleMapsCompatible</ows:Identifier><ows:SupportedCRS>EPSG:900913</ows:SupportedCRS>
              <TileMatrix><ows:Identifier>0</ows:Identifier><TileWidth>256</TileWidth></TileMatrix></TileMatrixSet></Contents></Capabilities>
            """
        let caps = try WmtsCapabilities.parse(Data(xml.utf8))
        let layer = try #require(caps.layers.first)
        let choice = try #require(WmtsSourceBuilder.defaultChoice(caps, layer))
        let spec = try WmtsSourceBuilder.build(caps, choice, "https://x.example/wmts?token=1")
        #expect(spec.template.hasPrefix("https://x.example/wmts?token=1&SERVICE=WMTS&REQUEST=GetTile"), "\(spec.template)")
        #expect(spec.name == "l")
    }

    @Test("matrix identifiers give zooms")
    func matrixZooms() {
        #expect(WmtsCapabilities.zoomOf("12") == 12)
        #expect(WmtsCapabilities.zoomOf("EPSG:3857:12") == 12)
        #expect(WmtsCapabilities.zoomOf("PM_7") == 7)
        #expect(WmtsCapabilities.zoomOf("LEVEL") == nil)
        #expect(UpdateDelay.toMillis("1Y") != nil)
    }
}
