// Port of data/src/main/kotlin/fr/toolmap/data/tracks/TrackLibrary.kt, with `AppendedPoints` and `AppendedSince` of
// TrackRepository.kt.

import Foundation

/// Everything in the library at one moment: what the list screen and the map layer are drawn from.
public struct TrackLibrary: Equatable, Sendable {
    private static let maxDepth = 64

    public let folders: [FolderEntity]
    public let tracks: [TrackEntity]
    public let routes: [RouteEntity]
    public let waypoints: [WaypointEntity]
    private let folderById: [Int64: FolderEntity]

    public init(
        folders: [FolderEntity] = [],
        tracks: [TrackEntity] = [],
        routes: [RouteEntity] = [],
        waypoints: [WaypointEntity] = []
    ) {
        self.folders = folders
        self.tracks = tracks
        self.routes = routes
        self.waypoints = waypoints
        var byId: [Int64: FolderEntity] = [:]
        for folder in folders {
            byId[folder.id] = folder
        }
        folderById = byId
    }

    public var isEmpty: Bool {
        tracks.isEmpty && routes.isEmpty && waypoints.isEmpty && folders.isEmpty
    }

    /// The folder and its ancestors, innermost first.
    public func chain(_ folderId: Int64?) -> [FolderEntity] {
        var out: [FolderEntity] = []
        var next = folderId
        while let current = next, out.count < TrackLibrary.maxDepth {
            guard let folder = folderById[current] else { break }
            out.append(folder)
            next = folder.parentId
        }
        return out
    }

    /// `Trip / Day 1`: the folders an item sits in, outermost first.
    public func pathOf(_ folderId: Int64?) -> String {
        chain(folderId).reversed().map { $0.name }.joined(separator: " / ")
    }

    /// An item is drawn when it is visible itself and so is every folder above it.
    public func isShown(_ folderId: Int64?, _ itemVisible: Bool) -> Bool {
        itemVisible && chain(folderId).allSatisfy { $0.visible }
    }

    /// Ids of the folder and of every folder below it.
    public func descendants(_ folderId: Int64) -> Set<Int64> {
        var out: Set<Int64> = [folderId]
        var grew = true
        while grew {
            grew = false
            for folder in folders {
                guard let parent = folder.parentId, out.contains(parent), !out.contains(folder.id) else { continue }
                out.insert(folder.id)
                grew = true
            }
        }
        return out
    }
}

/// The kind of an item of the library. The explorer's `LibraryKind` (LibraryOrder.swift) has the same three values;
/// as on Android, the two stay separate types.
public enum ItemKind: String, CaseIterable, Sendable {
    case track = "TRACK"
    case route = "ROUTE"
    case waypoint = "WAYPOINT"
}

/// One item of the library, whatever its kind.
public struct ItemRef: Equatable, Hashable, Sendable {
    public let kind: ItemKind
    public let id: Int64

    public init(kind: ItemKind, id: Int64) {
        self.kind = kind
        self.id = id
    }
}

/// What to export: the items, plus whole folders (their contents, at any depth).
public struct TrackSelection: Equatable, Hashable, Sendable {
    public var trackIds: [Int64]
    public var routeIds: [Int64]
    public var waypointIds: [Int64]
    public var folderIds: [Int64]

    public init(trackIds: [Int64] = [], routeIds: [Int64] = [], waypointIds: [Int64] = [], folderIds: [Int64] = []) {
        self.trackIds = trackIds
        self.routeIds = routeIds
        self.waypointIds = waypointIds
        self.folderIds = folderIds
    }
}

/// What an import put in the library.
public struct ImportSummary: Equatable, Sendable {
    public let folderId: Int64
    public let tracks: Int
    public let routes: Int
    public let waypoints: Int
    public let points: Int
    public let issues: [TrackIssue]

    public init(folderId: Int64, tracks: Int, routes: Int, waypoints: Int, points: Int, issues: [TrackIssue]) {
        self.folderId = folderId
        self.tracks = tracks
        self.routes = routes
        self.waypoints = waypoints
        self.points = points
        self.issues = issues
    }
}

/// A batch of points the recorder added at the end of a track. `startsSegment` is true when the batch opens a segment,
/// `endCount` is the number of points the whole track holds once the batch is in.
public struct AppendedPoints: Equatable, Sendable {
    public let revision: Int
    public let points: [TrackPoint]
    public let startsSegment: Bool
    public let endCount: Int

    public init(revision: Int, points: [TrackPoint], startsSegment: Bool, endCount: Int) {
        self.revision = revision
        self.points = points
        self.startsSegment = startsSegment
        self.endCount = endCount
    }
}

/// The batches that lead a track from an older revision to `revision`.
public struct AppendedSince: Equatable, Sendable {
    public let revision: Int
    public let batches: [AppendedPoints]

    public init(revision: Int, batches: [AppendedPoints]) {
        self.revision = revision
        self.batches = batches
    }
}
