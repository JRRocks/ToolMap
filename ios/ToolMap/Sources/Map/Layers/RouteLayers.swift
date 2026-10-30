import MapLibre
import ToolMapCore
import UIKit

/// The target and what is followed (Android `NavLayers`, `AlertLayers`): the circles of the watched waypoints, the line
/// being followed, the dotted line from the position to the target, the way BRouter computed to it (P30.3: white edge,
/// in the colour of the target; grey and dashed while the walker is off it) and a ring on the target. The colours come
/// with the data (`color`, « Couleur de cible » of the settings). `RoutingController` gives each set its GeoJSON.
enum RouteLayers {
    static let alertsId = "route-alerts"
    static let followId = "route-follow"
    static let straightId = "route-straight"
    static let wayId = "route-way"
    static let targetId = "route-target"

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: alertsId, install: RouteLayers.installAlerts),
        FeatureLayerSet(id: followId, install: RouteLayers.installFollow),
        FeatureLayerSet(id: straightId, install: RouteLayers.installStraight),
        FeatureLayerSet(id: wayId, install: RouteLayers.installWay),
        FeatureLayerSet(id: targetId, install: RouteLayers.installTarget),
    ]

    private static let alertOrange = UIColor.routeColor(0xEF6C00)
    private static let paleGrey = UIColor.routeColor(0x9AA6AB)

    /// The radius of each watched waypoint: a translucent disc with a dashed edge.
    private static func installAlerts(_ style: MLNStyle, _ source: MLNShapeSource) {
        let fill = MLNFillStyleLayer(identifier: "route-alerts-fill", source: source)
        fill.fillColor = NSExpression(forConstantValue: alertOrange)
        fill.fillOpacity = NSExpression(forConstantValue: NSNumber(value: 0.12))
        style.addLayer(fill)
        let edge = MLNLineStyleLayer(identifier: "route-alerts-edge", source: source)
        edge.lineColor = NSExpression(forConstantValue: alertOrange)
        edge.lineWidth = NSExpression(forConstantValue: NSNumber(value: 2))
        edge.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 3), NSNumber(value: 2)])
        style.addLayer(edge)
    }

    /// The followed track or route: a wide translucent band along it, under its own line's colour.
    private static func installFollow(_ style: MLNStyle, _ source: MLNShapeSource) {
        let band = MLNLineStyleLayer(identifier: "route-follow-band", source: source)
        band.lineColor = NSExpression(forKeyPath: "color")
        band.lineWidth = NSExpression(forConstantValue: NSNumber(value: 10))
        band.lineOpacity = NSExpression(forConstantValue: NSNumber(value: 0.3))
        band.lineCap = NSExpression(forConstantValue: "round")
        band.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(band)
    }

    /// The straight line from the position to the target, dotted.
    private static func installStraight(_ style: MLNStyle, _ source: MLNShapeSource) {
        let line = MLNLineStyleLayer(identifier: "route-straight-line", source: source)
        line.lineColor = NSExpression(forKeyPath: "color")
        line.lineWidth = NSExpression(forConstantValue: NSNumber(value: 3))
        line.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 1), NSNumber(value: 2)])
        style.addLayer(line)
    }

    /// The way to the target: white edge and the colour of the target; pale and dashed while the walker is off it.
    private static func installWay(_ style: MLNStyle, _ source: MLNShapeSource) {
        let casing = MLNLineStyleLayer(identifier: "route-way-casing", source: source)
        casing.predicate = NSPredicate(format: "pale == NO")
        casing.lineColor = NSExpression(forConstantValue: UIColor.white)
        casing.lineWidth = NSExpression(forConstantValue: NSNumber(value: 7))
        casing.lineCap = NSExpression(forConstantValue: "round")
        casing.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(casing)
        let way = MLNLineStyleLayer(identifier: "route-way-line", source: source)
        way.predicate = NSPredicate(format: "pale == NO")
        way.lineColor = NSExpression(forKeyPath: "color")
        way.lineWidth = NSExpression(forConstantValue: NSNumber(value: 4))
        way.lineCap = NSExpression(forConstantValue: "round")
        way.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(way)
        let pale = MLNLineStyleLayer(identifier: "route-way-pale", source: source)
        pale.predicate = NSPredicate(format: "pale == YES")
        pale.lineColor = NSExpression(forConstantValue: paleGrey)
        pale.lineWidth = NSExpression(forConstantValue: NSNumber(value: 2.5))
        pale.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 1.6), NSNumber(value: 1.2)])
        style.addLayer(pale)
    }

    /// A ring on the target.
    private static func installTarget(_ style: MLNStyle, _ source: MLNShapeSource) {
        let ring = MLNCircleStyleLayer(identifier: "route-target-ring", source: source)
        ring.circleRadius = NSExpression(forConstantValue: NSNumber(value: 12))
        ring.circleColor = NSExpression(forConstantValue: UIColor.clear)
        ring.circleStrokeColor = NSExpression(forKeyPath: "color")
        ring.circleStrokeWidth = NSExpression(forConstantValue: NSNumber(value: 3))
        style.addLayer(ring)
    }
}
