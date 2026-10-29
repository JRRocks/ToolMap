// The iPhone's routing engine against a fake BRouter web server (no network): the request it sends and how it reads the
// answers, the way and the messages BRouter gives instead.

import Foundation
import Testing
@testable import ToolMapCore

/// A fake BRouter server: answers every request with `answer` and records the URLs asked.
final class StubBRouterServer: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var recorded: [URL] = []
    nonisolated(unsafe) private static var answer: (Int, Data) = (200, Data())

    static func reset(status: Int, body: String) {
        lock.lock()
        recorded = []
        answer = (status, Data(body.utf8))
        lock.unlock()
    }

    static var urls: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        StubBRouterServer.lock.lock()
        StubBRouterServer.recorded.append(url)
        let (status, body) = StubBRouterServer.answer
        StubBRouterServer.lock.unlock()
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:]) else {
            return
        }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@Suite(.serialized)
struct BRouterWebEngineTests {
    private func engine() -> BRouterWebEngine {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubBRouterServer.self]
        return BRouterWebEngine(session: URLSession(configuration: configuration), userAgent: "ToolMap/test")
    }

    private func query(_ url: URL) -> [String: String] {
        var out: [String: String] = [:]
        for item in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] {
            out[item.name] = item.value ?? ""
        }
        return out
    }

    private let from = RouteStop(lat: 45.9, lon: 6.8)
    private let to = RouteStop(lat: 45.91, lon: 6.81)

    @Test("asks the public server for the leg, the zones as nogos, and reads its GeoJSON")
    func asksAndReads() async throws {
        StubBRouterServer.reset(status: 200, body: BRouterTests.sample)
        let zone = AvoidZone(lat: 45.905, lon: 6.79, radiusMeters: 250)
        let result = await engine().route(from: from, to: to, profile: RoutingProfiles.hiking, zones: [zone])
        guard case let .ok(ok) = result else {
            Issue.record("no way")
            return
        }
        #expect(ok.points.count == 3)
        #expect(ok.timeMillis == 2_710_000)
        let url = try #require(StubBRouterServer.urls.first)
        #expect(url.absoluteString.hasPrefix("https://brouter.de/brouter?"))
        let q = query(url)
        #expect(q["lonlats"] == "6.8000000,45.9000000|6.8100000,45.9100000")
        #expect(q["profile"] == "hiking-mountain")
        #expect(q["alternativeidx"] == "0")
        #expect(q["format"] == "geojson")
        #expect(q["nogos"] == "6.7900000,45.9050000,250")
    }

    @Test("without zone, no nogos is sent")
    func noZoneNoNogos() async throws {
        StubBRouterServer.reset(status: 200, body: BRouterTests.sample)
        let result = await engine().route(from: from, to: to, profile: RoutingProfiles.bike)
        guard case .ok = result else {
            Issue.record("no way")
            return
        }
        let url = try #require(StubBRouterServer.urls.first)
        let q = query(url)
        #expect(q["nogos"] == nil)
        #expect(q["profile"] == "trekking")
    }

    @Test("the messages of the server are sorted as those of the Android service")
    func messages() async {
        StubBRouterServer.reset(status: 500, body: "datafile E5_N45.rd5 not found")
        let noData = await engine().route(from: from, to: to, profile: RoutingProfiles.hiking)
        #expect(noData == .failed(LegResult.Failed(.noData, "datafile E5_N45.rd5 not found")))

        StubBRouterServer.reset(status: 500, body: "profile mtb-perso does not exist")
        let profile = await engine().route(from: from, to: to, profile: "mtb-perso")
        guard case let .failed(unknown) = profile else {
            Issue.record("a way for an unknown profile")
            return
        }
        #expect(unknown.error == .unknownProfile)
    }

    @Test("a broken answer or an empty error is no way")
    func brokenAnswers() async {
        StubBRouterServer.reset(status: 200, body: "{ not json")
        let broken = await engine().route(from: from, to: to, profile: RoutingProfiles.hiking)
        #expect(broken == .failed(LegResult.Failed(.other, "{ not json")))

        StubBRouterServer.reset(status: 503, body: "")
        let empty = await engine().route(from: from, to: to, profile: RoutingProfiles.hiking)
        #expect(empty == .failed(LegResult.Failed(.other, "HTTP 503")))
    }
}
