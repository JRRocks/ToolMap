import CoreGraphics
import Foundation
import Observation
import ToolMapCore

/// The coordinate grid over the map (Android `GridLayers` and the grid of `MapScreen`, P9.3, P24): the setting
/// « Grille sur l'écran » (`AppSettings.screenGrid`) drawn for the visible region. The plan follows the camera, at
/// most every 150 ms, and is computed off the main thread; the pills show its system, step and convergence, and the
/// UTM figures (`GridFiguresOverlay`) its kilometre lines.
@Observable
final class GridController {
    @ObservationIgnored unowned let app: AppModel

    /// The grid on screen; nil when there is none.
    private(set) var plan: ScreenGridPlan?
    /// Bottom of the information pills on the screen (global coordinates): the figures of the UTM grid stay below.
    var pillsBottom: CGFloat = 0

    @ObservationIgnored private var started = false
    @ObservationIgnored private var pending = false
    @ObservationIgnored private var lastKey: Key?
    @ObservationIgnored private var generation = 0

    /// Seconds between two plans while the map moves.
    private static let delay = 0.15

    /// Debug only (`-ToolMapGrid UTM`, CI screenshots): the grid shown instead of the setting, which is not changed.
    private let launchKind: GridKind? = UserDefaults.standard.string(forKey: "ToolMapGrid")
        .flatMap { GridKind(rawValue: $0) }

    /// What a plan depends on: when none of it changed, the plan on screen is kept.
    private struct Key: Equatable {
        let kind: GridKind
        let area: Bounds
        let spacing: Double
        let dashed: Bool
    }

    init(app: AppModel) {
        self.app = app
    }

    /// Starts following the camera and the settings; calling it again does nothing.
    func start() {
        guard !started else { return }
        started = true
        observe()
        schedule()
    }

    /// Asks again whenever the camera or a grid setting changes (Observation calls `onChange` once per registration).
    private func observe() {
        withObservationTracking {
            _ = app.center
            _ = app.zoom
            _ = app.direction
            _ = app.settings.screenGrid
            _ = app.settings.canvas.gridDensity
            _ = app.settings.canvas.gridStyle
        } onChange: { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.schedule()
                self.observe()
            }
        }
    }

    /// One plan at most every `delay`: the changes in between are taken by the next one.
    private func schedule() {
        guard !pending else { return }
        pending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + GridController.delay) { [weak self] in
            guard let self else { return }
            self.pending = false
            self.update()
        }
    }

    private func update() {
        let settings = app.settings
        let kind = launchKind ?? settings.screenGrid
        guard kind != GridKind.none, let area = app.mapProxy.visibleBounds else {
            if lastKey != nil || plan != nil {
                lastKey = nil
                generation += 1
                plan = nil
                publish(nil, nil)
            }
            return
        }
        let spacing = settings.canvas.gridDensity.spacing
        let dashed = settings.canvas.gridStyle == GridStyle.dashed
        let key = Key(kind: kind, area: area, spacing: spacing, dashed: dashed)
        if key == lastKey { return }
        lastKey = key
        generation += 1
        let ticket = generation
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let made = ScreenGrid.plan(kind, area, spacing: spacing)
            let lines = GridController.withDash(made.geoJson, dashed: dashed)
            DispatchQueue.main.async {
                guard let self, ticket == self.generation else { return }
                self.plan = made
                self.publish(lines, made.labelsGeoJson)
            }
        }
    }

    /// Puts the lines and the names in the map's GeoJSON, in one change.
    private func publish(_ lines: String?, _ labels: String?) {
        var features = app.mapFeatures
        features[GridLayers.linesId] = lines
        features[GridLayers.labelsId] = labels
        if features != app.mapFeatures {
            app.mapFeatures = features
        }
    }

    /// The lines of `ScreenGrid` with `"dash":1` (dashed) or `"dash":0` next to their `level`, for `GridLayers`.
    static func withDash(_ geoJson: String, dashed: Bool) -> String {
        let flag = dashed ? 1 : 0
        return geoJson.replacingOccurrences(
            of: #""properties":{"level":"#,
            with: #""properties":{"dash":\#(flag),"level":"#
        )
    }
}
