// The tile pipeline of the app (P32.4), against a fake tile server: what Android checks with MockWebServer
// (TileInterceptorTest, OfflineFlowTest).

import CoreGraphics
import Foundation
import Testing
@testable import ToolMapCore

/// A fake tile server: answers every request with `answer` and records the requests.
final class StubTileServer: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var recorded: [URLRequest] = []
    nonisolated(unsafe) private static var answer: (Int, Data) = (404, Data())

    static func reset(status: Int, body: Data) {
        lock.lock()
        recorded = []
        answer = (status, body)
        lock.unlock()
    }

    static var requests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        StubTileServer.lock.lock()
        StubTileServer.recorded.append(request)
        let (status, body) = StubTileServer.answer
        StubTileServer.lock.unlock()
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])
        else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@Suite(.serialized)
struct TileServiceTests {
    private func service() -> TileService {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubTileServer.self]
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("toolmap-tiles-\(UUID().uuidString)")
        return TileService(directory: directory, session: URLSession(configuration: configuration), userAgent: "ToolMap/test")
    }

    private func png() throws -> Data {
        let context = try #require(TileComposer.context(256))
        context.setFillColor(red: 0.2, green: 0.6, blue: 0.3, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: 256, height: 256))
        let image = try #require(context.makeImage())
        return try #require(TileComposer.png(image))
    }

    private func load(_ service: TileService, _ id: String, _ z: Int, _ x: Int, _ y: Int) async -> (Data?, String?) {
        await withCheckedContinuation { continuation in
            service.load(id, z, x, y) { data, type in
                continuation.resume(returning: (data, type))
            }
        }
    }

    private func source() throws -> TileSource {
        try TileSource(
            id: "t",
            name: "Test",
            levels: [
                LevelDef(
                    zooms: ZoomSet.range(0, 18),
                    servers: ["https://tiles.example.org/{$z}/{$x}/{$y}.png"],
                    referer: "https://example.org/",
                    headers: [HttpHeader("X-Key", "1")],
                    cookie: "a=b"
                ),
            ]
        )
    }

    @Test("a tile is asked with the headers of its level, then stored and served without the network")
    func storedThenServed() async throws {
        let body = try png()
        StubTileServer.reset(status: 200, body: body)
        let tiles = service()
        let map = try source()
        tiles.register([map], builtins: [])
        let (data, type) = await load(tiles, "t", 14, 8460, 5906)
        #expect(data == body)
        #expect(type == "image/png")
        let requests = StubTileServer.requests
        #expect(requests.count == 1)
        let request = try #require(requests.first)
        #expect(request.url?.absoluteString == "https://tiles.example.org/14/8460/5906.png")
        #expect(request.value(forHTTPHeaderField: "Referer") == "https://example.org/")
        #expect(request.value(forHTTPHeaderField: "User-Agent") == "ToolMap/test")
        #expect(request.value(forHTTPHeaderField: "X-Key") == "1")
        #expect(request.value(forHTTPHeaderField: "Cookie") == "a=b")
        // The second time, the MBTiles answers.
        let (again, _) = await load(tiles, "t", 14, 8460, 5906)
        #expect(again == body)
        #expect(StubTileServer.requests.count == 1)
        #expect(tiles.storedFiles().count == 1)
    }

    @Test("offline, nothing is requested: only stored tiles are shown")
    func offline() async throws {
        let body = try png()
        StubTileServer.reset(status: 200, body: body)
        let tiles = service()
        tiles.register([try source()], builtins: [])
        tiles.offline = true
        let (data, _) = await load(tiles, "t", 10, 528, 368)
        #expect(data == nil)
        #expect(StubTileServer.requests.isEmpty)
    }

    @Test("a server error gives no tile, and a later success stores it")
    func serverError() async throws {
        StubTileServer.reset(status: 503, body: Data("busy".utf8))
        let tiles = service()
        tiles.register([try source()], builtins: [])
        let (failed, _) = await load(tiles, "t", 12, 2115, 1477)
        #expect(failed == nil)
        let body = try png()
        StubTileServer.reset(status: 200, body: body)
        let (data, _) = await load(tiles, "t", 12, 2115, 1477)
        #expect(data == body)
    }

    @Test("a forced zoom is composed from the tiles of the referenced map")
    func forcedZoom() async throws {
        let body = try png()
        StubTileServer.reset(status: 200, body: body)
        let tiles = service()
        let maps = try TileSource(
            id: "maps",
            name: "Maps",
            levels: [LevelDef(zooms: ZoomSet.range(1, 18), servers: ["https://maps.example.org/{$z}/{$x}/{$y}.png"])]
        )
        let forced = try TileSource(
            id: "mz",
            name: "Multi-zoom",
            levels: [LevelDef(zooms: ZoomSet.range(13, 14), servers: [MapRef(sourceId: "maps", tileZoom: 15).toServer()])]
        )
        tiles.register([maps, forced], builtins: [maps])
        let (data, type) = await load(tiles, "mz", 13, 4200, 2980)
        let composed = try #require(data)
        #expect(type == "image/png")
        #expect(MbTilesFile.contentTypeOf(composed) == "image/png")
        // A zoom-13 tile is made of 4 x 4 zoom-15 tiles of the referenced map.
        #expect(StubTileServer.requests.count == 16)
        #expect(StubTileServer.requests.allSatisfy { $0.url?.path.hasPrefix("/15/") == true })
    }
}
