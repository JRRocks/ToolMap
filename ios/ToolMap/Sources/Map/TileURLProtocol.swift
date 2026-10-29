import Foundation
import MapLibre
import ToolMapCore

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
/// the fake host; `TileService` answers it from the MBTiles storage or from the server.
final class TileURLProtocol: URLProtocol {
    /// Puts this protocol in front of MapLibre's network stack. Call before creating the first map view.
    static func install() {
        let manager = MLNNetworkConfiguration.sharedManager
        let configuration: URLSessionConfiguration = manager.sessionConfiguration
        var classes: [AnyClass] = [TileURLProtocol.self]
        classes.append(contentsOf: configuration.protocolClasses ?? [])
        configuration.protocolClasses = classes
        manager.sessionConfiguration = configuration
    }

    private let state = LoadState()

    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == TileAddress.host
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url, let tile = TileAddress(url: url) else {
            answer(status: 404)
            return
        }
        TileService.shared.load(tile) { [weak self] data, contentType in
            guard let self, !self.state.isStopped else { return }
            if let data {
                self.answer(status: 200, data: data, contentType: contentType)
            } else {
                // MapLibre draws nothing for a missing tile and does not retry a 404.
                self.answer(status: 404)
            }
        }
    }

    override func stopLoading() {
        state.stop()
    }

    private func answer(status: Int, data: Data? = nil, contentType: String? = nil) {
        guard let url = request.url else { return }
        var headers: [String: String] = [:]
        if let contentType { headers["Content-Type"] = contentType }
        if let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers) {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        }
        if let data {
            client?.urlProtocol(self, didLoad: data)
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    /// Set when MapLibre no longer wants the tile (it scrolled away): the answer is then dropped.
    private final class LoadState: @unchecked Sendable {
        private let lock = NSLock()
        private var stopped = false

        var isStopped: Bool {
            lock.lock()
            defer { lock.unlock() }
            return stopped
        }

        func stop() {
            lock.lock()
            stopped = true
            lock.unlock()
        }
    }
}
