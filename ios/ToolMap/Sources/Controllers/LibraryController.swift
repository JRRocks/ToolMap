import Foundation
import Observation
import ToolMapCore
import UIKit

/// One of the user's own items as the search lists it (`LibraryController.searchItems`).
struct LibrarySearchItem: Equatable, Hashable {
    let ref: ItemRefKey
    let name: String
    /// « Trace · 11,9 km », « Itinéraire · 9,37 km », « Waypoint · 330 m ».
    let detail: String
    /// The waypoint, or the middle of the extent of a line.
    let position: LonLat
}

/// The item whose sheet is open on the map (Android `TappedItemSheet`).
struct LibraryTapped: Identifiable, Equatable {
    let ref: ItemRefKey

    var id: String { "\(ref.kind.rawValue)-\(ref.id)" }
}

/// The waypoint dialog (Android `WaypointDialog`): a new waypoint at `start` (`waypointId` nil) or one being edited.
struct WaypointDraft: Identifiable {
    let id = UUID()
    let waypointId: Int64?
    let initial: Waypoint?
    let start: LonLat
    let defaultName: String
    /// The format the coordinates are first written in (« Format de position »).
    let system: CoordinateSystem
}

/// A short message at the bottom of the library pages (the snackbar of Android's `TracksScreen`).
struct LibraryMessage: Identifiable {
    let id = UUID()
    let text: String
    let action: String?
    let perform: (() -> Void)?
}

/// Statistics, profile and line of a track, for its detail page (Android `TrackDetail`).
struct LibraryTrackDetail: Sendable {
    let trackId: Int64
    let stats: TrackStats
    let profile: ElevationProfile?
    let path: TrackPath
    /// Points without an elevation: « Compléter les altitudes » is offered when positive.
    let missingElevation: Int
}

/// Traces et waypoints (Android §6, `TracksViewModel`): the library file, its map layers, the sheet of an item touched
/// on the map, the waypoint dialog, the imports and exports. Every read and write of the file runs on one serial queue;
/// the core's `TrackRepository.onChange` makes the controller read the library again and rebuild the map layers.
///
/// What the other features call:
/// - `saveTrack(name:segments:)`, `saveRoute(name:points:)`: drawing, measure, recorder;
/// - `addWaypoint(at:name:openDialog:)`: menu of the centre point, long press, search;
/// - `paths(of:)`, `corridorPath(_:)`, `displayName(_:)`: the band to download, the print along a track;
/// - `searchItems(_:)`, `libraryItems()`: the search; `printItems()`, `setVisible(_:_:)`: the print;
/// - `reveal(_:)`: frames the map on an item and opens its sheet; `importFiles(_:)`: « Ouvrir avec ».
@Observable
final class LibraryController {
    @ObservationIgnored unowned let app: AppModel

    /// The library file in Application Support, the same schema as Android's (`TrackDatabase`).
    static let fileName = TrackDatabase.fileName
    /// The file of the captures (`-ToolMapDemo YES`), so that nothing is mixed with the user's own library.
    static let demoFileName = "tracks-demo.db"
    static let waypointPrefix = "Waypoint"
    /// Time for a sheet or a panel to go before the next one comes (one presentation at a time on iOS).
    static let presentationDelay = 0.45
    /// How long « Waypoint ici » waits for the relief before it records the waypoint without altitude.
    private static let quickElevationSeconds = 3.0
    private static let pointZoom = 15.0
    private static let corridorTolerance = 25.0

    /// The library as last read; nil until the first read.
    private(set) var library: TrackLibrary?
    /// The item whose sheet is open on the map.
    var tapped: LibraryTapped?
    /// The waypoint dialog opened on the map.
    var waypointDraft: WaypointDraft?
    /// An import or a completion of altitudes is running.
    private(set) var busy = false
    /// A message for the library pages while one is open.
    var screenMessage: LibraryMessage?
    /// Folders open in the list: kept back from a detail and from one opening to the next while the app runs.
    var expandedFolders: Set<Int64> = []

    @ObservationIgnored private let lock = NSLock()
    @ObservationIgnored private var store: TrackRepository?
    @ObservationIgnored private var storeDisplay: TrackDisplay?
    @ObservationIgnored private var openFailed = false
    @ObservationIgnored private let queue = DispatchQueue(label: "fr.toolmap.library", qos: .userInitiated)
    @ObservationIgnored private var shown: DisplayData?
    @ObservationIgnored private var shownPlacemarks: PlacemarkSettings?
    @ObservationIgnored private var refreshing = false
    @ObservationIgnored private var refreshAgain = false

    init(app: AppModel) {
        self.app = app
        // Not while the view that created the controller is being drawn.
        DispatchQueue.main.async { [weak self] in
            self?.start()
        }
    }

    static var isDemo: Bool {
        UserDefaults.standard.bool(forKey: "ToolMapDemo")
    }

    private func start() {
        app.tapHandlers[TrackLayers.linesId] = { [weak self] tap in
            self?.mapTapped(tap)
        }
        app.tapHandlers[TrackLayers.pointsId] = { [weak self] tap in
            self?.mapTapped(tap)
        }
        watchPlacemarks()
        refresh()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.applyLaunchArguments()
        }
    }

    // MARK: - The file

    /// The library file and its map display, opened on first use: `tracks.db`, or `tracks-demo.db` with
    /// `-ToolMapDemo YES`, filled with a few demonstration items when empty. Thread-safe.
    private func opened() -> (repository: TrackRepository, display: TrackDisplay)? {
        lock.lock()
        defer { lock.unlock() }
        if let store, let storeDisplay {
            return (store, storeDisplay)
        }
        if openFailed { return nil }
        let demo = LibraryController.isDemo
        let name = demo ? LibraryController.demoFileName : LibraryController.fileName
        let path = AppFiles.support.appendingPathComponent(name).path
        guard let repository = try? TrackRepository(path: path) else {
            openFailed = true
            return nil
        }
        if demo {
            LibraryDemo.fillIfEmpty(repository)
        }
        repository.onChange = { [weak self] in
            DispatchQueue.main.async {
                self?.refresh()
            }
        }
        let display = TrackDisplay(repository: repository)
        store = repository
        storeDisplay = display
        return (repository, display)
    }

    /// The library file, nil when it cannot be opened (the recorder appends to it directly). Thread-safe.
    var repository: TrackRepository? {
        opened()?.repository
    }

    /// The library as last read, or read now.
    func snapshot() -> TrackLibrary? {
        if let library { return library }
        return try? repository?.library()
    }

    // MARK: - Map layers

    /// Reads the library again and rebuilds the map layers off the main thread (at launch and after each change).
    func refresh() {
        if refreshing {
            refreshAgain = true
            return
        }
        guard let pair = opened() else {
            // The file cannot be opened: the pages show an empty library rather than wait for ever.
            if library == nil {
                library = TrackLibrary()
            }
            return
        }
        let repository = pair.repository
        let display = pair.display
        refreshing = true
        let marks = app.settings.placemarks
        queue.async { [weak self] in
            let lib = try? repository.library()
            let data = lib.flatMap { try? display.build($0) }
            let lines = data.map { TrackLayers.styledLines($0.linesGeoJson, marks) }
            let points = data.map { TrackLayers.styledPoints($0.pointsGeoJson, marks) }
            DispatchQueue.main.async {
                self?.refreshed(lib, data, lines, points, marks)
            }
        }
    }

    private func refreshed(
        _ lib: TrackLibrary?,
        _ data: DisplayData?,
        _ lines: String?,
        _ points: String?,
        _ marks: PlacemarkSettings
    ) {
        refreshing = false
        if let lib, lib != library {
            if library == nil, LibraryController.isDemo {
                // The captures show the demonstration folder open.
                expandedFolders = Set(lib.folders.map { $0.id })
            }
            library = lib
        }
        if let data, let lines, let points {
            shown = data
            shownPlacemarks = marks
            app.mapFeatures[TrackLayers.linesId] = lines
            app.mapFeatures[TrackLayers.pointsId] = points
        }
        if refreshAgain {
            refreshAgain = false
            refresh()
        } else if app.settings.placemarks != shownPlacemarks {
            restyle()
        }
    }

    /// The « Repères » settings changed: the same data, written again with them.
    private func restyle() {
        guard !refreshing, let data = shown else { return }
        let marks = app.settings.placemarks
        if marks == shownPlacemarks { return }
        refreshing = true
        queue.async { [weak self] in
            let lines = TrackLayers.styledLines(data.linesGeoJson, marks)
            let points = TrackLayers.styledPoints(data.pointsGeoJson, marks)
            DispatchQueue.main.async {
                self?.refreshed(nil, data, lines, points, marks)
            }
        }
    }

    private func watchPlacemarks() {
        withObservationTracking {
            _ = app.settings.placemarks
        } onChange: { [weak self] in
            DispatchQueue.main.async {
                self?.restyle()
                self?.watchPlacemarks()
            }
        }
    }

    private func mapTapped(_ tap: FeatureTap) {
        if app.tool != nil {
            // While a tool is open, the map is the tool's: a tap there is not a sheet.
            app.emptyTapHandler?(tap.position)
            return
        }
        guard let ref = TrackLayers.ref(tap) else { return }
        openSheet(ref)
    }

    // MARK: - Navigation

    /// Opens the sheet of an item on the map (G2).
    func openSheet(_ ref: ItemRefKey) {
        let item = LibraryTapped(ref: ref)
        if app.panel != nil || app.screen != nil {
            app.panel = nil
            app.screen = nil
            later { [weak self] in
                self?.tapped = item
            }
        } else {
            tapped = item
        }
    }

    /// Opens a page above the map: the panel or the sheet goes first.
    func openScreen(_ screen: Screen) {
        let presenting = app.panel != nil || tapped != nil || waypointDraft != nil
        app.panel = nil
        tapped = nil
        if presenting {
            later { [weak self] in
                self?.app.screen = screen
            }
        } else {
            app.screen = screen
        }
    }

    /// Starts a tool on the map from a sheet, a panel or a page, which all close.
    func startTool(_ tool: Tool) {
        tapped = nil
        app.panel = nil
        app.screen = nil
        app.tool = tool
    }

    /// « Télécharger autour » (P30.2): the line is shown, the page closes, the zone tool opens around it.
    func downloadAround(_ ref: ItemRefKey) {
        setVisible(ref, true)
        startTool(.zone(around: ref))
    }

    /// Back to the map, framed on `bounds` (a point is shown at a close zoom).
    func showOnMap(_ bounds: Bounds) {
        app.screen = nil
        app.panel = nil
        tapped = nil
        frame(bounds)
    }

    /// Frames the map on the item and opens its sheet (a route or a waypoint of the list, a result of the search).
    func reveal(_ ref: ItemRefKey) {
        guard let lib = snapshot(), let bounds = LibraryBounds.of(ref, in: lib) else { return }
        let dismissed = app.screen != nil || app.panel != nil || tapped != nil
        app.screen = nil
        app.panel = nil
        tapped = nil
        frame(bounds)
        let item = LibraryTapped(ref: ref)
        if dismissed {
            later { [weak self] in
                self?.tapped = item
            }
        } else {
            tapped = item
        }
    }

    private func frame(_ bounds: Bounds) {
        if bounds.maxX - bounds.minX < 1e-7, bounds.maxY - bounds.minY < 1e-7 {
            app.show(LonLat(lon: bounds.minX, lat: bounds.minY), zoom: max(app.zoom, LibraryController.pointZoom))
        } else {
            app.show(bounds)
        }
    }

    private func later(_ action: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + LibraryController.presentationDelay) {
            action()
        }
    }

    // MARK: - Messages

    /// A short message: at the bottom of the library page when one is open, of the map otherwise.
    func say(_ text: String, action: String? = nil, perform: (() -> Void)? = nil) {
        if pageOpen {
            screenMessage = LibraryMessage(text: text, action: action, perform: perform)
        } else {
            app.say(text, action: action, perform: perform)
        }
    }

    /// An error that needs reading: on the library page when one is open, as an alert on the map otherwise.
    private func report(_ text: String) {
        if pageOpen {
            screenMessage = LibraryMessage(text: text, action: nil, perform: nil)
        } else {
            app.notice = text
        }
    }

    private var pageOpen: Bool {
        guard let screen = app.screen else { return false }
        switch screen {
        case .library, .trackDetail:
            return true
        default:
            return false
        }
    }

    // MARK: - API for the other features

    /// A new track (drawing, measure, recorder) at the top of the library, in the colour of « Repères »; its id, nil
    /// when it could not be saved.
    @discardableResult
    func saveTrack(name: String?, segments: [[TrackPoint]]) -> Int64? {
        guard let repo = repository else { return nil }
        let style = PathStyle(colorArgb: app.settings.placemarks.defaultColor)
        return try? repo.createTrack(name, segments: segments, style: style)
    }

    /// A new route through `points` (itinerary, measure); its id, nil when it could not be saved.
    @discardableResult
    func saveRoute(name: String?, points: [Waypoint]) -> Int64? {
        guard let repo = repository else { return nil }
        return try? repo.createRoute(name, points)
    }

    /// A waypoint at `position` (menu of the centre point, long press, search). With `openDialog`, the dialog
    /// « Nouveau waypoint » opens on the map and nothing is saved yet (nil); otherwise the waypoint is recorded at
    /// once under `name` (« Waypoint N » when nil), with the symbol of the last waypoint and the altitude of the
    /// relief added when it comes; its id.
    @discardableResult
    func addWaypoint(at position: LonLat, name: String?, openDialog: Bool) -> Int64? {
        let given = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let chosen = given.isEmpty ? nextWaypointName() : given
        if openDialog {
            let draft = WaypointDraft(
                waypointId: nil,
                initial: nil,
                start: position,
                defaultName: chosen,
                system: app.settings.coordinates
            )
            presentDraft(draft)
            return nil
        }
        let waypoint = Waypoint(lat: position.lat, lon: position.lon, name: chosen, sym: lastSymbol())
        guard let repo = repository, let id = try? repo.createWaypoint(waypoint) else { return nil }
        LibraryRelief.lookup(position) { [weak self] ele in
            guard let ele else { return }
            DispatchQueue.main.async {
                self?.setElevation(id, ele)
            }
        }
        return id
    }

    /// The points of a track (its segments) or of a route (one line); empty for a waypoint or an unknown item.
    func paths(of ref: ItemRefKey) -> [[TrackPoint]] {
        guard let repo = repository else { return [] }
        switch ref.kind {
        case .track:
            return (try? repo.loadTrack(ref.id))?.segments ?? []
        case .route:
            guard let route = try? repo.loadRoute(ref.id) else { return [] }
            return [route.points.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }]
        case .waypoint:
            return []
        }
    }

    /// The tracks, routes and waypoints whose name holds every word of `query` (case and accents ignored).
    func searchItems(_ query: String) -> [LibrarySearchItem] {
        guard let lib = snapshot() else { return [] }
        let settings = app.settings
        var out: [LibrarySearchItem] = []
        for t in lib.tracks where t.pointCount > 0 && LibraryOrder.matches(t.name, query) {
            let detail = "Trace · " + LibraryText.distance(t.distanceMeters, settings)
            let middle = LonLat(lon: (t.minLon + t.maxLon) / 2, lat: (t.minLat + t.maxLat) / 2)
            let ref = ItemRefKey(kind: .track, id: t.id)
            out.append(LibrarySearchItem(ref: ref, name: t.name ?? LibraryText.unnamed, detail: detail, position: middle))
        }
        for r in lib.routes where r.pointCount > 0 && LibraryOrder.matches(r.name, query) {
            let detail = "Itinéraire · " + LibraryText.distance(r.distanceMeters, settings)
            let middle = LonLat(lon: (r.minLon + r.maxLon) / 2, lat: (r.minLat + r.maxLat) / 2)
            let ref = ItemRefKey(kind: .route, id: r.id)
            out.append(LibrarySearchItem(ref: ref, name: r.name ?? LibraryText.unnamed, detail: detail, position: middle))
        }
        for w in lib.waypoints where LibraryOrder.matches(w.name, query) {
            var detail = "Waypoint"
            if let ele = w.ele {
                detail += " · " + LibraryText.elevation(ele, settings)
            }
            let ref = ItemRefKey(kind: .waypoint, id: w.id)
            let at = LonLat(lon: w.lon, lat: w.lat)
            out.append(LibrarySearchItem(ref: ref, name: w.name ?? LibraryText.unnamed, detail: detail, position: at))
        }
        return out
    }

    /// The library as the search of the core sees it (`LocalSearch`): lines at the middle of their extent with it,
    /// waypoints at their place; the comment and the description as the note.
    func libraryItems() -> [LibraryItem] {
        guard let lib = snapshot() else { return [] }
        var out: [LibraryItem] = []
        for t in lib.tracks where t.pointCount > 0 {
            let bounds = Bounds(minX: t.minLon, minY: t.minLat, maxX: t.maxLon, maxY: t.maxLat)
            out.append(
                LibraryItem(
                    kind: .track,
                    id: t.id,
                    name: t.name,
                    note: LibraryController.note(t.cmt, t.desc),
                    lat: (t.minLat + t.maxLat) / 2,
                    lon: (t.minLon + t.maxLon) / 2,
                    bounds: bounds
                )
            )
        }
        for r in lib.routes where r.pointCount > 0 {
            let bounds = Bounds(minX: r.minLon, minY: r.minLat, maxX: r.maxLon, maxY: r.maxLat)
            out.append(
                LibraryItem(
                    kind: .route,
                    id: r.id,
                    name: r.name,
                    note: LibraryController.note(r.cmt, r.desc),
                    lat: (r.minLat + r.maxLat) / 2,
                    lon: (r.minLon + r.maxLon) / 2,
                    bounds: bounds
                )
            )
        }
        for w in lib.waypoints {
            out.append(
                LibraryItem(kind: .waypoint, id: w.id, name: w.name, note: LibraryController.note(w.cmt, w.desc), lat: w.lat, lon: w.lon)
            )
        }
        return out
    }

    private static func note(_ cmt: String?, _ desc: String?) -> String? {
        let parts = [cmt, desc].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }

    /// The visible tracks, routes and waypoints, as the print draws them; read off the main thread.
    func printItems() async -> PrintItems? {
        guard let pair = opened() else { return nil }
        let display = pair.display
        return await withCheckedContinuation { (continuation: CheckedContinuation<PrintItems?, Never>) in
            queue.async {
                continuation.resume(returning: try? display.printItems())
            }
        }
    }

    // MARK: - Reads off the main thread

    private func read<T: Sendable>(_ body: @escaping @Sendable (TrackRepository) -> T?) async -> T? {
        guard let repo = repository else { return nil }
        return await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
            queue.async {
                continuation.resume(returning: body(repo))
            }
        }
    }

    func trackDetail(_ id: Int64) async -> LibraryTrackDetail? {
        await read { (repo: TrackRepository) -> LibraryTrackDetail? in
            guard let track = try? repo.loadTrack(id) else { return nil }
            let path = TrackPath(track.segments)
            let missing = path.points.filter { $0.ele == nil }.count
            return LibraryTrackDetail(
                trackId: id,
                stats: TrackStatsCalculator.compute(track.segments),
                profile: ElevationProfile.build(path),
                path: path,
                missingElevation: missing
            )
        }
    }

    /// The elevation profile of a track, for the small one of its sheet; nil without enough altitudes.
    func profile(_ id: Int64) async -> ElevationProfile? {
        await read { (repo: TrackRepository) -> ElevationProfile? in
            guard let track = try? repo.loadTrack(id) else { return nil }
            return ElevationProfile.build(TrackPath(track.segments))
        }
    }

    func waypoint(_ id: Int64) async -> Waypoint? {
        await read { (repo: TrackRepository) -> Waypoint? in
            try? repo.loadWaypoint(id)
        }
    }

    /// The GPX of a selection (GPX 1.1, `GpxWriter`).
    func gpxData(_ selection: TrackSelection) async -> Data? {
        await read { (repo: TrackRepository) -> Data? in
            guard let document = try? repo.document(selection) else { return nil }
            return GpxWriter.toBytes(document)
        }
    }

    /// What `ShareLink` hands to another app: the GPX of the selection, written when the user picks where it goes.
    func share(_ selection: TrackSelection, name: String?) -> LibraryGpxShare? {
        guard let repo = repository else { return nil }
        return LibraryGpxShare(repository: repo, selection: selection, fileName: LibraryText.fileName(name))
    }

    // MARK: - Writes

    private func write(_ body: @escaping @Sendable (TrackRepository) throws -> Void) {
        guard let repo = repository else { return }
        queue.async {
            _ = try? body(repo)
        }
    }

    private func write<T: Sendable>(
        _ body: @escaping @Sendable (TrackRepository) throws -> T,
        then done: @escaping (T?) -> Void
    ) {
        guard let repo = repository else {
            done(nil)
            return
        }
        queue.async {
            let result = try? body(repo)
            DispatchQueue.main.async {
                done(result)
            }
        }
    }

    /// Shows or hides an item on the map.
    func setVisible(_ ref: ItemRefKey, _ visible: Bool) {
        let item = ref.libraryRef
        write { try $0.setVisible(item, visible) }
    }

    /// « Tout afficher sur la carte » (P14.10): every folder, track, route and waypoint at once.
    func setAllVisible(_ visible: Bool) {
        write { try $0.setAllVisible(visible) }
    }

    func setFolderVisible(_ id: Int64, _ visible: Bool) {
        write { try $0.setFolderVisible(id, visible) }
    }

    func rename(_ ref: ItemRefKey, _ name: String) {
        let item = ref.libraryRef
        write { try $0.rename(item, name) }
    }

    func renameFolder(_ id: Int64, _ name: String) {
        write { try $0.renameFolder(id, name) }
    }

    func setStyle(_ ref: ItemRefKey, _ style: PathStyle) {
        let item = ref.libraryRef
        write { try $0.setStyle(item, style) }
    }

    /// « Décrire »: the description shown in the detail and written to GPX.
    func describe(_ ref: ItemRefKey, _ text: String) {
        let item = ref.libraryRef
        write { try $0.describe(item, text) }
    }

    func delete(_ refs: [ItemRefKey]) {
        let items = refs.map { $0.libraryRef }
        if let current = tapped, refs.contains(current.ref) {
            tapped = nil
        }
        write { repo in
            for item in items {
                try repo.delete(item)
            }
        }
    }

    func deleteFolder(_ id: Int64) {
        write { try $0.deleteFolder(id) }
    }

    /// A new folder under `parent` (nil: the top of the library), opened in the list.
    func createFolder(_ parent: Int64?, _ name: String) {
        if let parent {
            expandedFolders.insert(parent)
        }
        write { repo in
            _ = try repo.createFolder(parent, name)
        }
    }

    /// « Déplacer vers… » of items: `folder` nil is the top of the library.
    func move(_ refs: [ItemRefKey], to folder: Int64?) {
        let items = refs.map { $0.libraryRef }
        if let folder {
            expandedFolders.insert(folder)
        }
        write({ (repo: TrackRepository) -> Bool in
            var all = true
            for item in items {
                let moved = try repo.moveItem(item, folder)
                if !moved { all = false }
            }
            return all
        }, then: { [weak self] moved in
            if moved != true {
                self?.say(LibraryText.moveFailed)
            }
        })
    }

    /// « Déplacer vers… » of a folder; it never goes into itself or one of its sub-folders.
    func moveFolder(_ id: Int64, to parent: Int64?) {
        if let parent {
            expandedFolders.insert(parent)
        }
        write({ (repo: TrackRepository) -> Bool in
            try repo.moveFolder(id, parent)
        }, then: { [weak self] moved in
            if moved != true {
                self?.say(LibraryText.moveFailed)
            }
        })
    }

    /// « Inverser le sens »: the times are dropped.
    func reverse(_ trackId: Int64) {
        write({ (repo: TrackRepository) -> Bool in
            try repo.reverseTrack(trackId)
            return true
        }, then: { [weak self] done in
            if done == true {
                self?.say("Sens inversé (les heures sont effacées).")
            }
        })
    }

    /// « Simplifier… » to `meters` (Douglas-Peucker).
    func simplify(_ trackId: Int64, _ meters: Double) {
        write({ (repo: TrackRepository) -> [Int] in
            guard let counts = try repo.simplifyTrack(trackId, meters) else { return [] }
            return [counts.before, counts.after]
        }, then: { [weak self] counts in
            guard let counts, counts.count == 2 else {
                self?.say(LibraryText.nothingToEdit)
                return
            }
            if counts[1] >= counts[0] {
                self?.say("Aucun point à supprimer avec cet écart.")
            } else {
                self?.say("Points avant : \(counts[0]) — après : \(counts[1])")
            }
        })
    }

    /// « Fusionner avec une autre trace… »: a new track, the sources stay.
    func merge(_ trackIds: [Int64], name: String) {
        write({ (repo: TrackRepository) -> Bool in
            try repo.mergeTracks(trackIds, name) != nil
        }, then: { [weak self] merged in
            self?.say(merged == true ? "Nouvelle trace créée ; les originales sont conservées." : "La fusion n'a pas pu se faire.")
        })
    }

    /// « Convertir en trace » of a route: its line copied into a new track; the route stays.
    func convertToTrack(_ routeId: Int64, name: String) {
        write({ (repo: TrackRepository) -> Bool in
            guard let route = try repo.loadRoute(routeId) else { return false }
            let points = route.points.map { TrackPoint(lat: $0.lat, lon: $0.lon, ele: $0.ele) }
            if points.count < 2 { return false }
            _ = try repo.createTrack(route.name, segments: [points], style: route.style ?? PathStyle())
            return true
        }, then: { [weak self] done in
            self?.say(done == true ? "Trace créée : \(name)" : LibraryText.nothingToEdit)
        })
    }

    /// « Enregistrer » of the waypoint dialog: a new waypoint (`id` nil) or the one edited.
    func saveWaypoint(_ id: Int64?, _ waypoint: Waypoint) {
        write({ (repo: TrackRepository) -> Bool in
            if let id {
                try repo.updateWaypoint(id, waypoint)
            } else {
                _ = try repo.createWaypoint(waypoint)
            }
            return true
        }, then: { [weak self] done in
            self?.say(done == true ? "Waypoint enregistré." : "L'enregistrement a échoué.")
        })
    }

    private func setElevation(_ id: Int64, _ ele: Double) {
        write { repo in
            guard let w = try repo.loadWaypoint(id), w.ele == nil else { return }
            try repo.updateWaypoint(id, w.libraryCopy(lat: w.lat, lon: w.lon, ele: ele, name: w.name, desc: w.desc, sym: w.sym))
        }
    }

    /// « Compléter les altitudes »: the points without one take the relief's.
    func completeElevation(_ trackId: Int64) {
        guard let repo = repository else { return }
        busy = true
        Task { [weak self] in
            let result = await LibraryElevation.complete(repo, trackId)
            await MainActor.run {
                self?.elevationCompleted(result)
            }
        }
    }

    private func elevationCompleted(_ result: LibraryElevation.Result?) {
        busy = false
        guard let result else {
            say("Cette trace est introuvable.")
            return
        }
        if result.filled == 0 {
            say("Aucune altitude n'a pu être ajoutée (hors ligne ou données indisponibles).")
        } else if result.missingTiles > 0 {
            say(
                "Altitudes ajoutées (points complétés : \(result.filled)), mais des données de terrain manquent (hors ligne ?) : réessayez en ligne."
            )
        } else {
            say("Altitudes ajoutées — points complétés : \(result.filled)")
        }
    }

    // MARK: - Waypoints

    /// « Waypoint N », N after the highest number in use (`WaypointNames`).
    func nextWaypointName() -> String {
        let names = snapshot()?.waypoints.map { $0.name } ?? []
        let number = WaypointNames.nextNumber(names, LibraryController.waypointPrefix)
        return "\(LibraryController.waypointPrefix) \(number)"
    }

    /// The symbol of the last waypoint created, the default flag otherwise.
    private func lastSymbol() -> String {
        let last = snapshot()?.waypoints.max { $0.createdAt < $1.createdAt }
        return WaypointSymbols.canonical(last?.sym)
    }

    /// « Waypoint ici » in one tap (P29.4, « Repère »): recorded at once at the centre of the map, with the altitude of
    /// the relief when it answers within 3 s, then « Modifier » in the message.
    func quickWaypoint() {
        let position = app.center
        let name = nextWaypointName()
        let symbol = lastSymbol()
        let once = LibraryOnce()
        let finish: @Sendable (Double?) -> Void = { [weak self] ele in
            DispatchQueue.main.async {
                guard once.claim() else { return }
                self?.recordQuick(position, name: name, symbol: symbol, ele: ele)
            }
        }
        LibraryRelief.lookup(position, completion: finish)
        DispatchQueue.main.asyncAfter(deadline: .now() + LibraryController.quickElevationSeconds) {
            finish(nil)
        }
    }

    private func recordQuick(_ position: LonLat, name: String, symbol: String, ele: Double?) {
        let waypoint = Waypoint(lat: position.lat, lon: position.lon, ele: ele, name: name, sym: symbol)
        guard let repo = repository, let id = try? repo.createWaypoint(waypoint) else {
            say("L'enregistrement a échoué.")
            return
        }
        var text = "« \(name) » enregistré"
        if let ele {
            text += " · " + LibraryText.elevation(ele, app.settings)
        }
        app.say(text, action: "Modifier") { [weak self] in
            self?.editWaypoint(id)
        }
    }

    /// « Modifier » of a waypoint on the map: its dialog.
    func editWaypoint(_ id: Int64) {
        guard let repo = repository, let found = try? repo.loadWaypoint(id) else { return }
        presentDraft(draft(id, found))
    }

    /// The dialog of waypoint `id` as it is now.
    func draft(_ id: Int64, _ waypoint: Waypoint) -> WaypointDraft {
        WaypointDraft(
            waypointId: id,
            initial: waypoint,
            start: LonLat(lon: waypoint.lon, lat: waypoint.lat),
            defaultName: waypoint.name ?? LibraryText.unnamed,
            system: app.settings.coordinates
        )
    }

    private func presentDraft(_ draft: WaypointDraft) {
        let presenting = app.panel != nil || tapped != nil || app.screen != nil
        app.panel = nil
        app.screen = nil
        tapped = nil
        if presenting {
            later { [weak self] in
                self?.waypointDraft = draft
            }
        } else {
            waypointDraft = draft
        }
    }

    // MARK: - Import

    /// Imports GPX, KML, KMZ or GeoJSON files chosen in Files or opened with ToolMap: each goes into a folder named
    /// after it; the message offers to show what came in.
    func importFiles(_ urls: [URL]) {
        guard !urls.isEmpty else { return }
        guard let repo = repository else {
            report("Import impossible : la bibliothèque ne peut pas être ouverte.")
            return
        }
        busy = true
        queue.async { [weak self] in
            let outcome = LibraryImport.run(urls, into: repo)
            DispatchQueue.main.async {
                self?.imported(outcome)
            }
        }
    }

    private func imported(_ outcome: LibraryImportOutcome) {
        busy = false
        if !outcome.failures.isEmpty {
            report("Import impossible : " + outcome.failures.joined(separator: "\n"))
        }
        let total = outcome.tracks + outcome.routes + outcome.waypoints
        if !outcome.failures.isEmpty, total == 0 { return }
        var text = LibraryText.imported(tracks: outcome.tracks, routes: outcome.routes, waypoints: outcome.waypoints)
        if outcome.issues > 0 {
            text += ". Anomalies corrigées ou ignorées dans le fichier : \(outcome.issues)."
        }
        if let bounds = outcome.bounds, total > 0 {
            say(text, action: "Afficher") { [weak self] in
                self?.showOnMap(bounds)
            }
        } else {
            say(text)
        }
    }

    // MARK: - Captures

    /// Debug only, with `-ToolMapDemo YES`: `-ToolMapLibrary detail` opens the detail of the first track, `sheet` the
    /// sheet of the first track, `waypoint` the sheet of the first waypoint, `new` the dialog « Nouveau waypoint ».
    private func applyLaunchArguments() {
        guard let what = UserDefaults.standard.string(forKey: "ToolMapLibrary"), let lib = snapshot() else { return }
        switch what {
        case "detail":
            if let first = lib.tracks.first {
                app.screen = .trackDetail(first.id)
            }
        case "sheet":
            if let first = lib.tracks.first {
                reveal(ItemRefKey(kind: .track, id: first.id))
            }
        case "waypoint":
            if let first = lib.waypoints.first {
                reveal(ItemRefKey(kind: .waypoint, id: first.id))
            }
        case "new":
            addWaypoint(at: app.center, name: nil, openDialog: true)
        default:
            break
        }
    }
}

// MARK: - Names used by the first version of `MapToolsBridge`

extension LibraryController {
    /// « Garder… ▸ Comme trace » of the measure: one segment (same as `saveTrack(name:segments:)`).
    @discardableResult
    func saveDrawnTrack(name: String, points: [TrackPoint]) -> Int64? {
        saveTrack(name: name, segments: [points])
    }

    /// « Garder… ▸ Comme itinéraire » of the measure (same as `saveRoute(name:points:)`).
    @discardableResult
    func saveDrawnRoute(name: String, points: [Waypoint]) -> Int64? {
        saveRoute(name: name, points: points)
    }

    /// A waypoint recorded at once with all its fields (« Enregistrer comme waypoint » of the search keeps the address
    /// as its description); the altitude of the relief is added when it comes. Its id, nil when it could not be saved.
    @discardableResult
    func createWaypoint(_ waypoint: Waypoint) -> Int64? {
        guard let repo = repository, let id = try? repo.createWaypoint(waypoint) else { return nil }
        if waypoint.ele == nil {
            LibraryRelief.lookup(LonLat(lon: waypoint.lon, lat: waypoint.lat)) { [weak self] ele in
                guard let ele else { return }
                DispatchQueue.main.async {
                    self?.setElevation(id, ele)
                }
            }
        }
        return id
    }

    /// The dialog « Nouveau waypoint » at `position` (same as `addWaypoint(at:name:openDialog:)` with the dialog).
    func newWaypoint(at position: LonLat) {
        addWaypoint(at: position, name: nil, openDialog: true)
    }

    /// The library as the search sees it (same as `libraryItems()`).
    func searchItems() -> [LibraryItem] {
        libraryItems()
    }
}

// MARK: - The band to download and the print along a track

extension LibraryController: ZonePathSource {
    /// Android `TracksViewModel.corridorPath`: each segment of a track simplified at 25 m (`PathSimplifier`), joined; a
    /// route's points; empty for a waypoint or an unknown item. Read off the main thread.
    func corridorPath(_ ref: ItemRefKey) async -> [LonLat] {
        let kind = ref.kind
        let id = ref.id
        let tolerance = LibraryController.corridorTolerance
        let found = await read { (repo: TrackRepository) -> [LonLat]? in
            switch kind {
            case .track:
                guard let track = try? repo.loadTrack(id) else { return nil }
                let points = track.segments.flatMap { PathSimplifier.simplify($0, tolerance) }
                return points.map { LonLat(lon: $0.lon, lat: $0.lat) }
            case .route:
                guard let route = try? repo.loadRoute(id) else { return nil }
                let points = route.points.map { TrackPoint(lat: $0.lat, lon: $0.lon) }
                return PathSimplifier.simplify(points, tolerance).map { LonLat(lon: $0.lon, lat: $0.lat) }
            case .waypoint:
                return nil
            }
        }
        return found ?? []
    }

    /// The name of a track or a route, for the job « Autour de « nom » ».
    func displayName(_ ref: ItemRefKey) -> String? {
        guard let lib = snapshot() else { return nil }
        switch ref.kind {
        case .track:
            guard let t = lib.tracks.first(where: { $0.id == ref.id }) else { return nil }
            return t.name ?? LibraryText.unnamed
        case .route:
            guard let r = lib.routes.first(where: { $0.id == ref.id }) else { return nil }
            return r.name ?? LibraryText.unnamed
        case .waypoint:
            return nil
        }
    }
}

/// Lets the first of two answers win (the relief or the 3 s wait of « Waypoint ici »).
final class LibraryOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
