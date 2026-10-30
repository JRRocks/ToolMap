import MapLibre
import SwiftUI
import ToolMapCore

/// The MapLibre Native map. Every ToolMap source is a raster source on the fake tile host, served by `TileURLProtocol`;
/// tracks, measures and the other features are GeoJSON sources above them (`MapFeatureRegistry`).
struct MapLibreView: UIViewRepresentable {
    let model: AppModel
    /// Passed as values so that SwiftUI calls `updateUIView` when they change.
    let layers: [LayerSpec]
    let nightMode: Bool
    let cameraRequest: CameraRequest?
    let followMode: FollowMode
    let features: [String: String]
    /// Gestures off (Android M3 « Verrouiller »).
    let locked: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(model: model)
    }

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero, styleURL: MapStyle.emptyStyleURL)
        mapView.delegate = context.coordinator
        mapView.logoView.isHidden = true
        mapView.attributionButton.isHidden = true
        mapView.compassView.isHidden = true
        mapView.showsScale = false
        mapView.maximumZoomLevel = 20
        mapView.setCenter(
            CLLocationCoordinate2D(latitude: model.center.lat, longitude: model.center.lon),
            zoomLevel: model.zoom,
            animated: false
        )
        context.coordinator.mapView = mapView
        model.mapProxy.mapView = mapView
        context.coordinator.addGestures(to: mapView)
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.wanted = MapStyle.Wanted(layers: layers, nightMode: nightMode)
        coordinator.wantedFeatures = features
        coordinator.applyStyle()
        coordinator.applyFeatures()
        if let request = cameraRequest {
            coordinator.apply(request)
        }
        // The position marker follows the « Position en temps réel » switch.
        let puck = model.location.isOn && model.location.isAuthorized
        if mapView.showsUserLocation != puck {
            mapView.showsUserLocation = puck
        }
        let tracking: MLNUserTrackingMode
        switch followMode {
        case .free: tracking = .none
        case .position: tracking = .follow
        case .heading: tracking = .followWithHeading
        }
        if mapView.userTrackingMode != tracking {
            mapView.setUserTrackingMode(tracking, animated: true, completionHandler: nil)
        }
        mapView.isScrollEnabled = !locked
        mapView.isZoomEnabled = !locked
        mapView.isRotateEnabled = !locked
        mapView.isPitchEnabled = false
    }

    final class Coordinator: NSObject, MLNMapViewDelegate, UIGestureRecognizerDelegate {
        let model: AppModel
        weak var mapView: MLNMapView?
        var wanted = MapStyle.Wanted(layers: [], nightMode: false)
        var wantedFeatures: [String: String] = [:]
        private var applied: MapStyle.Wanted?
        private var appliedFeatures: [String: String] = [:]
        private var featureSources: [String: MLNShapeSource] = [:]
        private var styleLoaded = false
        private var lastRequest: UUID?

        init(model: AppModel) {
            self.model = model
        }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            styleLoaded = true
            applied = nil
            appliedFeatures = [:]
            featureSources = [:]
            installFeatures(style)
            applyStyle()
            applyFeatures()
        }

        /// A gesture stops the following: the panel then shows « Libre ».
        func mapView(_ mapView: MLNMapView, didChange mode: MLNUserTrackingMode, animated: Bool) {
            if mode == .none, model.followMode != .free {
                model.followMode = .free
            }
        }

        func mapViewRegionIsChanging(_ mapView: MLNMapView) {
            report(mapView)
        }

        func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
            report(mapView)
        }

        private func report(_ mapView: MLNMapView) {
            let center = mapView.centerCoordinate
            model.center = LonLat(lon: center.longitude, lat: center.latitude)
            model.zoom = mapView.zoomLevel
            model.direction = mapView.direction
            model.metersPerPoint = mapView.metersPerPoint(atLatitude: center.latitude)
        }

        func apply(_ request: CameraRequest) {
            guard request.id != lastRequest, let mapView else { return }
            lastRequest = request.id
            switch request.kind {
            case let .zoomBy(step):
                mapView.setZoomLevel(mapView.zoomLevel + step, animated: true)
            case let .center(target, zoom):
                mapView.setCenter(
                    CLLocationCoordinate2D(latitude: target.lat, longitude: target.lon),
                    zoomLevel: zoom ?? mapView.zoomLevel,
                    animated: true
                )
            case let .fit(bounds, padding):
                let sw = CLLocationCoordinate2D(latitude: bounds.minY, longitude: bounds.minX)
                let ne = CLLocationCoordinate2D(latitude: bounds.maxY, longitude: bounds.maxX)
                let inset = CGFloat(padding)
                mapView.setVisibleCoordinateBounds(
                    MLNCoordinateBounds(sw: sw, ne: ne),
                    edgePadding: UIEdgeInsets(top: inset, left: inset, bottom: inset, right: inset),
                    animated: true,
                    completionHandler: nil
                )
            case .resetNorth:
                mapView.setDirection(0, animated: true)
            }
        }

        // MARK: Maps

        /// Brings the style to `wanted`: opacity and night mode in place, a new layer list by rebuilding the layers,
        /// always below the features.
        func applyStyle() {
            guard styleLoaded, let style = mapView?.style, wanted != applied else { return }
            let sameLayers = applied.map { MapStyle.sameLayers($0.layers, wanted.layers) } ?? false
            if !sameLayers {
                for spec in applied?.layers ?? [] {
                    if let layer = style.layer(withIdentifier: MapStyle.layerId(spec.sourceId)) {
                        style.removeLayer(layer)
                    }
                    if let source = style.source(withIdentifier: MapStyle.sourceId(spec.sourceId)) {
                        style.removeSource(source)
                    }
                }
                let anchor = style.layer(withIdentifier: MapStyle.anchorLayerId)
                for spec in wanted.layers {
                    let source = MLNRasterTileSource(
                        identifier: MapStyle.sourceId(spec.sourceId),
                        tileURLTemplates: [TileAddress.template(for: spec.sourceId)],
                        options: [
                            .minimumZoomLevel: NSNumber(value: spec.minZoom),
                            .maximumZoomLevel: NSNumber(value: spec.maxZoom),
                            .tileSize: NSNumber(value: MapStyle.declaredTileSize),
                        ]
                    )
                    style.addSource(source)
                    let layer = MLNRasterStyleLayer(identifier: MapStyle.layerId(spec.sourceId), source: source)
                    if let anchor {
                        style.insertLayer(layer, below: anchor)
                    } else {
                        style.addLayer(layer)
                    }
                }
            }
            for spec in wanted.layers {
                guard let layer = style.layer(withIdentifier: MapStyle.layerId(spec.sourceId)) as? MLNRasterStyleLayer
                else { continue }
                spec.paint(layer, nightMode: wanted.nightMode)
            }
            applied = wanted
        }

        // MARK: Features

        /// The empty GeoJSON sources and their layers, above an invisible anchor that the maps go under.
        private func installFeatures(_ style: MLNStyle) {
            let anchor = MLNBackgroundStyleLayer(identifier: MapStyle.anchorLayerId)
            anchor.backgroundOpacity = NSExpression(forConstantValue: NSNumber(value: 0))
            style.addLayer(anchor)
            for set in MapFeatureRegistry.all {
                let source = MLNShapeSource(identifier: set.id, shape: nil, options: nil)
                style.addSource(source)
                featureSources[set.id] = source
                set.install(style, source)
            }
        }

        /// Gives each GeoJSON source its new text, when it changed.
        func applyFeatures() {
            guard styleLoaded, let style = mapView?.style, wantedFeatures != appliedFeatures else { return }
            for set in MapFeatureRegistry.all {
                let text = wantedFeatures[set.id]
                guard text != appliedFeatures[set.id], let source = featureSources[set.id] else { continue }
                if let text {
                    set.prepare?(style, text)
                    source.shape = FeatureLayerSet.shape(text)
                } else {
                    source.shape = nil
                }
            }
            appliedFeatures = wantedFeatures
        }

        // MARK: Gestures

        func addGestures(to mapView: MLNMapView) {
            let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
            for recognizer in mapView.gestureRecognizers ?? [] {
                if let other = recognizer as? UITapGestureRecognizer, other.numberOfTapsRequired == 2 {
                    tap.require(toFail: other)
                }
            }
            tap.delegate = self
            mapView.addGestureRecognizer(tap)
            let press = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
            press.delegate = self
            mapView.addGestureRecognizer(press)
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            true
        }

        @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, let mapView else { return }
            let point = recognizer.location(in: mapView)
            let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
            let position = LonLat(lon: coordinate.longitude, lat: coordinate.latitude)
            // While measuring, a tap adds a point wherever it falls, on a track too (Android `onDrawTap`).
            if model.tool == .measure {
                model.emptyTapHandler?(position)
                return
            }
            // The topmost set first; a finger is about 44 points wide.
            let box = CGRect(x: point.x - 22, y: point.y - 22, width: 44, height: 44)
            for set in MapFeatureRegistry.all.reversed() where !set.tappableLayers.isEmpty {
                // While a tool is open, the library and the other features do not take the taps.
                if model.tool != nil, !set.id.hasPrefix("edit-"), !set.id.hasPrefix("measure-") { continue }
                guard let handler = model.tapHandlers[set.id] else { continue }
                for layerId in set.tappableLayers {
                    let found = mapView.visibleFeatures(in: box, styleLayerIdentifiers: [layerId])
                    if let feature = found.first {
                        handler(FeatureTap(setId: set.id, layerId: layerId, properties: feature.attributes, position: position))
                        return
                    }
                }
            }
            model.emptyTapHandler?(position)
        }

        @objc private func pressed(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began, let mapView else { return }
            let point = recognizer.location(in: mapView)
            let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
            model.longPressHandler?(LonLat(lon: coordinate.longitude, lat: coordinate.latitude))
        }
    }
}

enum MapStyle {
    struct Wanted: Equatable {
        let layers: [LayerSpec]
        let nightMode: Bool
    }

    /// The invisible layer between the maps (below) and the features (above).
    static let anchorLayerId = "toolmap-features-anchor"

    /// Size given to MapLibre for the 256-pixel tiles (ADR-032, Android « Netteté des cartes » at its default, sharp):
    /// one tile pixel on one screen pixel, as AlpineQuest draws its maps, so 256 / 3 points on a 3x screen.
    static let declaredTileSize: Int = {
        let scale = Double(UIScreen.main.scale)
        return Int((256.0 / max(scale, 1)).rounded())
    }()

    static func sourceId(_ id: String) -> String { "toolmap-source-\(id)" }
    static func layerId(_ id: String) -> String { "toolmap-layer-\(id)" }

    /// Same maps in the same order with the same zoom ranges: only paint properties changed.
    static func sameLayers(_ a: [LayerSpec], _ b: [LayerSpec]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy {
            $0.sourceId == $1.sourceId && $0.minZoom == $1.minZoom && $0.maxZoom == $1.maxZoom
        }
    }

    /// A style with a plain background only; the ToolMap layers are added once it is loaded. The glyphs (fonts of the
    /// labels) are made on the device (`GlyphServer`), so that names show offline.
    static let emptyStyleURL: URL = {
        let json = """
        {"version":8,"name":"ToolMap","glyphs":"\(GlyphServer.styleURL)","sources":{},"layers":[\
        {"id":"background","type":"background","paint":{"background-color":"#E8ECE6"}}]}
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("toolmap-empty-style.json")
        try? json.data(using: .utf8)?.write(to: url)
        return url
    }()
}
