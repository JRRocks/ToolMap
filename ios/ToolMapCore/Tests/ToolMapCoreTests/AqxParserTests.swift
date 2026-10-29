// Port of core/src/test/kotlin/fr/toolmap/core/aqx/AqxParserTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct AqxParserTests {
    private func warningsOf(_ text: String) throws -> [String] {
        try AqxParser.parse(text).warnings.map(\.message)
    }

    private func ignSource(_ id: String) throws -> TileSource {
        let sources = try AqxParser.parse(Fixtures.text("aqx/IGN_Topo_zoom_test.aqx")).file.sources
        return try #require(sources.first { $0.id == id })
    }

    @Test("reference fixture gives two sources")
    func referenceFixture() throws {
        let result = try AqxParser.parse(Fixtures.text("aqx/IGN_Topo_zoom_test.aqx"))
        let file = result.file
        #expect(file.version == 11)
        #expect(file.name == "IGN Cartes Privées (ign_scan_ws)")
        #expect(file.sources.map(\.id) == ["IGN_MAPS", "IGN_SCAN25TOUR"])
        #expect(result.warnings.isEmpty, "unexpected warnings: \(result.warnings)")
    }

    @Test("IGN_MAPS covers zooms 1 to 18")
    func ignMaps() throws {
        let source = try ignSource("IGN_MAPS")
        #expect(source.name == "IGN Cartes Topographiques")
        #expect(source.type == "topo")
        #expect(source.copyright == "IGN")
        #expect(source.region == "WRLD")
        #expect(source.dataSource == "https://data.geopf.fr")
        #expect(source.previewLocation == LonLatZoom(lon: 5.90, lat: 44.80, zoom: 12))
        #expect(source.isSupported)
        #expect(!source.isLayer)
        #expect(source.minZoom == 1)
        #expect(source.maxZoom == 18)
        #expect(source.levels.count == 1)
        let level = try #require(source.levels.first)
        #expect(level.zooms.isContiguous)
        #expect(level.referer == "https://data.geopf.fr/")
        #expect(
            level.servers == [
                "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&layer=GEOGRAPHICALGRIDSYSTEMS.MAPS&style=normal" +
                    "&tilematrixset=PM&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg" +
                    "&TileMatrix={$z}&TileCol={$x}&TileRow={$y}",
            ]
        )
    }

    @Test("IGN_SCAN25TOUR covers zooms 6 to 14")
    func ignScan25() throws {
        let source = try ignSource("IGN_SCAN25TOUR")
        #expect(source.minZoom == 6)
        #expect(source.maxZoom == 14)
        #expect(source.levelFor(5) == nil)
        #expect(source.levelFor(14) != nil)
        let level = try #require(source.levels.first)
        #expect(level.referer == "https://data.geopf.fr/")
        #expect(level.servers.count == 1)
        #expect(level.servers[0].contains("GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR"))
    }

    private let secondSyntax = """
    <aqx version="11">
      <param name="key">abc</param>
      <source id="A" layer="true" opacity="60" type="Hillshade">
        <name>Layer A</name>
        <outline>5.0,44.0 6.0,44.0 6.0,45.0</outline>
        <default-location>5.5,44.5</default-location>
        <zoom-levels z="1-3,5">
          <server><![CDATA[https://a.example/{$z}/{$x}/{$y}?k={$param:key}]]></server>
          <server>https://b.example/{$z}/{$x}/{$y}</server>
          <user-agent>UA/1</user-agent>
          <header>X-Test: 1</header>
          <header>Accept: image/png</header>
          <cookie>a=b</cookie>
          <update-delay>2W</update-delay>
          <max-threads>9</max-threads>
          <tiles-size>512</tiles-size>
          <clear-color r="240-255" g="240-255" b="250"/>
        </zoom-levels>
        <zoom-levels z="4"><server>https://c.example/{$q}</server></zoom-levels>
      </source>
    </aqx>
    """

    @Test("second syntax with zoom-levels, several servers, headers and params")
    func secondSyntaxIsRead() throws {
        let result = try AqxParser.parse(secondSyntax)
        #expect(result.warnings.isEmpty, "unexpected warnings: \(result.warnings)")
        #expect(result.file.sources.count == 1)
        let source = try #require(result.file.sources.first)
        #expect(source.isLayer)
        #expect(source.opacity == 60)
        #expect(source.type == "hillshade")
        #expect(source.outline.count == 3)
        #expect(source.defaultLocation?.lon == 5.5)
        #expect(source.params["key"] == "abc")
        #expect(source.levels.count == 2)
        let first = source.levels[0]
        #expect(first.zooms.toList() == [1, 2, 3, 5])
        #expect(first.servers.count == 2)
        #expect(first.servers[0] == "https://a.example/{$z}/{$x}/{$y}?k={$param:key}")
        #expect(first.userAgent == "UA/1")
        #expect(first.headers == [HttpHeader("X-Test", "1"), HttpHeader("Accept", "image/png")])
        #expect(first.cookie == "a=b")
        #expect(first.updateDelay == "2W")
        #expect(first.maxThreads == 9, "max-threads is kept up to the cap")
        let capped = try AqxParser.parse(
            secondSyntax.replacingOccurrences(of: "<max-threads>9<", with: "<max-threads>40<")
        )
        #expect(capped.file.sources.first?.levels.first?.maxThreads == 16, "max-threads is capped at 16")
        #expect(first.tileSize == 512)
        #expect(first.clearColor == ClearColor(r: IntRange(240, 255), g: IntRange(240, 255), b: IntRange(250, 250)))
        #expect(source.levelFor(5) == first)
        #expect(source.levelFor(4) == source.levels[1])
        #expect(source.minZoom == 1)
        #expect(source.maxZoom == 5)
    }

    @Test("legacy zoom-level element and non contiguous zoom values")
    func legacyZoomLevel() throws {
        let text = """
        <aqx version="8">
          <source id="L"><name>Legacy</name>
            <zoom-level z="5"><server>https://x.example/{$z}</server></zoom-level>
            <level><zoom-values>8,10,12</zoom-values><server>https://y.example/{$z}</server></level>
          </source>
        </aqx>
        """
        let source = try #require(try AqxParser.parse(text).file.sources.first)
        #expect(source.levels.count == 2)
        #expect(source.levels[0].zooms.toList() == [5])
        #expect(!source.levels[1].zooms.isContiguous)
    }

    @Test("custom projection marks the source unsupported")
    func customProjection() throws {
        let text = """
        <aqx version="11">
          <source id="P"><name>Lambert</name>
            <level><zoom-values>1-5</zoom-values><server>https://x.example/{$z}</server>
              <projection-code>EPSG:2154</projection-code>
              <projection-true-origin-x>700000</projection-true-origin-x>
            </level>
          </source>
          <source id="M"><name>Mercator</name>
            <level><zoom-values>1-5</zoom-values><server>https://x.example/{$z}</server>
              <projection-code>EPSG:3857</projection-code>
              <bbox-crs-code>3857</bbox-crs-code>
            </level>
          </source>
        </aqx>
        """
        let sources = try AqxParser.parse(text).file.sources
        #expect(sources.count == 2)
        let lambert = sources[0]
        let mercator = sources[1]
        #expect(!lambert.isSupported)
        #expect((lambert.unsupportedReason ?? "").contains("EPSG:2154"))
        #expect(mercator.isSupported)
        #expect(mercator.levels.first?.bboxCrs == "EPSG:3857")
    }

    @Test("unknown elements and variables produce warnings but not failures")
    func unknownElements() throws {
        let text = """
        <aqx version="13">
          <mystery/>
          <source id="W"><name>W</name><whatever/>
            <level><zoom-values>1-2</zoom-values>
              <server>https://x.example/{$z}/{$x}/{$y}</server>
              <variable set="v" type="single"/>
              <odd-tag/>
            </level>
          </source>
        </aqx>
        """
        let result = try AqxParser.parse(text)
        #expect(result.file.sources.count == 1)
        #expect(result.file.sources.first?.isSupported == true)
        let messages = result.warnings.map(\.message)
        #expect(messages.contains { $0.contains("<mystery>") })
        #expect(messages.contains { $0.contains("<whatever>") })
        #expect(messages.contains { $0.contains("<variable>") })
        #expect(messages.contains { $0.contains("<odd-tag>") })
    }

    @Test("broken levels and sources are skipped with a warning")
    func brokenLevels() throws {
        let text = """
        <aqx version="11">
          <source><name>No id</name></source>
          <source id="S"><name>S</name>
            <level><zoom-values>1-3</zoom-values></level>
            <level><zoom-values>x</zoom-values><server>https://x.example/</server></level>
          </source>
          <source id="S"><name>Duplicate</name>
            <level><zoom-values>1</zoom-values><server>https://x.example/</server></level>
          </source>
        </aqx>
        """
        let result = try AqxParser.parse(text)
        #expect(result.file.sources.count == 1)
        let source = try #require(result.file.sources.first)
        #expect(source.id == "S")
        #expect(!source.isSupported, "a source without any usable level is unsupported")
        let messages = result.warnings.map(\.message)
        #expect(messages.contains { $0.contains("without an id") })
        #expect(messages.contains { $0.contains("no <server>") })
        #expect(messages.contains { $0.contains("level ignored") })
        #expect(messages.contains { $0.contains("duplicate source id") })
    }

    @Test("source without a name falls back to its id")
    func nameFallsBackToId() throws {
        let level = "<level><zoom-values>1</zoom-values><server>https://x/</server></level>"
        let text = "<aqx><source id=\"Z\">\(level)</source></aqx>"
        #expect(try AqxParser.parse(text).file.sources.first?.name == "Z")
    }

    @Test("byte order mark is tolerated")
    func byteOrderMark() throws {
        let text = "\u{FEFF}<?xml version=\"1.0\"?><aqx version=\"11\"></aqx>"
        #expect(text.unicodeScalars.first?.value == 0xFEFF, "the test input must really start with a BOM")
        #expect(try AqxParser.parse(text).file.sources.isEmpty)
    }

    @Test("DOCTYPE and entities are rejected")
    func doctypeRejected() {
        let xxe = "<?xml version=\"1.0\"?><!DOCTYPE aqx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><aqx>&x;</aqx>"
        #expect(throws: AqxParseError.self) { try AqxParser.parse(xxe) }
        #expect(throws: AqxParseError.self) { try AqxParser.parse("<!doctype aqx><aqx/>") }
    }

    @Test("invalid input gives a clear exception")
    func invalidInput() throws {
        #expect(throws: AqxParseError.self) { try AqxParser.parse("") }
        #expect(throws: AqxParseError.self) { try AqxParser.parse("not xml at all") }
        #expect(throws: AqxParseError.self) { try AqxParser.parse("<aqx><source>") }
        do {
            _ = try AqxParser.parse("<gpx/>")
            Issue.record("a wrong root must be an error")
        } catch let error as AqxParseError {
            #expect(error.message.contains("gpx"))
        }
    }

    @Test("oversized input is rejected")
    func oversized() {
        let huge = "<aqx>" + String(repeating: " ", count: AqxParser.maxFileChars) + "</aqx>"
        #expect(throws: AqxParseError.self) { try AqxParser.parse(huge) }
    }

    @Test("warnings are reported even for an empty file")
    func emptyFile() throws {
        #expect(try warningsOf("<aqx version=\"11\"/>").isEmpty)
    }

    @Test("servers wrapped in a servers element are read")
    func serversElement() throws {
        // OpenSnowMap (COM.109): <level><servers><referer/><server/></servers></level>.
        let result = try AqxParser.parse(Fixtures.text("aqx/user/COM.109.AQX"))
        #expect(result.file.sources.map(\.id) == ["OSMSNOWM", "OSMSKIM"])
        let base = try #require(result.file.sources.first)
        let level = try #require(base.levels.first)
        #expect(base.levels.count == 1)
        #expect(level.servers == ["http://tiles.opensnowmap.org/base_snow_map/{$z}/{$x}/{$y}.png"])
        #expect(level.referer == "http://www.opensnowmap.org/")
        #expect(level.zooms.toSpec() == "3,5,7-17")
        #expect(result.file.sources.last?.isLayer == true)
        #expect(result.warnings.isEmpty, "unexpected warnings: \(result.warnings)")
    }

    @Test("a WMS asking for Web Mercator gets its bbox in metres")
    func wmsInMercator() throws {
        let sources = try AqxParser.parse(
            Fixtures.text("aqx/user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX")
        ).file.sources
        let tracks = try #require(sources.first { $0.id == "TRACESHIVERNALES" })
        #expect(tracks.levels.first?.bboxCrs == LevelDef.crsWebMercator)
        // An explicit <bbox-crs-code> always wins.
        let explicit = """
        <aqx version="11"><source id="w"><name>w</name><level><zoom-values>1-5</zoom-values>
          <bbox-crs-code>4326</bbox-crs-code>
          <server><![CDATA[https://h.example.org/wms?SRS=EPSG:3857&BBOX={$bbox}]]></server></level></source></aqx>
        """
        #expect(try AqxParser.parse(explicit).file.sources.first?.levels.first?.bboxCrs == LevelDef.crsWGS84)
    }

    @Test("an XML declaration naming another encoding is read as UTF-8")
    func declaredEncoding() throws {
        let text = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><aqx><name>Crête</name></aqx>"
        #expect(XmlTree.declaringUtf8(text).hasPrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        #expect(try AqxParser.parse(text).file.name == "Crête")
    }
}
