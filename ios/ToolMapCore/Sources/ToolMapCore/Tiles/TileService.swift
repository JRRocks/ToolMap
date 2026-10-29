import Foundation

/// The tile pipeline of the app (Android: `TileInterceptor`, `TileResolver`, `MbTilesStore`, `BitmapTileComposer`,
/// PLAN §2.3): for each tile MapLibre asks, the recipe (the map's own tile, a referenced map, a neighbouring zoom shrunk
/// or enlarged), then every real tile from its MBTiles file, or from its server (stored after), then the composed image.
/// Offline, nothing is requested: stored tiles only, even expired ones.
public final class TileService: @unchecked Sendable {
    private let lock = NSLock()
    private var sources: [String: TileSource] = [:]
    private var builders: [String: TileUrlBuilder] = [:]
    private var storageKeys = StorageKeys(builtins: [])
    private var files: [String: MbTilesFile] = [:]
    private var offlineFlag = false
    private let composer = TileComposer()
    private let session: URLSession
    private let userAgent: String

    /// Folder of the MBTiles files.
    public let directory: URL

    /// `directory` is created when missing; `session` makes the real tile requests (a plain one by default, outside
    /// MapLibre's configuration, so the requests are not intercepted again).
    public init(directory: URL, session: URLSession = TileService.makeSession(), userAgent: String = "ToolMap (iOS)") {
        self.directory = directory
        self.session = session
        self.userAgent = userAgent
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// The session of the real tile requests: six connections per server, no HTTP cache (the MBTiles is the cache).
    public static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.default
        configuration.httpMaximumConnectionsPerHost = 6
        configuration.timeoutIntervalForRequest = 20
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return URLSession(configuration: configuration)
    }

    public var offline: Bool {
        get { lock.lock(); defer { lock.unlock() }; return offlineFlag }
        set { lock.lock(); offlineFlag = newValue; lock.unlock() }
    }

    public func register(_ list: [TileSource], builtins: [TileSource]) {
        lock.lock()
        defer { lock.unlock() }
        storageKeys = StorageKeys(builtins: builtins)
        for source in list {
            sources[source.id] = source
            builders[source.id] = TileUrlBuilder(source)
        }
    }

    public func source(_ id: String) -> TileSource? {
        lock.lock()
        defer { lock.unlock() }
        return sources[id]
    }

    /// The image of tile `z`/`x`/`y` of the map `sourceId`, or nil when nothing can be shown there; `completion` is
    /// called once, on any thread.
    public func load(
        _ sourceId: String,
        _ z: Int,
        _ x: Int,
        _ y: Int,
        completion: @escaping @Sendable (Data?, String?) -> Void
    ) {
        guard let source = source(sourceId) else {
            completion(nil, nil)
            return
        }
        let resolver = TileResolver { [weak self] in self?.source($0) }
        let recipe = resolver.resolve(source, z, x, y, ScalingPolicy())
        if case let .direct(leafSource, level, tile) = recipe {
            fetch(TileRecipe.Leaf(source: leafSource, level: level, tile: tile)) { stored in
                completion(stored?.bytes, stored?.contentType)
            }
            return
        }
        let leaves = recipe.leaves()
        if leaves.isEmpty {
            completion(nil, nil)
            return
        }
        let group = DispatchGroup()
        let results = ResultBox()
        for leaf in leaves {
            group.enter()
            fetch(leaf) { stored in
                results.set(leaf.tile, leaf.source.id, stored?.bytes)
                group.leave()
            }
        }
        group.notify(queue: .global(qos: .userInitiated)) { [composer] in
            let png = composer.compose(recipe) { results.get($0.tile, $0.source.id) }
            completion(png, png == nil ? nil : "image/png")
        }
    }

    /// One real tile: stored and fresh, or from the server (then stored), or stored though expired when the server
    /// fails or the app is offline.
    public func fetch(_ leaf: TileRecipe.Leaf, completion: @escaping @Sendable (StoredTile?) -> Void) {
        let storageId = storageId(leaf)
        let file = storageId.flatMap { try? self.file($0) }
        let stored = try? file?.get(leaf.tile)
        let validity = UpdateDelay.toMillis(leaf.level.updateDelay)
        if let stored, !stored.isStale(nowMillis: StoredTile.nowMillis(), validityMillis: validity) {
            completion(stored)
            return
        }
        if offline || !leaf.level.hasServer {
            completion(stored)
            return
        }
        guard let request = request(leaf) else {
            completion(stored)
            return
        }
        session.dataTask(with: request) { data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard (200 ..< 300).contains(status), let data, let type = MbTilesFile.contentTypeOf(data) else {
                completion(stored)
                return
            }
            let tile = StoredTile(bytes: data, contentType: type)
            try? file?.put(leaf.tile, tile)
            completion(tile)
        }.resume()
    }

    /// The server request of a real tile: URL from the AQX templates, Referer, User-Agent, headers and cookie.
    public func request(_ leaf: TileRecipe.Leaf) -> URLRequest? {
        lock.lock()
        let builder = builders[leaf.source.id] ?? TileUrlBuilder(leaf.source)
        lock.unlock()
        guard let address = try? builder.build(leaf.level, leaf.tile.z, leaf.tile.x, leaf.tile.y, serverIndex: nextServer()),
              let url = URL(string: address)
        else { return nil }
        var request = URLRequest(url: url)
        request.setValue(leaf.level.userAgent ?? userAgent, forHTTPHeaderField: "User-Agent")
        if let referer = leaf.level.referer {
            request.setValue(referer, forHTTPHeaderField: "Referer")
        }
        for header in leaf.level.headers {
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }
        if let cookie = leaf.level.cookie {
            request.setValue(cookie, forHTTPHeaderField: "Cookie")
        }
        return request
    }

    public func storageId(_ leaf: TileRecipe.Leaf) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return storageKeys.storageIdOf(leaf.source, leaf.level)
    }

    /// The MBTiles file of a storage, opened once.
    public func file(_ storageId: String) throws -> MbTilesFile {
        lock.lock()
        defer { lock.unlock() }
        if let open = files[storageId] { return open }
        let file = try MbTilesFile.open(
            directory.appendingPathComponent("\(storageId).mbtiles"),
            initialMetadata: ["name": storageId, "type": "baselayer", "version": "1.1"]
        )
        files[storageId] = file
        return file
    }

    /// One MBTiles file on disk.
    public struct StoredFile: Identifiable, Equatable, Sendable {
        public let id: String
        public let bytes: Int64
    }

    /// Every MBTiles file on disk, by storage id, with its size.
    public func storedFiles() -> [StoredFile] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.hasSuffix(".mbtiles") }.sorted().map { name in
            let id = String(name.dropLast(".mbtiles".count))
            let path = directory.appendingPathComponent(name).path
            var size: Int64 = 0
            for suffix in ["", "-wal"] {
                let attributes = try? FileManager.default.attributesOfItem(atPath: path + suffix)
                size += (attributes?[.size] as? NSNumber)?.int64Value ?? 0
            }
            return StoredFile(id: id, bytes: size)
        }
    }

    /// Deletes the tiles of one storage.
    public func clear(_ storageId: String) {
        try? file(storageId).clear()
    }

    private var serverCounter = 0

    /// Servers are used in turn across all tiles (Android: `TileUrlBuilder` round robin).
    private func nextServer() -> Int {
        lock.lock()
        defer { lock.unlock() }
        serverCounter &+= 1
        return serverCounter
    }

    /// Tiles fetched for one composed tile.
    private final class ResultBox: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String: Data] = [:]

        func set(_ tile: TileId, _ sourceId: String, _ data: Data?) {
            guard let data else { return }
            lock.lock()
            values["\(sourceId)/\(tile.z)/\(tile.x)/\(tile.y)"] = data
            lock.unlock()
        }

        func get(_ tile: TileId, _ sourceId: String) -> Data? {
            lock.lock()
            defer { lock.unlock() }
            return values["\(sourceId)/\(tile.z)/\(tile.x)/\(tile.y)"]
        }
    }
}
