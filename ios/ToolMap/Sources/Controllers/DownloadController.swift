import Foundation
import Observation
import ToolMapCore
import UIKit

// MARK: - What the controller needs from the other features

/// What « Télécharger autour » needs from the library (P30.2). `LibraryController` conforms in its own file (`extension
/// LibraryController: ZonePathSource {}`); until it does, a band falls back to the rectangle.
protocol ZonePathSource: AnyObject {
    /// Android `TracksViewModel.corridorPath`: the line of a track (each segment simplified at 25 m, joined) or of a
    /// route, read off the main thread; empty for a waypoint or an unknown item. The print waits for the same call.
    func corridorPath(_ ref: ItemRefKey) async -> [LonLat]
    /// The name of a track or a route, for the job « Autour de « nom » ».
    func displayName(_ ref: ItemRefKey) -> String?
}

/// What « Supprimer cette carte » needs from the catalogue: forget a local map (imported `.mbtiles`) now. `MapsController`
/// conforms in its own file; until it does, the map's `.aqx` copy is deleted and the map goes at the next launch.
protocol ZoneMapCatalogue: AnyObject {
    func removeLocalMap(_ id: String)
}

// MARK: - Zone being chosen (Android `SaveAreaUi`)

/// One map offered in « Enregistrer une zone » (Android `AreaRow`), saved at the zooms `minZoom`...`maxZoom` it serves.
/// The « Altitudes » row (P20) has `forMaps`: the maps drawn in an elevation band, whose masks need these elevation
/// tiles offline; it saves only the zooms the masks read.
struct ZoneAreaRow: Identifiable, Equatable {
    let source: TileSource
    var checked: Bool
    var forMaps: [String] = []
    let minZoom: Int
    let maxZoom: Int

    init(source: TileSource, checked: Bool, forMaps: [String] = [], minZoom: Int? = nil, maxZoom: Int? = nil) {
        self.source = source
        self.checked = checked
        self.forMaps = forMaps
        self.minZoom = minZoom ?? source.minZoom ?? 0
        self.maxZoom = maxZoom ?? source.maxZoom ?? 0
    }

    var id: String { source.id }
    var isElevation: Bool { !forMaps.isEmpty }

    /// The zooms of the row when they are not the map's own (the « Altitudes » row).
    var zoomLimit: ZoomLimit? {
        minZoom == (source.minZoom ?? 0) && maxZoom == (source.maxZoom ?? 0) ? nil : ZoomLimit(min: minZoom, max: maxZoom)
    }
}

/// Tiles one map adds to a zone (Android `SourceEstimate`): `stored` of them are already on the phone, `bytes` is the
/// guessed size of the others.
struct ZoneSourceEstimate: Equatable, Sendable {
    let tiles: Int
    let bytes: Int
    let stored: Int

    var missing: Int { max(tiles - stored, 0) }
}

/// What a zone would cost, map by map (Android `ZoneEstimate`).
enum ZoneEstimate: Equatable, Sendable {
    case ready([String: ZoneSourceEstimate])
    /// The chosen zooms hold no tile for the chosen maps.
    case empty
    /// Beyond the safety limit of the planner.
    case tooMany
    /// A community server that limits bulk downloads would be asked for too much.
    case restricted(name: String, tiles: Int, limit: Int)

    var perSource: [String: ZoneSourceEstimate]? {
        if case let .ready(values) = self { return values }
        return nil
    }

    var tiles: Int { (perSource ?? [:]).values.reduce(0) { $0 + $1.tiles } }
    var bytes: Int { (perSource ?? [:]).values.reduce(0) { $0 + $1.bytes } }
    var stored: Int { (perSource ?? [:]).values.reduce(0) { $0 + $1.stored } }
    var missing: Int { (perSource ?? [:]).values.reduce(0) { $0 + $1.missing } }
}

/// The zone being chosen on the map: a rectangle with four handles, the band around a track or a route (P30.2) or the
/// polygon of a surface measure, the maps offered, the maximal zoom and the estimate.
struct ZoneDraft {
    /// The rectangle of the zone; the box around the band or the polygon when there is one.
    var selection: AreaSelection
    var corridor: AreaCorridor?
    /// The line the band is drawn on: its path thinned to a fifth of the width (Android `SaveCorridorOverlay`).
    var corridorLine: [LonLat] = []
    var polygon: [LonLat]?
    var rows: [ZoneAreaRow]
    var maxZoom: Int
    var sliderMax: Int
    /// The last estimate; `estimating` while a newer one is on the way.
    var estimate: ZoneEstimate?
    var estimating = true
    /// The track or route of the band, as the tool was opened.
    let around: ItemRefKey?

    /// Only the rectangle has handles; a band or a polygon is fixed.
    var hasHandles: Bool { corridor == nil && polygon == nil }

    /// Something to fetch: a zone already stored in full has nothing left to save.
    var canSave: Bool {
        guard let estimate, estimate.perSource != nil else { return false }
        return estimate.missing > 0 && rows.contains { $0.checked }
    }

    /// Per-map zoom limits of the request (the « Altitudes » row keeps to the zooms the masks read).
    var zoomLimits: [String: ZoomLimit] {
        var out: [String: ZoomLimit] = [:]
        for row in rows where row.checked {
            if let limit = row.zoomLimit {
                out[row.id] = limit
            }
        }
        return out
    }

    var shape: ZoneShape {
        if let corridor { return .corridor(corridor.toShape()) }
        if let polygon {
            return .polygon(ZoneShape.Polygon(points: polygon.map { ZoneShape.Point(lon: $0.lon, lat: $0.lat) }))
        }
        return .rectangle(selection.toShape())
    }
}

// MARK: - Storage (Android `StorageUiState`, `SourceUsage`)

/// One MBTiles file of the phone: the tiles of one tile set, shared by every map that serves them (ADR-031).
struct StorageUsage: Identifiable, Equatable {
    /// The storage id: a built-in map's id, a local map's id or `t-<hash>`.
    let id: String
    /// The maps that show these tiles, or the id when no map of the catalogue does any more.
    let name: String
    let tiles: Int
    let diskBytes: Int64
    let minZoom: Int?
    let maxZoom: Int?
    /// Ids of the maps that show these tiles (several when they are shared).
    let usedBy: [String]
    /// A map made from an imported package: no server, its tiles cannot be downloaded again.
    let isLocalMap: Bool
}

/// The room of the phone (the gauge of « Stockage hors ligne », the free space of the zone panel); 0 while unknown.
struct StorageSpace: Equatable {
    var total: Int64 = 0
    var free: Int64 = 0

    static func read() -> StorageSpace {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let keys: Set<URLResourceKey> = [.volumeTotalCapacityKey, .volumeAvailableCapacityForImportantUsageKey]
        guard let values = try? url.resourceValues(forKeys: keys) else { return StorageSpace() }
        let total = Int64(values.volumeTotalCapacity ?? 0)
        let free = values.volumeAvailableCapacityForImportantUsage ?? 0
        return StorageSpace(total: total, free: free)
    }
}

/// What an `.mbtiles` file declares about itself (Android `PackageInfo`).
struct MbTilesPackageInfo: Equatable, Sendable {
    let name: String?
    let attribution: String?
    let format: String?
    let minZoom: Int
    let maxZoom: Int
    let tiles: Int
    /// The ToolMap storage it was exported from, when it was made by ToolMap.
    let sourceId: String?
}

/// A package copied next to the tiles and checked, waiting for the user to say where it goes.
struct PendingMbTiles: Identifiable {
    let id = UUID()
    let file: URL
    let info: MbTilesPackageInfo
    /// The map the package was exported from, when it still exists here.
    let knownSourceId: String?
}

/// Where an imported package goes (Android `ImportTarget`).
enum MbTilesTarget {
    /// Into the storage of a map that already exists.
    case existing(String)
    /// A new local map of this name (no server: it only shows the imported tiles).
    case newLocalMap(String)
}

// MARK: - Controller

/// Cartes hors ligne (Android T5, DOWNLOADS, STORAGE; P29.7 « Poids », ADR-080; P30.2): the zone being chosen on the
/// map, the queue of zone downloads and the MBTiles files of the phone.
///
/// The downloads run one after the other while the app is open (`ZoneEngine`, 2 to 4 tiles at a time under the rules
/// of the servers, `ZoneBulkPolicy`), from a JSON file per job in Application Support/downloads (the fields of Android's
/// `ZoneJob`). When the app goes to the background, iOS gives a few more minutes (`beginBackgroundTask`); after that the
/// job waits and goes on at the next opening. No background `URLSession`: a zone is thousands of small requests.
@Observable
final class DownloadController {
    @ObservationIgnored unowned let app: AppModel

    /// Newest first (Android `ZoneJobStore.jobs`).
    private(set) var jobs: [ZoneJob] = []
    /// « Enregistrer une zone » on the map, while the tool is open.
    private(set) var zone: ZoneDraft?
    /// The room of the phone, read when the zone or the storage opens.
    private(set) var space = StorageSpace()

    private(set) var usages: [StorageUsage] = []
    private(set) var storageBusy = false
    /// A package waiting for « Importer un paquet de tuiles ».
    private(set) var pendingImport: PendingMbTiles?
    /// The outcome of the last storage action, shown at the bottom of the storage page.
    var storageMessage: String?

    @ObservationIgnored private let demo: Bool
    @ObservationIgnored private var started = false
    @ObservationIgnored private var runner: Task<Void, Never>?
    @ObservationIgnored private var runningId: String?
    @ObservationIgnored private var runningToken = UUID()
    @ObservationIgnored private var attempts: [String: Int] = [:]
    @ObservationIgnored private var suspended = false
    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var estimateGeneration = 0
    @ObservationIgnored private var beginGeneration = 0
    /// The one map to check in the next zone (`askZone(only:)`; « Cartes disponibles » sets `MapsController.zoneMap`).
    @ObservationIgnored private var onlyMap: String?
    /// The shape the next zone takes instead of the rectangle: the polygon of a surface measure (« Télécharger cette
    /// zone… », `MapToolsBridge.downloadZone`).
    @ObservationIgnored var pendingZoneShape: ZoneShape?

    private static let estimateQueue = DispatchQueue(label: "fr.toolmap.zone-estimate", qos: .userInitiated)
    /// Interrupted runs before a job is marked failed (Android `ZoneDownloadWorker.MAX_ATTEMPTS`).
    private static let maxAttempts = 6
    /// Android `MapsViewModel`: the rectangle starts in the upper part of the map, the band at zoom 16 at most.
    private static let upperPart = 0.55
    private static let startFraction = 0.7
    private static let extraZooms = 3
    private static let corridorMaxZoom = 16
    /// The line of a track is thinned to this before planning (Android `CORRIDOR_TOLERANCE_METERS`).
    private static let corridorTolerance = 25.0
    private static let purgeAfterMillis: Int64 = 30 * 86_400_000

    init(app: AppModel) {
        self.app = app
        let demo = UserDefaults.standard.bool(forKey: "ToolMapDemo")
        self.demo = demo
        jobs = demo ? DownloadDemo.jobs() : ZoneJobFiles.readAll()
    }

    /// Starts the queue once (the map overlay calls it at launch): jobs left running by a closed app go on.
    func start() {
        guard !started else { return }
        started = true
        // The maps must be registered with the tile service before a job reads them.
        _ = app.maps
        if demo { return }
        for job in jobs where job.status == .running || job.status == .interrupted {
            put(job.copy(status: .queued))
        }
        observeLifecycle()
        pump()
    }

    // MARK: Zone on the map

    /// Android T5: the bottom bar is hidden while a zone is framed on the map (✕ leaves the mode).
    var hidesBottomBar: Bool {
        guard zone != nil, case .some(.zone) = app.tool else { return false }
        return true
    }

    /// Opens « Enregistrer une zone » from anywhere (Téléchargements « Nouvelle zone », empty storage, Cartes disponibles
    /// › « Télécharger une zone… » with `only` that map checked).
    func askZone(only sourceId: String?) {
        onlyMap = sourceId
        openZoneTool()
    }

    /// « Télécharger cette zone… » of a surface measure: the zone is the polygon, at the zooms of a band.
    func savePolygon(_ ring: [LonLat]) {
        guard ring.count >= 3 else { return }
        pendingZoneShape = .polygon(ZoneShape.Polygon(points: ring.map { ZoneShape.Point(lon: $0.lon, lat: $0.lat) }))
        openZoneTool()
    }

    private func openZoneTool() {
        // Back to the map without the page or the panel coming back over the zone.
        app.maps.closeScreenToMap()
        app.panel = nil
        if app.tool == .zone(around: nil) {
            beginZone(around: nil)
        } else {
            app.tool = .zone(around: nil)
        }
    }

    /// Prepares the zone when the tool opens (`ZonePanel`): the band around `around`, the polygon of a measure, or the
    /// rectangle in the upper part of the map. The maps offered are those on screen that have a server, all checked.
    func beginZone(around: ItemRefKey?) {
        start()
        beginGeneration += 1
        let generation = beginGeneration
        let offered = areaRows()
        if let around, let library = (app.library as AnyObject) as? ZonePathSource {
            // The line is read off the main thread; the panel says « Estimation en cours… » meanwhile.
            zone = nil
            Task { @MainActor in
                let path = await library.corridorPath(around)
                let name = library.displayName(around) ?? "Tracé"
                guard generation == self.beginGeneration else { return }
                let band = self.corridorDraft(
                    name: name,
                    path: path,
                    radius: nil,
                    ref: around,
                    rows: offered.rows,
                    sliderMax: offered.sliderMax
                )
                self.present(band ?? self.rectangleDraft(rows: offered.rows, sliderMax: offered.sliderMax))
            }
            return
        }
        var draft: ZoneDraft?
        if around == nil, let shape = pendingZoneShape {
            draft = shapeDraft(shape, rows: offered.rows, sliderMax: offered.sliderMax)
        }
        pendingZoneShape = nil
        present(draft ?? rectangleDraft(rows: offered.rows, sliderMax: offered.sliderMax))
    }

    private func present(_ draft: ZoneDraft) {
        zone = draft
        refreshSpace()
        estimateSoon()
    }

    /// ✕ of the panel: the tool closes and the zone is forgotten.
    func endZone() {
        zone = nil
        beginGeneration += 1
        estimateGeneration += 1
        if case .some(.zone) = app.tool {
            app.tool = nil
        }
    }

    /// The panel went because another tool took its place.
    func zoneClosed() {
        zone = nil
        beginGeneration += 1
        estimateGeneration += 1
    }

    func dragCorner(_ corner: AreaCorner, to position: LonLat) {
        guard var draft = zone, draft.hasHandles,
              let moved = try? draft.selection.drag(corner, lat: position.lat, lon: position.lon),
              moved != draft.selection
        else { return }
        draft.selection = moved
        zone = draft
        estimateSoon()
    }

    func setChecked(_ sourceId: String, _ checked: Bool) {
        guard var draft = zone, let index = draft.rows.firstIndex(where: { $0.id == sourceId }) else { return }
        guard draft.rows[index].checked != checked else { return }
        draft.rows[index].checked = checked
        zone = draft
        estimateSoon()
    }

    func setMaxZoom(_ zoom: Int) {
        guard var draft = zone else { return }
        let value = min(max(zoom, 0), draft.sliderMax)
        guard value != draft.maxZoom else { return }
        draft.maxZoom = value
        zone = draft
        estimateSoon()
    }

    /// « Largeur de chaque côté » from its slider or typed: the band and its weight follow, the width is kept for the
    /// next band (`maps.corridorRadius`).
    func setCorridorRadius(_ meters: Int) {
        guard var draft = zone, let band = draft.corridor else { return }
        let radius = CorridorWidth.clamp(meters)
        guard radius != band.radiusMeters,
              let wider = try? AreaCorridor(name: band.name, path: band.path, radiusMeters: radius),
              let box = try? wider.bounds()
        else { return }
        draft.corridor = wider
        draft.selection = box
        draft.corridorLine = Self.bandLine(wider)
        zone = draft
        app.settings.maps.corridorRadius = radius
        estimateSoon()
    }

    /// « Enregistrer la zone » / « Enregistrer la bande »: queues the download and goes back to the map.
    func saveZone() {
        guard let draft = zone else { return }
        let checked = draft.rows.filter(\.checked)
        guard !checked.isEmpty, let estimate = draft.estimate, estimate.perSource != nil else {
            app.say(ZoneText.noMaps)
            return
        }
        if app.offline {
            app.say(ZoneText.offlineWarning)
            return
        }
        let now = StoredTile.nowMillis()
        let name = draft.corridor.map { "Autour de « \($0.name) »" } ?? "Zone du \(NameDates.format(now))"
        guard let request = try? ZoneRequest(
            id: UUID().uuidString.lowercased(),
            name: name,
            sourceIds: checked.map(\.source.id),
            minZoom: 0,
            maxZoom: min(max(draft.maxZoom, 0), ZoneRequest.maxAllowedZoom),
            shape: draft.shape,
            createdAtMillis: now,
            zoomLimits: draft.zoomLimits
        ) else { return }
        enqueue(request)
        endZone()
        let tiles = ZoneText.tiles(estimate.missing)
        let bytes = ZoneText.bytes(Int64(estimate.bytes))
        let owner = app
        owner.say("Téléchargement lancé : \(tiles) (\(bytes)). Suivi dans Cartes › Téléchargements.", action: "Voir") {
            owner.screen = .downloads
        }
    }

    /// The maps offered and the end of the zoom slider (Android `MapsViewModel.areaRows`): those of the layer stack that
    /// have a server, top first, the visible ones checked; asked for one map, that map first and alone checked. The
    /// « Altitudes » row follows them when maps are drawn in an elevation band.
    private func areaRows() -> (rows: [ZoneAreaRow], sliderMax: Int) {
        let maps = app.maps
        var only: TileSource?
        if let id = onlyMap ?? maps.zoneMap, let source = maps.source(id), source.isSupported, !source.isLocalOnly {
            only = source
        }
        onlyMap = nil
        maps.zoneMap = nil
        let layers = maps.stack.layers
        var seen = Set<String>()
        var shown: [ZoneAreaRow] = []
        for layer in layers {
            guard !seen.contains(layer.sourceId), let source = maps.source(layer.sourceId),
                  source.isSupported, !source.isLocalOnly
            else { continue }
            seen.insert(layer.sourceId)
            shown.append(ZoneAreaRow(source: source, checked: layer.visible && only == nil))
        }
        var rows: [ZoneAreaRow] = []
        if let only {
            rows.append(ZoneAreaRow(source: only, checked: true))
        }
        for row in shown.reversed() where row.id != only?.id {
            rows.append(row)
        }
        let sliderMax = ZoneStrips.sliderMax(rows.map(\.maxZoom))
        if let elevation = elevationRow(layers, rows) {
            rows.append(elevation)
        }
        return (rows, sliderMax)
    }

    /// « Altitudes (pour …) »: offered when maps of `rows` are drawn in an elevation band, checked when one of them is,
    /// from their first zoom to the deepest the masks read (Android `elevationRow`).
    private func elevationRow(_ layers: [LayerConfig], _ rows: [ZoneAreaRow]) -> ZoneAreaRow? {
        guard let dem = app.maps.source(LayerElevation.demSourceId), dem.isSupported, !dem.isLocalOnly,
              !rows.contains(where: { $0.id == dem.id })
        else { return nil }
        let banded = Set(layers.filter(\.hasElevationRange).map(\.sourceId))
        let maps = rows.filter { banded.contains($0.id) }
        guard !maps.isEmpty else { return nil }
        let demMin = dem.minZoom ?? 0
        let deepest = max(min(DemWindow.maxDemZoom, dem.maxZoom ?? 0), demMin)
        let first = maps.map(\.minZoom).min() ?? demMin
        return ZoneAreaRow(
            source: dem,
            checked: maps.contains { $0.checked },
            forMaps: maps.map(\.source.name),
            minZoom: min(max(first, demMin), deepest),
            maxZoom: deepest
        )
    }

    private func rectangleDraft(rows: [ZoneAreaRow], sliderMax: Int) -> ZoneDraft {
        let visible = upperVisibleArea()
        let selection = (try? AreaSelection.centered(visible, fraction: Self.startFraction)) ?? Self.fallbackSelection(app.center)
        let tileZoom = Int((app.zoom + log2(512.0 / Double(MapStyle.declaredTileSize))).rounded())
        let maxZoom = ZoneStrips.defaultMax(currentTileZoom: tileZoom, extra: Self.extraZooms, sliderMax: sliderMax)
        return ZoneDraft(selection: selection, rows: rows, maxZoom: maxZoom, sliderMax: sliderMax, around: nil)
    }

    /// The band around `path` at the width last used (or `radius`), up to zoom 16; the map is fitted to it.
    private func corridorDraft(
        name: String,
        path: [LonLat],
        radius: Int?,
        ref: ItemRefKey?,
        rows: [ZoneAreaRow],
        sliderMax: Int
    ) -> ZoneDraft? {
        let points = path.map { TrackPoint(lat: $0.lat, lon: $0.lon) }
        let thinned = PathSimplifier.simplify(points, Self.corridorTolerance)
        guard thinned.count >= 2 else { return nil }
        let width = CorridorWidth.clamp(radius ?? app.settings.maps.corridorRadius)
        let line = thinned.map { ZoneShape.Point(lon: $0.lon, lat: $0.lat) }
        guard let band = try? AreaCorridor(name: name, path: line, radiusMeters: width),
              let box = try? band.bounds()
        else { return nil }
        var draft = ZoneDraft(
            selection: box,
            rows: rows,
            maxZoom: min(Self.corridorMaxZoom, sliderMax),
            sliderMax: sliderMax,
            around: ref
        )
        draft.corridor = band
        draft.corridorLine = Self.bandLine(band)
        fit(box)
        return draft
    }

    /// A zone handed by another feature (`pendingZoneShape`).
    private func shapeDraft(_ shape: ZoneShape, rows: [ZoneAreaRow], sliderMax: Int) -> ZoneDraft? {
        switch shape {
        case let .polygon(polygon):
            return polygonDraft(polygon.points.map { LonLat(lon: $0.lon, lat: $0.lat) }, rows: rows, sliderMax: sliderMax)
        case let .corridor(band):
            let path = band.points.map { LonLat(lon: $0.lon, lat: $0.lat) }
            let radius = Int(band.radiusMeters.rounded())
            return corridorDraft(name: "Tracé", path: path, radius: radius, ref: nil, rows: rows, sliderMax: sliderMax)
        case let .rectangle(box):
            guard let selection = try? AreaSelection(west: box.west, south: box.south, east: box.east, north: box.north)
            else { return nil }
            fit(selection)
            let maxZoom = min(Self.corridorMaxZoom, sliderMax)
            return ZoneDraft(selection: selection, rows: rows, maxZoom: maxZoom, sliderMax: sliderMax, around: nil)
        }
    }

    private func polygonDraft(_ ring: [LonLat], rows: [ZoneAreaRow], sliderMax: Int) -> ZoneDraft? {
        let lons = ring.map(\.lon)
        let lats = ring.map(\.lat)
        guard let west = lons.min(), let east = lons.max(), let south = lats.min(), let north = lats.max(),
              let box = try? AreaSelection(west: west, south: south, east: east, north: north)
        else { return nil }
        var draft = ZoneDraft(
            selection: box,
            rows: rows,
            maxZoom: min(Self.corridorMaxZoom, sliderMax),
            sliderMax: sliderMax,
            around: nil
        )
        draft.polygon = ring
        fit(box)
        return draft
    }

    /// The map shows the whole band or polygon in its upper part, above the panel.
    private func fit(_ box: AreaSelection) {
        let height = box.north - box.south
        let south = max(box.south - height * 1.1, -AreaSelection.maxLatitude)
        app.show(Bounds(minX: box.west, minY: south, maxX: box.east, maxY: box.north), padding: 40)
    }

    /// The upper part of the map (Android `UPPER_PART`), where the rectangle starts.
    private func upperVisibleArea() -> ZoneShape.Rectangle {
        let proxy = app.mapProxy
        let size = proxy.size
        if size.width > 1, size.height > 1 {
            let bottom = size.height * Self.upperPart
            let corners = [
                CGPoint(x: 0, y: 0), CGPoint(x: size.width, y: 0),
                CGPoint(x: 0, y: bottom), CGPoint(x: size.width, y: bottom),
            ]
            let positions = corners.compactMap { proxy.position($0) }
            let lons = positions.map(\.lon)
            let lats = positions.map(\.lat)
            if positions.count == 4, let west = lons.min(), let east = lons.max(), let south = lats.min(),
               let north = lats.max(), west < east, south < north
            {
                return ZoneShape.Rectangle(west: west, south: south, east: east, north: north)
            }
        }
        // Before the map is laid out: the screen of a common iPhone around the centre.
        let center = app.center
        let degreesPerPoint = 360.0 / (512.0 * pow(2.0, app.zoom))
        let latitudePerPoint = degreesPerPoint * cos(center.lat * Double.pi / 180)
        let north = min(center.lat + 422 * latitudePerPoint, AreaSelection.maxLatitude)
        let south = max(north - 464 * latitudePerPoint, -AreaSelection.maxLatitude)
        let west = max(center.lon - 195 * degreesPerPoint, -180)
        let east = min(center.lon + 195 * degreesPerPoint, 180)
        return ZoneShape.Rectangle(west: west, south: south, east: east, north: north)
    }

    private static func fallbackSelection(_ center: LonLat) -> AreaSelection {
        let lat = min(max(center.lat, -80), 80)
        let lon = min(max(center.lon, -179), 179)
        if let box = try? AreaSelection(west: lon - 0.01, south: lat - 0.01, east: lon + 0.01, north: lat + 0.01) {
            return box
        }
        // swiftlint:disable:next force_try
        return try! AreaSelection(west: 0, south: 0, east: 0.01, north: 0.01)
    }

    /// The line the band is drawn on: thinned to a fifth of its width, at least 25 m (Android `BAND_THINNING`).
    private static func bandLine(_ band: AreaCorridor) -> [LonLat] {
        let points = band.path.map { TrackPoint(lat: $0.lat, lon: $0.lon) }
        let tolerance = max(Double(band.radiusMeters) / 5, 25)
        return PathSimplifier.simplify(points, tolerance).map { LonLat(lon: $0.lon, lat: $0.lat) }
    }

    /// Recomputes the estimate 250 ms after the last change (Android `ESTIMATE_DEBOUNCE_MS`), off the main thread.
    private func estimateSoon() {
        guard var draft = zone else { return }
        estimateGeneration += 1
        let generation = estimateGeneration
        let checked = draft.rows.filter(\.checked).map(\.source.id)
        let candidate = try? ZoneRequest(
            id: "estimate",
            name: "estimate",
            sourceIds: checked,
            minZoom: 0,
            maxZoom: min(max(draft.maxZoom, 0), ZoneRequest.maxAllowedZoom),
            shape: draft.shape,
            zoomLimits: draft.zoomLimits
        )
        guard let request = candidate else {
            draft.estimate = .empty
            draft.estimating = false
            zone = draft
            return
        }
        draft.estimating = true
        zone = draft
        let scaling = app.settings.maps.scaling
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            guard generation == self.estimateGeneration else { return }
            DownloadController.estimateQueue.async {
                let result = ZoneEngine.estimate(request, scaling: scaling)
                DispatchQueue.main.async {
                    guard generation == self.estimateGeneration, var current = self.zone else { return }
                    current.estimate = result
                    current.estimating = false
                    self.zone = current
                }
            }
        }
    }

    // MARK: Jobs

    func findJob(_ id: String) -> ZoneJob? {
        jobs.first { $0.request.id == id }
    }

    /// Queues a zone; it starts when the jobs before it are done.
    func enqueue(_ request: ZoneRequest) {
        put(ZoneJob(request: request, status: .queued, updatedAtMillis: StoredTile.nowMillis()))
        pump()
    }

    /// Stops the job; what was downloaded is kept and « Reprendre » goes on from there.
    func pause(_ id: String) {
        guard let job = findJob(id) else { return }
        if job.status == .queued || job.status == .running || job.status == .interrupted {
            put(job.copy(status: .paused))
        }
        if runningId == id {
            runner?.cancel()
        }
    }

    func resume(_ id: String) {
        guard let job = findJob(id) else { return }
        attempts[id] = 0
        put(job.copy(status: .queued).withMessage(nil))
        pump()
    }

    /// Forgets the job; the tiles already stored stay on the phone. Returns it for « Annuler ».
    @discardableResult
    func remove(_ id: String) -> ZoneJob? {
        let removed = findJob(id)
        if runningId == id {
            runner?.cancel()
        }
        jobs.removeAll { $0.request.id == id }
        if !demo {
            ZoneJobFiles.delete(id)
        }
        return removed
    }

    /// « Annuler » after « Retirer »: the job comes back; one that was going waits for its turn again.
    func restore(_ job: ZoneJob) {
        let active = job.status == .queued || job.status == .running || job.status == .interrupted
        put(active ? job.copy(status: .queued) : job)
        pump()
    }

    private func put(_ job: ZoneJob) {
        jobs.removeAll { $0.request.id == job.request.id }
        jobs.append(job)
        jobs.sort { $0.request.createdAtMillis > $1.request.createdAtMillis }
        if !demo {
            ZoneJobFiles.save(job)
        }
    }

    /// Starts the oldest waiting job when none runs.
    private func pump() {
        guard started, !demo, runner == nil, !suspended else {
            endBackgroundTimeIfIdle()
            return
        }
        let waiting = jobs.filter { $0.status == .queued }
        guard let next = waiting.min(by: { $0.request.createdAtMillis < $1.request.createdAtMillis }) else {
            endBackgroundTimeIfIdle()
            return
        }
        run(next)
    }

    private func run(_ job: ZoneJob) {
        let id = job.request.id
        let token = UUID()
        runningId = id
        runningToken = token
        put(job.copy(status: .running).withMessage(nil))
        let request = job.request
        let scaling = app.settings.maps.scaling
        runner = Task.detached(priority: .utility) {
            let result = await ZoneEngine.run(request, scaling: scaling) { progress in
                DispatchQueue.main.async {
                    self.progressed(token, id, progress)
                }
            }
            DispatchQueue.main.async {
                self.finished(token, id, result)
            }
        }
    }

    private func progressed(_ token: UUID, _ id: String, _ progress: ZoneProgress) {
        guard token == runningToken, let job = findJob(id), job.status == .running else { return }
        put(job.copy(progress: progress))
    }

    private func finished(_ token: UUID, _ id: String, _ result: ZoneRunResult) {
        guard token == runningToken else { return }
        runner = nil
        runningId = nil
        if let job = findJob(id) {
            switch result {
            case let .completed(progress):
                attempts[id] = nil
                put(job.copy(status: .done, progress: progress).withMessage(nil))
            case let .interrupted(progress, reason):
                let tries = (attempts[id] ?? 0) + 1
                attempts[id] = tries
                let giveUp = tries >= Self.maxAttempts
                put(job.copy(status: giveUp ? .failed : .interrupted, progress: progress).withMessage(reason))
                if !giveUp {
                    retryLater(id, attempt: tries)
                }
            case let .refused(reason):
                put(job.copy(status: .failed).withMessage(reason.text))
            case let .cancelled(progress):
                // Paused, removed or out of background time: the status is already the one wanted.
                let status: ZoneStatus = job.status == .running ? .paused : job.status
                put(job.copy(status: status, progress: progress))
            }
        }
        pump()
    }

    /// A job interrupted by the network or the server waits, then goes on (Android: WorkManager's back-off).
    private func retryLater(_ id: String, attempt: Int) {
        let delay = min(30.0 * pow(2.0, Double(attempt - 1)), 600)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            guard let job = self.findJob(id), job.status == .interrupted else { return }
            self.put(job.copy(status: .queued))
            self.pump()
        }
    }

    // MARK: Background time

    /// Called on the main thread (`start` comes from the views).
    private func observeLifecycle() {
        MainActor.assumeIsolated {
            let center = NotificationCenter.default
            let background = center.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { _ in
                self.didEnterBackground()
            }
            let foreground = center.addObserver(
                forName: UIApplication.willEnterForegroundNotification,
                object: nil,
                queue: .main
            ) { _ in
                self.willEnterForeground()
            }
            self.observers = [background, foreground]
        }
    }

    /// A download going on asks iOS for a few more minutes (no background `URLSession`: too many small requests).
    private func didEnterBackground() {
        guard runner != nil else { return }
        MainActor.assumeIsolated {
            guard self.backgroundTask == .invalid else { return }
            self.backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Téléchargement de zone") {
                self.backgroundTimeExpired()
            }
        }
    }

    /// iOS takes the time back: the job waits for the next opening, what was downloaded is kept.
    private func backgroundTimeExpired() {
        suspended = true
        if let id = runningId, let job = findJob(id), job.status == .running {
            put(job.copy(status: .queued))
        }
        runner?.cancel()
        endBackgroundTime()
    }

    private func willEnterForeground() {
        suspended = false
        endBackgroundTime()
        pump()
    }

    private func endBackgroundTimeIfIdle() {
        if runner == nil {
            endBackgroundTime()
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        MainActor.assumeIsolated {
            UIApplication.shared.endBackgroundTask(self.backgroundTask)
            self.backgroundTask = .invalid
        }
    }

    // MARK: Storage

    /// Reads the files of the tiles and the room of the phone, off the main thread.
    func refreshStorage() {
        let sources = app.maps.sources
        storageBusy = true
        DispatchQueue.global(qos: .userInitiated).async {
            let usages = StorageScan.usages(sources: sources)
            let space = StorageSpace.read()
            DispatchQueue.main.async {
                self.usages = usages
                self.space = space
                self.storageBusy = false
            }
        }
    }

    func refreshSpace() {
        DispatchQueue.global(qos: .utility).async {
            let space = StorageSpace.read()
            DispatchQueue.main.async {
                self.space = space
            }
        }
    }

    /// « Effacer les tuiles de plus de 30 j » (no confirmation, as on Android).
    func purgeOld(_ storageId: String) {
        let cutoff = StoredTile.nowMillis() - Self.purgeAfterMillis
        storageWork {
            let file = try TileService.shared.file(storageId)
            let removed = try file.deleteOlderThan(cutoff)
            MbTilesPackage.compact(file)
            return "Tuiles effacées : \(removed)"
        }
    }

    /// « Tout effacer », after its confirmation.
    func purgeAll(_ storageId: String) {
        storageWork {
            let file = try TileService.shared.file(storageId)
            try file.clear()
            MbTilesPackage.compact(file)
            return "Tuiles effacées"
        }
    }

    /// « Supprimer cette carte » of a local map, after its confirmation: the map and its tiles.
    func deleteLocalMap(_ storageId: String) {
        if let catalogue = (app.maps as AnyObject) as? ZoneMapCatalogue {
            catalogue.removeLocalMap(storageId)
        } else {
            MbTilesPackage.forgetSourceFile(storageId)
        }
        storageWork {
            let file = try TileService.shared.file(storageId)
            try file.clear()
            MbTilesPackage.compact(file)
            return "Carte supprimée"
        }
    }

    /// Runs `work` off the main thread with the busy bar, then shows its message and reads the storage again.
    private func storageWork(_ work: @escaping () throws -> String) {
        storageBusy = true
        DispatchQueue.global(qos: .userInitiated).async {
            let text: String
            do {
                text = try work()
            } catch {
                text = "Opération impossible : \(error.localizedDescription)"
            }
            DispatchQueue.main.async {
                self.storageMessage = text
                self.refreshStorage()
            }
        }
    }

    // MARK: Import of an `.mbtiles` package

    /// A `.mbtiles` package opened with ToolMap (« Ouvrir avec ») or chosen in « Cartes disponibles » (which opens the
    /// storage page itself): the storage page asks where it goes.
    func importMbTiles(_ url: URL) {
        stageMbTiles(url)
        guard app.screen != .storage, app.screen != .availableMaps else { return }
        if app.panel != nil {
            // Two sheets cannot be on screen together: the panel goes first.
            app.panel = nil
            let owner = app
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                owner.screen = .storage
            }
        } else {
            app.screen = .storage
        }
    }

    /// Copies the chosen file next to the tiles and checks it; « Importer un paquet de tuiles » then asks where it goes.
    func stageMbTiles(_ url: URL) {
        storageBusy = true
        let scoped = url.startAccessingSecurityScopedResource()
        let directory = TileService.shared.directory
        DispatchQueue.global(qos: .userInitiated).async {
            let result = MbTilesPackage.stage(url, into: directory)
            if scoped {
                url.stopAccessingSecurityScopedResource()
            }
            // A file opened with ToolMap was copied by iOS into the Inbox: it is no longer needed.
            if url.path.contains("/Inbox/") {
                try? FileManager.default.removeItem(at: url)
            }
            DispatchQueue.main.async {
                self.storageBusy = false
                switch result {
                case let .success(staged):
                    self.discardPending()
                    var known: String?
                    if let id = staged.info.sourceId, TileService.shared.source(id) != nil {
                        known = id
                    }
                    self.pendingImport = PendingMbTiles(file: staged.file, info: staged.info, knownSourceId: known)
                case let .failure(error):
                    self.storageMessage = error.text
                }
            }
        }
    }

    /// « Annuler » of the import, or the sheet swiped away.
    func cancelImport() {
        discardPending()
    }

    private func discardPending() {
        if let pending = pendingImport {
            MbTilesPackage.discard(pending.file)
        }
        pendingImport = nil
    }

    /// « Importer »: the tiles go into the chosen map, or into a new local map of that name.
    func commitImport(_ target: MbTilesTarget) {
        guard let pending = pendingImport else { return }
        pendingImport = nil
        storageBusy = true
        switch target {
        case let .existing(id):
            guard let source = TileService.shared.source(id) else {
                MbTilesPackage.discard(pending.file)
                importDone(.failure(MbTilesPackageError(.unreadable, id)), name: id)
                return
            }
            DispatchQueue.global(qos: .userInitiated).async {
                let result = MbTilesPackage.merge(pending.file, into: source)
                DispatchQueue.main.async {
                    self.importDone(result, name: source.name)
                }
            }
        case let .newLocalMap(name):
            let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
            let id = freeLocalId(title)
            DispatchQueue.global(qos: .userInitiated).async {
                let result = MbTilesPackage.place(pending.file, asLocalMap: id)
                DispatchQueue.main.async {
                    if case .success = result {
                        self.registerLocalMap(id: id, name: title, info: pending.info)
                    }
                    self.importDone(result, name: title)
                }
            }
        }
    }

    private func importDone(_ result: Result<Int, MbTilesPackageError>, name: String) {
        switch result {
        case .success:
            storageMessage = "Tuiles importées dans « \(name) »"
        case let .failure(error):
            storageMessage = error.text
        }
        refreshStorage()
    }

    /// A free id for a new local map, from its name (Android `StorageManager.freeId`).
    private func freeLocalId(_ name: String) -> String {
        let base = MbTilesPackage.slug(name)
        let taken = Set(app.maps.sources.map(\.id))
        let directory = TileService.shared.directory
        var candidate = base
        var n = 2
        while taken.contains(candidate)
            || FileManager.default.fileExists(atPath: directory.appendingPathComponent("\(candidate).mbtiles").path)
        {
            candidate = "\(base)-\(n)"
            n += 1
        }
        return candidate
    }

    /// The new local map enters the catalogue as an `.aqx` with a level that has no server (Android
    /// `createLocalSource`), through the import of the maps.
    private func registerLocalMap(id: String, name: String, info: MbTilesPackageInfo) {
        guard let zooms = try? ZoomSet.range(min(info.minZoom, info.maxZoom), max(info.minZoom, info.maxZoom)) else { return }
        let level = LevelDef(zooms: zooms, servers: [LevelDef.localOnlyServer])
        let source = TileSource(id: id, name: name, copyright: info.attribution, levels: [level])
        let url = AppFiles.temporary("\(id).aqx")
        do {
            try AqxWriter.write([source]).write(to: url, atomically: true, encoding: .utf8)
        } catch {
            return
        }
        _ = app.maps.importMaps(from: url)
    }
}

// MARK: - Jobs on disk (Android `ZoneJobStore`)

extension ZoneJob {
    /// The job with some fields changed, the message kept; `updatedAtMillis` becomes now.
    func copy(status: ZoneStatus? = nil, progress: ZoneProgress? = nil) -> ZoneJob {
        ZoneJob(
            request: request,
            status: status ?? self.status,
            progress: progress ?? self.progress,
            message: message,
            updatedAtMillis: StoredTile.nowMillis()
        )
    }

    func withMessage(_ text: String?) -> ZoneJob {
        ZoneJob(request: request, status: status, progress: progress, message: text, updatedAtMillis: updatedAtMillis)
    }
}

/// One JSON file per job in Application Support/downloads, with the fields of Android (`ZoneJobCodec`).
enum ZoneJobFiles {
    static let directory: URL = AppFiles.folder("downloads")

    static func readAll() -> [ZoneJob] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        var jobs: [ZoneJob] = []
        for name in names where name.hasSuffix(".json") {
            let url = directory.appendingPathComponent(name)
            guard let text = try? String(contentsOf: url, encoding: .utf8), let job = ZoneJobCodec.decodeOrNull(text) else {
                continue
            }
            jobs.append(job)
        }
        return jobs.sorted { $0.request.createdAtMillis > $1.request.createdAtMillis }
    }

    static func save(_ job: ZoneJob) {
        guard let text = try? ZoneJobCodec.encode(job) else { return }
        try? text.write(to: url(job.request.id), atomically: true, encoding: .utf8)
    }

    static func delete(_ id: String) {
        try? FileManager.default.removeItem(at: url(id))
    }

    /// Job ids are made by the app, but never trusted as file names.
    private static func url(_ id: String) -> URL {
        let safe = id.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return directory.appendingPathComponent("\(safe).json")
    }
}

// MARK: - Download engine (Android `ZoneDownloader`, `BulkPolicy`, `TileBatch`)

/// How hard a zone download may push a tile server (Android `BulkLimits`).
struct ZoneBulkLimits: Sendable {
    /// Most tiles one job may fetch from the server, nil for no limit.
    let maxTiles: Int?
    let maxThreads: Int
    /// Pause after each network download, per worker.
    let delayMillis: Int
}

/// Community tile servers forbid or discourage bulk downloads (OpenStreetMap and OpenTopoMap usage policies,
/// docs/specs/performance.md §4): their zones are capped at 1 500 tiles to download, 2 workers and 300 ms of pause.
/// Matched on the server host, so an imported copy of the same service is covered too.
enum ZoneBulkPolicy {
    static let restrictedMaxTiles = 1_500
    private static let restrictedHosts = ["openstreetmap.org", "openstreetmap.fr", "opentopomap.org"]

    static func limits(for source: TileSource) -> ZoneBulkLimits {
        var hosts: [String] = []
        for level in source.levels {
            for template in level.servers {
                if let host = host(of: template) {
                    hosts.append(host)
                }
            }
        }
        let restricted = hosts.contains { host in
            restrictedHosts.contains { host == $0 || host.hasSuffix("." + $0) }
        }
        if restricted {
            return ZoneBulkLimits(maxTiles: restrictedMaxTiles, maxThreads: 2, delayMillis: 300)
        }
        // Other servers: the map's own `<max-threads>` decides.
        return ZoneBulkLimits(maxTiles: nil, maxThreads: AqxParser.maxThreads, delayMillis: 0)
    }

    /// The host of a URL template (it holds `{$x}`-style placeholders: only the host part matters).
    private static func host(of template: String) -> String? {
        var rest = Substring(template)
        if let range = template.range(of: "://") {
            rest = template[range.upperBound...]
        }
        let authority = rest.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        let host = authority.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first ?? ""
        return host.isEmpty ? nil : host.lowercased()
    }
}

/// How many tiles a zone needs, map by map (Android `ZonePlan`).
struct ZonePlan {
    /// Requested map → displayed zoom → real tiles to fetch for it; a tile needed by several maps or zooms is counted
    /// once, for the first one.
    var perSource: [String: [Int: Int]] = [:]
    /// Map whose server serves the tiles → tile zooms, in the order met.
    var fetches: [String: Set<Int>] = [:]
    var fetchOrder: [String] = []
    /// Real tiles per map whose server serves them (for the limits of `ZoneBulkPolicy`).
    var perMember: [String: Int] = [:]
    /// Requested map → tiles already stored (filled for estimates).
    var stored: [String: Int] = [:]
    var storedPerMember: [String: Int] = [:]
    /// Requested map → the storages its tiles go to (their average tile size gives the weight).
    var storages: [String: Set<String>] = [:]

    var total: Int {
        perSource.values.reduce(0) { $0 + $1.values.reduce(0, +) }
    }

    mutating func addFetch(_ memberId: String, _ zoom: Int, _ tiles: Int) {
        if fetches[memberId] == nil {
            fetchOrder.append(memberId)
        }
        fetches[memberId, default: []].insert(zoom)
        perMember[memberId, default: 0] += tiles
    }
}

/// Why a job could not start (Android `RefusalReason`), with the message shown under it.
enum ZoneRefusal: Sendable {
    case offline, unknownSource, tooManyTiles, restricted, nothing

    var text: String {
        switch self {
        case .offline: "Le mode hors ligne est actif : repassez en ligne pour télécharger."
        case .unknownSource: "Une carte de la liste n'existe plus."
        case .tooManyTiles: "Zone trop grande."
        case .restricted: "Ce serveur communautaire limite les téléchargements : choisissez une zone plus petite."
        case .nothing: "Aucune tuile dans cette zone avec ces zooms."
        }
    }
}

/// How a run of a job ended (Android `ZoneResult`).
enum ZoneRunResult: Sendable {
    case completed(ZoneProgress)
    /// Too many tiles in a row failed (network down, server refusing): tried again later.
    case interrupted(ZoneProgress, String)
    case refused(ZoneRefusal)
    /// Paused, removed or out of background time.
    case cancelled(ZoneProgress)
}

/// Plans, estimates and downloads a zone with the tile service of the app (Android `ZoneDownloader`, `ZoneEstimator`).
/// A displayed zoom is expanded into the real tiles the map shows there (ADR-031): the tiles of the maps a multi-zoom
/// map is made of, or the neighbouring zoom used where the map has none. Tiles already stored are skipped, so a resume
/// is a plain new run.
enum ZoneEngine {
    /// At most this many tiles at a time for one map, whatever its `<max-threads>` (2 for community servers).
    static let maxWorkers = 4
    private static let failureStreakLimit = 25
    private static let retryDelaysMillis: [UInt64] = [1_000, 3_000]
    /// Its own connections, so that a download does not hold up the tiles of the map on screen.
    private static let session = TileService.makeSession()

    // MARK: Plan

    static func plan(_ request: ZoneRequest, scaling: ScalingPolicy, withStored: Bool) throws -> ZonePlan {
        let service = TileService.shared
        let resolver = TileResolver { service.source($0) }
        let area = request.shape.toArea()
        var plan = ZonePlan()
        var counted = Set<String>()
        var counts: [Int: Int] = [:]
        var total = 0
        for id in request.sourceIds {
            guard let source = service.source(id) else { continue }
            let limit = request.zoomLimits[id]
            let zooms = Array(request.minZoom ... request.maxZoom).filter { limit?.contains($0) ?? true }
            if zooms.isEmpty { continue }
            let needs = zooms.map { z in (z, resolver.tileZooms(source, z, scaling)) }
            var perZoom: [Int: Int] = [:]
            for z in zooms {
                perZoom[z] = 0
            }
            // Tiles of the displayed zoom itself first, so that a zoom drawn from its neighbours shows only what it adds.
            for direct in [true, false] {
                for (z, members) in needs {
                    for memberId in members.keys.sorted() {
                        guard let member = service.source(memberId) else { continue }
                        let tileZooms = (members[memberId] ?? []).sorted()
                        for tileZoom in tileZooms where (tileZoom == z) == direct {
                            guard let level = member.levelFor(tileZoom), !level.isLocalOnly else { continue }
                            let leaf = TileRecipe.Leaf(source: member, level: level, tile: TileId(tileZoom, 0, 0))
                            guard let storage = service.storageId(leaf) else { continue }
                            guard counted.insert("\(storage)#\(tileZoom)").inserted else { continue }
                            let n = try count(area, tileZoom, &counts)
                            total += n
                            if total > TilePlanner.maxTiles {
                                throw TooManyTilesError(limit: TilePlanner.maxTiles)
                            }
                            perZoom[z, default: 0] += n
                            plan.addFetch(member.id, tileZoom, n)
                            plan.storages[id, default: []].insert(storage)
                            if withStored {
                                let already = storedIn(storage, tileZoom, area)
                                plan.stored[id, default: 0] += already
                                plan.storedPerMember[member.id, default: 0] += already
                            }
                        }
                    }
                }
            }
            plan.perSource[id] = perZoom
        }
        return plan
    }

    private static func count(_ area: DownloadArea, _ zoom: Int, _ cache: inout [Int: Int]) throws -> Int {
        if let known = cache[zoom] { return known }
        let n = try TilePlanner.count(area, [zoom])[zoom] ?? 0
        cache[zoom] = n
        return n
    }

    /// Tiles of `area` at `zoom` already in the storage (a download skips them); 0 when its file does not exist yet.
    private static func storedIn(_ storageId: String, _ zoom: Int, _ area: DownloadArea) -> Int {
        let service = TileService.shared
        let path = service.directory.appendingPathComponent("\(storageId).mbtiles").path
        guard FileManager.default.fileExists(atPath: path), let file = try? service.file(storageId) else { return 0 }
        let stored = try? TilePlanner.countStored(
            area,
            zoom,
            countIn: { xs, ys in
                try file.countTiles(zoom, x: xs, y: ys)
            },
            storedIn: { xs, ys in
                let tiles = try file.storedTiles(zoom, x: xs, y: ys)
                return { x, y in tiles.contains(TileId(zoom, x, y)) }
            }
        )
        return stored ?? 0
    }

    /// The first map of `plan` whose server allows fewer tiles than the plan needs from it; only the tiles still
    /// missing count when the plan counted the stored ones.
    static func restricted(in plan: ZonePlan) -> (source: TileSource, tiles: Int, limit: Int)? {
        for id in plan.fetchOrder {
            guard let source = TileService.shared.source(id), let limit = ZoneBulkPolicy.limits(for: source).maxTiles else {
                continue
            }
            let missing = max((plan.perMember[id] ?? 0) - (plan.storedPerMember[id] ?? 0), 0)
            if missing > limit {
                return (source, missing, limit)
            }
        }
        return nil
    }

    // MARK: Estimate

    /// What a zone would cost, with the average size of the tiles already stored for each map.
    static func estimate(_ request: ZoneRequest, scaling: ScalingPolicy) -> ZoneEstimate {
        let plan: ZonePlan
        do {
            plan = try ZoneEngine.plan(request, scaling: scaling, withStored: true)
        } catch is TooManyTilesError {
            return .tooMany
        } catch {
            return .empty
        }
        if let limited = restricted(in: plan) {
            return .restricted(name: limited.source.name, tiles: limited.tiles, limit: limited.limit)
        }
        var perSource: [String: ZoneSourceEstimate] = [:]
        for (id, counts) in plan.perSource {
            let tiles = counts.values.reduce(0, +)
            let stored = min(plan.stored[id] ?? 0, tiles)
            let average = ZoneTileSizes.shared.average(plan.storages[id] ?? [])
            let bytes = TilePlanner.estimateBytes(tiles - stored, averageTileBytes: average)
            perSource[id] = ZoneSourceEstimate(tiles: tiles, bytes: bytes, stored: stored)
        }
        return plan.total == 0 ? .empty : .ready(perSource)
    }

    // MARK: Run

    static func run(
        _ request: ZoneRequest,
        scaling: ScalingPolicy,
        report: @escaping @Sendable (ZoneProgress) -> Void
    ) async -> ZoneRunResult {
        let service = TileService.shared
        for id in request.sourceIds where service.source(id) == nil {
            return .refused(.unknownSource)
        }
        if service.offline {
            return .refused(.offline)
        }
        var plan: ZonePlan
        do {
            plan = try ZoneEngine.plan(request, scaling: scaling, withStored: false)
            // Counting the stored tiles costs reads: only when a limited server could refuse the zone.
            if restricted(in: plan) != nil {
                plan = try ZoneEngine.plan(request, scaling: scaling, withStored: true)
            }
        } catch {
            return .refused(.tooManyTiles)
        }
        if restricted(in: plan) != nil {
            return .refused(.restricted)
        }
        if plan.total == 0 {
            return .refused(.nothing)
        }
        let counters = ZoneCounters(total: plan.total, report: report)
        counters.report(force: true)
        let area = request.shape.toArea()
        do {
            for memberId in plan.fetchOrder {
                guard let member = service.source(memberId), let zooms = plan.fetches[memberId] else { continue }
                try await download(member, zooms.sorted(), area, counters)
            }
        } catch let stop as ZoneInterrupted {
            counters.report(force: true)
            return .interrupted(counters.snapshot(), stop.message)
        } catch {
            counters.report(force: true)
            if Task.isCancelled {
                return .cancelled(counters.snapshot())
            }
            return .interrupted(counters.snapshot(), String(describing: error))
        }
        counters.report(force: true)
        return .completed(counters.snapshot())
    }

    /// The tiles of one map at its tile zooms, by 1 to 4 workers on one feed; the tiles are written in batches.
    private static func download(
        _ member: TileSource,
        _ zooms: [Int],
        _ area: DownloadArea,
        _ counters: ZoneCounters
    ) async throws {
        guard let first = zooms.first else { return }
        let limits = ZoneBulkPolicy.limits(for: member)
        let threads = member.levelFor(first)?.maxThreads ?? 1
        let workers = max(1, min(threads, limits.maxThreads, maxWorkers))
        var storages: [Int: String] = [:]
        for z in zooms {
            guard let level = member.levelFor(z) else { continue }
            let leaf = TileRecipe.Leaf(source: member, level: level, tile: TileId(z, 0, 0))
            if let id = TileService.shared.storageId(leaf) {
                storages[z] = id
            }
        }
        let storageOf = storages
        let feed = ZoneTileFeed(area: area, zooms: zooms)
        let batch = ZoneTileBatch()
        // Whatever the end (done, interrupted, paused), the tiles downloaded so far are stored: a resume skips them.
        defer { batch.flush() }
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0 ..< workers {
                group.addTask {
                    try await ZoneEngine.work(member, feed, storageOf, limits, counters, batch)
                }
            }
            try await group.waitForAll()
        }
    }

    private static func work(
        _ member: TileSource,
        _ feed: ZoneTileFeed,
        _ storages: [Int: String],
        _ limits: ZoneBulkLimits,
        _ counters: ZoneCounters,
        _ batch: ZoneTileBatch
    ) async throws {
        let service = TileService.shared
        while let tile = try feed.next() {
            try Task.checkCancellation()
            guard let level = member.levelFor(tile.z), let storage = storages[tile.z],
                  let file = try? service.file(storage)
            else {
                counters.addMissing()
                continue
            }
            if (try? file.contains(tile)) == true {
                counters.addStored()
            } else {
                let leaf = TileRecipe.Leaf(source: member, level: level, tile: tile)
                try await fetch(leaf, storage, counters, batch)
                if limits.delayMillis > 0 {
                    try await Task.sleep(nanoseconds: UInt64(limits.delayMillis) * 1_000_000)
                }
            }
            batch.flushIfDue()
            counters.report()
        }
    }

    /// One tile from its server, tried three times; 404 and empty answers are tiles the map does not have.
    private static func fetch(
        _ leaf: TileRecipe.Leaf,
        _ storage: String,
        _ counters: ZoneCounters,
        _ batch: ZoneTileBatch
    ) async throws {
        var lastError = "no answer"
        for attempt in 0 ... retryDelaysMillis.count {
            if attempt > 0 {
                try await Task.sleep(nanoseconds: retryDelaysMillis[attempt - 1] * 1_000_000)
            }
            guard let request = TileService.shared.request(leaf) else {
                lastError = "bad tile address"
                break
            }
            do {
                let (data, response) = try await session.data(for: request)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                if (200 ..< 300).contains(code) {
                    if let type = MbTilesFile.contentTypeOf(data) {
                        batch.add(storage, TileEntry(leaf.tile, StoredTile(bytes: data, contentType: type)))
                        counters.addDownloaded(Int64(data.count))
                    } else {
                        counters.addMissing()
                    }
                    return
                }
                if code == 404 || code == 204 {
                    counters.addMissing()
                    return
                }
                lastError = "HTTP \(code)"
                // A bad template or an unusable answer will not improve by retrying.
                if code == 400 || code == 502 {
                    break
                }
            } catch {
                if Task.isCancelled {
                    throw CancellationError()
                }
                lastError = error.localizedDescription
            }
        }
        if counters.addFailed() >= failureStreakLimit {
            throw ZoneInterrupted(message: "\(failureStreakLimit) tiles in a row failed (last: \(lastError))")
        }
    }
}

/// Too many tiles in a row failed.
struct ZoneInterrupted: Error {
    let message: String
}

/// The tiles of a zone, zoom after zoom, handed to the workers one at a time; a rectangle is walked without listing it.
final class ZoneTileFeed: @unchecked Sendable {
    private struct Grid {
        let z: Int
        let minX: Int
        let maxX: Int
        let maxY: Int
    }

    private let lock = NSLock()
    private let area: DownloadArea
    private var zooms: [Int]
    private var list: [TileId] = []
    private var listIndex = 0
    private var grid: Grid?
    private var x = 0
    private var y = 0

    init(area: DownloadArea, zooms: [Int]) {
        self.area = area
        self.zooms = zooms
    }

    func next() throws -> TileId? {
        lock.lock()
        defer { lock.unlock() }
        while true {
            if let grid {
                if y <= grid.maxY {
                    let tile = TileId(grid.z, x, y)
                    x += 1
                    if x > grid.maxX {
                        x = grid.minX
                        y += 1
                    }
                    return tile
                }
                self.grid = nil
            }
            if listIndex < list.count {
                let tile = list[listIndex]
                listIndex += 1
                return tile
            }
            guard !zooms.isEmpty else { return nil }
            let z = zooms.removeFirst()
            list = []
            listIndex = 0
            if case let .rectangle(bounds) = area {
                let a = try TileMath.tileAt(bounds.minX, bounds.maxY, z)
                let b = try TileMath.tileAt(bounds.maxX, bounds.minY, z)
                if a.x <= b.x, a.y <= b.y {
                    grid = Grid(z: z, minX: a.x, maxX: b.x, maxY: b.y)
                    x = a.x
                    y = a.y
                }
            } else {
                list = try TilePlanner.tiles(area, [z])
            }
        }
    }
}

/// The tiles fetched and not written yet: written together, one transaction per storage, once 64 have come or a second
/// has passed (Android `TileBatch`, ADR-058).
final class ZoneTileBatch: @unchecked Sendable {
    private static let maxTiles = 64
    private static let maxNanos: UInt64 = 1_000_000_000

    private let lock = NSLock()
    private var pending: [String: [TileEntry]] = [:]
    private var count = 0
    private var firstAt: UInt64 = 0

    func add(_ storage: String, _ entry: TileEntry) {
        lock.lock()
        if count == 0 {
            firstAt = DispatchTime.now().uptimeNanoseconds
        }
        pending[storage, default: []].append(entry)
        count += 1
        let ready = count >= ZoneTileBatch.maxTiles ? take() : [:]
        lock.unlock()
        write(ready)
    }

    func flushIfDue() {
        let now = DispatchTime.now().uptimeNanoseconds
        lock.lock()
        let due = count > 0 && now >= firstAt + ZoneTileBatch.maxNanos
        let ready = due ? take() : [:]
        lock.unlock()
        write(ready)
    }

    func flush() {
        lock.lock()
        let ready = take()
        lock.unlock()
        write(ready)
    }

    private func take() -> [String: [TileEntry]] {
        let out = pending
        pending = [:]
        count = 0
        return out
    }

    private func write(_ ready: [String: [TileEntry]]) {
        for (storage, entries) in ready where !entries.isEmpty {
            try? TileService.shared.file(storage).putAll(entries)
        }
    }
}

/// Counters of a run, reported at most once a second (Android `ZoneDownloader.Counters`).
final class ZoneCounters: @unchecked Sendable {
    private static let reportNanos: UInt64 = 1_000_000_000

    private let lock = NSLock()
    private let total: Int64
    private let listener: @Sendable (ZoneProgress) -> Void
    private var downloaded: Int64 = 0
    private var stored: Int64 = 0
    private var missing: Int64 = 0
    private var failed: Int64 = 0
    private var bytes: Int64 = 0
    private var streak = 0
    private var lastReport: UInt64 = 0

    init(total: Int, report: @escaping @Sendable (ZoneProgress) -> Void) {
        self.total = Int64(total)
        listener = report
    }

    func addDownloaded(_ size: Int64) {
        lock.lock()
        downloaded += 1
        bytes += size
        streak = 0
        lock.unlock()
    }

    func addStored() {
        lock.lock()
        stored += 1
        lock.unlock()
    }

    func addMissing() {
        lock.lock()
        missing += 1
        streak = 0
        lock.unlock()
    }

    /// Returns the failures in a row.
    func addFailed() -> Int {
        lock.lock()
        defer { lock.unlock() }
        failed += 1
        streak += 1
        return streak
    }

    func snapshot() -> ZoneProgress {
        lock.lock()
        defer { lock.unlock() }
        return ZoneProgress(
            total: total,
            downloaded: downloaded,
            alreadyStored: stored,
            missing: missing,
            failed: failed,
            bytes: bytes
        )
    }

    func report(force: Bool = false) {
        let now = DispatchTime.now().uptimeNanoseconds
        lock.lock()
        let due = force || now >= lastReport + ZoneCounters.reportNanos
        if due {
            lastReport = now
        }
        lock.unlock()
        if due {
            listener(snapshot())
        }
    }
}

/// Average size of the tiles already stored in a storage, read once (Android `ZoneEstimator.averageTileBytes`).
final class ZoneTileSizes: @unchecked Sendable {
    static let shared = ZoneTileSizes()
    private static let minSample = 20

    private struct Sample {
        let count: Int
        let bytes: Int64
    }

    private let lock = NSLock()
    private var samples: [String: Sample] = [:]

    func average(_ storages: Set<String>) -> Int {
        var count = 0
        var bytes: Int64 = 0
        for id in storages {
            let sample = self.sample(id)
            count += sample.count
            bytes += sample.bytes
        }
        guard count >= ZoneTileSizes.minSample else { return TilePlanner.defaultTileBytes }
        return Int(bytes / Int64(count))
    }

    private func sample(_ storageId: String) -> Sample {
        lock.lock()
        let known = samples[storageId]
        lock.unlock()
        if let known { return known }
        var sample = Sample(count: 0, bytes: 0)
        let service = TileService.shared
        let path = service.directory.appendingPathComponent("\(storageId).mbtiles").path
        if FileManager.default.fileExists(atPath: path), let file = try? service.file(storageId) {
            let count = (try? file.tileCount()) ?? 0
            let bytes = (try? file.payloadBytes()) ?? 0
            sample = Sample(count: count, bytes: bytes)
        }
        lock.lock()
        samples[storageId] = sample
        lock.unlock()
        return sample
    }
}

// MARK: - Storage files (Android `StorageManager`)

enum StorageScan {
    /// Every MBTiles file that holds tiles, with the maps that show them.
    static func usages(sources: [TileSource]) -> [StorageUsage] {
        let service = TileService.shared
        var users: [String: [TileSource]] = [:]
        // Multi-zoom maps are named through the maps they show.
        for source in sources where !source.isMultiZoom {
            for id in storageIds(of: source) {
                users[id, default: []].append(source)
            }
        }
        var out: [StorageUsage] = []
        for stored in service.storedFiles() {
            guard let file = try? service.file(stored.id), let count = try? file.tileCount(), count > 0 else { continue }
            let zooms = Array(((try? file.tileCountByZoom()) ?? [:]).keys)
            let mapUsers = users[stored.id] ?? []
            let own = sources.first { $0.id == stored.id }
            let names = mapUsers.map(\.name).joined(separator: ", ")
            out.append(StorageUsage(
                id: stored.id,
                name: names.isEmpty ? (own?.name ?? stored.id) : names,
                tiles: count,
                diskBytes: file.diskBytes(),
                minZoom: zooms.min(),
                maxZoom: zooms.max(),
                usedBy: mapUsers.map(\.id),
                isLocalMap: own?.isLocalOnly ?? false
            ))
        }
        return out
    }

    /// The storages a map's own levels write to (references to other maps left out).
    static func storageIds(of source: TileSource) -> Set<String> {
        var out = Set<String>()
        for level in source.levels {
            if level.isLocalOnly {
                out.insert(source.id)
                continue
            }
            guard level.hasServer else { continue }
            let leaf = TileRecipe.Leaf(source: source, level: level, tile: TileId(level.zooms.min, 0, 0))
            if let id = TileService.shared.storageId(leaf) {
                out.insert(id)
            }
        }
        return out
    }
}

/// Why a package was refused (Android `PackageException`), with its message.
struct MbTilesPackageError: Error {
    enum Reason {
        case notMbtiles, unsupportedFormat, empty, tooLarge, unreadable
    }

    let reason: Reason
    let detail: String

    init(_ reason: Reason, _ detail: String) {
        self.reason = reason
        self.detail = detail
    }

    var text: String {
        switch reason {
        case .notMbtiles: "Ce fichier n'est pas un MBTiles valide."
        case .unsupportedFormat: "Seules les cartes raster (PNG, JPEG, WebP) sont prises en charge."
        case .empty: "Le fichier ne contient aucune tuile."
        case .tooLarge: "Fichier trop volumineux (4 Go au maximum)."
        case .unreadable: "Lecture impossible : \(detail)"
        }
    }
}

/// A package copied and checked.
struct StagedMbTiles {
    let file: URL
    let info: MbTilesPackageInfo
}

/// Export and import of `.mbtiles` packages (ADR-003: standard MBTiles, so packages travel between phones and tools).
enum MbTilesPackage {
    /// Packages above 4 GB are refused.
    static let maxBytes: Int64 = 4 * 1_024 * 1_024 * 1_024
    private static let rasterFormats: Set<String> = ["png", "jpg", "jpeg", "webp"]

    /// Copies `url` next to the tiles and checks that it is a raster MBTiles file.
    static func stage(_ url: URL, into directory: URL) -> Result<StagedMbTiles, MbTilesPackageError> {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        if Int64(size) > maxBytes {
            return .failure(MbTilesPackageError(.tooLarge, "more than \(maxBytes) bytes"))
        }
        let temp = directory.appendingPathComponent(".import-\(UUID().uuidString).tmp")
        do {
            try FileManager.default.copyItem(at: url, to: temp)
        } catch {
            return .failure(MbTilesPackageError(.unreadable, error.localizedDescription))
        }
        do {
            let info = try inspect(temp)
            return .success(StagedMbTiles(file: temp, info: info))
        } catch let error as MbTilesPackageError {
            discard(temp)
            return .failure(error)
        } catch {
            discard(temp)
            return .failure(MbTilesPackageError(.notMbtiles, error.localizedDescription))
        }
    }

    static func discard(_ file: URL) {
        for suffix in ["", "-wal", "-shm", "-journal"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: file.path + suffix))
        }
    }

    private static func inspect(_ url: URL) throws -> MbTilesPackageInfo {
        let db: SQLiteDatabase
        do {
            db = try SQLiteDatabase(path: url.path)
        } catch {
            throw MbTilesPackageError(.notMbtiles, "not an SQLite file")
        }
        defer { db.close() }
        var metadata: [String: String] = [:]
        let stats: [[SQLiteDatabase.Value]]
        let sample: [[SQLiteDatabase.Value]]
        do {
            for row in try db.query("SELECT name, value FROM metadata") {
                if let name = row[0].string {
                    metadata[name] = row[1].string ?? ""
                }
            }
            stats = try db.query("SELECT COUNT(*), MIN(zoom_level), MAX(zoom_level) FROM tiles")
            sample = try db.query("SELECT tile_data FROM tiles LIMIT 1")
        } catch {
            throw MbTilesPackageError(.notMbtiles, "missing metadata or tiles table")
        }
        let count = stats.first?[0].int64 ?? 0
        if count == 0 {
            throw MbTilesPackageError(.empty, "no tile in the file")
        }
        let format = metadata["format"]?.lowercased()
        if let format, !rasterFormats.contains(format) {
            throw MbTilesPackageError(.unsupportedFormat, "format \(format) is not a raster map")
        }
        guard let bytes = sample.first?[0].data, MbTilesFile.contentTypeOf(bytes) != nil else {
            throw MbTilesPackageError(.unsupportedFormat, "tiles are not PNG, JPEG or WebP images")
        }
        return MbTilesPackageInfo(
            name: nonBlank(metadata["name"]),
            attribution: nonBlank(metadata["attribution"]),
            format: format,
            minZoom: Int(stats.first?[1].int64 ?? 0),
            maxZoom: Int(stats.first?[2].int64 ?? 0),
            tiles: Int(count),
            sourceId: nonBlank(metadata["toolmap-source-id"])
        )
    }

    private static func nonBlank(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return text
    }

    /// Into the storages of an existing map, zoom by zoom (zooms the map does not serve are left out).
    static func merge(_ staged: URL, into source: TileSource) -> Result<Int, MbTilesPackageError> {
        copyTiles(staged) { zoom in
            guard let level = source.levelFor(zoom) else { return nil }
            if level.isLocalOnly { return source.id }
            let leaf = TileRecipe.Leaf(source: source, level: level, tile: TileId(zoom, 0, 0))
            return TileService.shared.storageId(leaf)
        }
    }

    /// The storage of a new local map: a plain package simply becomes its file.
    static func place(_ staged: URL, asLocalMap id: String) -> Result<Int, MbTilesPackageError> {
        let destination = TileService.shared.directory.appendingPathComponent("\(id).mbtiles")
        if !FileManager.default.fileExists(atPath: destination.path), isPlainTable(staged) {
            do {
                try FileManager.default.moveItem(at: staged, to: destination)
                let file = try MbTilesFile.open(destination, initialMetadata: ["toolmap-source-id": id])
                let count = (try? file.tileCount()) ?? 0
                file.close()
                discard(staged)
                return .success(count)
            } catch {
                return .failure(MbTilesPackageError(.unreadable, error.localizedDescription))
            }
        }
        return copyTiles(staged) { _ in id }
    }

    /// Copies the tiles column by column (bounded memory), their download date unknown (they never expire).
    private static func copyTiles(_ staged: URL, storageFor: (Int) -> String?) -> Result<Int, MbTilesPackageError> {
        defer { discard(staged) }
        do {
            let db = try SQLiteDatabase(path: staged.path)
            defer { db.close() }
            var copied = 0
            for zoomRow in try db.query("SELECT DISTINCT zoom_level FROM tiles") {
                guard let zoomValue = zoomRow[0].int64 else { continue }
                let z = Int(zoomValue)
                guard let storage = storageFor(z), let target = try? TileService.shared.file(storage) else { continue }
                let columns = try db.query("SELECT DISTINCT tile_column FROM tiles WHERE zoom_level = ?", [.int(z)])
                for columnRow in columns {
                    guard let column = columnRow[0].int64 else { continue }
                    let rows = try db.query(
                        "SELECT tile_row, tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ?",
                        [.int(z), .integer(column)]
                    )
                    var entries: [TileEntry] = []
                    for row in rows {
                        guard let tmsRow = row[0].int64, let data = row[1].data, !data.isEmpty else { continue }
                        let y = try TileMath.tmsY(Int(tmsRow), z)
                        let type = MbTilesFile.contentTypeOf(data) ?? "application/octet-stream"
                        let stored = StoredTile(bytes: data, contentType: type, storedAtMillis: StoredTile.unknownDateMillis)
                        entries.append(TileEntry(TileId(z, Int(column), y), stored))
                    }
                    try target.putAll(entries)
                    copied += entries.count
                }
            }
            return .success(copied)
        } catch {
            return .failure(MbTilesPackageError(.unreadable, String(describing: error)))
        }
    }

    private static func isPlainTable(_ file: URL) -> Bool {
        guard let db = try? SQLiteDatabase(path: file.path) else { return false }
        defer { db.close() }
        let rows = try? db.query("SELECT type FROM sqlite_master WHERE name = 'tiles'")
        return rows?.first?[0].string == "table"
    }

    /// Gives the room of deleted tiles back to the phone (Android `compact`): SQLite keeps it otherwise.
    static func compact(_ file: MbTilesFile) {
        try? file.checkpoint()
        if let db = try? SQLiteDatabase(path: file.url.path) {
            try? db.execute("VACUUM")
            db.close()
        }
        try? file.checkpoint()
    }

    /// Deletes the `.aqx` kept for a local map, so that it is gone at the next launch.
    static func forgetSourceFile(_ id: String) {
        let directory = SourceLibrary.directory
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.lowercased().hasSuffix(".aqx") {
            let url = directory.appendingPathComponent(name)
            guard let data = try? Data(contentsOf: url),
                  let result = try? AqxParser.parse(String(decoding: data, as: UTF8.self))
            else { continue }
            let sources = result.file.sources
            if sources.count == 1, sources[0].id == id {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    /// A file or map id from a display name: lower case, letters and digits separated by dashes (Android `slug`).
    static func slug(_ name: String) -> String {
        var out = ""
        var dash = false
        for character in name.lowercased() {
            if character.isASCII, character.isLetter || character.isNumber {
                out.append(character)
                dash = false
            } else if !dash {
                out.append("-")
                dash = true
            }
        }
        let trimmed = out.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        let short = String(trimmed.prefix(40))
        return short.isEmpty ? "map" : short
    }
}

// MARK: - Demo jobs (`-ToolMapDemo YES`, screenshots of the iOS CI only)

enum DownloadDemo {
    static func jobs() -> [ZoneJob] {
        let now = StoredTile.nowMillis()
        let chartreuse = ZoneShape.rectangle(ZoneShape.Rectangle(west: 5.80, south: 45.30, east: 5.95, north: 45.42))
        let bauges = ZoneShape.rectangle(ZoneShape.Rectangle(west: 6.05, south: 45.58, east: 6.25, north: 45.72))
        let belledonne = ZoneShape.rectangle(ZoneShape.Rectangle(west: 5.95, south: 45.15, east: 6.10, north: 45.28))
        var out: [ZoneJob] = []
        if let request = try? ZoneRequest(
            id: "demo-1", name: "Zone du \(NameDates.format(now - 600_000))", sourceIds: ["ign-maps", "opentopomap"],
            minZoom: 0, maxZoom: 16, shape: chartreuse, createdAtMillis: now - 600_000
        ) {
            let progress = ZoneProgress(total: 446, downloaded: 200, alreadyStored: 28, bytes: 5_200_000)
            out.append(ZoneJob(request: request, status: .running, progress: progress, updatedAtMillis: now))
        }
        if let request = try? ZoneRequest(
            id: "demo-2", name: "Autour de « Tour du Granier »", sourceIds: ["ign-maps"],
            minZoom: 0, maxZoom: 16, shape: bauges, createdAtMillis: now - 300_000
        ) {
            out.append(ZoneJob(request: request, status: .queued, progress: ZoneProgress(total: 812), updatedAtMillis: now))
        }
        if let request = try? ZoneRequest(
            id: "demo-3", name: "Belledonne", sourceIds: ["opentopomap"],
            minZoom: 0, maxZoom: 15, shape: belledonne, createdAtMillis: now - 86_400_000
        ) {
            let progress = ZoneProgress(total: 1_210, downloaded: 380, alreadyStored: 12, failed: 25, bytes: 7_900_000)
            out.append(ZoneJob(
                request: request, status: .failed, progress: progress,
                message: "25 tiles in a row failed (last: HTTP 503)", updatedAtMillis: now - 86_000_000
            ))
        }
        if let request = try? ZoneRequest(
            id: "demo-4", name: "Zone du \(NameDates.format(now - 4 * 86_400_000))", sourceIds: ["ign-maps"],
            minZoom: 0, maxZoom: 16, shape: chartreuse, createdAtMillis: now - 4 * 86_400_000
        ) {
            let progress = ZoneProgress(total: 2_380, downloaded: 2_380, bytes: 41_300_000)
            out.append(ZoneJob(request: request, status: .done, progress: progress, updatedAtMillis: now - 4 * 86_000_000))
        }
        return out.sorted { $0.request.createdAtMillis > $1.request.createdAtMillis }
    }
}
