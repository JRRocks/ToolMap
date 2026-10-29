// Port of core/src/test/kotlin/fr/toolmap/core/wizard/SourceDraftTest.kt (Kotlin `DraftIssue` of the wizard is
// `SourceDraftIssue` here).

import Testing
@testable import ToolMapCore

struct SourceDraftTests {
    private let good = SourceDraft(
        name: "Ma carte",
        urls: ["https://tiles.example/{$z}/{$x}/{$y}.png"],
        zoomSpec: "0-17"
    )

    /// Kotlin `good.copy(...)`.
    private func changed(_ change: (inout SourceDraft) -> Void) -> SourceDraft {
        var draft = good
        change(&draft)
        return draft
    }

    private func issues(_ draft: SourceDraft) -> Set<SourceDraftIssue> {
        Set(draft.validate())
    }

    @Test("a minimal draft is valid")
    func minimal() {
        #expect(good.isValid)
        #expect(good.validate().isEmpty)
    }

    @Test("each mistake has its own code")
    func eachMistake() {
        #expect(issues(changed { $0.name = " " }) == [.nameEmpty])
        #expect(issues(changed { $0.urls = [""] }).contains(.urlEmpty))
        #expect(issues(changed { $0.urls = ["ftp://x/{$z}/{$x}/{$y}"] }).contains(.urlNotHttp))
        #expect(issues(changed { $0.urls = ["https://x/tiles.png"] }).contains(.noTilePlaceholder))
        #expect(issues(changed { $0.urls = ["https://x/{$z}/{$x}.png"] }).contains(.noTilePlaceholder))
        #expect(issues(changed { $0.urls = ["https://{$serverpart}/{$z}/{$x}/{$y}"] }).contains(.unknownPlaceholder))
        #expect(issues(changed { $0.urls = ["https://x/{$z}/{$x}/{$y}?k={$param:k}"] }).contains(.missingParam))
        #expect(issues(changed { $0.zoomSpec = "a-b" }).contains(.zoomInvalid))
        #expect(issues(changed { $0.tileSize = 300 }).contains(.tileSizeInvalid))
        #expect(issues(changed { $0.updateDelay = "soon" }).contains(.updateDelayInvalid))
        #expect(issues(changed { $0.headersText = "no colon here" }).contains(.headerInvalid))
        #expect(issues(changed { $0.maxThreads = 0 }).contains(.threadsInvalid))
        #expect(issues(changed { $0.opacity = 101 }).contains(.opacityInvalid))
    }

    @Test("quadkey and tms and bbox templates count as tile placeholders")
    func tilePlaceholders() {
        let urls = ["https://x/{$q}.jpg", "https://x/{$z}/{$x}/{$!y}.png", "https://x/wms?BBOX={$bbox}", "https://x/{$ibbox}"]
        for url in urls {
            #expect(!issues(changed { $0.urls = [url] }).contains(.noTilePlaceholder), "\(url)")
        }
    }

    @Test("warnings do not block saving and come last")
    func warningsLast() {
        let draft = changed { draft in
            draft.urls = ["http://plain.example/{$z}/{$x}/{$y}.png"]
            draft.insecure = true
        }
        #expect(draft.isValid)
        // `insecure` is only carried over from an imported file: it is not applied, so nothing to warn about.
        #expect(issues(draft) == [.cleartext])
        var mixed = draft
        mixed.name = ""
        #expect(mixed.validate().first == .nameEmpty)
    }

    @Test("a key left in the url is flagged and extractKeys moves it to a parameter")
    func keyInUrl() throws {
        let draft = changed { $0.urls = ["https://x/{$z}/{$x}/{$y}.png?apikey=secret123"] }
        #expect(issues(draft).contains(.keyInUrl))
        let moved = draft.extractKeys()
        #expect(moved.urls == ["https://x/{$z}/{$x}/{$y}.png?apikey={$param:apikey}"])
        #expect(moved.params == ["apikey": "secret123"])
        #expect(moved.validate().isEmpty)
        #expect(try moved.toSource().params == ["apikey": "secret123"])
    }

    @Test("unused parameters are not written to the source")
    func unusedParameters() throws {
        let source = try changed { $0.params = ["old": "x"] }.toSource()
        #expect(source.params.isEmpty)
    }

    @Test("http settings reach the level")
    func httpSettings() throws {
        let draft = changed { draft in
            draft.referer = " https://data.geopf.fr/ "
            draft.userAgent = "ToolMap-test"
            draft.headersText = "X-One: 1\n\nAccept-Language:  fr "
            draft.cookie = "a=b"
            draft.maxThreads = 2
            draft.updateDelay = "2w"
            draft.tileSize = 512
            draft.type = " Topo "
            draft.isLayer = true
            draft.opacity = 60
            draft.copyright = " © Moi "
        }
        let source = try draft.toSource()
        try #require(source.levels.count == 1)
        let level = source.levels[0]
        #expect(level.referer == "https://data.geopf.fr/")
        #expect(level.userAgent == "ToolMap-test")
        #expect(level.headers == [HttpHeader("X-One", "1"), HttpHeader("Accept-Language", "fr")])
        #expect(level.cookie == "a=b")
        #expect(level.maxThreads == 2)
        #expect(level.updateDelay == "2W")
        #expect(level.tileSize == 512)
        #expect(source.type == "topo")
        #expect(source.isLayer)
        #expect(source.opacity == 60)
        #expect(source.copyright == "© Moi")
        #expect(try level.zooms == ZoomSet.range(0, 17))
    }

    @Test("an invalid draft cannot become a source")
    func invalidDraft() {
        #expect(throws: ToolMapError.self) {
            _ = try changed { $0.name = "" }.toSource()
        }
    }

    @Test("draft survives the aqx round trip and back into a draft")
    func aqxRoundTrip() throws {
        let draft = changed { draft in
            draft.urls = ["https://{a,b}.example/{$z}/{$x}/{$y}.png?k={$param:k}"]
            draft.params = ["k": "v"]
            draft.referer = "https://ref.example/"
            draft.headersText = "X-A: 1"
            draft.zoomSpec = "2-5,9"
            draft.previewLocation = LonLatZoom(lon: 2.35, lat: 48.85, zoom: 9)
            draft.updateDelay = "3M"
            draft.bboxCrs = "EPSG:3857"
        }
        var withId = draft
        withId.id = "ma-carte"
        let source = try withId.toSource()
        let sources = try AqxParser.parse(AqxWriter.write([source])).file.sources
        try #require(sources.count == 1)
        let parsed = sources[0]
        #expect(parsed == source)
        let back = try #require(SourceDraft.fromSource(parsed))
        #expect(back == withId)
    }

    @Test("a source with several levels cannot be edited in the wizard")
    func severalLevels() throws {
        let single = try good.toSource()
        let first = try #require(single.levels.first)
        let zooms = try ZoomSet.range(18, 19)
        let second = first.withFields { $0.zooms = zooms }
        let two = single.withFields { $0.levels = single.levels + [second] }
        #expect(SourceDraft.fromSource(two) == nil)
        let unsupported = single.withFields { $0.unsupportedReason = "projection" }
        #expect(SourceDraft.fromSource(unsupported) == nil)
    }

    @Test("ids come from the name")
    func ids() throws {
        #expect(SourceIds.fromName("  Ma carte — Été !  ") == "ma-carte-ete")
        #expect(SourceIds.fromName("???") == "map")
        #expect(SourceIds.fromName(String(repeating: "a", count: 100)).count == 40)
        #expect(SourceIds.unique("x", taken: { _ in false }) == "x")
        #expect(SourceIds.unique("x", taken: { $0 == "x" || $0 == "x-2" }) == "x-3")
        #expect(try good.toSource().id == "ma-carte", "id is derived from the name when left blank")
    }

    @Test("preview tile uses the preview location or Paris")
    func previewTile() throws {
        let zooms = try ZoomSet.range(0, 17)
        let paris = try PreviewTile.choose(zooms, nil)
        let parisTile = try TileMath.tileAt(2.35, 48.85, 12)
        #expect(paris.z == 12)
        #expect(paris.x == parisTile.x)
        #expect(paris.y == parisTile.y)
        let custom = try PreviewTile.choose(zooms, LonLatZoom(lon: -1.55, lat: 47.2, zoom: 10))
        #expect(custom.z == 10)
        // The location's zoom is outside what the source serves: the closest served zoom is used.
        #expect(try PreviewTile.choose(ZoomSet.range(6, 14), LonLatZoom(lon: 2.35, lat: 48.85, zoom: 17)).z == 14)
        #expect(try PreviewTile.choose(ZoomSet.range(8, 16), nil).z == 12)
        #expect(try PreviewTile.choose(ZoomSet.range(14, 16), nil).z == 14)
        #expect(try PreviewTile.choose(ZoomSet.of(1, 5, 9), LonLatZoom(lon: 0.0, lat: 0.0, zoom: 4)).z == 5)
        let url = try TileUrlBuilder(good.toSource()).build(paris.z, paris.x, paris.y)
        #expect(!(url ?? "").isEmpty)
    }
}
