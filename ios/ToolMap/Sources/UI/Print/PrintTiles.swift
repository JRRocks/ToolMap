import Foundation
import ToolMapCore

/// What reading one tile for a print gave (Android `TileFetch`).
enum PrintTileFetch {
    case found(Data)
    /// The source has no tile there (outside its coverage, empty): nothing is missing, there is nothing to draw.
    case empty
    /// The tile could not be read: offline and not stored, network failure, server error. It is missing.
    case unavailable
}

/// Community tile servers forbid or strongly discourage bulk downloads (OpenStreetMap and OpenTopoMap usage policies):
/// the print never reaches them while drawing, and its download of the missing tiles is slowed down (Android
/// `BulkPolicy`). Matched on the server host, so an imported copy of the same service is covered too.
enum PrintBulkPolicy {
    private static let restrictedHosts = ["openstreetmap.org", "openstreetmap.fr", "opentopomap.org"]

    static func isRestricted(_ source: TileSource) -> Bool {
        for level in source.levels {
            for template in level.servers {
                let host = hostOf(template)
                for restricted in restrictedHosts where host == restricted || host.hasSuffix("." + restricted) {
                    return true
                }
            }
        }
        return false
    }

    /// The host part of a URL template (`https://{$s}.tile.openstreetmap.org/{$z}/…`), lower case.
    private static func hostOf(_ template: String) -> String {
        var rest = Substring(template)
        if let scheme = rest.range(of: "://") {
            rest = rest[scheme.upperBound...]
        }
        if let slash = rest.firstIndex(of: "/") {
            rest = rest[..<slash]
        }
        if let colon = rest.firstIndex(of: ":") {
            rest = rest[..<colon]
        }
        return rest.lowercased()
    }
}

/// Reads tiles for a print (Android `HttpPrintTileFetcher`): local storage first (stored tiles never expire here: a map
/// printed offline must not change because a delay ran out), then, online and for sources without a restrictive usage
/// policy, the server, whose answer is stored. A tile of a multi-zoom map, of a forced zoom or of a zoom the map does
/// not serve is drawn from the real tiles it is made of (`TileResolver`, ADR-031). Blocking: call it off the main thread,
/// from several threads at once if needed.
final class PrintTileFetcher: @unchecked Sendable {
    private static let session = TileService.makeSession()
    private static let notFound = 404

    private let service = TileService.shared
    private let resolver = TileResolver { TileService.shared.source($0) }
    private let composer = TileComposer()
    /// How many zoom levels a map may borrow from a neighbouring zoom (setting of the maps, as on screen).
    private let scaling: ScalingPolicy

    init(scaling: ScalingPolicy = ScalingPolicy()) {
        self.scaling = scaling
    }

    func fetch(_ source: TileSource, _ tile: TileId) -> PrintTileFetch {
        let recipe = resolver.resolve(source, tile.z, tile.x, tile.y, scaling)
        switch recipe {
        case let .direct(leafSource, level, leafTile):
            return fetchReal(TileRecipe.Leaf(source: leafSource, level: level, tile: leafTile))
        case .none:
            return .empty
        default:
            var unavailable = false
            let image = composer.compose(recipe) { leaf in
                switch self.fetchReal(leaf) {
                case let .found(data):
                    return data
                case .unavailable:
                    unavailable = true
                    return nil
                case .empty:
                    return nil
                }
            }
            if let image { return .found(image) }
            return unavailable ? .unavailable : .empty
        }
    }

    /// One real tile: stored, else from its server when that is allowed.
    func fetchReal(_ leaf: TileRecipe.Leaf) -> PrintTileFetch {
        if let bytes = stored(leaf) { return .found(bytes) }
        // A local package has no server: a tile it does not hold is missing.
        if !leaf.level.hasServer { return .unavailable }
        if service.offline || PrintBulkPolicy.isRestricted(leaf.source) { return .unavailable }
        return download(leaf)
    }

    func stored(_ leaf: TileRecipe.Leaf) -> Data? {
        guard let storageId = service.storageId(leaf),
              let file = try? service.file(storageId),
              let tile = try? file.get(leaf.tile)
        else { return nil }
        return tile.bytes
    }

    func isStored(_ leaf: TileRecipe.Leaf) -> Bool {
        guard let storageId = service.storageId(leaf), let file = try? service.file(storageId) else { return false }
        return (try? file.contains(leaf.tile)) ?? false
    }

    /// The tile from its server, stored when it is an image; a 404 means there is no tile there.
    func download(_ leaf: TileRecipe.Leaf) -> PrintTileFetch {
        guard let request = service.request(leaf) else { return .unavailable }
        let box = FetchBox()
        let done = DispatchSemaphore(value: 0)
        PrintTileFetcher.session.dataTask(with: request) { data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if (200 ..< 300).contains(status), let data, !data.isEmpty, let type = MbTilesFile.contentTypeOf(data) {
                box.value = .found(data)
                if let storageId = self.service.storageId(leaf), let file = try? self.service.file(storageId) {
                    try? file.put(leaf.tile, StoredTile(bytes: data, contentType: type))
                }
            } else if status == PrintTileFetcher.notFound || status == 204 {
                box.value = .empty
            } else {
                box.value = .unavailable
            }
            done.signal()
        }.resume()
        done.wait()
        return box.value
    }

    private final class FetchBox: @unchecked Sendable {
        var value: PrintTileFetch = .unavailable
    }
}

/// The tiles a plan needs that are not stored yet (Android `ZoneDownloader.missing`), as the real tiles to download.
enum PrintTiles {
    static func missingLeaves(_ plan: PrintPlan?, scaling: ScalingPolicy = ScalingPolicy()) -> [TileRecipe.Leaf] {
        guard let plan, !plan.pages.isEmpty, !plan.issues.contains(.tooManyTiles),
              let required = try? plan.requiredTiles()
        else { return [] }
        let fetcher = PrintTileFetcher(scaling: scaling)
        let service = TileService.shared
        let resolver = TileResolver { TileService.shared.source($0) }
        var seen = Set<String>()
        var out: [TileRecipe.Leaf] = []
        for (sourceId, tiles) in required {
            guard let source = service.source(sourceId) else { continue }
            for tile in tiles {
                let recipe = resolver.resolve(source, tile.z, tile.x, tile.y, scaling)
                for leaf in recipe.leaves() where leaf.level.hasServer {
                    let key = "\(leaf.source.id)|\(leaf.tile.z)/\(leaf.tile.x)/\(leaf.tile.y)"
                    guard seen.insert(key).inserted else { continue }
                    if !fetcher.isStored(leaf) {
                        out.append(leaf)
                    }
                }
            }
        }
        return out
    }
}

/// Downloads the missing tiles of a print before its export (Android `ZoneDownloader.runTiles` from the print sheet):
/// six at a time, two at a time with a pause on the community servers.
enum PrintTileDownload {
    struct Counts {
        var downloaded = 0
        /// The server has no tile there.
        var absent = 0
        var failed = 0
    }

    static func run(
        _ leaves: [TileRecipe.Leaf],
        token: PrintJobToken,
        progress: @escaping (Int, Int) -> Void
    ) -> Counts {
        let fetcher = PrintTileFetcher()
        let tally = Tally(total: leaves.count, progress: progress)
        let restricted = leaves.filter { PrintBulkPolicy.isRestricted($0.source) }
        let normal = leaves.filter { !PrintBulkPolicy.isRestricted($0.source) }
        pool(normal, width: 6, pause: 0, fetcher, token, tally)
        pool(restricted, width: 2, pause: 0.3, fetcher, token, tally)
        return tally.counts
    }

    private static func pool(
        _ leaves: [TileRecipe.Leaf],
        width: Int,
        pause: TimeInterval,
        _ fetcher: PrintTileFetcher,
        _ token: PrintJobToken,
        _ tally: Tally
    ) {
        guard !leaves.isEmpty else { return }
        let next = Cursor()
        let group = DispatchGroup()
        for _ in 0 ..< Swift.min(width, leaves.count) {
            group.enter()
            DispatchQueue.global(qos: .utility).async {
                while !token.isCancelled, let index = next.take(leaves.count) {
                    tally.add(fetcher.download(leaves[index]))
                    if pause > 0 { Thread.sleep(forTimeInterval: pause) }
                }
                group.leave()
            }
        }
        group.wait()
    }

    /// The next index to fetch, shared by the workers.
    private final class Cursor: @unchecked Sendable {
        private let lock = NSLock()
        private var value = 0

        func take(_ count: Int) -> Int? {
            lock.lock()
            defer { lock.unlock() }
            guard value < count else { return nil }
            let index = value
            value += 1
            return index
        }
    }

    private final class Tally: @unchecked Sendable {
        private let lock = NSLock()
        private let total: Int
        private let progress: (Int, Int) -> Void
        private var current = Counts()
        private var done = 0

        init(total: Int, progress: @escaping (Int, Int) -> Void) {
            self.total = total
            self.progress = progress
        }

        var counts: Counts {
            lock.lock()
            defer { lock.unlock() }
            return current
        }

        func add(_ result: PrintTileFetch) {
            lock.lock()
            switch result {
            case .found: current.downloaded += 1
            case .empty: current.absent += 1
            case .unavailable: current.failed += 1
            }
            done += 1
            let count = done
            lock.unlock()
            progress(count, total)
        }
    }
}
