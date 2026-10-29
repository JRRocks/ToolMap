// Port of core/src/test/kotlin/fr/toolmap/core/maps/OnlineCatalogTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct OnlineCatalogTests {
    private static let strange =
        "<aqx version=\"11\"><source id=\"mine\"><name>Mine</name><zoom-levels z=\"0-5\">" +
        "<server>https://example.org/{$z}/{$x}/{$y}.png</server></zoom-levels></source></aqx>"

    private let groups: [CatalogGroup]
    private let all: [CatalogEntry]

    init() throws {
        groups = try OnlineCatalog.load()
        all = groups.flatMap(\.entries)
    }

    @Test("every provider has maps, in the order of the screen")
    func providers() {
        #expect(groups.map(\.provider) == CatalogProvider.allCases)
    }

    @Test("every map can be displayed and has its own id")
    func displayable() {
        #expect(all.allSatisfy { $0.source.isSupported }, "a map of the catalogue cannot be displayed")
        #expect(Set(all.map(\.source.id)).count == all.count)
        #expect(all.allSatisfy { $0.source.id.hasPrefix($0.provider.prefix) })
    }

    @Test("no map of the catalogue is already one of the built in maps")
    func notBuiltin() throws {
        let builtin = try Set(BuiltinSources.load().map(\.id))
        #expect(!all.contains(where: { builtin.contains($0.source.id) }))
    }

    @Test("no tile address carries a key or goes through Bing or Google")
    func noKeyNoBing() {
        let servers = all.flatMap { entry in entry.source.levels.flatMap(\.servers) }.map { $0.lowercased() }
        #expect(!servers.contains(where: { $0.contains("bing") || $0.contains("google") }))
        #expect(!servers.contains(where: { $0.contains("apikey=") }))
    }

    @Test("only Ordnance Survey maps ask for a key")
    func onlyOrdnanceSurvey() throws {
        #expect(all.filter { !$0.keyParams.isEmpty }.allSatisfy { $0.provider == .ordnanceSurvey })
        let os = try #require(all.first(where: { $0.provider == .ordnanceSurvey }))
        #expect(os.keyParams == ["os-key"])
        #expect(all.filter { $0.provider != .ordnanceSurvey }.allSatisfy { $0.keyParams.isEmpty })
    }

    @Test("the key the user gives is filled in and trimmed")
    func keyFilledIn() throws {
        let os = try #require(all.first(where: { !$0.keyParams.isEmpty }))
        let keyed = OnlineCatalog.withKeys(os.source, ["os-key": " abc123 "])
        #expect(keyed.params["os-key"] == "abc123")
        #expect(OnlineCatalog.keyParams(keyed).isEmpty)
        #expect(OnlineCatalog.keyParams(os.source) == ["os-key"])
    }

    @Test("a source of no known provider is left out")
    func unknownProvider() throws {
        let strange = try AqxParser.parse(OnlineCatalogTests.strange).file.sources
        #expect(!OnlineCatalog.groups(strange).contains(where: { !$0.entries.isEmpty }))
    }
}
