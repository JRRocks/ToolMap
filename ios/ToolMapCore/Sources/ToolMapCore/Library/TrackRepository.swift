// Port of data/src/main/kotlin/fr/toolmap/data/tracks/TrackRepository.kt and of the queries of TrackDao.kt (Android
// module :data, Room). Synchronous: the app calls it from a background task. `completeElevation` is not ported here:
// the Terrarium completer of :data is not ported yet (the app loads the track, fills it and calls `replaceSegments`).

import Foundation

/// The library of tracks, routes and waypoints: import, read back, edit, export. One SQLite connection, every call
/// under one lock, so it can be shared between threads; `onChange` is called after each call that wrote something.
public final class TrackRepository: @unchecked Sendable {
    /// Points per stored chunk: a few hundred kilobytes even with heavy per-point extensions.
    public static let chunkPoints = 2000
    /// Points kept in the append log of a track; a display that falls further behind loads the track again.
    static let appendLogPoints = 10_000
    public static let defaultFolderName = "Import"
    public static let folderSeparator = " / "

    private let db: SQLiteDatabase
    private let now: @Sendable () -> Int64
    private let lock = NSRecursiveLock()
    /// Nesting of the public calls on this thread (a call may use others): `onChange` runs when the outermost ends.
    private var depth = 0
    private var changed = false
    private var inTransaction = false
    private var changeHandler: (@Sendable () -> Void)?
    /// How many times the points of each track were rewritten or extended since the repository was opened.
    private var revisions: [Int64: Int] = [:]
    /// The last batches the recorder appended to each track, so that the display can extend its line (P15.8).
    private var appendLogs: [Int64: [AppendedPoints]] = [:]

    /// Opens (or creates) the library file at `path`, e.g. `Application Support/tracks.db`.
    public convenience init(
        path: String,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) throws {
        let database = try SQLiteDatabase(path: path)
        try self.init(database: database, now: now)
    }

    public init(
        database: SQLiteDatabase,
        now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) throws {
        db = database
        self.now = now
        try TrackDatabase.prepare(database)
    }

    /// Called (on the thread that wrote, once the lock is released) after each call that changed the library: the app
    /// reads `library()` again and rebuilds the map layer from it.
    public var onChange: (@Sendable () -> Void)? {
        get {
            lock.lock()
            defer { lock.unlock() }
            return changeHandler
        }
        set {
            lock.lock()
            changeHandler = newValue
            lock.unlock()
        }
    }

    /// Closes the file (before a backup is restored over it); the repository is unusable afterwards.
    public func close() {
        lock.lock()
        db.close()
        lock.unlock()
    }

    // MARK: - Revisions of the points

    /// Changes whenever the points of `trackId` change (recording, edit, reverse, simplify), so that what is derived
    /// from them (the simplified line on the map) can tell it is out of date. Every write of points goes through this
    /// repository, so a counter in memory is enough; it starts again at 0 with the process, as do the caches it guards.
    public func pointsRevision(_ trackId: Int64) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return revisions[trackId] ?? 0
    }

    /// The batches appended to `trackId` since `revision` (as returned by `pointsRevision`) with the revision they lead
    /// to, or nil when the points changed in another way or the log no longer reaches back that far: the caller then
    /// loads the whole track.
    public func appendedSince(_ trackId: Int64, _ revision: Int) -> AppendedSince? {
        lock.lock()
        defer { lock.unlock() }
        let current = revisions[trackId] ?? 0
        if revision == current { return AppendedSince(revision: current, batches: []) }
        if revision > current { return nil }
        guard let log = appendLogs[trackId] else { return nil }
        let wanted = log.filter { $0.revision > revision && $0.revision <= current }
        let contiguous = wanted.count == current - revision && wanted.first?.revision == revision + 1
        return contiguous ? AppendedSince(revision: current, batches: wanted) : nil
    }

    /// Under the lock.
    private func pointsChanged(_ trackId: Int64) {
        revisions[trackId, default: 0] += 1
        // A rewrite is not an append: what the display kept about this track can no longer be extended.
        appendLogs[trackId] = nil
    }

    /// Under the lock.
    private func pointsAppended(_ trackId: Int64, _ points: [TrackPoint], _ startsSegment: Bool, _ endCount: Int) {
        revisions[trackId, default: 0] += 1
        let revision = revisions[trackId] ?? 1
        var log = appendLogs[trackId] ?? []
        log.append(AppendedPoints(revision: revision, points: points, startsSegment: startsSegment, endCount: endCount))
        var held = log.reduce(0) { $0 + $1.points.count }
        while held > TrackRepository.appendLogPoints && log.count > 1 {
            held -= log.removeFirst().points.count
        }
        appendLogs[trackId] = log
    }

    // MARK: - Reading

    /// Everything the list screen and the map need, without decoding a point. Same order as the Room queries:
    /// folders by name (ASCII case ignored), items most recent first.
    public func library() throws -> TrackLibrary {
        return try access {
            let folders = try self.fetchAll(FolderEntity.self, " ORDER BY `name` COLLATE NOCASE, `id`")
            let tracks = try self.fetchAll(TrackEntity.self, " ORDER BY `createdAt` DESC, `id` ASC")
            let routes = try self.fetchAll(RouteEntity.self, " ORDER BY `createdAt` DESC, `id` ASC")
            let waypoints = try self.fetchAll(WaypointEntity.self, " ORDER BY `createdAt` DESC, `id` ASC")
            return TrackLibrary(folders: folders, tracks: tracks, routes: routes, waypoints: waypoints)
        }
    }

    public func loadTrack(_ id: Int64) throws -> Track? {
        return try access {
            guard let e = try self.fetch(TrackEntity.self, id) else { return nil }
            let stored = try self.chunks(id)
            var segments: [[TrackPoint]] = []
            var currentSegment: Int?
            for chunk in stored {
                let points = try PointCodec.decode(chunk.data)
                if chunk.segment == currentSegment, !segments.isEmpty {
                    segments[segments.count - 1].append(contentsOf: points)
                } else {
                    segments.append(points)
                    currentSegment = chunk.segment
                }
            }
            let links = try TrackJson.decodeLinks(e.linksJson)
            let extras = try TrackJson.decodeExtras(e.extrasJson)
            return Track(
                name: e.name,
                cmt: e.cmt,
                desc: e.desc,
                src: e.src,
                links: links,
                number: e.number,
                type: e.type,
                segments: segments,
                extras: extras,
                style: PathStyle(colorArgb: e.colorArgb, widthDp: e.widthDp, dashed: e.dashed, arrows: e.arrows)
            )
        }
    }

    public func loadRoute(_ id: Int64) throws -> Route? {
        return try access {
            guard let e = try self.fetch(RouteEntity.self, id) else { return nil }
            let links = try TrackJson.decodeLinks(e.linksJson)
            let points = try TrackJson.decodeWaypoints(e.pointsJson)
            let extras = try TrackJson.decodeExtras(e.extrasJson)
            return Route(
                name: e.name,
                cmt: e.cmt,
                desc: e.desc,
                src: e.src,
                links: links,
                number: e.number,
                type: e.type,
                points: points,
                extras: extras,
                style: PathStyle(colorArgb: e.colorArgb, widthDp: e.widthDp, dashed: e.dashed, arrows: e.arrows)
            )
        }
    }

    public func loadWaypoint(_ id: Int64) throws -> Waypoint? {
        return try access {
            guard let e = try self.fetch(WaypointEntity.self, id) else { return nil }
            return try self.waypointOf(e)
        }
    }

    /// Statistics of a stored track, from its points.
    public func stats(_ trackId: Int64) throws -> TrackStats? {
        guard let track = try loadTrack(trackId) else { return nil }
        return TrackStatsCalculator.compute(track.segments)
    }

    // MARK: - Import and export

    /// Puts everything of `document` in one new folder named after the document (or the file). KML folders become
    /// sub-folders. File-level metadata other than the name (author, keywords, copyright) is not kept.
    @discardableResult
    public func `import`(_ document: TrackDocument, fileName: String?) throws -> ImportSummary {
        return try write {
            let created = self.now()
            let fromFile = fileName.map { TrackRepository.substringBeforeLast($0, ".") }
            let rootName = TrackRepository.trimmedOrNil(document.name)
                ?? TrackRepository.trimmedOrNil(fromFile)
                ?? TrackRepository.defaultFolderName
            let namespacesJson = try TrackJson.encodeNamespaces(document.namespaces)
            let rootId = try self.insert(FolderEntity(name: rootName, createdAt: created, namespacesJson: namespacesJson))
            var folders: [String: Int64] = [:]

            func folderFor(_ path: String?) throws -> Int64 {
                guard let folderPath = path, !kotlinTrim(folderPath).isEmpty else { return rootId }
                var parent = rootId
                var key = ""
                for piece in folderPath.components(separatedBy: TrackRepository.folderSeparator) {
                    let part = kotlinTrim(piece)
                    if part.isEmpty { continue }
                    key = key.isEmpty ? part : key + TrackRepository.folderSeparator + part
                    if let known = folders[key] {
                        parent = known
                    } else {
                        let id = try self.insert(FolderEntity(parentId: parent, name: part, createdAt: created))
                        folders[key] = id
                        parent = id
                    }
                }
                return parent
            }

            let format = document.format?.rawValue
            var points = 0
            // The points of interest of a route of the file are in its plan (P30.1): their `<wpt>` copies, written for
            // the other apps, are not taken a second time.
            let waypoints = document.waypoints.filter { !RoutePlanGpx.isPoiOf($0, document.routes) }
            for w in waypoints {
                let folderId = try folderFor(w.folder)
                let entity = try self.waypointEntity(w, folderId: folderId, created: created, format: format)
                try self.insert(entity)
            }
            for (i, r) in document.routes.enumerated() {
                let folderId = try folderFor(r.folder)
                let entity = try self.routeEntity(r, folderId: folderId, created: created, format: format, index: i)
                try self.insert(entity)
                points += r.points.count
            }
            for (i, t) in document.tracks.enumerated() {
                let folderId = try folderFor(t.folder)
                let entity = try self.trackEntity(t, folderId: folderId, created: created, format: format, index: i)
                let id = try self.insert(entity)
                try self.insertChunks(self.chunksOf(id, t.segments))
                points += t.pointCount
            }
            return ImportSummary(
                folderId: rootId,
                tracks: document.tracks.count,
                routes: document.routes.count,
                waypoints: waypoints.count,
                points: points + waypoints.count,
                issues: document.issues
            )
        }
    }

    /// The selection as a document ready for `GpxWriter`.
    public func document(_ selection: TrackSelection) throws -> TrackDocument {
        return try access {
            let lib = try self.library()
            var folderIds = Set<Int64>()
            for id in selection.folderIds {
                folderIds.formUnion(lib.descendants(id))
            }
            func inSelectedFolder(_ folderId: Int64?) -> Bool {
                guard let folder = folderId else { return false }
                return folderIds.contains(folder)
            }
            // Ids grow in file order, so sorting them writes items in the order they were imported or drawn.
            var trackIds = Set(lib.tracks.filter { inSelectedFolder($0.folderId) }.map { $0.id })
            trackIds.formUnion(selection.trackIds)
            var routeIds = Set(lib.routes.filter { inSelectedFolder($0.folderId) }.map { $0.id })
            routeIds.formUnion(selection.routeIds)
            var waypointIds = Set(lib.waypoints.filter { inSelectedFolder($0.folderId) }.map { $0.id })
            waypointIds.formUnion(selection.waypointIds)

            let namespaces = NamespaceMerger(lib)
            var tracks: [Track] = []
            for id in trackIds.sorted() {
                guard let track = try self.loadTrack(id) else { continue }
                let folderId = lib.tracks.first(where: { $0.id == id })?.folderId
                let adopted = try namespaces.adopt(folderId) { fix in
                    TrackRepository.copyTrack(
                        track,
                        extras: fix(track.extras),
                        segments: track.segments.map { s in s.map { p in TrackRepository.copyPoint(p, extras: fix(p.extras)) } }
                    )
                }
                tracks.append(adopted)
            }
            var routes: [Route] = []
            for id in routeIds.sorted() {
                guard let route = try self.loadRoute(id) else { continue }
                let folderId = lib.routes.first(where: { $0.id == id })?.folderId
                let adopted = try namespaces.adopt(folderId) { fix in
                    TrackRepository.copyRoute(
                        route,
                        extras: fix(route.extras),
                        points: route.points.map { p in TrackRepository.copyWaypoint(p, extras: fix(p.extras)) }
                    )
                }
                routes.append(adopted)
            }
            var waypoints: [Waypoint] = []
            for id in waypointIds.sorted() {
                guard let waypoint = try self.loadWaypoint(id) else { continue }
                let folderId = lib.waypoints.first(where: { $0.id == id })?.folderId
                let adopted = try namespaces.adopt(folderId) { fix in
                    TrackRepository.copyWaypoint(waypoint, extras: fix(waypoint.extras))
                }
                waypoints.append(adopted)
            }
            // The points of interest of the routes (P30.1) as waypoints too, for the apps that do not read the plan.
            let pois = routes.flatMap { RoutePlanGpx.poiWaypoints($0) }
            let onlyFolder: Int64? =
                selection.folderIds.count == 1 && selection.trackIds.isEmpty && selection.routeIds.isEmpty
                    && selection.waypointIds.isEmpty ? selection.folderIds[0] : nil
            var name: String?
            if let folder = onlyFolder {
                name = lib.folders.first(where: { $0.id == folder })?.name
            }
            return TrackDocument(
                name: name,
                waypoints: waypoints + pois,
                routes: routes,
                tracks: tracks,
                namespaces: namespaces.merged
            )
        }
    }

    // MARK: - Tracks

    /// Replaces the points of a track (editing, elevation completion) and refreshes its cached figures.
    public func replaceSegments(_ trackId: Int64, _ segments: [[TrackPoint]]) throws {
        try access {
            try self.transaction {
                guard let entity = try self.fetch(TrackEntity.self, trackId) else { return }
                try self.deleteChunks(trackId)
                try self.insertChunks(self.chunksOf(trackId, segments))
                try self.update(self.withStats(entity, segments))
            }
            self.pointsChanged(trackId)
        }
    }

    /// A track made in the app (drawn or being recorded): it has no file behind it and sits at the root of the library.
    /// Returns its id.
    @discardableResult
    public func createTrack(
        _ name: String?,
        segments: [[TrackPoint]] = [],
        style: PathStyle = PathStyle(),
        type: String? = nil
    ) throws -> Int64 {
        return try write {
            let empty = TrackEntity(
                name: TrackRepository.trimmedOrNil(name),
                type: type,
                colorArgb: style.colorArgb,
                widthDp: style.widthDp,
                dashed: style.dashed,
                arrows: style.arrows,
                createdAt: self.now(),
                pointCount: 0,
                segmentCount: 0,
                minLat: 0.0,
                minLon: 0.0,
                maxLat: 0.0,
                maxLon: 0.0,
                distanceMeters: 0.0
            )
            let id = try self.insert(self.withStats(empty, segments))
            try self.insertChunks(self.chunksOf(id, segments))
            return id
        }
    }

    /// Adds points at the end of a track, for the recorder: only the last chunk is rewritten, so the cost does not grow
    /// with the length of the track. `newSegment` starts a new segment with the first of `points`. The cached figures
    /// that are cheap to keep current (size, bounds, distance, dates) are updated; climb is recomputed by `finishTrack`.
    public func appendPoints(_ trackId: Int64, _ points: [TrackPoint], newSegment: Bool = false) throws {
        if points.isEmpty { return }
        try access {
            var endCount = -1
            var startsNewSegment = false
            try self.transaction {
                guard let entity = try self.fetch(TrackEntity.self, trackId) else { return }
                let last = try self.lastChunk(trackId)
                let startsSegment = newSegment || last == nil
                var segment = last?.segment ?? 0
                var chunk = last?.chunk ?? 0
                var kept: [TrackPoint] = []
                if startsSegment {
                    if last != nil { segment += 1 }
                    chunk = 0
                } else if let tail = last {
                    kept = try PointCodec.decode(tail.data)
                }
                endCount = entity.pointCount + points.count
                startsNewSegment = startsSegment
                let before = kept.last
                var writes: [TrackChunkEntity] = []
                for (i, part) in TrackRepository.chunked(kept + points, TrackRepository.chunkPoints).enumerated() {
                    writes.append(
                        TrackChunkEntity(
                            trackId: trackId,
                            segment: segment,
                            chunk: chunk + i,
                            pointCount: part.count,
                            data: PointCodec.encode(part)
                        )
                    )
                }
                try self.insertChunks(writes)

                var distance = entity.distanceMeters
                var from = before
                for p in points {
                    if let previous = from { distance += Geodesy.distance(previous, p) }
                    from = p
                }
                var minLat = points[0].lat
                var minLon = points[0].lon
                var maxLat = points[0].lat
                var maxLon = points[0].lon
                for p in points {
                    minLat = Swift.min(minLat, p.lat)
                    minLon = Swift.min(minLon, p.lon)
                    maxLat = Swift.max(maxLat, p.lat)
                    maxLon = Swift.max(maxLon, p.lon)
                }
                let first = entity.pointCount == 0
                var updated = entity
                updated.pointCount = entity.pointCount + points.count
                updated.segmentCount = entity.segmentCount + (startsSegment ? 1 : 0)
                updated.minLat = first ? minLat : Swift.min(entity.minLat, minLat)
                updated.minLon = first ? minLon : Swift.min(entity.minLon, minLon)
                updated.maxLat = first ? maxLat : Swift.max(entity.maxLat, maxLat)
                updated.maxLon = first ? maxLon : Swift.max(entity.maxLon, maxLon)
                updated.distanceMeters = distance
                updated.startTimeMillis = entity.startTimeMillis ?? points.first(where: { $0.timeMillis != nil })?.timeMillis
                updated.endTimeMillis = points.last(where: { $0.timeMillis != nil })?.timeMillis ?? entity.endTimeMillis
                try self.update(updated)
            }
            if endCount >= 0 {
                self.pointsAppended(trackId, points, startsNewSegment, endCount)
            } else {
                self.pointsChanged(trackId)
            }
        }
    }

    /// Recomputes every cached figure of a track from its points (end of a recording).
    public func finishTrack(_ trackId: Int64) throws {
        try access {
            self.appendLogs[trackId] = nil
            guard let track = try self.loadTrack(trackId) else { return }
            try self.transaction {
                guard let entity = try self.fetch(TrackEntity.self, trackId) else { return }
                try self.update(self.withStats(entity, track.segments))
            }
        }
    }

    /// The track walked the other way (its times are dropped, see `TrackOps.reverse`).
    public func reverseTrack(_ trackId: Int64) throws {
        try access {
            guard let track = try self.loadTrack(trackId) else { return }
            try self.replaceSegments(trackId, TrackOps.reverse(track.segments))
        }
    }

    /// Thins a track to `toleranceMeters` with Douglas-Peucker; returns the point counts before and after, or nil when
    /// the track does not exist. Nothing is written when no point can be dropped.
    @discardableResult
    public func simplifyTrack(_ trackId: Int64, _ toleranceMeters: Double) throws -> (before: Int, after: Int)? {
        return try access {
            guard let track = try self.loadTrack(trackId) else { return nil }
            let thinned = track.segments.map { TrackOps.simplify($0, toleranceMeters) }
            let before = track.segments.reduce(0) { $0 + $1.count }
            let after = thinned.reduce(0) { $0 + $1.count }
            if after < before { try self.replaceSegments(trackId, thinned) }
            return (before: before, after: after)
        }
    }

    /// A new track made of `trackIds` one after the other (each stays a segment of its own: the way between two tracks
    /// is not part of the result). The sources are left alone. Returns its id, or nil with fewer than two tracks.
    @discardableResult
    public func mergeTracks(_ trackIds: [Int64], _ name: String?) throws -> Int64? {
        return try access {
            var tracks: [Track] = []
            for id in trackIds {
                if let track = try self.loadTrack(id) { tracks.append(track) }
            }
            if tracks.count < 2 { return nil }
            var segments = tracks[0].segments
            for track in tracks.dropFirst() {
                segments = TrackOps.merge(segments, track.segments)
            }
            return try self.createTrack(name, segments: segments, style: tracks[0].style ?? PathStyle())
        }
    }

    // MARK: - Routes and waypoints

    /// A route made in the app. Returns its id.
    @discardableResult
    public func createRoute(
        _ name: String?,
        _ points: [Waypoint],
        style: PathStyle = PathStyle(dashed: true),
        extras: [RawXml] = []
    ) throws -> Int64 {
        return try write {
            let route = Route(name: TrackRepository.trimmedOrNil(name), points: points, extras: extras, style: style)
            let entity = try self.routeEntity(route, folderId: nil, created: self.now(), format: nil, index: 0)
            return try self.insert(entity)
        }
    }

    /// Replaces the points of a route (editing on the map) and refreshes its cached figures. `extras`, when given,
    /// replace its GPX extensions too (the plan of a computed route, P19).
    public func replaceRoutePoints(_ routeId: Int64, _ points: [Waypoint], extras: [RawXml]? = nil) throws {
        try write {
            guard var entity = try self.fetch(RouteEntity.self, routeId) else { return }
            let updated = try self.routeEntity(
                Route(points: points),
                folderId: entity.folderId,
                created: entity.createdAt,
                format: entity.format,
                index: 0
            )
            entity.pointCount = updated.pointCount
            entity.minLat = updated.minLat
            entity.minLon = updated.minLon
            entity.maxLat = updated.maxLat
            entity.maxLon = updated.maxLon
            entity.distanceMeters = updated.distanceMeters
            entity.pointsJson = updated.pointsJson
            if let newExtras = extras {
                entity.extrasJson = try TrackJson.encodeExtras(newExtras)
            }
            try self.update(entity)
        }
    }

    /// A waypoint put on the map. Returns its id.
    @discardableResult
    public func createWaypoint(_ waypoint: Waypoint, folderId: Int64? = nil) throws -> Int64 {
        return try write {
            let entity = try self.waypointEntity(waypoint, folderId: folderId, created: self.now(), format: nil)
            return try self.insert(entity)
        }
    }

    /// Rewrites a waypoint's own fields (name, notes, symbol, place, altitude); visibility and folder stay.
    public func updateWaypoint(_ id: Int64, _ waypoint: Waypoint) throws {
        try write {
            guard var entity = try self.fetch(WaypointEntity.self, id) else { return }
            entity.name = TrackRepository.trimmedOrNil(waypoint.name)
            entity.cmt = waypoint.cmt
            entity.desc = waypoint.desc
            entity.lat = waypoint.lat
            entity.lon = waypoint.lon
            entity.ele = waypoint.ele
            entity.timeMillis = waypoint.timeMillis
            entity.sym = waypoint.sym
            entity.type = waypoint.type
            try self.update(entity)
        }
    }

    // MARK: - Items

    /// Description and comment of a track or route, shown in the detail screen and written to GPX.
    public func describe(_ ref: ItemRef, _ description: String?) throws {
        let value = TrackRepository.trimmedOrNil(description)
        try write {
            switch ref.kind {
            case .track:
                guard var e = try self.fetch(TrackEntity.self, ref.id) else { return }
                e.desc = value
                try self.update(e)
            case .route:
                guard var e = try self.fetch(RouteEntity.self, ref.id) else { return }
                e.desc = value
                try self.update(e)
            case .waypoint:
                guard var e = try self.fetch(WaypointEntity.self, ref.id) else { return }
                e.desc = value
                try self.update(e)
            }
        }
    }

    public func setVisible(_ ref: ItemRef, _ visible: Bool) throws {
        try write {
            switch ref.kind {
            case .track:
                guard var e = try self.fetch(TrackEntity.self, ref.id) else { return }
                e.visible = visible
                try self.update(e)
            case .route:
                guard var e = try self.fetch(RouteEntity.self, ref.id) else { return }
                e.visible = visible
                try self.update(e)
            case .waypoint:
                guard var e = try self.fetch(WaypointEntity.self, ref.id) else { return }
                e.visible = visible
                try self.update(e)
            }
        }
    }

    public func rename(_ ref: ItemRef, _ name: String) throws {
        let value = TrackRepository.trimmedOrNil(name)
        try write {
            switch ref.kind {
            case .track:
                guard var e = try self.fetch(TrackEntity.self, ref.id) else { return }
                e.name = value
                try self.update(e)
            case .route:
                guard var e = try self.fetch(RouteEntity.self, ref.id) else { return }
                e.name = value
                try self.update(e)
            case .waypoint:
                guard var e = try self.fetch(WaypointEntity.self, ref.id) else { return }
                e.name = value
                try self.update(e)
            }
        }
    }

    /// Colour, width, dashes and direction arrows of a track or a route.
    public func setStyle(_ ref: ItemRef, _ style: PathStyle) throws {
        try write {
            switch ref.kind {
            case .track:
                guard var e = try self.fetch(TrackEntity.self, ref.id) else { return }
                e.colorArgb = style.colorArgb
                e.widthDp = style.widthDp
                e.dashed = style.dashed
                e.arrows = style.arrows
                try self.update(e)
            case .route:
                guard var e = try self.fetch(RouteEntity.self, ref.id) else { return }
                e.colorArgb = style.colorArgb
                e.widthDp = style.widthDp
                e.dashed = style.dashed
                e.arrows = style.arrows
                try self.update(e)
            case .waypoint:
                // Waypoints have no line to style.
                return
            }
        }
    }

    /// Deletes the item (the points of a track go with it).
    public func delete(_ ref: ItemRef) throws {
        let table: String
        switch ref.kind {
        case .track: table = TrackEntity.table
        case .route: table = RouteEntity.table
        case .waypoint: table = WaypointEntity.table
        }
        try write {
            _ = try self.exec("DELETE FROM \(LibrarySql.quoted(table)) WHERE `id` = ?", [.integer(ref.id)])
        }
    }

    /// « Tout afficher sur la carte » of the tracks panel (P14.10): every folder, track, route and waypoint at once.
    public func setAllVisible(_ visible: Bool) throws {
        try write {
            for table in [FolderEntity.table, TrackEntity.table, RouteEntity.table, WaypointEntity.table] {
                try self.exec("UPDATE \(LibrarySql.quoted(table)) SET `visible` = ?", [LibrarySql.bool(visible)])
            }
        }
    }

    // MARK: - Folders

    public func setFolderVisible(_ folderId: Int64, _ visible: Bool) throws {
        try write {
            guard var folder = try self.fetch(FolderEntity.self, folderId) else { return }
            folder.visible = visible
            try self.update(folder)
        }
    }

    public func renameFolder(_ folderId: Int64, _ name: String) throws {
        guard let value = TrackRepository.trimmedOrNil(name) else { return }
        try write {
            guard var folder = try self.fetch(FolderEntity.self, folderId) else { return }
            folder.name = value
            try self.update(folder)
        }
    }

    /// Deletes the folder and everything in it (sub-folders, items and their points, by the cascades).
    public func deleteFolder(_ folderId: Int64) throws {
        try write {
            _ = try self.exec("DELETE FROM `folders` WHERE `id` = ?", [.integer(folderId)])
        }
    }

    /// A new empty folder under `parentId` (nil = at the top of the library); nil when the name is blank or the parent
    /// is gone.
    @discardableResult
    public func createFolder(_ parentId: Int64?, _ name: String) throws -> Int64? {
        guard let value = TrackRepository.trimmedOrNil(name) else { return nil }
        return try write {
            if let parent = parentId {
                let found = try self.fetch(FolderEntity.self, parent)
                if found == nil { return nil }
            }
            return try self.insert(FolderEntity(parentId: parentId, name: value, createdAt: self.now()))
        }
    }

    /// Puts a track, route or waypoint in `folderId` (nil = top of the library). False when it or the folder is gone.
    @discardableResult
    public func moveItem(_ ref: ItemRef, _ folderId: Int64?) throws -> Bool {
        return try write {
            if let target = folderId {
                let found = try self.fetch(FolderEntity.self, target)
                if found == nil { return false }
            }
            let lib = try self.foldersOnly()
            switch ref.kind {
            case .track:
                guard var e = try self.fetch(TrackEntity.self, ref.id) else { return false }
                try self.carryNamespaces(lib, e.folderId, folderId)
                e.folderId = folderId
                try self.update(e)
            case .route:
                guard var e = try self.fetch(RouteEntity.self, ref.id) else { return false }
                try self.carryNamespaces(lib, e.folderId, folderId)
                e.folderId = folderId
                try self.update(e)
            case .waypoint:
                guard var e = try self.fetch(WaypointEntity.self, ref.id) else { return false }
                try self.carryNamespaces(lib, e.folderId, folderId)
                e.folderId = folderId
                try self.update(e)
            }
            return true
        }
    }

    /// Moves a folder, with all it holds, under `parentId` (nil = top of the library). False when it would end up
    /// inside itself or one of its own sub-folders, or when either folder is gone.
    @discardableResult
    public func moveFolder(_ folderId: Int64, _ parentId: Int64?) throws -> Bool {
        return try write {
            guard let folder = try self.fetch(FolderEntity.self, folderId) else { return false }
            let lib = try self.foldersOnly()
            if let parent = parentId {
                let found = try self.fetch(FolderEntity.self, parent)
                if found == nil || lib.descendants(folderId).contains(parent) { return false }
            }
            let oldRoot = lib.chain(folderId).last
            var moved = folder
            moved.parentId = parentId
            if parentId == nil {
                // It becomes a top-level folder: it must now carry the prefixes of the import it came from.
                if let root = oldRoot, root.id != folderId {
                    moved.namespacesJson = root.namespacesJson
                }
            } else {
                try self.carryNamespaces(lib, folderId, parentId)
            }
            try self.update(moved)
            return true
        }
    }

    // MARK: - Locking, transactions, change notification

    /// Runs `body` under the lock; when the outermost call ends and something was written, `onChange` is called once
    /// the lock is released.
    @discardableResult
    private func access<T>(_ body: () throws -> T) throws -> T {
        lock.lock()
        depth += 1
        defer {
            depth -= 1
            var handler: (@Sendable () -> Void)?
            if depth == 0 && changed {
                changed = false
                handler = changeHandler
            }
            lock.unlock()
            handler?()
        }
        return try body()
    }

    /// Room's `withTransaction`: a transaction already open takes the work in.
    @discardableResult
    private func transaction<T>(_ body: () throws -> T) throws -> T {
        if inTransaction { return try body() }
        inTransaction = true
        defer { inTransaction = false }
        return try db.transaction(body)
    }

    @discardableResult
    private func write<T>(_ body: () throws -> T) throws -> T {
        return try access {
            try self.transaction(body)
        }
    }

    // MARK: - Rows (TrackDao)

    @discardableResult
    private func exec(_ sql: String, _ values: [SQLiteDatabase.Value] = []) throws -> Int {
        let count = try db.run(sql, values)
        if count > 0 { changed = true }
        return count
    }

    private func fetch<E: LibraryRecord>(_ type: E.Type, _ id: Int64) throws -> E? {
        let rows = try db.query(E.selectSql + " WHERE `id` = ?", [.integer(id)])
        guard let row = rows.first else { return nil }
        return E(row: LibraryRow(row))
    }

    private func fetchAll<E: LibraryRecord>(_ type: E.Type, _ order: String = "") throws -> [E] {
        let rows = try db.query(E.selectSql + order)
        return rows.map { E(row: LibraryRow($0)) }
    }

    /// Inserts the row without its `id` and returns the one SQLite gave.
    @discardableResult
    private func insert<E: LibraryRecord>(_ record: E) throws -> Int64 {
        try exec(E.insertSql, Array(record.columnValues.dropFirst()))
        return db.lastInsertRowId
    }

    private func update<E: LibraryRecord>(_ record: E) throws {
        var values = Array(record.columnValues.dropFirst())
        values.append(.integer(record.id))
        try exec(E.updateSql, values)
    }

    /// The stored chunks of a track, in order (TrackDao `chunks`).
    func chunks(_ trackId: Int64) throws -> [TrackChunkEntity] {
        return try access {
            let sql = TrackChunkEntity.selectSql + " WHERE `trackId` = ? ORDER BY `segment`, `chunk`"
            let rows = try self.db.query(sql, [.integer(trackId)])
            return rows.map { TrackChunkEntity(row: LibraryRow($0)) }
        }
    }

    private func lastChunk(_ trackId: Int64) throws -> TrackChunkEntity? {
        let sql = TrackChunkEntity.selectSql + " WHERE `trackId` = ? ORDER BY `segment` DESC, `chunk` DESC LIMIT 1"
        let rows = try db.query(sql, [.integer(trackId)])
        guard let row = rows.first else { return nil }
        return TrackChunkEntity(row: LibraryRow(row))
    }

    private func insertChunks(_ chunks: [TrackChunkEntity]) throws {
        for chunk in chunks {
            try exec(TrackChunkEntity.insertSql, chunk.columnValues)
        }
    }

    private func deleteChunks(_ trackId: Int64) throws {
        try exec("DELETE FROM `track_chunks` WHERE `trackId` = ?", [.integer(trackId)])
    }

    private func foldersOnly() throws -> TrackLibrary {
        let folders = try fetchAll(FolderEntity.self)
        return TrackLibrary(folders: folders)
    }

    /// Raw XML of an item relies on the prefixes of the import at the top of its folder (see `NamespaceMerger`): when
    /// the item changes top-level folder, the destination learns the prefixes of the old one (its own win on a clash).
    private func carryNamespaces(_ lib: TrackLibrary, _ from: Int64?, _ to: Int64?) throws {
        guard let oldRoot = lib.chain(from).last, let newRoot = lib.chain(to).last else { return }
        if oldRoot.id == newRoot.id { return }
        let old = try TrackJson.decodeNamespaces(oldRoot.namespacesJson)
        let own = try TrackJson.decodeNamespaces(newRoot.namespacesJson)
        let merged = old.merging(own) { _, mine in mine }
        if merged != own {
            var updated = newRoot
            updated.namespacesJson = try TrackJson.encodeNamespaces(merged)
            try update(updated)
        }
    }

    // MARK: - Entities from the model

    private func chunksOf(_ trackId: Int64, _ segments: [[TrackPoint]]) -> [TrackChunkEntity] {
        var out: [TrackChunkEntity] = []
        for (s, points) in segments.enumerated() {
            for (c, part) in TrackRepository.chunked(points, TrackRepository.chunkPoints).enumerated() {
                out.append(
                    TrackChunkEntity(trackId: trackId, segment: s, chunk: c, pointCount: part.count, data: PointCodec.encode(part))
                )
            }
        }
        return out
    }

    private func trackEntity(_ t: Track, folderId: Int64?, created: Int64, format: String?, index: Int) throws -> TrackEntity {
        let style = t.style ?? PathStyle(colorArgb: PathStyle.palette[index % PathStyle.palette.count])
        let linksJson = try TrackJson.encodeLinks(t.links)
        let extrasJson = try TrackJson.encodeExtras(t.extras)
        let entity = TrackEntity(
            folderId: folderId,
            name: t.name,
            cmt: t.cmt,
            desc: t.desc,
            src: t.src,
            number: t.number,
            type: t.type,
            linksJson: linksJson,
            extrasJson: extrasJson,
            colorArgb: style.colorArgb,
            widthDp: style.widthDp,
            dashed: style.dashed,
            arrows: style.arrows,
            createdAt: created,
            format: format,
            pointCount: 0,
            segmentCount: 0,
            minLat: 0.0,
            minLon: 0.0,
            maxLat: 0.0,
            maxLon: 0.0,
            distanceMeters: 0.0
        )
        return withStats(entity, t.segments)
    }

    /// The entity with the figures a list shows (size, bounds, distance, climb, dates) computed from `segments`.
    private func withStats(_ entity: TrackEntity, _ segments: [[TrackPoint]]) -> TrackEntity {
        let stats = TrackStatsCalculator.compute(segments)
        var minLat: Double?
        var minLon: Double?
        var maxLat: Double?
        var maxLon: Double?
        for segment in segments {
            for p in segment {
                minLat = Swift.min(minLat ?? p.lat, p.lat)
                minLon = Swift.min(minLon ?? p.lon, p.lon)
                maxLat = Swift.max(maxLat ?? p.lat, p.lat)
                maxLon = Swift.max(maxLon ?? p.lon, p.lon)
            }
        }
        let hasElevation = stats.minEleMeters != nil
        var e = entity
        e.pointCount = stats.pointCount
        e.segmentCount = stats.segmentCount
        e.minLat = minLat ?? 0.0
        e.minLon = minLon ?? 0.0
        e.maxLat = maxLat ?? 0.0
        e.maxLon = maxLon ?? 0.0
        e.distanceMeters = stats.distance2dMeters
        e.ascentMeters = hasElevation ? stats.ascentMeters : nil
        e.descentMeters = hasElevation ? stats.descentMeters : nil
        e.startTimeMillis = stats.startTimeMillis
        e.endTimeMillis = stats.endTimeMillis
        return e
    }

    private func routeEntity(_ r: Route, folderId: Int64?, created: Int64, format: String?, index: Int) throws -> RouteEntity {
        let style = r.style
            ?? PathStyle(colorArgb: PathStyle.palette[(index + 1) % PathStyle.palette.count], dashed: true)
        let line = [r.points.map { TrackPoint(lat: $0.lat, lon: $0.lon) }]
        let linksJson = try TrackJson.encodeLinks(r.links)
        let extrasJson = try TrackJson.encodeExtras(r.extras)
        let pointsJson = try TrackJson.encodeWaypoints(r.points)
        return RouteEntity(
            folderId: folderId,
            name: r.name,
            cmt: r.cmt,
            desc: r.desc,
            src: r.src,
            number: r.number,
            type: r.type,
            linksJson: linksJson,
            extrasJson: extrasJson,
            colorArgb: style.colorArgb,
            widthDp: style.widthDp,
            dashed: style.dashed,
            arrows: style.arrows,
            createdAt: created,
            format: format,
            pointCount: r.points.count,
            minLat: r.points.map { $0.lat }.min() ?? 0.0,
            minLon: r.points.map { $0.lon }.min() ?? 0.0,
            maxLat: r.points.map { $0.lat }.max() ?? 0.0,
            maxLon: r.points.map { $0.lon }.max() ?? 0.0,
            distanceMeters: TrackStatsCalculator.compute(line).distance2dMeters,
            pointsJson: pointsJson
        )
    }

    private func waypointEntity(_ w: Waypoint, folderId: Int64?, created: Int64, format: String?) throws -> WaypointEntity {
        let linksJson = try TrackJson.encodeLinks(w.links)
        let extrasJson = try TrackJson.encodeExtras(w.extras)
        return WaypointEntity(
            folderId: folderId,
            name: w.name,
            cmt: w.cmt,
            desc: w.desc,
            src: w.src,
            lat: w.lat,
            lon: w.lon,
            ele: w.ele,
            timeMillis: w.timeMillis,
            sym: w.sym,
            type: w.type,
            linksJson: linksJson,
            extrasJson: extrasJson,
            createdAt: created,
            format: format
        )
    }

    private func waypointOf(_ e: WaypointEntity) throws -> Waypoint {
        let links = try TrackJson.decodeLinks(e.linksJson)
        let extras = try TrackJson.decodeExtras(e.extrasJson)
        return Waypoint(
            lat: e.lat,
            lon: e.lon,
            ele: e.ele,
            timeMillis: e.timeMillis,
            name: e.name,
            cmt: e.cmt,
            desc: e.desc,
            src: e.src,
            links: links,
            sym: e.sym,
            type: e.type,
            extras: extras
        )
    }

    // MARK: - Kotlin helpers

    /// Kotlin `text?.trim()?.takeIf { it.isNotEmpty() }`.
    static func trimmedOrNil(_ text: String?) -> String? {
        guard let text else { return nil }
        let trimmed = kotlinTrim(text)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Kotlin `substringBeforeLast(delimiter)`: the whole text when the delimiter is missing.
    static func substringBeforeLast(_ text: String, _ delimiter: Character) -> String {
        guard let index = text.lastIndex(of: delimiter) else { return text }
        return String(text[..<index])
    }

    /// Kotlin `chunked(size)`.
    static func chunked<T>(_ list: [T], _ size: Int) -> [[T]] {
        stride(from: 0, to: list.count, by: size).map { Array(list[$0 ..< Swift.min($0 + size, list.count)]) }
    }

    /// Kotlin `copy(extras = …, segments = …)` of a track.
    static func copyTrack(_ t: Track, extras: [RawXml], segments: [[TrackPoint]]) -> Track {
        Track(
            name: t.name,
            cmt: t.cmt,
            desc: t.desc,
            src: t.src,
            links: t.links,
            number: t.number,
            type: t.type,
            segments: segments,
            extras: extras,
            style: t.style,
            folder: t.folder
        )
    }

    /// Kotlin `copy(extras = …, points = …)` of a route.
    static func copyRoute(_ r: Route, extras: [RawXml], points: [Waypoint]) -> Route {
        Route(
            name: r.name,
            cmt: r.cmt,
            desc: r.desc,
            src: r.src,
            links: r.links,
            number: r.number,
            type: r.type,
            points: points,
            extras: extras,
            style: r.style,
            folder: r.folder
        )
    }

    /// Kotlin `copy(extras = …)` of a waypoint.
    static func copyWaypoint(_ w: Waypoint, extras: [RawXml]) -> Waypoint {
        Waypoint(
            lat: w.lat,
            lon: w.lon,
            ele: w.ele,
            timeMillis: w.timeMillis,
            name: w.name,
            cmt: w.cmt,
            desc: w.desc,
            src: w.src,
            links: w.links,
            sym: w.sym,
            type: w.type,
            extras: extras,
            folder: w.folder
        )
    }

    /// Kotlin `copy(extras = …)` of a point.
    static func copyPoint(_ p: TrackPoint, extras: [RawXml]) -> TrackPoint {
        TrackPoint(lat: p.lat, lon: p.lon, ele: p.ele, timeMillis: p.timeMillis, extras: extras)
    }
}
