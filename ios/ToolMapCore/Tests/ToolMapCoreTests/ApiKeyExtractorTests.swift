// Port of core/src/test/kotlin/fr/toolmap/core/wizard/ApiKeyExtractorTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct ApiKeyExtractorTests {
    @Test("key of the reference fixture url is extracted")
    func referenceFixture() throws {
        let sources = try AqxParser.parse(Fixtures.text("aqx/IGN_Topo_zoom_test.aqx")).file.sources
        let source = try #require(sources.first(where: { $0.id == "IGN_SCAN25TOUR" }))
        let firstLevel = try #require(source.levels.first)
        let pasted = try #require(firstLevel.servers.first)
        #expect(pasted.contains("apikey=ign_scan_ws"), "the fixture writes the key in the url")

        let result = ApiKeyExtractor.extract(pasted)
        #expect(result.found)
        #expect(result.params == ["apikey": "ign_scan_ws"])
        #expect(!result.template.contains("ign_scan_ws"))
        #expect(result.template.contains("apikey={$param:apikey}"))

        // The rewritten source asks for exactly the same tile as the original one.
        let level = firstLevel.withFields { $0.servers = [result.template] }
        var params = source.params
        for (key, value) in result.params {
            params[key] = value
        }
        let rewritten = source.withFields { fields in
            fields.params = params
            fields.levels = [level]
        }
        let before = try TileUrlBuilder(source).build(14, 8460, 5950)
        let after = try TileUrlBuilder(rewritten).build(14, 8460, 5950)
        #expect(before == after)
    }

    @Test("several names and case are handled")
    func severalNames() {
        let result = ApiKeyExtractor.extract("https://t.example/{$z}/{$x}/{$y}.png?Key=abc&token=def&size=2")
        #expect(result.params == ["key": "abc", "token": "def"])
        #expect(result.template == "https://t.example/{$z}/{$x}/{$y}.png?Key={$param:key}&token={$param:token}&size=2")
    }

    @Test("placeholders, empty values and other parameters are left alone")
    func leftAlone() {
        let urls = [
            "https://t/{$z}?apikey={$param:apikey}",
            "https://t/{$z}?apikey=",
            "https://t/{$z}?layer=apikey&size=3",
            "https://t/apikey=abc/{$z}",
        ]
        for url in urls {
            let result = ApiKeyExtractor.extract(url)
            #expect(!result.found, "\(url)")
            #expect(result.template == url)
        }
    }

    @Test("a name already used with another value gets a numbered parameter")
    func numberedParameter() {
        let result = ApiKeyExtractor.extract("https://t/{$z}?apikey=second", existingParams: ["apikey": "first"])
        #expect(result.params == ["apikey-2": "second"])
        #expect(result.template == "https://t/{$z}?apikey={$param:apikey-2}")
        // Same value: the existing parameter is reused.
        let same = ApiKeyExtractor.extract("https://t/{$z}?apikey=first", existingParams: ["apikey": "first"])
        #expect(same.template == "https://t/{$z}?apikey={$param:apikey}")
    }

    @Test("masked shows only the end of the secret")
    func masked() {
        #expect(ApiKeyExtractor.masked("ign_scan_ws") == "••••••n_ws")
        #expect(ApiKeyExtractor.masked("abc") == "•••")
        #expect(ApiKeyExtractor.masked("") == "")
    }
}
