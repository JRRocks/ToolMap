import CoreLocation
import Foundation
import MapLibre
import Observation
import ToolMapCore
import UIKit

/// The three steps of a print (ADR-061, ADR-098): the sheet and the scale, the frame on the map, the confirmation.
enum PrintStage: Equatable {
    case setup
    case frame
    case confirm
}

/// The pages of the confirmation (P17.8, variant A): the main one, « Éléments de la feuille », « Qualité et marges ».
enum PrintSheetPage: Equatable {
    case main
    case elements
    case quality
}

/// A long job the panel shows a progress bar for.
enum PrintJob: Equatable {
    case downloading(done: Int, total: Int)
    case exporting(fraction: Double)
}

/// How an export ended, for the sheet that follows it.
struct PrintOutcome: Identifiable {
    enum Kind {
        case exported(url: URL, pages: Int, bytes: Int, missingTiles: Int)
        case failed(String?)
    }

    let id = UUID()
    let kind: Kind
}

/// Cancels a download or an export from the panel; read from the worker threads.
final class PrintJobToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }
}

/// Imprimer à l'échelle (Android T3 and T3b, P17, `PrintViewModel`): the settings, the frame placed on the map, the
/// plan computed for it by the core (`PrintPlanner`), the missing tiles, and the two long jobs (download of the missing
/// tiles, export of the PDF by the core's `PrintExporter` with `PrintBandRenderer`).
@Observable
final class PrintController {
    /// The missing tiles can be fetched from the panel; above this they stay hatched (P17, Q18).
    static let maxDownloadTiles = 1_500

    @ObservationIgnored unowned let app: AppModel

    /// nil when the print is not open.
    private(set) var stage: PrintStage?
    private(set) var template = PrintTemplate()
    /// The centre of the frame on the ground: the frame stays there when the map moves.
    private(set) var center = LonLat(lon: 0, lat: 0)
    /// « Imprimer le long de cette trace… » (T3b): one page per frame along `path`; the frames do not move.
    private(set) var isAlongTrack = false
    private(set) var path: [LonLat] = []
    private(set) var plan: PrintPlan?
    private(set) var planning = false
    /// Tiles of the plan not in local storage yet; nil while they are being counted.
    private(set) var missingTiles: Int?
    var sheetPage: PrintSheetPage = .main
    private(set) var job: PrintJob?
    var outcome: PrintOutcome?

    /// Top of the print panel in window points, measured by the panel: the frame is fitted to the map above it.
    @ObservationIgnored var panelTop: CGFloat = 0

    @ObservationIgnored private var trackId: Int64?
    @ObservationIgnored private var missingLeaves: [TileRecipe.Leaf] = []
    @ObservationIgnored private var planTask: Task<Void, Never>?
    @ObservationIgnored private var fitTask: Task<Void, Never>?
    @ObservationIgnored private var pendingFit = false
    @ObservationIgnored private var jobToken: PrintJobToken?
    @ObservationIgnored private var launchStageApplied = false
    @ObservationIgnored private var launchExportPending = false

    init(app: AppModel) {
        self.app = app
    }

    var isOpen: Bool { stage != nil }

    /// While the sheet is chosen and the frame placed, only the frame, its pill and the panel stay over the map
    /// (Android P17.5, P17.8): no information pills, credits, round buttons, bottom bar or centre point.
    var hidesMapChrome: Bool { stage == .setup || stage == .frame }

    var canDownloadMissing: Bool {
        guard let missing = missingTiles else { return false }
        return missing >= 1 && missing <= PrintController.maxDownloadTiles
    }

    // MARK: The three steps

    /// « Imprimer » (`alongTrack` nil) or « Imprimer le long de cette trace… »: the sheet and the scale, with the
    /// settings of the last print; the first print takes the grid of the coordinate format of the settings.
    func open(alongTrack id: Int64?) {
        if stage != nil, trackId == id { return }
        if stage != nil { reset() }
        let defaults = UserDefaults.standard
        let demoAlong = id == nil && defaults.bool(forKey: "ToolMapPrintAlong")
        isAlongTrack = id != nil || demoAlong
        trackId = id
        var next = lastTemplate() ?? PrintTemplate(grid: PrintController.gridFor(app.settings.coordinates))
        next.atlas = isAlongTrack ? .track : .single
        next.title = defaultTitle()
        next.subtitle = ""
        template = next
        center = app.center
        path = []
        plan = nil
        missingTiles = nil
        missingLeaves = []
        job = nil
        outcome = nil
        sheetPage = .main
        stage = .setup
        if let id {
            planning = true
            Task { @MainActor in
                let points = await PrintLibraryBridge.trackPath(self.app, id)
                guard self.stage != nil, self.trackId == id else { return }
                // As Android: a track of fewer than two points is not printed along.
                if points.count < 2 {
                    self.close()
                    return
                }
                PrintLibraryBridge.showTrack(self.app, id)
                self.startAlong(points)
            }
        } else if demoAlong {
            startAlong(PrintController.demoPath)
        } else {
            requestPlan()
        }
        requestFit()
        applyLaunchStage()
    }

    /// « Choisir la zone »: the frame on the map.
    func chooseZone() {
        guard stage != nil else { return }
        stage = .frame
        sheetPage = .main
        requestFit()
    }

    /// « Suivant »: the confirmation, the frame stays where it is.
    func confirm() {
        guard stage == .frame else { return }
        stage = .confirm
        sheetPage = .main
        requestFit()
    }

    /// « Retour à la zone ».
    func backToFrame() {
        guard stage == .confirm, job == nil else { return }
        stage = .frame
        sheetPage = .main
        requestFit()
    }

    /// Annuler, ✕: the print is left, its settings remembered for the next one.
    func close() {
        reset()
        if case .print? = app.tool {
            app.tool = nil
        }
    }

    /// Everything of the print forgotten but the tool itself.
    private func reset() {
        jobToken?.cancel()
        jobToken = nil
        planTask?.cancel()
        fitTask?.cancel()
        if stage != nil { remember(template) }
        stage = nil
        plan = nil
        planning = false
        job = nil
        outcome = nil
        missingTiles = nil
        missingLeaves = []
        path = []
        trackId = nil
        isAlongTrack = false
        pendingFit = false
        sheetPage = .main
        app.mapFeatures[PrintLayers.id] = nil
    }

    /// The tool panel went away without « Annuler » (another tool took its place). A page or a preview presented over
    /// the map also makes the panel disappear for a while: the print stays open then.
    func toolDismissed() {
        if case .print? = app.tool { return }
        if stage != nil { close() }
    }

    func openPage(_ page: PrintSheetPage) {
        guard stage == .confirm else { return }
        sheetPage = page
    }

    // MARK: The frame and the settings

    /// The frame was dragged to a new centre.
    func moveFrame(to position: LonLat) {
        guard stage == .frame, !isAlongTrack else { return }
        center = position
        requestPlan()
    }

    /// The maps on screen changed: they are the ones printed.
    func layersChanged() {
        guard stage != nil else { return }
        requestPlan()
    }

    /// A setting changed; a new paper, orientation or scale keeps the centre and fits the zoom again while the sheet is
    /// chosen and while the frame is placed.
    func update(_ change: (inout PrintTemplate) -> Void) {
        var next = template
        change(&next)
        next.atlas = isAlongTrack ? .track : .single
        let resized = next.page != template.page || next.scale != template.scale
        template = next
        if resized, stage == .setup || stage == .frame {
            requestFit()
        }
        requestPlan()
    }

    /// ⟲: portrait ↔ landscape.
    func rotate() {
        update { $0.page.landscape.toggle() }
    }

    // MARK: The plan

    private func startAlong(_ points: [LonLat]) {
        path = points
        let lats = points.map(\.lat)
        let lons = points.map(\.lon)
        let lat = ((lats.min() ?? 0) + (lats.max() ?? 0)) / 2
        let lon = ((lons.min() ?? 0) + (lons.max() ?? 0)) / 2
        center = LonLat(lon: lon, lat: lat)
        requestPlan()
        requestFit()
    }

    /// Plans again after a short pause (typing, dragging), off the main thread, then counts the missing tiles.
    private func requestPlan() {
        guard stage != nil else { return }
        if isAlongTrack, path.count < 2 { return }
        planTask?.cancel()
        let template = self.template
        let area = PrintArea(centerLat: center.lat, centerLon: center.lon, path: path)
        let stack = printStack()
        let sources = sourceTable(stack)
        let scaling = app.settings.maps.scaling
        planning = true
        missingTiles = nil
        planTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            let computed = await PrintController.background {
                try? PrintPlanner.plan(template, area, stack) { sources[$0] }
            }
            if Task.isCancelled || self.stage == nil { return }
            self.plan = computed
            self.publishPages()
            if self.pendingFit { self.performFit() }
            let missing = await PrintController.background { PrintTiles.missingLeaves(computed, scaling: scaling) }
            if Task.isCancelled || self.stage == nil { return }
            self.missingLeaves = missing
            self.missingTiles = missing.count
            self.planning = false
            self.exportForLaunchIfAsked()
        }
    }

    /// The layers of the map, bottom to top (Android `state.stack.layers`): the planner keeps the visible ones, at the
    /// zooms of their ranges, with their opacity, colours and elevation bands.
    private func printStack() -> [LayerConfig] {
        app.maps.stack.layers
    }

    /// The sources the planner may ask for: the maps of the stack and the elevations of an elevation band.
    private func sourceTable(_ stack: [LayerConfig]) -> [String: TileSource] {
        var table: [String: TileSource] = [:]
        var ids = stack.map(\.sourceId)
        ids.append(LayerElevation.demSourceId)
        for id in ids {
            if let source = app.maps.source(id) {
                table[id] = source
            }
        }
        return table
    }

    /// The pages of a print along a track, drawn by `PrintLayers` (T3b).
    private func publishPages() {
        guard stage != nil, isAlongTrack, let plan else {
            app.mapFeatures[PrintLayers.id] = nil
            return
        }
        app.mapFeatures[PrintLayers.id] = PrintLayers.geoJson(PrintController.pageCorners(plan))
    }

    /// The four corners on the ground of each map page, clockwise from the top left.
    static func pageCorners(_ plan: PrintPlan) -> [[LonLat]] {
        plan.mapPages.map { page in
            let f = page.frame
            return [
                f.toLonLat(0.0, 0.0),
                f.toLonLat(f.frameWidthMm, 0.0),
                f.toLonLat(f.frameWidthMm, f.frameHeightMm),
                f.toLonLat(0.0, f.frameHeightMm),
            ]
        }
    }

    // MARK: The camera

    /// Fits the frame (or the pages along the track) to the map once the panel has its height.
    func requestFit() {
        fitTask?.cancel()
        fitTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 150_000_000)
            if Task.isCancelled { return }
            self.performFit()
        }
    }

    /// The frame takes about 70 % of the map left free while it is placed (P17, Q4), north up; on the sheet choice
    /// and on the confirmation it is fitted to the map above the panel, with room on the sides for the round buttons.
    private func performFit() {
        guard let stage else { return }
        let size = app.mapProxy.size
        guard size.width > 0, size.height > 0 else {
            pendingFit = true
            return
        }
        let safeTop = Double(app.mapProxy.mapView?.safeAreaInsets.top ?? 47)
        let height = Double(size.height)
        let width = Double(size.width)
        let top = safeTop + 56
        let measured = Double(panelTop)
        let panel = measured > top + 120 && measured <= height ? measured : height * 0.6
        let bottom = panel - 12
        let free = Swift.max(bottom - top, 120)
        let availableWidth: Double
        let availableHeight: Double
        if stage == .frame {
            availableWidth = width * 0.7
            availableHeight = free * 0.7
        } else {
            availableWidth = Swift.max(width - 2 * 72, 120)
            availableHeight = Swift.max(free - 24, 120)
        }
        var fit: CameraFit
        if isAlongTrack {
            guard let plan, plan.template.page == template.sanitized().page, plan.template.scale == template.sanitized().scale
            else {
                pendingFit = true
                return
            }
            let corners = PrintController.pageCorners(plan).flatMap { $0 }
            guard let fitted = PrintViewport.fit(corners, availableWidth, availableHeight) else { return }
            fit = fitted
        } else {
            let zoom = PrintViewport.zoomToFit(template.page, template.scale, center.lat, availableWidth, availableHeight)
            fit = CameraFit(lat: center.lat, lon: center.lon, zoom: zoom)
        }
        pendingFit = false
        guard fit.zoom.isFinite, fit.lat.isFinite, fit.lon.isFinite else { return }
        // The middle of the free room is `shift` points above the middle of the screen: the camera goes that far south.
        let shift = height / 2 - (top + bottom) / 2
        let metersPerPoint = ScaleBar.metersPerDp(fit.lat, fit.zoom)
        let lat = fit.lat - shift * metersPerPoint / 111_320
        fit = CameraFit(lat: lat, lon: fit.lon, zoom: fit.zoom)
        fly(fit)
    }

    /// Moves the camera there, north up (the print is always north up).
    private func fly(_ fit: CameraFit) {
        let target = CLLocationCoordinate2D(latitude: fit.lat, longitude: fit.lon)
        let wasFollowing = app.followMode != .free
        app.followMode = .free
        guard let mapView = app.mapProxy.mapView else {
            app.show(LonLat(lon: fit.lon, lat: fit.lat), zoom: fit.zoom)
            return
        }
        let zoom = Swift.min(Swift.max(fit.zoom, 0), mapView.maximumZoomLevel)
        // The map leaves the following of the position first, or it would take the camera back.
        let delay = wasFollowing ? 0.1 : 0
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            mapView.setCenter(target, zoomLevel: zoom, direction: 0, animated: true)
        }
    }

    // MARK: Downloading what is missing

    /// « Télécharger »: the missing tiles of the plan, at most `maxDownloadTiles`, then they are counted again.
    func downloadMissing() {
        guard job == nil, stage != nil else { return }
        if app.offline {
            app.say("Le mode hors ligne est actif : repassez en ligne pour télécharger.")
            return
        }
        let leaves = missingLeaves
        if leaves.isEmpty {
            app.say("Rien à télécharger.")
            return
        }
        let counted = plan
        let scaling = app.settings.maps.scaling
        let token = PrintJobToken()
        jobToken = token
        job = .downloading(done: 0, total: leaves.count)
        Task { @MainActor in
            let result = await PrintController.background {
                PrintTileDownload.run(leaves, token: token) { done, total in
                    DispatchQueue.main.async {
                        if self.jobToken === token {
                            self.job = .downloading(done: done, total: total)
                        }
                    }
                }
            }
            if self.jobToken === token {
                self.jobToken = nil
                self.job = nil
            }
            if !token.isCancelled {
                self.app.say(
                    "Tuiles téléchargées : \(result.downloaded). Absentes chez le serveur : \(result.absent). Échecs : \(result.failed)."
                )
            }
            // Whatever happened, some tiles may now be stored.
            guard self.stage != nil else { return }
            self.missingTiles = nil
            let missing = await PrintController.background { PrintTiles.missingLeaves(counted, scaling: scaling) }
            guard self.stage != nil, !self.planning else { return }
            self.missingLeaves = missing
            self.missingTiles = missing.count
        }
    }

    func cancelJob() {
        jobToken?.cancel()
    }

    // MARK: Exporting

    /// « Créer le PDF »: written in Application Support/prints, then « PDF enregistré » with Ouvrir and Partager.
    func export() {
        guard let plan, plan.canExport, !planning, job == nil else { return }
        remember(plan.template)
        let token = PrintJobToken()
        jobToken = token
        job = .exporting(fraction: 0)
        outcome = nil
        let url = pdfURL()
        let dressing = plan.template.dressing
        let scaling = app.settings.maps.scaling
        Task { @MainActor in
            var items: PrintItems?
            if dressing.tracks || dressing.waypoints {
                items = await PrintLibraryBridge.printItems(self.app)
            }
            let content = PrintWords.content(plan, items: items)
            let result = await PrintController.background {
                PrintExportRun.run(plan, content, url, token, scaling) { progress in
                    DispatchQueue.main.async {
                        if self.jobToken === token, case .exporting? = self.job {
                            self.job = .exporting(fraction: progress.fraction)
                        }
                    }
                }
            }
            guard self.jobToken === token else { return }
            self.jobToken = nil
            self.job = nil
            switch result {
            case let .done(done):
                self.outcome = PrintOutcome(
                    kind: .exported(url: url, pages: done.pages, bytes: done.bytes, missingTiles: done.missingTiles)
                )
            case .cancelled:
                self.app.say("Export annulé.")
            case let .failed(message):
                self.outcome = PrintOutcome(kind: .failed(message))
            }
        }
    }

    /// The name proposed for the PDF: `ToolMap_<title>_1-25000_2026-10-29.pdf`, made unique in the folder.
    private func pdfURL() -> URL {
        let folder = AppFiles.folder("prints")
        let name = suggestedFileName()
        let base = (name as NSString).deletingPathExtension
        var url = folder.appendingPathComponent(name)
        var number = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent("\(base)-\(number).pdf")
            number += 1
        }
        return url
    }

    func suggestedFileName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        let iso = formatter.string(from: Date())
        // The default title ends with today's date, which the name already carries in ISO form.
        let today = PrintWords.shortDate(Date())
        var title = template.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.hasSuffix(today) {
            title = String(title.dropLast(today.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if title.hasSuffix("·") {
            title = String(title.dropLast()).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return PrintFormat.fileName(title, template.scale, iso)
    }

    // MARK: The last print

    private static let lastFile = "last.json"

    private func lastTemplate() -> PrintTemplate? {
        let url = AppFiles.folder("print").appendingPathComponent(PrintController.lastFile)
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        return PrintTemplateCodec.decode(text)
    }

    private func remember(_ template: PrintTemplate) {
        let url = AppFiles.folder("print").appendingPathComponent(PrintController.lastFile)
        guard let text = try? PrintTemplateCodec.encode(template) else { return }
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }

    /// « IGN Cartes · 29/10/2026 »: the map printed and the day (P17, Q20).
    private func defaultTitle() -> String {
        var parts: [String] = []
        if let first = app.maps.stack.layers.first(where: { $0.visible }), let name = app.maps.source(first.sourceId)?.name {
            parts.append(name)
        }
        parts.append(PrintWords.shortDate(Date()))
        return parts.joined(separator: " · ")
    }

    /// The grid of the first print: the one of the coordinate format of the settings (P17, Q8).
    static func gridFor(_ coordinates: CoordinateSystem) -> GridKind {
        switch coordinates {
        case .dd: .wgs84Decimal
        case .ddm, .dms: .wgs84
        case .utm: .utm
        case .mgrs: .mgrs
        case .lambert93: .lambert93
        }
    }

    // MARK: Background work

    /// Runs `work` on a background queue (plans, tile counts, the export), off the cooperative pool: the tile reads
    /// block on the network.
    static func background<T>(_ work: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: work())
            }
        }
    }

    // MARK: Launch arguments (CI screenshots)

    /// Debug only: `-ToolMapPrintStage frame|confirm` opens that step, `-ToolMapPrintExport YES` makes the PDF once the
    /// plan is ready, `-ToolMapPrintAlong YES` prints along a demo track (no library needed).
    private func applyLaunchStage() {
        guard !launchStageApplied else { return }
        launchStageApplied = true
        let defaults = UserDefaults.standard
        // `-ToolMapPrintScale 50000 -ToolMapPrintGrid UTM`: the PDF that checks the scale (10 km = 200 mm at 1:50 000).
        let scale = defaults.double(forKey: "ToolMapPrintScale")
        let grid = defaults.string(forKey: "ToolMapPrintGrid").flatMap { GridKind(rawValue: $0) }
        if scale > 0 || grid != nil {
            update { template in
                if scale > 0 { template.scale = scale }
                if let grid {
                    template.grid = grid
                    template.gridStep = nil
                }
            }
        }
        switch defaults.string(forKey: "ToolMapPrintStage") {
        case "frame":
            chooseZone()
        case "confirm":
            chooseZone()
            confirm()
        default:
            break
        }
        launchExportPending = defaults.bool(forKey: "ToolMapPrintExport")
    }

    private func exportForLaunchIfAsked() {
        guard launchExportPending, stage == .confirm, let plan, plan.canExport else { return }
        launchExportPending = false
        export()
    }

    /// A made-up walk east of Chambéry, for the screenshot of the pages along a track.
    static let demoPath: [LonLat] = [
        LonLat(lon: 5.9178, lat: 45.5646),
        LonLat(lon: 5.9420, lat: 45.5712),
        LonLat(lon: 5.9690, lat: 45.5655),
        LonLat(lon: 5.9930, lat: 45.5790),
        LonLat(lon: 6.0180, lat: 45.5905),
        LonLat(lon: 6.0420, lat: 45.5850),
    ]
}

/// How the export of a PDF ended, from the background queue.
enum PrintExportRun {
    enum Result {
        case done(PrintResult)
        case cancelled
        case failed(String?)
    }

    /// Writes the PDF of `plan` to `url` with the core's exporter; a cancelled or failed PDF is removed.
    static func run(
        _ plan: PrintPlan,
        _ content: PrintContent,
        _ url: URL,
        _ token: PrintJobToken,
        _ scaling: ScalingPolicy,
        progress: @escaping (PrintProgress) -> Void
    ) -> Result {
        do {
            let output = try PdfFileOutput(url: url)
            let renderer = PrintBandRenderer(fetcher: PrintTileFetcher(scaling: scaling))
            let exporter = PrintExporter(renderer, ensureActive: {
                if token.isCancelled { throw CancellationError() }
            })
            let result: PrintResult
            do {
                result = try exporter.export(plan, content, output, onProgress: progress)
            } catch {
                try? output.close()
                throw error
            }
            try output.close()
            return .done(result)
        } catch is CancellationError {
            try? FileManager.default.removeItem(at: url)
            return .cancelled
        } catch let error as ToolMapError {
            try? FileManager.default.removeItem(at: url)
            return .failed(error.message)
        } catch {
            try? FileManager.default.removeItem(at: url)
            return .failed(error.localizedDescription)
        }
    }
}
