import CoreGraphics
import Foundation
import ImageIO
import ToolMapCore

extension TileService {
    /// The app's tile pipeline: MBTiles files in Application Support/tiles (kept by iOS, not backed up to iCloud).
    static let shared: TileService = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var url = base.appendingPathComponent("tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        return TileService(directory: url, userAgent: "ToolMap/\(version) (iOS)")
    }()
}

/// What the tile protocol needs beyond `TileService` (Android `MbTilesStore`, `ForcedRefresh`, `CoverageInterceptor`):
/// where the tiles of a map are stored, for the stored-coverage overlay and the storage figures, and the maps whose
/// stored tiles are fetched again in this session (« Forcer la mise à jour des données locales »).
final class MapTileContext: @unchecked Sendable {
    static let shared = MapTileContext()

    private let lock = NSLock()
    private var keys = StorageKeys(builtins: [])
    private var forced: Set<String> = []
    private var refreshed: Set<String> = []
    private let session = TileService.makeSession()

    /// Coverage tiles (Android `CoverageInterceptor`): green with a frame where the tile is stored, pale green where only
    /// deeper tiles are.
    static let fullTile: Data? = try? SolidPng.create(256, fill: 0x6633_CC33, frame: 0xCC1E_7A1E, frameWidth: 2)
    static let partialTile: Data? = try? SolidPng.create(256, fill: 0x4466_DD66)

    /// The maps shipped with the app, whose tile sets give their names to the shared storages (ADR-031).
    func setBuiltins(_ builtins: [TileSource]) {
        let made = StorageKeys(builtins: builtins)
        lock.lock()
        keys = made
        lock.unlock()
    }

    private var storageKeys: StorageKeys {
        lock.lock()
        defer { lock.unlock() }
        return keys
    }

    /// The maps whose stored tiles are fetched again (this session only); an empty set forgets what was refreshed.
    func setForced(_ ids: Set<String>) {
        lock.lock()
        forced = ids
        refreshed = refreshed.filter { key in ids.contains { key.hasPrefix($0 + "/") } }
        lock.unlock()
    }

    /// True the first time a tile of a forced map is asked in this session: it is then fetched again from its server.
    private func shouldRefresh(_ mapId: String, _ z: Int, _ x: Int, _ y: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard forced.contains(mapId) else { return false }
        return refreshed.insert("\(mapId)/\(z)/\(x)/\(y)").inserted
    }

    /// Every storage the tiles of `source` can come from (several for a multi-zoom map).
    func storageIds(of source: TileSource) -> [String] {
        let service = TileService.shared
        return storageKeys.storageIdsOf(source, lookup: { service.source($0) })
    }

    /// The MBTiles file of a storage, or nil when nothing was ever stored there (it is not created).
    func existingFile(_ storageId: String) -> MbTilesFile? {
        let service = TileService.shared
        let path = service.directory.appendingPathComponent("\(storageId).mbtiles").path
        guard FileManager.default.fileExists(atPath: path) else { return nil }
        return try? service.file(storageId)
    }

    /// What is stored for tile `z`/`x`/`y` of the map `mapId` (Android `CoverageInterceptor`).
    func coverage(_ mapId: String, _ z: Int, _ x: Int, _ y: Int) -> Coverage {
        let service = TileService.shared
        guard let source = service.source(mapId),
              let storageId = storageKeys.storageIdFor(source, z, lookup: { service.source($0) }),
              let file = existingFile(storageId),
              let found = try? file.coverage(TileId(z, x, y))
        else { return .none }
        return found
    }

    /// The tile of a map as the map draws it: from `TileService`, or once fetched again from its server when the map is
    /// forced to update (the new tile replaces the stored one).
    func load(
        _ mapId: String,
        _ z: Int,
        _ x: Int,
        _ y: Int,
        completion: @escaping @Sendable (Data?, String?) -> Void
    ) {
        let service = TileService.shared
        guard shouldRefresh(mapId, z, x, y), !service.offline, let source = service.source(mapId) else {
            service.load(mapId, z, x, y, completion: completion)
            return
        }
        let resolver = TileResolver(lookup: { service.source($0) })
        guard case let .direct(leafSource, level, tile) = resolver.resolve(source, z, x, y, ScalingPolicy()),
              level.hasServer
        else {
            service.load(mapId, z, x, y, completion: completion)
            return
        }
        let leaf = TileRecipe.Leaf(source: leafSource, level: level, tile: tile)
        guard let request = service.request(leaf) else {
            service.load(mapId, z, x, y, completion: completion)
            return
        }
        session.dataTask(with: request) { data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status), let data, let type = MbTilesFile.contentTypeOf(data) else {
                service.load(mapId, z, x, y, completion: completion)
                return
            }
            if let storageId = service.storageId(leaf), let file = try? service.file(storageId) {
                try? file.put(tile, StoredTile(bytes: data, contentType: type))
            }
            completion(data, type)
        }.resume()
    }
}

/// A decoded elevation tile: `size` × `size` metres, row after row from the top.
final class MaskDemGrid: @unchecked Sendable {
    let size: Int
    let elevations: [Float]

    init(size: Int, elevations: [Float]) {
        self.size = size
        self.elevations = elevations
    }
}

/// Draws a layer's tiles in an elevation band (P20, Android `BitmapElevationMasker`): the pixels outside the band
/// become transparent. Decoded elevation tiles are kept in memory, so a new band only redraws the map tiles.
final class TileElevationMasker: @unchecked Sendable {
    static let shared = TileElevationMasker()

    /// About 256 KB each: a screen at zoom 13 or finer reads a few of them, coarser zooms one per tile.
    private static let cachedTiles = 64

    private let lock = NSLock()
    private var cache: [String: MaskDemGrid] = [:]
    private var order: [String] = []
    private var waiting: [String: [@Sendable (MaskDemGrid?) -> Void]] = [:]

    /// The elevation tile under `window`, decoded, from memory or from the tile pipeline (`terrarium`, stored tiles
    /// only when offline); nil when it is not available. Requests for the same tile at the same time share one load.
    func dem(_ window: DemWindow, completion: @escaping @Sendable (MaskDemGrid?) -> Void) {
        let key = "\(window.demZ)/\(window.demX)/\(window.demY)"
        lock.lock()
        if let grid = cache[key] {
            lock.unlock()
            completion(grid)
            return
        }
        if waiting[key] != nil {
            waiting[key]?.append(completion)
            lock.unlock()
            return
        }
        waiting[key] = [completion]
        lock.unlock()
        load(window) { [weak self] grid in
            self?.finish(key, grid)
        }
    }

    private func finish(_ key: String, _ grid: MaskDemGrid?) {
        lock.lock()
        if let grid {
            cache[key] = grid
            order.removeAll { $0 == key }
            order.append(key)
            while order.count > TileElevationMasker.cachedTiles {
                let oldest = order.removeFirst()
                cache.removeValue(forKey: oldest)
            }
        }
        let callbacks = waiting.removeValue(forKey: key) ?? []
        lock.unlock()
        for callback in callbacks {
            callback(grid)
        }
    }

    private func load(_ window: DemWindow, completion: @escaping @Sendable (MaskDemGrid?) -> Void) {
        let service = TileService.shared
        guard let dem = service.source(LayerElevation.demSourceId) else {
            completion(nil)
            return
        }
        let resolver = TileResolver(lookup: { service.source($0) })
        guard case let .direct(source, level, tile) = resolver.resolve(dem, window.demZ, window.demX, window.demY, .off)
        else {
            completion(nil)
            return
        }
        service.fetch(TileRecipe.Leaf(source: source, level: level, tile: tile)) { stored in
            guard let bytes = stored?.bytes, !bytes.isEmpty else {
                completion(nil)
                return
            }
            completion(TileElevationMasker.decodeDem(bytes))
        }
    }

    /// `tile` (PNG, JPEG or WebP bytes) with its pixels outside `mask` made transparent, as a PNG; nil if undecodable.
    static func mask(_ tile: Data, _ grid: MaskDemGrid, _ window: DemWindow, _ mask: ElevationMask) -> Data? {
        guard var image = decodeRGBA(tile) else { return nil }
        let size = image.size
        let elevations = MaskRaster.elevations(grid.elevations, grid.size, window, size)
        let alphas = MaskRaster.alphas(elevations, size, mask)
        // The colours are premultiplied: every channel is scaled by the mask's opacity.
        for index in 0 ..< size * size {
            let alpha = UInt32(alphas[index])
            if alpha == 255 { continue }
            let base = index * 4
            for channel in 0 ..< 4 {
                let value = UInt32(image.pixels[base + channel])
                image.pixels[base + channel] = UInt8((value * alpha + 127) / 255)
            }
        }
        return encodePng(image.pixels, size)
    }

    /// The Terrarium elevations of a tile (`red * 256 + green + blue / 256 - 32768` metres).
    static func decodeDem(_ bytes: Data) -> MaskDemGrid? {
        if let png = try? PngDecoder.decode(bytes), png.width == png.height, png.width > 0, png.channels >= 3 {
            let size = png.width
            var values = [Float](repeating: 0, count: size * size)
            for y in 0 ..< size {
                for x in 0 ..< size {
                    let meters = TerrariumTile.decodeElevation(png.sample(x, y, 0), png.sample(x, y, 1), png.sample(x, y, 2))
                    values[y * size + x] = Float(meters)
                }
            }
            return MaskDemGrid(size: size, elevations: values)
        }
        // Another format: the colours through Core Graphics (an elevation tile is opaque, so not premultiplied).
        guard let image = decodeRGBA(bytes) else { return nil }
        let size = image.size
        var values = [Float](repeating: 0, count: size * size)
        for index in 0 ..< size * size {
            let base = index * 4
            let red = Int(image.pixels[base])
            let green = Int(image.pixels[base + 1])
            let blue = Int(image.pixels[base + 2])
            values[index] = Float(TerrariumTile.decodeElevation(red, green, blue))
        }
        return MaskDemGrid(size: size, elevations: values)
    }

    private struct RGBAImage {
        var pixels: [UInt8]
        let size: Int
    }

    /// A square image as premultiplied RGBA bytes, the top row first.
    private static func decodeRGBA(_ data: Data) -> RGBAImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let size = image.width
        guard size > 0, image.height == size else { return nil }
        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.premultipliedLast.rawValue
        let drawn = pixels.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: size * 4,
                space: space,
                bitmapInfo: info
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
            return true
        }
        return drawn ? RGBAImage(pixels: pixels, size: size) : nil
    }

    private static func encodePng(_ pixels: [UInt8], _ size: Int) -> Data? {
        let space = CGColorSpaceCreateDeviceRGB()
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue)
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let image = CGImage(
                  width: size,
                  height: size,
                  bitsPerComponent: 8,
                  bitsPerPixel: 32,
                  bytesPerRow: size * 4,
                  space: space,
                  bitmapInfo: info,
                  provider: provider,
                  decode: nil,
                  shouldInterpolate: false,
                  intent: .defaultIntent
              )
        else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, "public.png" as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return output as Data
    }
}
