// Port of core/src/test/kotlin/fr/toolmap/core/wizard/CapabilitiesDocumentTest.kt and wizard/OgcXmlTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct CapabilitiesDocumentTests {
    private func norm(_ url: String, _ service: OgcService = .wmts) throws -> String {
        try CapabilitiesUrl.normalize(url, service)
    }

    /// The problem `normalize` reports for `url`, or nil when it accepts it.
    private func problem(_ url: String) -> CapabilitiesUrlProblem? {
        do {
            _ = try CapabilitiesUrl.normalize(url, .wmts)
            return nil
        } catch let error as CapabilitiesUrlError {
            return error.problem
        } catch {
            return nil
        }
    }

    /// The message of the `CapabilitiesError` that reading `text` gives, or nil when it reads.
    private func readError(_ data: Data) -> String? {
        do {
            _ = try CapabilitiesReader.read(data)
            return nil
        } catch let error as CapabilitiesError {
            return error.message
        } catch {
            return nil
        }
    }

    @Test("a bare service address gets the capabilities request")
    func bareAddress() throws {
        #expect(try norm("  https://data.geopf.fr/wmts ") == "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetCapabilities")
        #expect(
            try norm("https://data.geopf.fr/private/wmts?apikey=ign_scan_ws")
                == "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&SERVICE=WMTS&REQUEST=GetCapabilities"
        )
        #expect(
            try norm("https://x.example/ows?map=demo", .wms)
                == "https://x.example/ows?map=demo&SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"
        )
    }

    @Test("an address that already is a capabilities request is kept")
    func keptAddress() throws {
        let full = "https://x.example/wmts?service=WMTS&Request=GetCapabilities&version=1.0.0"
        #expect(try norm(full) == full)
        let rest = "https://x.example/wmts/1.0.0/WMTSCapabilities.xml"
        #expect(try norm(rest) == rest)
        #expect(try norm(rest + "#top") == rest)
    }

    @Test("tile requests and templates are not capabilities addresses")
    func tileRequests() {
        for url in ["https://x/wmts?REQUEST=GetTile&LAYER=a", "https://x/wms?request=getmap&layers=a", "https://x/{$z}/{$x}/{$y}.png"] {
            #expect(problem(url) == .tileRequest, "\(url)")
        }
        #expect(problem("  ") == .empty)
        #expect(problem("ftp://x") == .notHttp)
        #expect(problem("data.geopf.fr/wmts") == .notHttp)
    }

    @Test("reader picks the parser from the root element")
    func readerPicksParser() throws {
        let fixture = try Fixtures.data("wmts/ign-capabilities-excerpt.xml")
        let wmts = try CapabilitiesReader.read(fixture)
        guard case let .wmts(wmtsCapabilities) = wmts else {
            Issue.record("expected a WMTS document, got \(wmts)")
            return
        }
        #expect(wmtsCapabilities.layers.count == 4)
        let wmsText = """
            <?xml version="1.0"?><!-- c --><wms:WMS_Capabilities xmlns:wms="http://www.opengis.net/wms" version="1.3.0">
               <wms:Capability><wms:Layer><wms:Name>a</wms:Name><wms:CRS>EPSG:3857</wms:CRS></wms:Layer></wms:Capability>
               </wms:WMS_Capabilities>
            """
        let wms = try CapabilitiesReader.read(Data(wmsText.utf8))
        guard case let .wms(wmsCapabilities) = wms else {
            Issue.record("expected a WMS document, got \(wms)")
            return
        }
        #expect(wmsCapabilities.layers.map(\.name) == ["a"])
    }

    @Test("server error reports and other documents give readable errors")
    func errorReports() {
        let report = """
            <?xml version="1.0"?><ExceptionReport xmlns="http://www.opengis.net/ows/1.1" version="1.1.0">
               <Exception exceptionCode="InvalidParameterValue"><ExceptionText>Invalid apikey</ExceptionText></Exception></ExceptionReport>
            """
        #expect(readError(Data(report.utf8)) == "Invalid apikey")
        let wmsError =
            "<ServiceExceptionReport version=\"1.1.1\"><ServiceException code=\"X\">Unknown layer</ServiceException></ServiceExceptionReport>"
        #expect(readError(Data(wmsError.utf8)) == "Unknown layer")
        let html = readError(Data("<html><body>x</body></html>".utf8))
        #expect(html?.contains("<html>") == true)
        #expect(readError(Data("plain text".utf8)) != nil)
        #expect(readError(Data()) != nil)
    }

    @Test("image formats are recognised from the first bytes")
    func imageFormats() {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0])
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0])
        let webp = Data("RIFF....WEBPVP8 ".utf8)
        #expect(ImageSniffer.formatOf(png) == .png)
        #expect(ImageSniffer.formatOf(jpeg) == .jpeg)
        #expect(ImageSniffer.formatOf(webp) == .webp)
        #expect(ImageSniffer.formatOf(Data("GIF89a".utf8)) == .gif)
        #expect(ImageSniffer.formatOf(Data("<html>".utf8)) == nil)
        #expect(ImageSniffer.formatOf(Data()) == nil)
        #expect(ImageSniffer.formatOf(Data([0x89, 0x50])) == nil)
    }
}

struct OgcXmlTests {
    private let wmts = """
        <Capabilities xmlns="http://www.opengis.net/wmts/1.0" xmlns:ows="http://www.opengis.net/ows/1.1">
          <Contents>
            <Layer><ows:Identifier>a</ows:Identifier><Format>image/png</Format></Layer>
          </Contents>
        </Capabilities>
        """

    @Test("entity declarations are refused")
    func entitiesRefused() {
        let xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Capabilities>&e;</Capabilities>"
        #expect(throws: CapabilitiesError.self) {
            _ = try WmtsCapabilities.parse(Data(xxe.utf8))
        }
    }

    @Test("a doctype with an internal subset is refused")
    func internalSubsetRefused() {
        let doc = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ELEMENT x ANY>]><Capabilities/>"
        #expect(throws: CapabilitiesError.self) {
            _ = try WmtsCapabilities.parse(Data(doc.utf8))
        }
    }

    @Test("a simple external doctype is blanked, not fetched")
    func externalDoctypeBlanked() throws {
        let doc = "<?xml version=\"1.0\"?><!DOCTYPE Capabilities SYSTEM \"http://127.0.0.1:1/never.dtd\">" + wmts
        // Would throw a connection error if the DTD were fetched.
        let capabilities = try WmtsCapabilities.parse(Data(doc.utf8))
        #expect(capabilities.layers.map(\.id) == ["a"])
    }

    @Test("text that is not xml gives a clear error")
    func notXml() {
        let html = "<html><body>Forbidden</body>"
        do {
            _ = try WmtsCapabilities.parse(Data(html.utf8))
            Issue.record("an unfinished document must be refused")
        } catch let error as CapabilitiesError {
            #expect(!kotlinTrim(error.message).isEmpty)
        } catch {
            Issue.record("unexpected error \(error)")
        }
        #expect(throws: CapabilitiesError.self) {
            _ = try WmtsCapabilities.parse(Data())
        }
    }

    @Test("a well formed document with no layer is refused")
    func noLayer() {
        #expect(throws: CapabilitiesError.self) {
            _ = try WmtsCapabilities.parse(Data("<Capabilities/>".utf8))
        }
        #expect(throws: CapabilitiesError.self) {
            _ = try WmsCapabilities.parse(Data("<WMS_Capabilities/>".utf8))
        }
    }
}
