import Foundation
import ToolMapCore

/// A folder line of the list.
struct LibraryFolderLine {
    let folder: FolderEntity
    let depth: Int
    let itemCount: Int
    let expanded: Bool
    /// False when a folder above hides it, whatever its own eye says.
    let parentsShown: Bool
}

/// A track, route or waypoint line of the list: what the explorer sorts, filters and selects (F7).
struct LibraryItemLine {
    let ref: ItemRefKey
    let depth: Int
    let name: String?
    let folderId: Int64?
    /// Its own eye.
    let visible: Bool
    /// Drawn on the map: its own eye and every folder above.
    let shown: Bool
    let key: LibraryKey
    /// Line colour of a track or a route.
    let colorArgb: Int32
    let style: PathStyle?
    /// Symbol of a waypoint.
    let sym: String?
    let ascentMeters: Double?
    let elevationMeters: Double?
    /// Walking time of a saved route.
    let duration: String?
    let bounds: Bounds?
}

/// One line of the list.
enum LibraryListRow: Identifiable {
    case folder(LibraryFolderLine)
    case item(LibraryItemLine)

    var id: String {
        switch self {
        case let .folder(line): return "f\(line.folder.id)"
        case let .item(line): return "\(line.ref.kind.rawValue)\(line.ref.id)"
        }
    }

    var depth: Int {
        switch self {
        case let .folder(line): return line.depth
        case let .item(line): return line.depth
        }
    }
}

/// The lines of the explorer (Android `rows`): the folders on top, then the items of each level in the chosen order
/// and of the chosen kind (F7). A search lists the matching items of every folder, without the folders.
enum LibraryListModel {
    static func rows(_ lib: TrackLibrary, expanded: Set<Int64>, view: LibraryViewSettings, query: String) -> [LibraryListRow] {
        if !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return items(lib, depth: 0, view: view, query: query) { _ in true }.map { LibraryListRow.item($0) }
        }
        var out: [LibraryListRow] = []
        visit(lib, nil, 0, expanded, view, &out)
        return out
    }

    private static func visit(
        _ lib: TrackLibrary,
        _ parent: Int64?,
        _ depth: Int,
        _ expanded: Set<Int64>,
        _ view: LibraryViewSettings,
        _ out: inout [LibraryListRow]
    ) {
        for f in lib.folders where f.parentId == parent {
            let inside = lib.descendants(f.id)
            let total = itemCount(lib, inside)
            let open = expanded.contains(f.id)
            let line = LibraryFolderLine(
                folder: f,
                depth: depth,
                itemCount: total,
                expanded: open,
                parentsShown: lib.isShown(f.parentId, true)
            )
            out.append(.folder(line))
            if open {
                visit(lib, f.id, depth + 1, expanded, view, &out)
            }
        }
        let here = items(lib, depth: depth, view: view, query: "") { $0 == parent }
        out.append(contentsOf: here.map { LibraryListRow.item($0) })
    }

    private static func itemCount(_ lib: TrackLibrary, _ folders: Set<Int64>) -> Int {
        func inside(_ id: Int64?) -> Bool {
            guard let id else { return false }
            return folders.contains(id)
        }
        let tracks = lib.tracks.filter { inside($0.folderId) }.count
        let routes = lib.routes.filter { inside($0.folderId) }.count
        let waypoints = lib.waypoints.filter { inside($0.folderId) }.count
        return tracks + routes + waypoints
    }

    /// Every item, whatever its folder (the selection and the visibility of several items).
    static func allItems(_ lib: TrackLibrary) -> [LibraryItemLine] {
        let sorted = LibraryViewSettings(sort: .date, filter: .all)
        return items(lib, depth: 0, view: sorted, query: "") { _ in true }
    }

    /// The items whose folder passes `inFolder`, of the kind of `view` and matching `query`, in the order of `view`.
    private static func items(
        _ lib: TrackLibrary,
        depth: Int,
        view: LibraryViewSettings,
        query: String,
        inFolder: (Int64?) -> Bool
    ) -> [LibraryItemLine] {
        var found: [LibraryItemLine] = []
        for t in lib.tracks where inFolder(t.folderId) {
            found.append(track(t, lib, depth))
        }
        for r in lib.routes where inFolder(r.folderId) {
            found.append(route(r, lib, depth))
        }
        for w in lib.waypoints where inFolder(w.folderId) {
            found.append(waypoint(w, lib, depth))
        }
        let kept = found.filter {
            LibraryOrder.accepts(view.filter, $0.key.kind) && LibraryOrder.matches($0.name, query)
        }
        let sort = view.sort
        let indexed = Array(kept.enumerated())
        let ordered = indexed.sorted { a, b in
            let c = LibraryOrder.compare(a.element.key, b.element.key, sort)
            return c != 0 ? c < 0 : a.offset < b.offset
        }
        return ordered.map { $0.element }
    }

    private static func track(_ t: TrackEntity, _ lib: TrackLibrary, _ depth: Int) -> LibraryItemLine {
        let key = LibraryKey(kind: .track, name: t.name, timeMillis: t.startTimeMillis ?? t.createdAt, distanceMeters: t.distanceMeters)
        let bounds: Bounds? = t.pointCount > 0 ? Bounds(minX: t.minLon, minY: t.minLat, maxX: t.maxLon, maxY: t.maxLat) : nil
        return LibraryItemLine(
            ref: ItemRefKey(kind: .track, id: t.id),
            depth: depth,
            name: t.name,
            folderId: t.folderId,
            visible: t.visible,
            shown: lib.isShown(t.folderId, t.visible),
            key: key,
            colorArgb: t.colorArgb,
            style: PathStyle(colorArgb: t.colorArgb, widthDp: t.widthDp, dashed: t.dashed, arrows: t.arrows),
            sym: nil,
            ascentMeters: t.ascentMeters,
            elevationMeters: nil,
            duration: nil,
            bounds: bounds
        )
    }

    private static func route(_ r: RouteEntity, _ lib: TrackLibrary, _ depth: Int) -> LibraryItemLine {
        let key = LibraryKey(kind: .route, name: r.name, timeMillis: r.createdAt, distanceMeters: r.distanceMeters)
        let bounds: Bounds? = r.pointCount > 0 ? Bounds(minX: r.minLon, minY: r.minLat, maxX: r.maxLon, maxY: r.maxLat) : nil
        return LibraryItemLine(
            ref: ItemRefKey(kind: .route, id: r.id),
            depth: depth,
            name: r.name,
            folderId: r.folderId,
            visible: r.visible,
            shown: lib.isShown(r.folderId, r.visible),
            key: key,
            colorArgb: r.colorArgb,
            style: PathStyle(colorArgb: r.colorArgb, widthDp: r.widthDp, dashed: r.dashed, arrows: r.arrows),
            sym: nil,
            ascentMeters: nil,
            elevationMeters: nil,
            duration: LibraryText.routeDuration(LibraryText.routeInfo(r)),
            bounds: bounds
        )
    }

    private static func waypoint(_ w: WaypointEntity, _ lib: TrackLibrary, _ depth: Int) -> LibraryItemLine {
        let key = LibraryKey(kind: .waypoint, name: w.name, timeMillis: w.timeMillis ?? w.createdAt, distanceMeters: nil)
        return LibraryItemLine(
            ref: ItemRefKey(kind: .waypoint, id: w.id),
            depth: depth,
            name: w.name,
            folderId: w.folderId,
            visible: w.visible,
            shown: lib.isShown(w.folderId, w.visible),
            key: key,
            colorArgb: WaypointSymbols.colorOf(w.sym),
            style: nil,
            sym: w.sym,
            ascentMeters: nil,
            elevationMeters: w.ele,
            duration: nil,
            bounds: Bounds(minX: w.lon, minY: w.lat, maxX: w.lon, maxY: w.lat)
        )
    }

    /// « 11,9 km · ↑ 905 m · 18 oct. », « 9,37 km · 3 h 05 · 23 oct. », « 330 m · 18 oct. ».
    static func subtitle(_ line: LibraryItemLine, _ settings: AppSettings) -> String {
        var parts: [String] = []
        if let meters = line.key.distanceMeters {
            parts.append(LibraryText.distance(meters, settings))
        }
        if let ascent = line.ascentMeters {
            parts.append("↑ " + LibraryText.elevation(ascent, settings))
        }
        if let duration = line.duration {
            parts.append(duration)
        }
        if let ele = line.elevationMeters {
            parts.append(LibraryText.elevation(ele, settings))
        }
        parts.append(LibraryText.shortDate(line.key.timeMillis))
        // A line only wraps at a « · ».
        return parts.map { $0.replacingOccurrences(of: " ", with: "\u{00A0}") }.joined(separator: " · ")
    }

    /// Every folder, track, route and waypoint is visible (« Tout afficher sur la carte »).
    static func allVisible(_ lib: TrackLibrary) -> Bool {
        lib.folders.allSatisfy { $0.visible } && lib.tracks.allSatisfy { $0.visible }
            && lib.routes.allSatisfy { $0.visible } && lib.waypoints.allSatisfy { $0.visible }
    }
}
