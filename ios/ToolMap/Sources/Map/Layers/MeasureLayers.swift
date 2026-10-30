import MapLibre
import ToolMapCore
import UIKit

/// The measure tool (Android `MeasureLayers`): the translucent fill, the line (blue, to tell it from the orange of the
/// drawing) and the points, the first green and the last red. `MeasureController` gives each set its GeoJSON
/// (`MeasureGeoJson`).
enum MeasureLayers {
    /// The polygon of the « Surface » mode.
    static let fillId = "measure-fill"
    /// The line through the points, closed in « Surface » mode.
    static let lineId = "measure-line"
    /// The points (`kind`: `a` first, `z` last, `v` the others).
    static let pointsId = "measure-points"

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: fillId, install: MeasureLayers.installFill),
        FeatureLayerSet(id: lineId, install: MeasureLayers.installLine),
        FeatureLayerSet(id: pointsId, install: MeasureLayers.installPoints),
    ]

    private static let color = rgb(0x0277BD)
    private static let green = rgb(0x43A047)
    private static let red = rgb(0xE53935)
    private static let fillOpacity = 0.2
    private static let lineWidth = 4.0
    private static let pointRadius = 6.0
    private static let endRadius = 8.0
    private static let strokeWidth = 2.0

    private static func installFill(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNFillStyleLayer(identifier: "measure-fill-layer", source: source)
        layer.fillColor = NSExpression(forConstantValue: color)
        layer.fillOpacity = NSExpression(forConstantValue: NSNumber(value: fillOpacity))
        style.addLayer(layer)
    }

    private static func installLine(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNLineStyleLayer(identifier: "measure-line-layer", source: source)
        layer.lineColor = NSExpression(forConstantValue: color)
        layer.lineWidth = NSExpression(forConstantValue: NSNumber(value: lineWidth))
        layer.lineCap = NSExpression(forConstantValue: "round")
        layer.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(layer)
    }

    /// One circle layer per kind of point: the plain ones below, the two ends above them.
    private static func installPoints(_ style: MLNStyle, _ source: MLNShapeSource) {
        let middle = NSPredicate(format: "kind != %@ AND kind != %@", "a", "z")
        style.addLayer(circles("measure-points-layer", source, middle, UIColor.white, pointRadius))
        let first = NSPredicate(format: "kind == %@", "a")
        style.addLayer(circles("measure-first-layer", source, first, green, endRadius))
        let last = NSPredicate(format: "kind == %@", "z")
        style.addLayer(circles("measure-last-layer", source, last, red, endRadius))
    }

    private static func circles(
        _ identifier: String,
        _ source: MLNShapeSource,
        _ predicate: NSPredicate,
        _ fill: UIColor,
        _ radius: Double
    ) -> MLNCircleStyleLayer {
        let layer = MLNCircleStyleLayer(identifier: identifier, source: source)
        layer.predicate = predicate
        layer.circleRadius = NSExpression(forConstantValue: NSNumber(value: radius))
        layer.circleColor = NSExpression(forConstantValue: fill)
        layer.circleStrokeColor = NSExpression(forConstantValue: color)
        layer.circleStrokeWidth = NSExpression(forConstantValue: NSNumber(value: strokeWidth))
        return layer
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
