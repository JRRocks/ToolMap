import Foundation
import MapLibre
import Observation
import ToolMapCore
import UIKit

/// One map drawn on the map view, bottom to top (`MapsController.layerSpecs`).
struct LayerSpec: Equatable {
    /// Key of the layer in the style and in its tile address (`MapStyleKey`): the map id for a plain map shown once,
    /// followed by its elevation band, its rank (a map shown twice) or `coverage`. `MapsController.source(_:)` reads it.
    let sourceId: String
    /// The map shown.
    let mapId: String
    /// Tile zooms of the map's source in MapLibre: from a few levels under its first zoom (the tile pipeline draws those
    /// from it, ADR-031) to its last one (MapLibre enlarges beyond).
    let minZoom: Int
    let maxZoom: Int
    /// What MapLibre needs to draw the layer (`StackToStyle`): visibility, opacity, colours, night mode, zoom range.
    let props: LayerProps

    var opacity: Double { Double(props.opacity) }

    /// MapLibre's default upper bound of a layer, given back when a layer has no zoom range.
    static let maxLayerZoom: Float = 24

    /// Paint properties of the raster layer that do not need a new layer. The night mode (Android M9: the maps are
    /// darkened, not the tracks) is already in the brightness of `props`.
    func paint(_ layer: MLNRasterStyleLayer, nightMode: Bool) {
        layer.isVisible = props.visible
        layer.minimumZoomLevel = props.minZoom ?? 0
        layer.maximumZoomLevel = props.maxZoom ?? LayerSpec.maxLayerZoom
        layer.rasterOpacity = NSExpression(forConstantValue: NSNumber(value: props.opacity))
        layer.rasterContrast = NSExpression(forConstantValue: NSNumber(value: props.contrast))
        layer.rasterSaturation = NSExpression(forConstantValue: NSNumber(value: props.saturation))
        layer.rasterHueRotation = NSExpression(forConstantValue: NSNumber(value: props.hueRotate))
        // An inverted layer has its brightness range upside down (`RasterAdjust.brightnessRange`).
        layer.minimumRasterBrightness = NSExpression(forConstantValue: NSNumber(value: props.brightnessMin))
        layer.maximumRasterBrightness = NSExpression(forConstantValue: NSNumber(value: props.brightnessMax))
    }
}

/// Tiles and disk size stored for one map.
struct MapsStoredSize: Equatable {
    let tiles: Int
    let bytes: Int64
}

/// Disk figures of « Cartes disponibles » and of the details of a layer.
struct MapsStorageFigures: Equatable {
    let tilesBytes: Int64
    let freeBytes: Int64
    /// Per map; a tile set shown by several maps (ADR-031) counts for each of them.
    let perSource: [String: MapsStoredSize]
}

/// The maps of the app and the ones on screen (Android « Cartes et calques », §5, `MapViewModel` and `MapsViewModel`):
/// the catalogue (built-in maps and the ones the user added), the layer stack with its zoom ranges, elevation bands and
/// colours, the favourites, the maps hidden from the list, and the saved state (`state/workstate.json`, Android keys).
@Observable
final class MapsController {
    @ObservationIgnored unowned let app: AppModel

    /// The map of a first start (the validated captures of P32.3 show it).
    static let firstBase = "ign-maps"

    /// Camera / tile zoom relation of this screen (ADR-032), the same as `MapStyle.declaredTileSize`.
    static let mapping = ZoomMapping.forScreen(Double(UIScreen.main.scale), TileSharpness.sharp)

    private(set) var sources: [TileSource]
    /// Ids of the maps shipped with the app (the others were added by the user).
    private(set) var builtinIds: Set<String>
    /// The layers on the map, bottom first.
    private(set) var stack: LayerStack
    /// The two last base maps, most recent first.
    private(set) var recentBases: [String]
    /// Favourites saved by the user, oldest first (the provided ones are `Workspaces.builtin`).
    private(set) var savedWorkspaces: [Workspace]
    /// Provided favourites the user deleted.
    private(set) var hiddenBuiltins: Set<String>
    /// The layer whose line is unfolded in « Cartes et calques » (P29.3: one at a time).
    var expandedLayer: String?
    /// The layer whose « Conditions d'affichage » are open (P20).
    var conditionsLayer: String?
    /// « Couverture »: the stored tiles of the base map drawn over it.
    var coverage = false
    /// Maps whose stored tiles are fetched again when the map asks for them (this session only).
    private(set) var forced: Set<String> = []
    /// « Afficher les cartes masquées » of « Cartes disponibles ».
    var showHidden = false
    /// Disk figures, read by `refreshStorage`.
    private(set) var storage: MapsStorageFigures?

    @ObservationIgnored private var saveWork: DispatchWorkItem?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    /// False when the debug launch arguments changed the maps: nothing of this run is saved.
    @ObservationIgnored private var persists = true
    @ObservationIgnored private lazy var onlineGroups: [CatalogGroup] = (try? OnlineCatalog.load()) ?? []
    /// A page opened from the panel: the panel comes back when it closes (Android P18.7).
    @ObservationIgnored private var panelAfterScreen = false
    /// The page to open again when the one opened from it closes (« Stockage hors ligne… » of « Cartes disponibles »).
    @ObservationIgnored private var pendingReturn: Screen?
    @ObservationIgnored private var returnAfter: Screen?

    init(app: AppModel) {
        self.app = app
        let builtins = (try? BuiltinSources.load()) ?? []
        let ids = Set(builtins.map(\.id))
        let imported = SourceLibrary.load().filter { !ids.contains($0.id) }
        let loaded = builtins + imported
        let knownIds = Set(loaded.map(\.id))
        let saved = WorkStateFile.load()
        var firstStack = WorkState.defaultStack
        var firstBases = ["osm"]
        if let saved {
            firstStack = Workspaces.replaceRetired(saved.stack) { knownIds.contains($0) }
            let bases = saved.recentBases.filter { knownIds.contains($0) }
            firstBases = bases.isEmpty ? ["osm"] : bases
        } else {
            let preferred = loaded.first(where: { $0.id == MapsController.firstBase })
            let first = preferred ?? loaded.first(where: { !$0.isLayer && $0.type != "dem" && $0.isSupported })
            if let first {
                firstStack = LayerStack().add(first.id)
                firstBases = [first.id]
            }
        }
        sources = loaded
        builtinIds = ids
        stack = firstStack
        recentBases = firstBases
        savedWorkspaces = WorkspaceFiles.load()
        hiddenBuiltins = WorkspaceFiles.loadHidden()

        TileService.shared.register(loaded, builtins: builtins)
        MapTileContext.shared.setBuiltins(builtins)
        if let saved {
            app.offline = saved.offline
            app.center = LonLat(lon: saved.camera.lon, lat: saved.camera.lat)
            app.zoom = saved.camera.zoom
        }
        let center = NotificationCenter.default
        let names = [UIApplication.didEnterBackgroundNotification, UIApplication.willTerminateNotification]
        for name in names {
            let token = center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                self?.persistNow()
            }
            observers.append(token)
        }
        watchScreen()
    }

    // MARK: Pages opened from the panel

    /// Opens a page from « Cartes et calques »: the panel closes first (two sheets cannot be on screen together) and
    /// comes back when the page closes, as on Android (P18.7).
    func openFromPanel(_ screen: Screen) {
        panelAfterScreen = true
        app.panel = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
            self?.app.screen = screen
        }
    }

    private func watchScreen() {
        withObservationTracking {
            _ = app.screen
        } onChange: { [weak self] in
            DispatchQueue.main.async {
                self?.screenChanged()
            }
        }
    }

    /// Opens a page from « Cartes disponibles », which comes back when that page closes (Android: the back of STORAGE
    /// returns to AVAILABLE).
    func openFromAvailableMaps(_ screen: Screen) {
        pendingReturn = .availableMaps
        app.screen = screen
    }

    private func screenChanged() {
        if let now = app.screen {
            if let back = pendingReturn, now != back {
                returnAfter = back
                pendingReturn = nil
            }
        } else if let back = returnAfter {
            returnAfter = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                guard let self, self.app.screen == nil else { return }
                self.app.screen = back
            }
        } else if panelAfterScreen {
            panelAfterScreen = false
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                guard let self, self.app.screen == nil, self.app.panel == nil, self.app.tool == nil else { return }
                self.app.panel = .maps
            }
        }
        watchScreen()
    }

    /// Leaves « Cartes disponibles » for the map without coming back to the panel (a zone to select, a map to see).
    func closeScreenToMap() {
        panelAfterScreen = false
        returnAfter = nil
        pendingReturn = nil
        app.screen = nil
    }

    // MARK: Catalogue

    /// The map of `id`, or of a layer key of `layerSpecs` (`MapStyleKey`).
    func source(_ id: String) -> TileSource? {
        if let found = sources.first(where: { $0.id == id }) { return found }
        let mapId = MapStyleKey.mapId(id)
        if mapId == id { return nil }
        return sources.first { $0.id == mapId }
    }

    /// A map the user added (imported, made in the wizard or the multi-zoom editor, taken from the online catalogue).
    func isImported(_ id: String) -> Bool {
        !builtinIds.contains(id) && sources.contains { $0.id == id }
    }

    /// A base map shows a whole background: not a transparent overlay and not elevation data.
    func isBase(_ id: String) -> Bool {
        guard let map = source(id) else { return false }
        return !map.isLayer && map.type != "dem"
    }

    /// Background maps: drawable sources that are not overlays (elevation data left out).
    var baseMaps: [TileSource] {
        sources.filter { !$0.isLayer && $0.type != "dem" && $0.isSupported }
    }

    var overlayMaps: [TileSource] {
        sources.filter { $0.isLayer && $0.isSupported }
    }

    /// The sections of « Cartes disponibles » (the hidden maps only when `showHidden`).
    func groups() -> [MapGroup] {
        MapCatalog.groups(
            sources,
            hidden: app.settings.maps.hiddenMaps,
            showHidden: showHidden,
            isImported: { id in self.isImported(id) }
        )
    }

    /// The provided maps the user hid from the list (F3), among those the app still ships.
    var hiddenMaps: Set<String> {
        let ids = app.settings.maps.hiddenMaps
        var found = Set<String>()
        for map in sources {
            let hidden = MapCatalog.isHidden(map.id, hidden: ids, isImported: { id in self.isImported(id) })
            if hidden { found.insert(map.id) }
        }
        return found
    }

    /// « Masquer de la liste »: the map leaves the list, the favourites and the map.
    func hideFromList(_ id: String) {
        hideSource(id)
        app.settingsStore.update { _ = $0.maps.hiddenMaps.insert(id) }
    }

    /// « Remettre dans la liste »; the last hidden map back in the list also turns « Afficher » off.
    func showInList(_ id: String) {
        app.settingsStore.update { _ = $0.maps.hiddenMaps.remove(id) }
        if hiddenMaps.isEmpty { showHidden = false }
    }

    /// The online catalogue (P16.4), read once: it ships with the app.
    var onlineCatalog: [CatalogGroup] { onlineGroups }

    /// Ids of the maps of the online catalogue the catalogue already gives (same id or same tiles).
    func catalogAdded() -> Set<String> {
        var added = Set<String>()
        for group in onlineGroups {
            for entry in group.entries where alreadyHave(entry.source) {
                added.insert(entry.source.id)
            }
        }
        return added
    }

    private func alreadyHave(_ map: TileSource) -> Bool {
        source(map.id) != nil || SourceDuplicates.of(map, sources).duplicateOf != nil
    }

    /// The maps of the online catalogue whose name holds every word of `query` (P29.6).
    func catalogMatches(_ query: String) -> [CatalogEntry] {
        var found: [CatalogEntry] = []
        for group in onlineGroups {
            for entry in group.entries where MapCatalog.matches(entry.source.name, query) {
                found.append(entry)
            }
        }
        return found
    }

    /// Adds a map of the online catalogue with the API keys the user gave; false when its id is already in use.
    @discardableResult
    func addFromCatalog(_ entry: CatalogEntry, keys: [String: String]) -> Bool {
        guard source(entry.source.id) == nil else { return false }
        do {
            try saveSource(OnlineCatalog.withKeys(entry.source, keys))
            return true
        } catch {
            return false
        }
    }

    /// Creates or replaces a map the user added (wizard, multi-zoom editor, catalogue). A built-in map is refused.
    func saveSource(_ map: TileSource) throws {
        if builtinIds.contains(map.id) {
            throw CocoaError(.fileWriteNoPermission)
        }
        try SourceLibrary.save(map)
        if let index = sources.firstIndex(where: { $0.id == map.id }) {
            sources[index] = map
        } else {
            sources.append(map)
        }
        registerSources()
    }

    /// Removes a map the user added, and takes it off the map.
    func deleteSource(_ id: String) {
        guard isImported(id) else { return }
        SourceLibrary.delete(id)
        sources.removeAll { $0.id == id }
        hideSource(id)
    }

    /// Maps whose band shows `id`: deleting it would leave them a band with no map.
    func usersOf(_ id: String) -> [TileSource] {
        sources.filter { map in map.id != id && map.levels.contains { $0.mapRef?.sourceId == id } }
    }

    private func registerSources() {
        let builtins = sources.filter { builtinIds.contains($0.id) }
        TileService.shared.register(sources, builtins: builtins)
    }

    /// Adds the maps of an `.aqx` file chosen by the user or opened with ToolMap; returns a sentence for the alert.
    func importMaps(from url: URL) -> String {
        do {
            let result = try SourceLibrary.importFile(url, existing: Set(sources.map(\.id)))
            sources.append(contentsOf: result.added)
            registerSources()
            var lines = ["Cartes importées : \(result.added.count)"]
            if !result.added.isEmpty {
                lines.append(result.added.map(\.name).joined(separator: ", "))
            }
            if !result.skipped.isEmpty {
                lines.append("Déjà présentes : " + result.skipped.joined(separator: ", "))
            }
            let unsupported = result.added.filter { !$0.isSupported }
            for map in unsupported {
                lines.append("Non supportée : \(map.name) (\(map.unsupportedReason ?? "?"))")
            }
            if !result.warnings.isEmpty {
                lines.append("Avertissements : \(result.warnings.count)")
            }
            return lines.joined(separator: "\n")
        } catch let error as AqxParseError {
            return "Import impossible : \(error.message)"
        } catch {
            return "Import impossible : \(error.localizedDescription)"
        }
    }

    /// « Exporter en .aqx… » (F4): the map alone in a temporary `.aqx` file to share. The keys of a map the user added
    /// are left out, as the wizard does by default; a provided map keeps the public key it ships with.
    func exportFile(_ id: String) -> URL? {
        guard let map = source(id) else { return nil }
        let redacted: Set<String> = isImported(id) ? Set(map.params.keys) : Set<String>()
        let text = AqxWriter.write([map], name: map.name, redactedParams: redacted)
        let url = AppFiles.temporary(SourceIds.fromName(map.name) + ".aqx")
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }

    // MARK: Layer stack

    /// The zoom of the tiles on screen (AlpineQuest's and Android's zoom, ADR-032).
    var displayedZoom: Double {
        app.zoom + MapsController.mapping.offset
    }

    /// Whether the map draws `layer` now (visible, not transparent, inside its zoom range or band).
    func isDrawn(_ layer: LayerConfig) -> Bool {
        layer.visible && layer.opacity > 0 && LayerElevation.isDrawnAt(layer, displayedZoom)
    }

    /// What the map draws, bottom to top: one raster layer per layer of the stack whose map is known, then the
    /// stored-coverage overlay.
    var layerSpecs: [LayerSpec] {
        let maps = app.settings.maps
        let applied = StackToStyle.desired(
            stack,
            mapping: MapsController.mapping,
            darkening: maps.darkening,
            isKnownSource: { id in self.source(id)?.isSupported == true }
        )
        var styles: [String: LayerProps] = [:]
        for layer in applied {
            styles[layer.id] = layer.props
        }
        let shrink = maps.scaling.shrinkLevels
        var specs: [LayerSpec] = []
        var ranks: [String: Int] = [:]
        for layer in stack.layers {
            guard let map = source(layer.sourceId), let props = styles[layer.id] else { continue }
            let mask = LayerElevation.maskOf(layer)
            let look = MapStyleKey.make(map.id, mask: mask, rank: 1)
            let rank = (ranks[look] ?? 0) + 1
            ranks[look] = rank
            let low = Swift.max(0, (map.minZoom ?? 0) - shrink)
            let spec = LayerSpec(
                sourceId: MapStyleKey.make(map.id, mask: mask, rank: rank),
                mapId: map.id,
                minZoom: low,
                maxZoom: map.maxZoom ?? 22,
                props: props
            )
            specs.append(spec)
        }
        if coverage, let base = stack.baseLayer({ self.isBase($0) }), let map = source(base.sourceId) {
            let overlay = LayerSpec(
                sourceId: MapStyleKey.coverage(map.id),
                mapId: map.id,
                minZoom: 0,
                maxZoom: 22,
                props: LayerProps(opacity: 0.7)
            )
            specs.append(overlay)
        }
        return specs
    }

    /// Credits of the maps on screen, on one short line (`Credits.short`).
    var credits: String {
        var copyrights: [String] = []
        for layer in stack.layers where layer.visible && layer.opacity > 0 {
            if let copyright = source(layer.sourceId)?.copyright {
                copyrights.append(copyright)
            }
        }
        return Credits.short(copyrights)
    }

    private func setStack(_ next: LayerStack) {
        guard next != stack else { return }
        stack = next
        if let open = expandedLayer, next[open] == nil { expandedLayer = nil }
        scheduleSave()
    }

    /// Adds `sourceId` on top with the opacity its file gives.
    func addLayer(_ sourceId: String) {
        guard let map = source(sourceId) else { return }
        setStack(stack.add(sourceId, opacity: Float(map.opacity) / 100))
    }

    func removeLayer(_ id: String) {
        setStack(stack.remove(id))
    }

    /// « Ne plus afficher les autres »: keeps only the layer `id`.
    func removeOtherLayers(_ id: String) {
        setStack(stack.removeOthers(id))
    }

    /// Takes every layer of the map `sourceId` off the stack.
    func hideSource(_ sourceId: String) {
        var next = stack
        for layer in stack.layers where layer.sourceId == sourceId {
            next = next.remove(layer.id)
        }
        setStack(next)
    }

    /// Puts back a stack taken before a change: « Annuler » of « Carte affichée » (P29.6).
    func restoreStack(_ previous: LayerStack) {
        setStack(previous)
    }

    /// One step towards the top of the screen.
    func moveUp(_ id: String) {
        setStack(stack.moveUp(id))
    }

    /// One step towards the bottom of the screen.
    func moveDown(_ id: String) {
        setStack(stack.moveDown(id))
    }

    /// The layers in a new order, given from the top of the screen down (a drag in the list).
    func setOrder(topToBottom ids: [String]) {
        var layers: [LayerConfig] = []
        for id in ids.reversed() {
            if let layer = stack[id] { layers.append(layer) }
        }
        guard layers.count == stack.layers.count else { return }
        setStack(LayerStack(layers))
    }

    func setVisible(_ id: String, _ visible: Bool) {
        setStack(stack.setVisible(id, visible))
    }

    func setOpacity(_ id: String, _ opacity: Float) {
        setStack(stack.setOpacity(id, opacity))
    }

    /// Display range of a layer in displayed zoom, inclusive; nil = no limit (N3).
    func setZoomRange(_ id: String, _ min: Int?, _ max: Int?) {
        setStack(stack.setZoomRange(id, min, max))
    }

    /// Elevation band of a layer in metres, inclusive; nil = no limit (P20).
    func setElevationRange(_ id: String, _ min: Int?, _ max: Int?, _ logic: RangeLogic) {
        setStack(stack.setElevationRange(id, min, max, logic))
    }

    func setAdjustments(_ id: String, contrast: Float, brightness: Float, saturation: Float) {
        setStack(stack.setAdjustments(id, contrast, brightness, saturation))
    }

    func setHue(_ id: String, _ hue: Float) {
        setStack(stack.setHue(id, hue))
    }

    func setInverted(_ id: String, _ inverted: Bool) {
        setStack(stack.setInverted(id, inverted))
    }

    func resetColors(_ id: String) {
        setStack(stack.resetColors(id))
    }

    /// Puts `sourceId` in place of the lowest base map and remembers it for the quick swap.
    func replaceBase(_ sourceId: String) {
        setStack(stack.replaceBase(sourceId) { self.isBase($0) })
        useBase(sourceId)
    }

    /// Shows a map: a base replaces the lowest base of the stack, a layer goes on top.
    func showMap(_ sourceId: String) {
        if isBase(sourceId) {
            replaceBase(sourceId)
        } else {
            addLayer(sourceId)
        }
    }

    /// The lowest base map of the stack.
    var bottomBase: String? {
        stack.baseLayer { self.isBase($0) }?.sourceId
    }

    /// The id of the lowest base map, "" when the stack has none (the information pills read its last zoom).
    var baseId: String {
        bottomBase ?? ""
    }

    private func useBase(_ id: String) {
        var bases = [id]
        for other in recentBases where !bases.contains(other) {
            bases.append(other)
        }
        recentBases = Array(bases.prefix(2))
        scheduleSave()
    }

    /// « Forcer la mise à jour des données locales (cette session) ».
    func setForced(_ sourceId: String, _ on: Bool) {
        if on {
            forced.insert(sourceId)
        } else {
            forced.remove(sourceId)
        }
        MapTileContext.shared.setForced(forced)
    }

    /// « Hors ligne »: only the stored tiles are shown, nothing is requested; kept in the saved state.
    func setOffline(_ on: Bool) {
        app.offline = on
        scheduleSave()
    }

    // MARK: Favourites

    /// What the favourites list shows: the provided ones not deleted, then the saved ones.
    var favorites: [Workspace] {
        let provided = Workspaces.builtin.filter { !hiddenBuiltins.contains($0.id) }
        return provided + savedWorkspaces
    }

    /// The favourite the map shows exactly, if any.
    var appliedFavorite: Workspace? {
        favorites.first { $0.matches(stack) }
    }

    /// Replaces the stack with `workspace`; maps not available here (or hidden from the list) are left out. Returns the
    /// message to show.
    @discardableResult
    func applyWorkspace(_ workspace: Workspace) -> String {
        let hidden = app.settings.maps.hiddenMaps
        let applied = workspace.toStack { id in
            guard let map = self.source(id), map.isSupported else { return false }
            return !MapCatalog.isHidden(id, hidden: hidden, isImported: { other in self.isImported(other) })
        }
        setStack(applied.stack)
        if let base = applied.stack.baseLayer({ self.isBase($0) }) {
            useBase(base.sourceId)
        }
        var text = "Favori appliqué : \(workspace.name)"
        if !applied.skipped.isEmpty {
            text += "\nCartes indisponibles ignorées : \(applied.skipped.count)"
        }
        return text
    }

    /// Saves the current stack, with its zoom ranges and bands, as a favourite called `name`; returns the message.
    func saveWorkspace(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let workspace = Workspace.of(WorkspaceFiles.newId(), trimmed, stack)
        try? WorkspaceFiles.save(workspace)
        savedWorkspaces.removeAll { $0.id == workspace.id }
        savedWorkspaces.append(workspace)
        return "Favori enregistré : \(trimmed)"
    }

    /// Deletes a saved favourite, or hides a provided one (« Rétablir » brings it back).
    func deleteWorkspace(_ workspace: Workspace) {
        if workspace.isBuiltin {
            hiddenBuiltins.insert(workspace.id)
            WorkspaceFiles.writeHidden(hiddenBuiltins)
        } else {
            WorkspaceFiles.delete(workspace.id)
            savedWorkspaces.removeAll { $0.id == workspace.id }
        }
    }

    /// Brings back the favourite just deleted: « Annuler » of « Favori supprimé ».
    func restoreWorkspace(_ workspace: Workspace) {
        if workspace.isBuiltin {
            hiddenBuiltins.remove(workspace.id)
            WorkspaceFiles.writeHidden(hiddenBuiltins)
        } else {
            try? WorkspaceFiles.save(workspace)
            savedWorkspaces.removeAll { $0.id == workspace.id }
            savedWorkspaces.append(workspace)
        }
    }

    /// Shows again the provided favourites the user deleted.
    func restoreBuiltinWorkspaces() {
        hiddenBuiltins = []
        WorkspaceFiles.writeHidden([])
    }

    // MARK: Storage

    /// Reads the disk figures off the main thread (a few queries on the tile files).
    func refreshStorage() {
        let list = sources
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let figures = MapsController.computeStorage(list)
            DispatchQueue.main.async {
                self?.storage = figures
            }
        }
    }

    /// Figures stored for one map, nil when nothing is stored.
    func stored(_ id: String) -> MapsStoredSize? {
        guard let size = storage?.perSource[id], size.tiles > 0 else { return nil }
        return size
    }

    /// « Vider les tuiles stockées » (F4): the tile files of the map emptied, then the figures read again.
    func clearTiles(_ id: String, done: @escaping (Bool) -> Void) {
        guard let map = source(id) else {
            done(false)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            for storageId in MapTileContext.shared.storageIds(of: map) {
                if MapTileContext.shared.existingFile(storageId) != nil {
                    TileService.shared.clear(storageId)
                }
            }
            DispatchQueue.main.async {
                self?.refreshStorage()
                done(true)
            }
        }
    }

    private static func computeStorage(_ list: [TileSource]) -> MapsStorageFigures {
        let files = TileService.shared.storedFiles()
        var bytesById: [String: Int64] = [:]
        var total: Int64 = 0
        for file in files {
            bytesById[file.id] = file.bytes
            total += file.bytes
        }
        var counts: [String: Int] = [:]
        var perSource: [String: MapsStoredSize] = [:]
        for map in list where !map.isMultiZoom {
            var tiles = 0
            var bytes: Int64 = 0
            for storageId in MapTileContext.shared.storageIds(of: map) {
                guard let size = bytesById[storageId] else { continue }
                if counts[storageId] == nil {
                    let count = (try? MapTileContext.shared.existingFile(storageId)?.tileCount()) ?? 0
                    counts[storageId] = count
                }
                tiles += counts[storageId] ?? 0
                bytes += size
            }
            if tiles > 0 || bytes > 0 {
                perSource[map.id] = MapsStoredSize(tiles: tiles, bytes: bytes)
            }
        }
        let home = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? home.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        let free = values?.volumeAvailableCapacityForImportantUsage ?? 0
        return MapsStorageFigures(tilesBytes: total, freeBytes: free, perSource: perSource)
    }

    // MARK: Saved state

    private func scheduleSave() {
        guard persists else { return }
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.persistNow()
        }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    /// Writes the state now (also when the app goes to the background, for the camera).
    func persistNow() {
        guard persists else { return }
        let camera = CameraState(lat: app.center.lat, lon: app.center.lon, zoom: app.zoom, bearing: app.direction)
        let state = WorkState(stack: stack, camera: camera, recentBases: recentBases, offline: app.offline)
        DispatchQueue.global(qos: .utility).async {
            WorkStateFile.save(state)
        }
    }

    /// Debug only (.github/workflows/ios.yml screenshots): `-ToolMapBase osm`, `-ToolMapOverlays ign-pentes`,
    /// `-ToolMapFavorite sortie-ski` (a provided favourite), `-ToolMapLayerOpen top` (the top line unfolded),
    /// `-ToolMapConditions top` (its conditions open), `-ToolMapCoverage YES`. Nothing of such a run is saved.
    func applyLaunchArguments(_ defaults: UserDefaults) {
        let keys = [
            "ToolMapBase", "ToolMapOverlays", "ToolMapFavorite", "ToolMapLayerOpen", "ToolMapConditions",
            "ToolMapCoverage", "ToolMapDemo",
        ]
        if keys.contains(where: { defaults.object(forKey: $0) != nil }) {
            persists = false
        }
        if defaults.bool(forKey: "ToolMapDemo") {
            let demo = Workspace(
                id: "ws-demo",
                name: "Ma rando",
                layers: [WorkspaceLayer("osm"), WorkspaceLayer("ign-pentes", opacity: 0.4, displayMinZoom: 12)]
            )
            savedWorkspaces.append(demo)
        }
        if let name = defaults.string(forKey: "ToolMapFavorite") {
            let id = name.hasPrefix(Workspace.builtinPrefix) ? name : Workspace.builtinPrefix + name
            if let favorite = favorites.first(where: { $0.id == id || $0.name == name }) {
                applyWorkspace(favorite)
            }
        }
        if let base = defaults.string(forKey: "ToolMapBase"), source(base) != nil {
            replaceBase(base)
        }
        if let list = defaults.string(forKey: "ToolMapOverlays") {
            for part in list.split(separator: ",") {
                let id = String(part)
                if source(id) != nil, !stack.layers.contains(where: { $0.sourceId == id }) {
                    addLayer(id)
                }
            }
        }
        if defaults.bool(forKey: "ToolMapCoverage") {
            coverage = true
        }
        if let which = defaults.string(forKey: "ToolMapLayerOpen") {
            expandedLayer = which == "top" ? stack.topToBottom().first?.id : Optional(which)
        }
        if let which = defaults.string(forKey: "ToolMapConditions") {
            let id: String? = which == "top" ? stack.topToBottom().first?.id : Optional(which)
            expandedLayer = id
            conditionsLayer = id
        }
    }
}

/// « Supprimer cette carte » of the storage page: a local map (imported `.mbtiles`) leaves the catalogue at once.
extension MapsController: ZoneMapCatalogue {
    func removeLocalMap(_ id: String) {
        deleteSource(id)
        registerSources()
    }
}
