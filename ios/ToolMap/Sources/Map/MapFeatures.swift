import MapLibre
import UIKit
import ToolMapCore

/// A GeoJSON source and the MapLibre layers drawn from it, above the maps (Android: `TrackOverlay`, `MeasureLayers`…).
/// A feature controller puts its GeoJSON text in `AppModel.mapFeatures[id]`; the map view installs the layers once per
/// style and swaps the data when the text changes.
struct FeatureLayerSet {
    /// Key in `AppModel.mapFeatures` and identifier of the GeoJSON source.
    let id: String
    /// Layers that answer a tap, topmost first; a tap gives `AppModel.tapHandlers[id]` the feature's properties.
    var tappableLayers: [String] = []
    /// Adds the layers of the set; the (empty) source is already in the style.
    let install: (MLNStyle, MLNShapeSource) -> Void
    /// Called before new data is shown, for instance to add the images that the data names.
    var prepare: ((MLNStyle, String) -> Void)?

    init(
        id: String,
        tappableLayers: [String] = [],
        install: @escaping (MLNStyle, MLNShapeSource) -> Void,
        prepare: ((MLNStyle, String) -> Void)? = nil
    ) {
        self.id = id
        self.tappableLayers = tappableLayers
        self.install = install
        self.prepare = prepare
    }

    /// An empty feature collection.
    static let empty = #"{"type":"FeatureCollection","features":[]}"#

    /// The shape of a GeoJSON text, or `nil` when MapLibre cannot read it.
    static func shape(_ geoJson: String) -> MLNShape? {
        try? MLNShape(data: Data(geoJson.utf8), encoding: String.Encoding.utf8.rawValue)
    }
}

/// Every set of feature layers, bottom to top (above the maps).
enum MapFeatureRegistry {
    static let all: [FeatureLayerSet] =
        GridLayers.sets + ZoneLayers.sets + PrintLayers.sets + TrackLayers.sets + TrailLayers.sets + RouteLayers.sets
        + EditLayers.sets + MeasureLayers.sets + ShareLayers.sets
}

/// A tap on a feature of the map.
struct FeatureTap {
    /// The `FeatureLayerSet.id` of the feature.
    let setId: String
    /// The layer that was tapped.
    let layerId: String
    /// The GeoJSON properties of the feature (`id`, `name`…), as MapLibre gives them back.
    let properties: [String: Any]
    /// Where the finger was.
    let position: LonLat
}

/// The map view seen from the interface: conversions between positions and points of the screen, and the visible
/// area. Not observed: views that place things on the map also read `AppModel.center` and `zoom`, so that they are
/// drawn again when the camera moves.
final class MapProxy {
    weak var mapView: MLNMapView?

    /// The point of the map view where `position` is, in points.
    func point(_ position: LonLat) -> CGPoint? {
        guard let mapView else { return nil }
        return mapView.convert(CLLocationCoordinate2D(latitude: position.lat, longitude: position.lon), toPointTo: mapView)
    }

    /// The position under `point` of the map view.
    func position(_ point: CGPoint) -> LonLat? {
        guard let mapView else { return nil }
        let coordinate = mapView.convert(point, toCoordinateFrom: mapView)
        return LonLat(lon: coordinate.longitude, lat: coordinate.latitude)
    }

    /// Size of the map view, in points.
    var size: CGSize {
        mapView?.bounds.size ?? .zero
    }

    /// The visible area, as west, south, east, north.
    var visibleBounds: Bounds? {
        guard let mapView else { return nil }
        let b = mapView.visibleCoordinateBounds
        return Bounds(minX: b.sw.longitude, minY: b.sw.latitude, maxX: b.ne.longitude, maxY: b.ne.latitude)
    }

    /// Meters on the ground for one point of the screen at the centre.
    var metersPerPoint: Double {
        guard let mapView else { return 0 }
        return mapView.metersPerPoint(atLatitude: mapView.centerCoordinate.latitude)
    }
}
