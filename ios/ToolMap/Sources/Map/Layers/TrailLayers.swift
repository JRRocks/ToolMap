import Foundation
import MapLibre
import ToolMapCore
import UIKit

/// The previous positions of the device (Android `TrailLayers`, « Afficher les positions précédentes ») and the end of
/// the track being recorded that is not in the library yet. The stored part of the recording is a track of the
/// library, drawn by the library layers and extended as the recorder appends to it (`TrackDisplay`, P15.8); this line
/// joins its last stored point to the points waiting to be written, so that the line reaches the position at once.
/// `RecorderController` gives both sets their GeoJSON.
enum TrailLayers {
    /// The last positions, a thin blue line under the position marker.
    static let trailId = "position-trail"
    /// The points of the recording not yet written to the library.
    static let recordingId = "recording-tail"

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: trailId, install: TrailLayers.installTrail),
        FeatureLayerSet(id: recordingId, install: TrailLayers.installRecording),
    ]

    private static let trailColor = rgb(0x1E88E5)
    private static let trailOpacity = 0.6
    private static let trailWidth = 3.0
    /// The colour and width a recorded track takes in the library (`PathStyle()`).
    private static let recordingColor = rgb(0xE53935)
    private static let recordingWidth = Double(PathStyle.defaultWidth)

    private static func installTrail(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNLineStyleLayer(identifier: "position-trail-line", source: source)
        layer.lineColor = NSExpression(forConstantValue: trailColor)
        layer.lineOpacity = NSExpression(forConstantValue: NSNumber(value: trailOpacity))
        layer.lineWidth = NSExpression(forConstantValue: NSNumber(value: trailWidth))
        layer.lineCap = NSExpression(forConstantValue: "round")
        layer.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(layer)
    }

    private static func installRecording(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNLineStyleLayer(identifier: "recording-tail-line", source: source)
        layer.lineColor = NSExpression(forConstantValue: recordingColor)
        layer.lineWidth = NSExpression(forConstantValue: NSNumber(value: recordingWidth))
        layer.lineCap = NSExpression(forConstantValue: "round")
        layer.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(layer)
    }

    /// A feature collection of lines; a line of fewer than two points is left out, nil when none is left.
    static func geoJson(_ lines: [[LonLat]]) -> String? {
        var features: [String] = []
        for line in lines where line.count >= 2 {
            let coordinates = line.map { point in
                String(format: "[%.6f,%.6f]", point.lon, point.lat)
            }
            let joined = coordinates.joined(separator: ",")
            features.append(
                "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[\(joined)]}}"
            )
        }
        if features.isEmpty { return nil }
        let body = features.joined(separator: ",")
        return "{\"type\":\"FeatureCollection\",\"features\":[\(body)]}"
    }

    private static func rgb(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}
