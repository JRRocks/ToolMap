import MapLibre
import SwiftUI
import ToolMapCore

/// The MapLibre Native map. Every ToolMap source is a raster source on the fake tile host, served by `TileURLProtocol`.
struct MapLibreView: UIViewRepresentable {
    let model: AppModel
    /// Passed as values so that SwiftUI calls `updateUIView` when they change.
    let layers: [LayerSpec]
    let nightMode: Bool
    let cameraRequest: CameraRequest?
    let followMode: FollowMode

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
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.wanted = MapStyle.Wanted(layers: layers, nightMode: nightMode)
        coordinator.applyStyle()
        if let request = cameraRequest {
            coordinator.apply(request)
        }
        if model.location.isAuthorized, !mapView.showsUserLocation {
            mapView.showsUserLocation = true
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
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        let model: AppModel
        weak var mapView: MLNMapView?
        var wanted = MapStyle.Wanted(layers: [], nightMode: false)
        private var applied: MapStyle.Wanted?
        private var styleLoaded = false
        private var lastRequest: UUID?

        init(model: AppModel) {
            self.model = model
        }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            styleLoaded = true
            applied = nil
            applyStyle()
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
            case .resetNorth:
                mapView.setDirection(0, animated: true)
            }
        }

        /// Brings the style to `wanted`: opacity and night mode in place, a new layer list by rebuilding the layers.
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
                for spec in wanted.layers {
                    let source = MLNRasterTileSource(
                        identifier: MapStyle.sourceId(spec.sourceId),
                        tileURLTemplates: [TileAddress.template(for: spec.sourceId)],
                        options: [
                            .minimumZoomLevel: NSNumber(value: spec.minZoom),
                            .maximumZoomLevel: NSNumber(value: spec.maxZoom),
                            .tileSize: NSNumber(value: 256),
                        ]
                    )
                    style.addSource(source)
                    style.addLayer(MLNRasterStyleLayer(identifier: MapStyle.layerId(spec.sourceId), source: source))
                }
            }
            for spec in wanted.layers {
                guard let layer = style.layer(withIdentifier: MapStyle.layerId(spec.sourceId)) as? MLNRasterStyleLayer
                else { continue }
                layer.rasterOpacity = NSExpression(forConstantValue: NSNumber(value: spec.opacity))
                // Night mode (Android M9): the maps are darkened to 40 % of their brightness, not the tracks.
                layer.maximumRasterBrightness = NSExpression(forConstantValue: NSNumber(value: wanted.nightMode ? 0.4 : 1))
            }
            applied = wanted
        }
    }
}

enum MapStyle {
    struct Wanted: Equatable {
        let layers: [LayerSpec]
        let nightMode: Bool
    }

    static func sourceId(_ id: String) -> String { "toolmap-source-\(id)" }
    static func layerId(_ id: String) -> String { "toolmap-layer-\(id)" }

    /// Same maps in the same order with the same zoom ranges: only paint properties changed.
    static func sameLayers(_ a: [LayerSpec], _ b: [LayerSpec]) -> Bool {
        a.count == b.count && zip(a, b).allSatisfy {
            $0.sourceId == $1.sourceId && $0.minZoom == $1.minZoom && $0.maxZoom == $1.maxZoom
        }
    }

    /// A style with a plain background only; the ToolMap layers are added once it is loaded.
    static let emptyStyleURL: URL = {
        let json = """
        {"version":8,"name":"ToolMap","sources":{},"layers":[{"id":"background","type":"background",\
        "paint":{"background-color":"#E8ECE6"}}]}
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("toolmap-empty-style.json")
        try? json.data(using: .utf8)?.write(to: url)
        return url
    }()
}
