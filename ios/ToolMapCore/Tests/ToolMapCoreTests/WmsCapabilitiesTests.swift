// Port of core/src/test/kotlin/fr/toolmap/core/wizard/WmsCapabilitiesTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct WmsCapabilitiesTests {
    private let wms130 = """
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
        """

    @Test("layers inherit the crs of their parent")
    func inheritedCrs() throws {
        let caps = try WmsCapabilities.parse(Data(wms130.utf8))
        #expect(caps.version == "1.3.0")
        #expect(caps.title == "Demo server")
        #expect(caps.formats == ["image/jpeg", "image/png"])
        #expect(caps.getMapHref == "https://wms.example/ows?map=demo&")
        #expect(caps.layers.map(\.name) == ["roads", "lambert"])
        #expect(caps.usableLayers.map(\.name) == ["roads", "lambert"], "both inherit EPSG:3857")
        #expect(caps.layers.first?.supportsWebMercator == true)
    }

    @Test("png is the default format and the template uses a mercator bbox")
    func defaultFormat() throws {
        let caps = try WmsCapabilities.parse(Data(wms130.utf8))
        let format = WmsSourceBuilder.defaultFormat(caps)
        #expect(format == "image/png")
        let layer = try #require(caps.layers.first)
        let spec = try WmsSourceBuilder.build(
            caps,
            layer,
            format,
            "https://wms.example/ows?SERVICE=WMS&REQUEST=GetCapabilities"
        )
        #expect(
            spec.template == "https://wms.example/ows?map=demo&SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=roads&STYLES=" +
                "&CRS=EPSG:3857&BBOX={$bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/png&TRANSPARENT=TRUE"
        )
        #expect(spec.bboxCrs == "EPSG:3857")
        let draft = SourceDraft(name: "Roads", urls: [spec.template], bboxCrs: spec.bboxCrs)
        #expect(draft.isValid, "\(draft.validate())")
        let url = try #require(try TileUrlBuilder(draft.toSource()).build(0, 0, 0))
        #expect(url.contains("BBOX=-20037508.3428,-20037508.3428,20037508.3428,20037508.3428"), "\(url)")
    }

    @Test("wms 1_1_1 with a doctype uses srs and a plain jpeg request")
    func wms111() throws {
        let wms111 = """
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
            """
        let caps = try WmsCapabilities.parse(Data(wms111.utf8))
        #expect(caps.version == "1.1.1")
        #expect(caps.usableLayers.map(\.name) == ["ortho"])
        let layer = try #require(caps.layers.first)
        let spec = try WmsSourceBuilder.build(caps, layer, WmsSourceBuilder.defaultFormat(caps), "http://old.example/wms")
        #expect(
            spec.template == "http://old.example/wms?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=ortho&STYLES=&SRS=EPSG:3857" +
                "&BBOX={$bbox}&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg"
        )
        #expect(!spec.template.contains("TRANSPARENT"))
    }

    @Test("a layer that cannot be drawn in web mercator is refused")
    func notMercator() throws {
        let xml = "<WMS_Capabilities version=\"1.3.0\"><Capability><Layer><Name>l</Name><Title>L</Title><CRS>EPSG:2154</CRS></Layer></Capability></WMS_Capabilities>"
        let caps = try WmsCapabilities.parse(Data(xml.utf8))
        #expect(caps.usableLayers.isEmpty)
        let layer = try #require(caps.layers.first)
        #expect(throws: ToolMapError.self) {
            _ = try WmsSourceBuilder.build(caps, layer, "image/png", "https://x/wms")
        }
    }
}
