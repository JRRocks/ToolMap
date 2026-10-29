import Foundation
import MapLibre
import ToolMapCore

/// The sources the map can show, by id, with one URL builder each (its server rotation is kept between tiles).
final class TileRegistry: @unchecked Sendable {
    static let shared = TileRegistry()

    private let lock = NSLock()
    private var sources: [String: TileSource] = [:]
    private var builders: [String: TileUrlBuilder] = [:]

    func register(_ list: [TileSource]) {
        lock.lock()
        defer { lock.unlock() }
        for source in list {
            sources[source.id] = source
            builders[source.id] = TileUrlBuilder(source)
        }
    }

    func lookup(_ id: String) -> (source: TileSource, builder: TileUrlBuilder)? {
        lock.lock()
        defer { lock.unlock() }
        guard let source = sources[id], let builder = builders[id] else { return nil }
        return (source, builder)
    }
}

/// A tile asked by the map: `https://tiles.toolmap.local/<source id>/<z>/<x>/<y>` (same fake host as Android, ADR-002).
struct TileAddress: Equatable {
    static let host = "tiles.toolmap.local"

    let sourceId: String
    let z: Int
    let x: Int
    let y: Int

    init?(url: URL) {
        guard url.host == TileAddress.host else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 4, let z = Int(parts[1]), let x = Int(parts[2]), let y = Int(parts[3]) else { return nil }
        sourceId = parts[0]
        self.z = z
        self.x = x
        self.y = y
    }

    static func template(for sourceId: String) -> String {
        let id = sourceId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? sourceId
        return "https://\(host)/\(id)/{z}/{x}/{y}"
    }
}

/// The iOS counterpart of the Android `TileInterceptor` (ADR-002): MapLibre asks every tile of a ToolMap source from
/// the fake host; this protocol builds the real URL from the AQX definition (`TileUrlBuilder`), adds the Referer,
/// User-Agent, headers and cookie of the level, and answers with the server's tile.
final class TileURLProtocol: URLProtocol {
    /// Identifies ToolMap to the tile servers that do not set their own User-Agent.
    static let userAgent: String = {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        return "ToolMap/\(version) (iOS)"
    }()

    /// Real tile requests: a plain session, outside MapLibre's configuration, so they are not intercepted again.
    static let upstream: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.httpMaximumConnectionsPerHost = 6
        configuration.timeoutIntervalForRequest = 20
        return URLSession(configuration: configuration)
    }()

    /// Puts this protocol in front of MapLibre's network stack. Call before creating the first map view.
    static func install() {
        let manager = MLNNetworkConfiguration.sharedManager
        let configuration: URLSessionConfiguration = manager.sessionConfiguration
        var classes: [AnyClass] = [TileURLProtocol.self]
        classes.append(contentsOf: configuration.protocolClasses ?? [])
        configuration.protocolClasses = classes
        manager.sessionConfiguration = configuration
    }

    private var upstreamTask: URLSessionDataTask?

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == TileAddress.host
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url, let tile = TileAddress(url: url),
              let entry = TileRegistry.shared.lookup(tile.sourceId),
              let level = entry.source.levelFor(tile.z),
              let address = try? entry.builder.build(tile.z, tile.x, tile.y),
              let remote = URL(string: address)
        else {
            answer(status: 404)
            return
        }
        var upstreamRequest = URLRequest(url: remote)
        upstreamRequest.setValue(level.userAgent ?? TileURLProtocol.userAgent, forHTTPHeaderField: "User-Agent")
        if let referer = level.referer {
            upstreamRequest.setValue(referer, forHTTPHeaderField: "Referer")
        }
        for header in level.headers {
            upstreamRequest.setValue(header.value, forHTTPHeaderField: header.name)
        }
        if let cookie = level.cookie {
            upstreamRequest.setValue(cookie, forHTTPHeaderField: "Cookie")
        }
        let dataTask = TileURLProtocol.upstream.dataTask(with: upstreamRequest) { [weak self] data, response, error in
            guard let self else { return }
            if let error {
                self.client?.urlProtocol(self, didFailWithError: error)
                return
            }
            let http = response as? HTTPURLResponse
            self.answer(
                status: http?.statusCode ?? 502,
                data: data,
                contentType: http?.value(forHTTPHeaderField: "Content-Type")
            )
        }
        upstreamTask = dataTask
        dataTask.resume()
    }

    override func stopLoading() {
        upstreamTask?.cancel()
        upstreamTask = nil
    }

    private func answer(status: Int, data: Data? = nil, contentType: String? = nil) {
        guard let url = request.url else { return }
        var headers: [String: String] = [:]
        if let contentType { headers["Content-Type"] = contentType }
        if let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        if let data, (200 ..< 300).contains(status) {
            client?.urlProtocol(self, didLoad: data)
        }
        client?.urlProtocolDidFinishLoading(self)
    }
}
