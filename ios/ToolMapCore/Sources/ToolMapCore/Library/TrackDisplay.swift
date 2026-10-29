// Port of data/src/main/kotlin/fr/toolmap/data/tracks/TrackDisplay.kt. Kotlin's `data` flow (rebuilt on each change of
// the library) becomes `data()`, which the app calls from a background task when `TrackRepository.onChange` fires.

import Foundation

/// What the map layers of the library are fed with.
public struct DisplayData: Equatable, Sendable {
    public let linesGeoJson: String
    public let pointsGeoJson: String
    public let lineCount: Int
    public let pointCount: Int
    /// Ids of the waypoint icons the points use: the map loads those glyphs only.
    public let pointIcons: Set<String>

    public init(linesGeoJson: String, pointsGeoJson: String, lineCount: Int, pointCount: Int, pointIcons: Set<String> = []) {
        self.linesGeoJson = linesGeoJson
        self.pointsGeoJson = pointsGeoJson
        self.lineCount = lineCount
        self.pointCount = pointCount
        self.pointIcons = pointIcons
    }
}

/// The visible tracks, routes and waypoints as the print draws them.
public struct PrintItems: Equatable, Sendable {
    public let tracks: [PrintTrack]
    public let waypoints: [PrintWaypoint]

    public init(tracks: [PrintTrack], waypoints: [PrintWaypoint]) {
        self.tracks = tracks
        self.waypoints = waypoints
    }
}

/// Builds the GeoJSON of the visible tracks, routes and waypoints (call it off the main thread). Decoding and
/// simplifying a track is the expensive part and only depends on its points, so the simplified geometry is kept (within
/// a budget of points) and showing, hiding or recolouring never decodes again.
public final class TrackDisplay: @unchecked Sendable {
    public static let defaultToleranceMeters = 1.0
    private static let mmPerDp: Float = 25.4 / 160
    private static let minPaperWidthMm = 0.35
    private static let maxPaperWidthMm = 1.5
    private static let cachePointBudget = 400_000
    /// Raw points of a recording kept before they are simplified into the line (P15.8).
    private static let tailPoints = 256

    /// The simplified geometry of a track as of one revision. Finished segments are simplified once; the last one is
    /// kept as a simplified part (`frozen`) followed by the raw points not simplified yet (`tail`), so that a recording
    /// adding a batch only simplifies the tail and never the whole track (P15.8).
    private struct Cached {
        let revision: Int
        /// Points of the track this was built from (the length of the raw track).
        let rawCount: Int
        let done: [[TrackPoint]]
        let frozen: [TrackPoint]
        let tail: [TrackPoint]
        /// The segments to draw; a segment of one point is not a line.
        let segments: [[TrackPoint]]
        let points: Int

        init(revision: Int, rawCount: Int, done: [[TrackPoint]], frozen: [TrackPoint], tail: [TrackPoint]) {
            self.revision = revision
            self.rawCount = rawCount
            self.done = done
            self.frozen = frozen
            self.tail = tail
            let all = frozen.isEmpty && tail.isEmpty ? done : done + [frozen + tail]
            let drawn = all.filter { $0.count >= 2 }
            segments = drawn
            points = drawn.reduce(0) { $0 + $1.count }
        }
    }

    private let repository: TrackRepository
    private let toleranceMeters: Double
    private let lock = NSLock()
    private var cache: [Int64: Cached] = [:]
    /// Ids of `cache`, least recently used first (Kotlin's access-ordered `LinkedHashMap`).
    private var order: [Int64] = []
    private var cachedPoints = 0
    private var loads = 0

    public init(repository: TrackRepository, toleranceMeters: Double = TrackDisplay.defaultToleranceMeters) {
        self.repository = repository
        self.toleranceMeters = toleranceMeters
    }

    /// How many times a whole track was read and simplified (for the tests).
    var fullLoads: Int {
        lock.lock()
        defer { lock.unlock() }
        return loads
    }

    /// The map layers of the library as it is now.
    public func data() throws -> DisplayData {
        let library = try repository.library()
        return try build(library)
    }

    /// The map layers of `library` (a snapshot the caller already read).
    public func build(_ library: TrackLibrary) throws -> DisplayData {
        var lines: [LineFeature] = []
        for t in library.tracks where library.isShown(t.folderId, t.visible) {
            let segments = try simplified(t.id)
            lines.append(
                LineFeature(id: t.id, colorArgb: t.colorArgb, widthDp: t.widthDp, dashed: t.dashed, segments: segments, arrows: t.arrows)
            )
        }
        var places: [PointFeature] = []
        for r in library.routes where library.isShown(r.folderId, r.visible) {
            let waypoints = try TrackJson.decodeWaypoints(r.pointsJson)
            let points = waypoints.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
            lines.append(
                LineFeature(id: -r.id, colorArgb: r.colorArgb, widthDp: r.widthDp, dashed: r.dashed, segments: [points], arrows: r.arrows)
            )
            // A computed route shows its start, its end and its named stops (P19).
            if let extras = r.extrasJson, extras.contains(RoutePlanGpx.namespace) {
                let decoded = try TrackJson.decodeExtras(extras)
                if let plan = RoutePlanGpx.planOf(points, decoded) {
                    places.append(contentsOf: RouteMarkers.saved(-r.id, plan))
                }
            }
        }
        for w in library.waypoints where library.isShown(w.folderId, w.visible) {
            places.append(PointFeature(id: w.id, name: w.name, lat: w.lat, lon: w.lon, sym: w.sym))
        }
        var icons = Set<String>()
        for place in places {
            icons.insert(place.iconId ?? WaypointSymbols.iconOf(place.sym).id)
        }
        return DisplayData(
            linesGeoJson: TrackGeoJson.lines(lines, toleranceMeters: 0.0),
            pointsGeoJson: TrackGeoJson.points(places),
            lineCount: lines.count,
            pointCount: places.count,
            pointIcons: icons
        )
    }

    /// What is shown on the map right now, ready for the page: line widths in paper millimeters.
    public func printItems() throws -> PrintItems {
        let library = try repository.library()
        var tracks: [PrintTrack] = []
        for t in library.tracks where library.isShown(t.folderId, t.visible) {
            let simplifiedSegments = try simplified(t.id)
            let segments = simplifiedSegments.map { segment in segment.map { LonLat(lon: $0.lon, lat: $0.lat) } }
            tracks.append(
                PrintTrack(name: t.name ?? "", colorArgb: Int(t.colorArgb), widthMm: paperWidthMm(t.widthDp), segments: segments)
            )
        }
        for r in library.routes where library.isShown(r.folderId, r.visible) {
            let waypoints = try TrackJson.decodeWaypoints(r.pointsJson)
            let points = waypoints.map { LonLat(lon: $0.lon, lat: $0.lat) }
            tracks.append(
                PrintTrack(name: r.name ?? "", colorArgb: Int(r.colorArgb), widthMm: paperWidthMm(r.widthDp), segments: [points])
            )
        }
        var places: [PrintWaypoint] = []
        for w in library.waypoints where library.isShown(w.folderId, w.visible) {
            let icon = WaypointSymbols.iconOf(w.sym)
            places.append(PrintWaypoint(name: w.name, lat: w.lat, lon: w.lon, colorArgb: Int(icon.colorArgb), iconId: icon.id))
        }
        return PrintItems(tracks: tracks, waypoints: places)
    }

    private func paperWidthMm(_ widthDp: Float) -> Double {
        let mm = Double(widthDp * TrackDisplay.mmPerDp)
        return Swift.min(Swift.max(mm, TrackDisplay.minPaperWidthMm), TrackDisplay.maxPaperWidthMm)
    }

    private func simplified(_ trackId: Int64) throws -> [[TrackPoint]] {
        // The revision is read before the points: a write in between leaves an older number with newer points, which
        // only costs one more load the next time.
        let revision = repository.pointsRevision(trackId)
        let known = cached(trackId)
        if let entry = known, entry.revision == revision { return entry.segments }
        // A recording only adds points at the end: extend what is known instead of reading the track again.
        if let entry = known, let extended = extend(trackId, entry) {
            return store(trackId, extended).segments
        }
        countFullLoad()
        guard let track = try repository.loadTrack(trackId) else { return [] }
        let thinned = track.segments.map { PathSimplifier.simplify($0, toleranceMeters) }
        let loaded = Cached(
            revision: revision,
            rawCount: track.pointCount,
            done: Array(thinned.dropLast()),
            frozen: thinned.last ?? [],
            tail: []
        )
        return store(trackId, loaded).segments
    }

    private func countFullLoad() {
        lock.lock()
        loads += 1
        lock.unlock()
    }

    /// The cached entry, marked as the most recently used.
    private func cached(_ trackId: Int64) -> Cached? {
        lock.lock()
        defer { lock.unlock() }
        guard let entry = cache[trackId] else { return nil }
        if let index = order.firstIndex(of: trackId) { order.remove(at: index) }
        order.append(trackId)
        return entry
    }

    private func store(_ trackId: Int64, _ entry: Cached) -> Cached {
        lock.lock()
        defer { lock.unlock() }
        if let old = cache.removeValue(forKey: trackId) {
            cachedPoints -= old.points
            order.removeAll { $0 == trackId }
        }
        cache[trackId] = entry
        order.append(trackId)
        cachedPoints += entry.points
        var index = 0
        while cachedPoints > TrackDisplay.cachePointBudget && cache.count > 1 && index < order.count {
            let oldest = order[index]
            if oldest == trackId {
                index += 1
                continue
            }
            if let evicted = cache.removeValue(forKey: oldest) { cachedPoints -= evicted.points }
            order.remove(at: index)
        }
        return entry
    }

    /// `known` brought up to date with the batches the recorder appended since, or nil if it cannot be.
    private func extend(_ trackId: Int64, _ known: Cached) -> Cached? {
        guard let appended = repository.appendedSince(trackId, known.revision) else { return nil }
        var done = known.done
        var frozen = known.frozen
        var tail = known.tail
        var raw = known.rawCount
        for batch in appended.batches {
            // A batch the track already held when it was read (the revision is bumped after the write) is skipped.
            if batch.endCount <= raw { continue }
            if batch.endCount - batch.points.count != raw { return nil }
            if batch.startsSegment {
                let closed = settle(frozen, tail)
                if !closed.isEmpty { done.append(closed) }
                frozen = []
                tail = batch.points
            } else {
                tail += batch.points
            }
            raw = batch.endCount
            if tail.count >= TrackDisplay.tailPoints {
                frozen = settle(frozen, tail)
                tail = []
            }
        }
        return Cached(revision: appended.revision, rawCount: raw, done: done, frozen: frozen, tail: tail)
    }

    /// The simplified `frozen` part followed by the simplification of `tail`, which starts at the last frozen point so
    /// that the line stays in one piece. The result stays within the tolerance of the raw points.
    private func settle(_ frozen: [TrackPoint], _ tail: [TrackPoint]) -> [TrackPoint] {
        if tail.isEmpty { return frozen }
        guard let anchor = frozen.last else { return PathSimplifier.simplify(tail, toleranceMeters) }
        let joined = PathSimplifier.simplify([anchor] + tail, toleranceMeters)
        return frozen + Array(joined.dropFirst())
    }
}
