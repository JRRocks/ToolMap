import MapLibre
import ToolMapCore
import UIKit

/// The coordinate grid over the map (Android `GridLayers`): lines as heavy as their `level` (`GridLineLevel`), the
/// edges of the UTM and MGRS grid zones darker and heavier, solid even when the grid is dashed (P24.2), and the names
/// of the zones and squares (P24.3, ADR-073) in the font made on the device. `GridController` gives the lines with a
/// `dash` property (1: « Style des grilles » dashed) next to `level`: a dash pattern cannot follow the data, so the
/// dashed grid is a second layer that takes the lines the solid one leaves.
enum GridLayers {
    /// The lines (`ScreenGridPlan.geoJson` with `dash`).
    static let linesId = "grid"
    /// The names of the zones and squares (`ScreenGridPlan.labelsGeoJson`).
    static let labelsId = "grid-labels"

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: linesId, install: GridLayers.installLines),
        FeatureLayerSet(id: labelsId, install: GridLayers.installLabels),
    ]

    private static let color = rgb(0x455A64)
    private static let edgeColor = rgb(0x1C2429)
    private static let textColor = rgb(0x263238)
    private static let opacity = 0.75
    private static let dashOn = 3.0
    private static let dashOff = 2.0

    // Widths in points. WGS84, Lambert 93 and UTM under 1 km as before P24; UTM and MGRS from 1 km and the zone edges
    // as in the validated preview (P24.0, variant A, `docs/previews/p24-mgrs.html`).
    private static let minorWidth = 1.0
    private static let majorWidth = 2.2
    private static let stepWidth = 0.8
    private static let tenKmWidth = 1.5
    private static let squareWidth = 2.3
    private static let edgeWidth = 3.2

    // The names as in the validated preview: dark grey with a white halo, the zone larger, the letters of a square in
    // the regular weight; « 31T GL » just above the middle of its square (clear of the centre ring), « GL » below.
    private static let haloWidth = 1.5
    private static let haloBlur = 0.4
    private static let textSize = 12.0
    private static let zoneTextSize = 14.0
    private static let nameRaiseEms = -1.2
    private static let squareDropEms = 1.3

    private static func installLines(_ style: MLNStyle, _ source: MLNShapeSource) {
        let edge = GridLineLevel.zoneEdge
        let solid = MLNLineStyleLayer(identifier: "grid-lines-layer", source: source)
        solid.predicate = NSPredicate(format: "level != \(edge) AND dash != 1")
        paintGrid(solid)
        style.addLayer(solid)

        let dashed = MLNLineStyleLayer(identifier: "grid-dashed-layer", source: source)
        dashed.predicate = NSPredicate(format: "level != \(edge) AND dash == 1")
        paintGrid(dashed)
        dashed.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: dashOn), NSNumber(value: dashOff)])
        style.addLayer(dashed)

        let edges = MLNLineStyleLayer(identifier: "grid-edges-layer", source: source)
        edges.predicate = NSPredicate(format: "level == \(edge)")
        edges.lineColor = NSExpression(forConstantValue: edgeColor)
        edges.lineWidth = NSExpression(forConstantValue: NSNumber(value: edgeWidth))
        style.addLayer(edges)
    }

    private static func paintGrid(_ layer: MLNLineStyleLayer) {
        layer.lineColor = NSExpression(forConstantValue: color)
        layer.lineOpacity = NSExpression(forConstantValue: NSNumber(value: opacity))
        layer.lineWidth = widthByLevel()
    }

    /// The width of a line from its `level`.
    private static func widthByLevel() -> NSExpression {
        var widths: [NSExpression: NSExpression] = [:]
        widths[constant(GridLineLevel.major)] = constant(majorWidth)
        widths[constant(GridLineLevel.step)] = constant(stepWidth)
        widths[constant(GridLineLevel.tenKm)] = constant(tenKmWidth)
        widths[constant(GridLineLevel.square)] = constant(squareWidth)
        return NSExpression(
            forMLNMatchingKey: NSExpression(forKeyPath: "level"),
            in: widths,
            default: constant(minorWidth)
        )
    }

    /// One symbol layer per kind of name: « 31T GL », « 31T », « GL ».
    private static func installLabels(_ style: MLNStyle, _ source: MLNShapeSource) {
        style.addLayer(
            labels("grid-names-layer", source, GridLabelKind.name, GlyphServer.font, textSize, nameRaiseEms)
        )
        style.addLayer(
            labels("grid-zones-layer", source, GridLabelKind.zone, GlyphServer.font, zoneTextSize, 0)
        )
        style.addLayer(
            labels("grid-squares-layer", source, GridLabelKind.square, GlyphServer.regularFont, textSize, squareDropEms)
        )
    }

    private static func labels(
        _ identifier: String,
        _ source: MLNShapeSource,
        _ kind: String,
        _ font: String,
        _ size: Double,
        _ offsetEms: Double
    ) -> MLNSymbolStyleLayer {
        let layer = MLNSymbolStyleLayer(identifier: identifier, source: source)
        layer.predicate = NSPredicate(format: "kind == %@", kind)
        layer.text = NSExpression(forKeyPath: "text")
        layer.textFontNames = NSExpression(forConstantValue: [font])
        layer.textFontSize = NSExpression(forConstantValue: NSNumber(value: size))
        layer.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: offsetEms)))
        layer.textColor = NSExpression(forConstantValue: textColor)
        layer.textHaloColor = NSExpression(forConstantValue: UIColor.white)
        layer.textHaloWidth = NSExpression(forConstantValue: NSNumber(value: haloWidth))
        layer.textHaloBlur = NSExpression(forConstantValue: NSNumber(value: haloBlur))
        // A name of the grid never hides a waypoint's or a member's name (it may run over one).
        layer.textAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: false))
        layer.textIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        return layer
    }

    private static func constant(_ value: Int) -> NSExpression {
        NSExpression(forConstantValue: NSNumber(value: value))
    }

    private static func constant(_ value: Double) -> NSExpression {
        NSExpression(forConstantValue: NSNumber(value: value))
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
