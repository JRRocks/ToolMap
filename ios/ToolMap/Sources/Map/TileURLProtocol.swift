import Foundation
import MapLibre
import ToolMapCore

/// The key of a map layer in the style and in its tile address (`MapsController.layerSpecs`): the map id, then parts
/// separated by `~`: the elevation band of the layer (`alt=1500,`, `whole=8,16`, the query of `ElevationMask`, P20), the
/// rank of a map shown a second time (`n2`), or `coverage` for the stored-coverage overlay. Only characters a URL path
/// allows, so that the key travels in the tile address as it is, and a new band is a new address that MapLibre asks
/// again (Android: `StyleSources`, the band in the query of the tile URL).
enum MapStyleKey {
    static let separator: Character = "~"
    private static let coverageMark = "coverage"
    private static let rankMark = "n"

    /// The key of a layer of `mapId`, drawn in `mask` (nil: whole), the `rank`-th one of the stack with that look.
    static func make(_ mapId: String, mask: ElevationMask?, rank: Int) -> String {
        var key = mapId
        if let mask {
            let parts = mask.query().split(separator: "&").map(String.init)
            for part in parts {
                key += String(separator) + part
            }
        }
        if rank > 1 {
            key += "\(separator)\(rankMark)\(rank)"
        }
        return key
    }

    /// The key of the stored-coverage overlay of `mapId`.
    static func coverage(_ mapId: String) -> String {
        "\(mapId)\(separator)\(coverageMark)"
    }

    /// The map shown by a layer key (the key itself for a plain map).
    static func mapId(_ key: String) -> String {
        guard let index = key.firstIndex(of: separator) else { return key }
        return String(key[..<index])
    }

    /// The parts of a key: the map, the URL query of its elevation band (nil when drawn whole), coverage or not.
    static func parse(_ key: String) -> (mapId: String, query: String?, coverage: Bool) {
        let parts = key.split(separator: separator, omittingEmptySubsequences: false).map(String.init)
        guard let first = parts.first else { return (key, nil, false) }
        var band: [String] = []
        var coverage = false
        for part in parts.dropFirst() {
            if part == coverageMark {
                coverage = true
            } else if part.contains("=") {
                band.append(part)
            }
        }
        return (first, band.isEmpty ? nil : band.joined(separator: "&"), coverage)
    }
}

/// A tile asked by the map: `https://tiles.toolmap.local/<layer key>/<z>/<x>/<y>` (same fake host as Android, ADR-002);
/// the layer key is described by `MapStyleKey`.
struct TileAddress: Equatable {
    static let host = "tiles.toolmap.local"

    /// The layer key of the address (`MapStyleKey`); for a plain map, its id.
    let sourceId: String
    /// The map whose tile is asked.
    let mapId: String
    /// The elevation band the tile is drawn in, nil when whole (P20).
    let mask: ElevationMask?
    /// A tile of the stored-coverage overlay of `mapId`.
    let coverage: Bool
    let z: Int
    let x: Int
    let y: Int

    init?(url: URL) {
        guard url.host == TileAddress.host else { return nil }
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count == 4, let z = Int(parts[1]), let x = Int(parts[2]), let y = Int(parts[3]) else { return nil }
        let key = MapStyleKey.parse(parts[0])
        sourceId = parts[0]
        mapId = key.mapId
        coverage = key.coverage
        // The band may also come in the query of the address (Android's form).
        mask = ElevationMask.parse(key.query ?? url.query)
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
/// the fake host; `TileService` answers it from the MBTiles storage or from the server. A layer drawn in an elevation
/// band has its tiles cut out along the contour (P20), and the stored-coverage overlay is drawn here too.
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
        let host = request.url?.host
        return host == TileAddress.host || host == GlyphServer.host
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        if let url = request.url, url.host == GlyphServer.host {
            GlyphServer.shared.load(url) { [weak self] data in
                guard let self, !self.state.isStopped else { return }
                if let data {
                    self.answer(status: 200, data: data, contentType: "application/x-protobuf")
                } else {
                    self.answer(status: 404)
                }
            }
            return
        }
        guard let url = request.url, let tile = TileAddress(url: url) else {
            answer(status: 404)
            return
        }
        if tile.coverage {
            serveCoverage(tile)
        } else if let mask = tile.mask, !mask.isWholeAt(tile.z) {
            serveMasked(tile, mask)
        } else {
            MapTileContext.shared.load(tile.mapId, tile.z, tile.x, tile.y) { [weak self] data, contentType in
                self?.deliver(data, contentType)
            }
        }
    }

    override func stopLoading() {
        state.stop()
    }

    /// A tile of the map, or 404: MapLibre draws nothing for a missing tile and does not retry a 404.
    private func deliver(_ data: Data?, _ contentType: String?) {
        guard !state.isStopped else { return }
        if let data {
            answer(status: 200, data: data, contentType: contentType)
        } else {
            answer(status: 404)
        }
    }

    /// The stored coverage of a map (Android `CoverageInterceptor`): never touches the network.
    private func serveCoverage(_ tile: TileAddress) {
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let found = MapTileContext.shared.coverage(tile.mapId, tile.z, tile.x, tile.y)
            switch found {
            case Coverage.full: self?.deliver(MapTileContext.fullTile, "image/png")
            case Coverage.partial: self?.deliver(MapTileContext.partialTile, "image/png")
            case Coverage.none: self?.deliver(nil, nil)
            }
        }
    }

    /// A tile of a layer drawn in an elevation band (P20). The elevation tile under it says whether the band covers none
    /// of it (no tile), all of it (the plain tile) or part of it (the plain tile cut out along the contour, never
    /// stored). Without the elevations (offline and not stored), the layer shows nothing here.
    private func serveMasked(_ tile: TileAddress, _ mask: ElevationMask) {
        guard TileService.shared.source(LayerElevation.demSourceId) != nil else {
            MapTileContext.shared.load(tile.mapId, tile.z, tile.x, tile.y) { [weak self] data, contentType in
                self?.deliver(data, contentType)
            }
            return
        }
        let window = DemWindow.of(tile.z, tile.x, tile.y)
        TileElevationMasker.shared.dem(window) { [weak self] grid in
            guard let self, !self.state.isStopped else { return }
            guard let grid else {
                self.deliver(nil, nil)
                return
            }
            let (low, high) = MaskRaster.range(grid.elevations, grid.size, window)
            let coverage = mask.coverage(low, high)
            if coverage == ElevationMask.Coverage.none {
                self.deliver(nil, nil)
                return
            }
            MapTileContext.shared.load(tile.mapId, tile.z, tile.x, tile.y) { [weak self] data, contentType in
                guard let self, !self.state.isStopped else { return }
                guard let data, coverage == ElevationMask.Coverage.part else {
                    self.deliver(data, contentType)
                    return
                }
                DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                    let image = TileElevationMasker.mask(data, grid, window, mask)
                    self?.deliver(image, image == nil ? nil : "image/png")
                }
            }
        }
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
