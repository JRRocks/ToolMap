import CoreTransferable
import Foundation
import SwiftUI
import ToolMapCore
import UniformTypeIdentifiers

// MARK: - Items

extension ItemRefKey {
    /// The same item for the core library.
    var libraryRef: ItemRef {
        switch kind {
        case .track: return ItemRef(kind: .track, id: id)
        case .route: return ItemRef(kind: .route, id: id)
        case .waypoint: return ItemRef(kind: .waypoint, id: id)
        }
    }

    /// The selection that exports this item alone.
    var librarySelection: TrackSelection {
        switch kind {
        case .track: return TrackSelection(trackIds: [id])
        case .route: return TrackSelection(routeIds: [id])
        case .waypoint: return TrackSelection(waypointIds: [id])
        }
    }
}

/// The selection of several items, for one GPX file.
enum LibrarySelection {
    static func of(_ refs: [ItemRefKey]) -> TrackSelection {
        TrackSelection(
            trackIds: refs.filter { $0.kind == .track }.map { $0.id },
            routeIds: refs.filter { $0.kind == .route }.map { $0.id },
            waypointIds: refs.filter { $0.kind == .waypoint }.map { $0.id }
        )
    }
}

extension Waypoint {
    /// A copy with the fields the waypoint dialog changes; the others (time, comment, links, extensions) are kept.
    func libraryCopy(lat: Double, lon: Double, ele: Double?, name: String?, desc: String?, sym: String?) -> Waypoint {
        Waypoint(
            lat: lat,
            lon: lon,
            ele: ele,
            timeMillis: timeMillis,
            name: name,
            cmt: cmt,
            desc: desc,
            src: src,
            links: links,
            sym: sym,
            type: type,
            extras: extras,
            folder: folder
        )
    }
}

/// Where the items are, to frame them on the map.
enum LibraryBounds {
    static func of(_ ref: ItemRefKey, in lib: TrackLibrary) -> Bounds? {
        switch ref.kind {
        case .track:
            guard let t = lib.tracks.first(where: { $0.id == ref.id }), t.pointCount > 0 else { return nil }
            return Bounds(minX: t.minLon, minY: t.minLat, maxX: t.maxLon, maxY: t.maxLat)
        case .route:
            guard let r = lib.routes.first(where: { $0.id == ref.id }), r.pointCount > 0 else { return nil }
            return Bounds(minX: r.minLon, minY: r.minLat, maxX: r.maxLon, maxY: r.maxLat)
        case .waypoint:
            guard let w = lib.waypoints.first(where: { $0.id == ref.id }) else { return nil }
            return Bounds(minX: w.lon, minY: w.lat, maxX: w.lon, maxY: w.lat)
        }
    }

    /// Everything in `folders` and below them; nil when they hold nothing with a place.
    static func of(folders: Set<Int64>, in lib: TrackLibrary) -> Bounds? {
        var all = Set<Int64>()
        for folder in folders {
            all.formUnion(lib.descendants(folder))
        }
        func inside(_ folderId: Int64?) -> Bool {
            guard let folderId else { return false }
            return all.contains(folderId)
        }
        var boxes: [Bounds] = []
        for t in lib.tracks where t.pointCount > 0 && inside(t.folderId) {
            boxes.append(Bounds(minX: t.minLon, minY: t.minLat, maxX: t.maxLon, maxY: t.maxLat))
        }
        for r in lib.routes where r.pointCount > 0 && inside(r.folderId) {
            boxes.append(Bounds(minX: r.minLon, minY: r.minLat, maxX: r.maxLon, maxY: r.maxLat))
        }
        for w in lib.waypoints where inside(w.folderId) {
            boxes.append(Bounds(minX: w.lon, minY: w.lat, maxX: w.lon, maxY: w.lat))
        }
        return union(boxes)
    }

    static func union(_ boxes: [Bounds]) -> Bounds? {
        guard let first = boxes.first else { return nil }
        var minX = first.minX
        var minY = first.minY
        var maxX = first.maxX
        var maxY = first.maxY
        for box in boxes.dropFirst() {
            minX = min(minX, box.minX)
            minY = min(minY, box.minY)
            maxX = max(maxX, box.maxX)
            maxY = max(maxY, box.maxY)
        }
        return Bounds(minX: minX, minY: minY, maxX: maxX, maxY: maxY)
    }
}

// MARK: - Relief

/// The altitude of the relief from the Terrarium tiles (Android `ElevationCompleter`, zoom 13), through the tile
/// service of the app: stored tiles first, nothing asked offline.
enum LibraryRelief {
    static let sourceId = "terrarium"
    static let zoom = 13

    /// The decoded tile, nil when it cannot be had.
    static func tile(_ coord: TileCoord) async -> TerrariumTile? {
        let source = LibraryRelief.sourceId
        let level = LibraryRelief.zoom
        return await withCheckedContinuation { (continuation: CheckedContinuation<TerrariumTile?, Never>) in
            TileService.shared.load(source, level, coord.x, coord.y) { data, _ in
                guard let data, let decoded = try? TerrariumTile.fromPng(data) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: decoded)
            }
        }
    }

    /// Meters above the sea at `position`, given to `completion` on any thread; nil when the tile cannot be had.
    static func lookup(_ position: LonLat, completion: @escaping @Sendable (Double?) -> Void) {
        let level = LibraryRelief.zoom
        guard let coord = try? TileMath.tileAt(position.lon, position.lat, level) else {
            completion(nil)
            return
        }
        TileService.shared.load(LibraryRelief.sourceId, level, coord.x, coord.y) { data, _ in
            guard let data,
                  let decoded = try? TerrariumTile.fromPng(data),
                  let fraction = try? TerrariumTile.fractionIn(position.lon, position.lat, level, coord.x, coord.y)
            else {
                completion(nil)
                return
            }
            completion(decoded.elevationAt(fraction.0, fraction.1))
        }
    }

    static func at(_ position: LonLat) async -> Double? {
        await withCheckedContinuation { (continuation: CheckedContinuation<Double?, Never>) in
            lookup(position) { value in
                continuation.resume(returning: value)
            }
        }
    }
}

/// « Compléter les altitudes » (Android `TrackRepository.completeElevation`): the points without an elevation take
/// the relief's, tile by tile.
enum LibraryElevation {
    struct Result: Sendable {
        let filled: Int
        let missingTiles: Int
    }

    static func complete(_ repository: TrackRepository, _ trackId: Int64) async -> Result? {
        guard let track = try? repository.loadTrack(trackId) else { return nil }
        let zoom = LibraryRelief.zoom
        var tiles: [String: TerrariumTile] = [:]
        var failed = Set<String>()
        var filled = 0
        var segments: [[TrackPoint]] = []
        for segment in track.segments {
            var out: [TrackPoint] = []
            out.reserveCapacity(segment.count)
            for p in segment {
                guard p.ele == nil, let coord = try? TileMath.tileAt(p.lon, p.lat, zoom) else {
                    out.append(p)
                    continue
                }
                let key = "\(coord.x)/\(coord.y)"
                if tiles[key] == nil, !failed.contains(key) {
                    if let found = await LibraryRelief.tile(coord) {
                        tiles[key] = found
                    } else {
                        failed.insert(key)
                    }
                }
                if let terrain = tiles[key],
                   let fraction = try? TerrariumTile.fractionIn(p.lon, p.lat, zoom, coord.x, coord.y) {
                    let ele = terrain.elevationAt(fraction.0, fraction.1)
                    out.append(TrackPoint(lat: p.lat, lon: p.lon, ele: ele, timeMillis: p.timeMillis, extras: p.extras))
                    filled += 1
                } else {
                    out.append(p)
                }
            }
            segments.append(out)
        }
        if filled > 0 {
            _ = try? repository.replaceSegments(trackId, segments)
        }
        return Result(filled: filled, missingTiles: failed.count)
    }
}

// MARK: - Import

/// What an import of one or more files added (Android `ImportSummary`, summed).
struct LibraryImportOutcome: Sendable {
    var tracks = 0
    var routes = 0
    var waypoints = 0
    var issues = 0
    var failures: [String] = []
    /// Everything the files added, to show it on the map.
    var bounds: Bounds?
}

enum LibraryImport {
    /// Reads and stores each file (GPX, KML, KMZ, GeoJSON: the content decides); runs off the main thread.
    static func run(_ urls: [URL], into repository: TrackRepository) -> LibraryImportOutcome {
        var outcome = LibraryImportOutcome()
        var folders = Set<Int64>()
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            do {
                let data = try Data(contentsOf: url)
                let name = url.lastPathComponent
                let document = try TrackImporter.read(data, nameHint: name)
                let summary = try repository.import(document, fileName: name)
                outcome.tracks += summary.tracks
                outcome.routes += summary.routes
                outcome.waypoints += summary.waypoints
                outcome.issues += summary.issues.count
                folders.insert(summary.folderId)
            } catch let error as TrackFormatError {
                outcome.failures.append(error.message)
            } catch {
                outcome.failures.append(error.localizedDescription)
            }
        }
        if !folders.isEmpty, let lib = try? repository.library() {
            outcome.bounds = LibraryBounds.of(folders: folders, in: lib)
        }
        return outcome
    }
}

// MARK: - Files

enum LibraryFiles {
    static let gpxType = UTType(importedAs: "com.topografix.gpx", conformingTo: .xml)

    /// What « Importer un fichier… » offers: the track files ToolMap reads, and any file (the content decides).
    static let importTypes: [UTType] = {
        var types: [UTType] = [
            LibraryFiles.gpxType,
            UTType(importedAs: "com.google.earth.kml", conformingTo: .xml),
            UTType(importedAs: "com.google.earth.kmz", conformingTo: .zip),
            .json,
        ]
        if let geoJson = UTType("public.geojson") {
            types.append(geoJson)
        }
        types.append(.item)
        return types
    }()
}

/// A GPX file of the library handed to `ShareLink`: written when the user picks where it goes.
struct LibraryGpxShare: Transferable {
    let repository: TrackRepository
    let selection: TrackSelection
    let fileName: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: LibraryFiles.gpxType) { (share: LibraryGpxShare) in
            let url = try share.write()
            return SentTransferredFile(url)
        }
    }

    /// The GPX of the selection in a fresh temporary file.
    func write() throws -> URL {
        let document = try repository.document(selection)
        let url = AppFiles.temporary(fileName)
        try GpxWriter.toBytes(document).write(to: url)
        return url
    }
}

/// The GPX of « Exporter en GPX… », for `fileExporter`.
struct LibraryGpxDocument: FileDocument {
    static var readableContentTypes: [UTType] { [LibraryFiles.gpxType] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// « Exporter en GPX… » of an item, a folder or a selection.
struct LibraryExportRequest: Identifiable, Equatable {
    let id = UUID()
    let selection: TrackSelection
    let name: String?
}

// MARK: - Demonstration

/// The items of the captures (`-ToolMapDemo YES`): two tracks and four waypoints around Fort Barraux, in a folder
/// « Chartreuse », written in `tracks-demo.db` only.
enum LibraryDemo {
    static func fillIfEmpty(_ repository: TrackRepository) {
        guard let lib = try? repository.library(), lib.isEmpty else { return }
        let document = TrackDocument(name: "Chartreuse", waypoints: waypoints(), tracks: tracks())
        _ = try? repository.import(document, fileName: nil)
    }

    private static func waypoints() -> [Waypoint] {
        [
            Waypoint(lat: 45.5646, lon: 5.9178, ele: 300, name: "Fort Barraux", sym: "Fort"),
            Waypoint(lat: 45.5612, lon: 5.9135, ele: 281, name: "Parking du fort", sym: "Parking"),
            Waypoint(lat: 45.5718, lon: 5.9302, ele: 523, name: "Belvédère", sym: "View point"),
            Waypoint(lat: 45.5580, lon: 5.9260, ele: 342, name: "Source", sym: "Drinking water"),
        ]
    }

    private static func tracks() -> [Track] {
        let loop: [(Double, Double, Double)] = [
            (45.5612, 5.9135, 281), (45.5646, 5.9178, 300), (45.5690, 5.9240, 412), (45.5718, 5.9302, 523),
            (45.5660, 5.9330, 431), (45.5580, 5.9260, 342), (45.5612, 5.9135, 281),
        ]
        let climb: [(Double, Double, Double)] = [
            (45.5646, 5.9178, 300), (45.5700, 5.9150, 384), (45.5760, 5.9200, 612), (45.5795, 5.9290, 781),
            (45.5752, 5.9330, 664), (45.5718, 5.9302, 523),
        ]
        let loopPoints = line(loop, start: 1_792_308_600_000)
        let climbPoints = line(climb, start: 1_792_757_400_000)
        return [
            Track(name: "Boucle du fort", segments: [loopPoints]),
            Track(name: "Montée au belvédère", segments: [climbPoints]),
        ]
    }

    /// Points every few tens of meters between the corners, one every 40 s.
    private static func line(_ corners: [(Double, Double, Double)], start: Int64) -> [TrackPoint] {
        var out: [TrackPoint] = []
        var time = start
        let steps = 12
        for i in 0 ..< max(corners.count - 1, 0) {
            let a = corners[i]
            let b = corners[i + 1]
            for step in 0 ..< steps {
                let f = Double(step) / Double(steps)
                let lat = a.0 + (b.0 - a.0) * f
                let lon = a.1 + (b.1 - a.1) * f
                let ele = a.2 + (b.2 - a.2) * f
                out.append(TrackPoint(lat: lat, lon: lon, ele: ele, timeMillis: time))
                time += 40_000
            }
        }
        if let last = corners.last {
            out.append(TrackPoint(lat: last.0, lon: last.1, ele: last.2, timeMillis: time))
        }
        return out
    }
}
