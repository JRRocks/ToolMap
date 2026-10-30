import MapLibre
import ToolMapCore
import UIKit

/// The line being drawn and its handles (Android `EditLayers`): the line in the orange of the drawing, the vertices as
/// circles styled by their `kind` (`EditorGeoJson`). A route (P19, `RoutePlanGeoJson`) uses the same sources: its legs
/// carry `st` (`r` routed, `h` by hand: dashed, `p` computing: grey dots, `g` pale), a `label` at the middle of the
/// legs by hand or computing, and stops whose `kind` is `start`, `end`, `named` or `poi` (a coloured disc with a letter
/// or an icon, and the name) or `pass` (a small white disc). Below them the zones to avoid (P19.8): a pale red disc
/// with a dashed edge and a no-entry sign, the circle that follows the crosshair, and the zone under it with a solid
/// edge (P30.4). `DrawController` gives each set its GeoJSON.
enum EditLayers {
    static let zonesId = "edit-zones"
    static let lineId = "edit-line"
    static let elasticId = "edit-elastic"
    static let handlesId = "edit-handles"
    /// The point of the route under the cursor of its elevation profile.
    static let cursorId = "edit-cursor"

    /// The handle images are named after their `icon` with this prefix (`prefixIcons`).
    static let iconPrefix = "edit-icon-"
    static let noEntryImage = "edit-no-entry"

    private static let routedLayer = "edit-line-routed"
    private static let byHandLayer = "edit-line-hand"
    private static let pendingLayer = "edit-line-pending"
    private static let takenLayer = "edit-line-taken"
    private static let labelLayer = "edit-leg-label"
    private static let midpointLayer = "edit-handles-mid"
    private static let vertexLayer = "edit-handles-vertex"
    private static let passLayer = "edit-handles-pass"
    private static let firstLayer = "edit-handles-first"
    private static let lastLayer = "edit-handles-last"
    private static let selectedLayer = "edit-handles-selected"
    private static let passSelectedLayer = "edit-handles-pass-selected"
    private static let discLayer = "edit-handles-disc"
    private static let discSelectedLayer = "edit-handles-disc-sel"

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: zonesId, install: EditLayers.installZones),
        FeatureLayerSet(
            id: lineId,
            tappableLayers: [routedLayer, byHandLayer, pendingLayer],
            install: EditLayers.installLine
        ),
        FeatureLayerSet(id: elasticId, install: EditLayers.installElastic),
        FeatureLayerSet(
            id: handlesId,
            tappableLayers: [
                discSelectedLayer, discLayer, selectedLayer, passSelectedLayer, firstLayer, lastLayer, passLayer, vertexLayer,
                midpointLayer,
            ],
            install: EditLayers.installHandles,
            prepare: EditLayers.addIcons
        ),
        FeatureLayerSet(id: cursorId, install: EditLayers.installCursor),
    ]

    private static let orange = UIColor.routeColor(0xFF6F00)
    private static let grey = UIColor.routeColor(0x9AA0AE)
    private static let zoneRed = UIColor.routeColor(0xD32F2F)
    private static let green = UIColor.routeColor(0x43A047)
    private static let red = UIColor.routeColor(0xE53935)
    private static let lineWidth = 4.0
    /// Half of the 20-point disc plus a gap of 4 points, in ems of the 13-point text.
    private static let labelOffsetEms = 14.0 / 13.0

    // MARK: GeoJSON helpers

    /// The line of a track: `EditorGeoJson.line` with `st` = `r`, drawn by the layer of the routed legs.
    static func trackLine(_ points: [TrackPoint]) -> String {
        EditorGeoJson.line(points).replacingOccurrences(of: #""properties":{}"#, with: #""properties":{"st":"r"}"#)
    }

    /// The handles with their image names: `letter-A` becomes `edit-icon-letter-A`.
    static func prefixIcons(_ geoJson: String) -> String {
        geoJson.replacingOccurrences(of: #""icon":""#, with: #""icon":""# + iconPrefix)
    }

    /// The image names a GeoJSON text uses (`"icon":"edit-icon-…"`).
    static func iconNames(_ geoJson: String) -> Set<String> {
        var names = Set<String>()
        let marker = #""icon":""#
        var rest = geoJson[...]
        while let found = rest.range(of: marker) {
            let after = rest[found.upperBound...]
            guard let end = after.firstIndex(of: "\"") else { break }
            names.insert(String(after[..<end]))
            rest = after[end...]
        }
        return names
    }

    // MARK: Zones

    private static func installZones(_ style: MLNStyle, _ source: MLNShapeSource) {
        style.setImage(noEntrySign(), forName: noEntryImage)
        style.addLayer(zoneFill("edit-zones-fill", source, "zone", 0.16))
        style.addLayer(zoneFill("edit-zones-ring", source, "ring", 0.07))
        style.addLayer(zoneFill("edit-zones-hover", source, "hover", 0.22))
        let edge = MLNLineStyleLayer(identifier: "edit-zones-edge", source: source)
        edge.predicate = NSPredicate(format: "z == %@ OR z == %@", "zone", "ring")
        edge.lineColor = NSExpression(forConstantValue: zoneRed)
        edge.lineWidth = NSExpression(forConstantValue: NSNumber(value: 2))
        edge.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 2.5), NSNumber(value: 2)])
        style.addLayer(edge)
        let hover = MLNLineStyleLayer(identifier: "edit-zones-hover-edge", source: source)
        hover.predicate = NSPredicate(format: "z == %@", "hover")
        hover.lineColor = NSExpression(forConstantValue: zoneRed)
        hover.lineWidth = NSExpression(forConstantValue: NSNumber(value: 2.6))
        style.addLayer(hover)
        let sign = MLNSymbolStyleLayer(identifier: "edit-zones-sign", source: source)
        sign.predicate = NSPredicate(format: "z == %@", "sign")
        sign.iconImageName = NSExpression(forConstantValue: noEntryImage)
        sign.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        sign.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        style.addLayer(sign)
    }

    private static func zoneFill(_ id: String, _ source: MLNShapeSource, _ kind: String, _ opacity: Double) -> MLNFillStyleLayer {
        let layer = MLNFillStyleLayer(identifier: id, source: source)
        layer.predicate = NSPredicate(format: "z == %@", kind)
        layer.fillColor = NSExpression(forConstantValue: zoneRed)
        layer.fillOpacity = NSExpression(forConstantValue: NSNumber(value: opacity))
        return layer
    }

    /// A red disc ringed with white and crossed by a white bar, as the road sign (16 points).
    private static func noEntrySign() -> UIImage {
        let side: CGFloat = 16
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { context in
            let cg = context.cgContext
            cg.setFillColor(UIColor.white.cgColor)
            cg.fillEllipse(in: CGRect(x: 0, y: 0, width: side, height: side))
            cg.setFillColor(zoneRed.cgColor)
            cg.fillEllipse(in: CGRect(x: 1.5, y: 1.5, width: side - 3, height: side - 3))
            cg.setFillColor(UIColor.white.cgColor)
            let half = side / 2
            let bar = half * 0.55
            cg.fill(CGRect(x: half - bar, y: half - 1.25, width: 2 * bar, height: 2.5))
        }
    }

    // MARK: Line

    private static func installLine(_ style: MLNStyle, _ source: MLNShapeSource) {
        let routed = MLNLineStyleLayer(identifier: routedLayer, source: source)
        routed.predicate = NSPredicate(format: "st == %@", "r")
        routed.lineColor = NSExpression(forConstantValue: orange)
        routed.lineWidth = NSExpression(forConstantValue: NSNumber(value: lineWidth))
        routed.lineCap = NSExpression(forConstantValue: "round")
        routed.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(routed)
        // A dash pattern cannot follow the data: each kind of leg has its own layer.
        let byHand = MLNLineStyleLayer(identifier: byHandLayer, source: source)
        byHand.predicate = NSPredicate(format: "st == %@", "h")
        byHand.lineColor = NSExpression(forConstantValue: orange)
        byHand.lineWidth = NSExpression(forConstantValue: NSNumber(value: lineWidth))
        byHand.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 2), NSNumber(value: 1.5)])
        byHand.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(byHand)
        let pending = MLNLineStyleLayer(identifier: pendingLayer, source: source)
        pending.predicate = NSPredicate(format: "st == %@", "p")
        pending.lineColor = NSExpression(forConstantValue: grey)
        pending.lineWidth = NSExpression(forConstantValue: NSNumber(value: lineWidth))
        pending.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 0.5), NSNumber(value: 1.5)])
        pending.lineCap = NSExpression(forConstantValue: "round")
        style.addLayer(pending)
        // A stop taken at the crosshair: its legs pale until it is put down.
        let taken = MLNLineStyleLayer(identifier: takenLayer, source: source)
        taken.predicate = NSPredicate(format: "st == %@", "g")
        taken.lineColor = NSExpression(forConstantValue: orange)
        taken.lineWidth = NSExpression(forConstantValue: NSNumber(value: lineWidth))
        taken.lineOpacity = NSExpression(forConstantValue: NSNumber(value: 0.35))
        taken.lineCap = NSExpression(forConstantValue: "round")
        taken.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(taken)
        // « calcul… », « à la main » at the middle of those legs.
        let label = MLNSymbolStyleLayer(identifier: labelLayer, source: source)
        label.text = NSExpression(forKeyPath: "label")
        label.textFontNames = NSExpression(forConstantValue: [GlyphServer.font])
        label.textFontSize = NSExpression(forConstantValue: NSNumber(value: 11))
        label.textColor = NSExpression(forConstantValue: UIColor.routeColor(0x45464F))
        label.textHaloColor = NSExpression(forConstantValue: UIColor.white)
        label.textHaloWidth = NSExpression(forConstantValue: NSNumber(value: 2.5))
        label.textAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: false))
        style.addLayer(label)
    }

    /// The dashed lines that join a stop being moved to its neighbours.
    private static func installElastic(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNLineStyleLayer(identifier: "edit-elastic-layer", source: source)
        layer.lineColor = NSExpression(forConstantValue: orange)
        layer.lineWidth = NSExpression(forConstantValue: NSNumber(value: 3))
        layer.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: 2), NSNumber(value: 1.5)])
        layer.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(layer)
    }

    // MARK: Handles

    /// One circle layer per kind of handle (constant styles), then the letters and icons, then the names.
    private static func installHandles(_ style: MLNStyle, _ source: MLNShapeSource) {
        let midpoint = circles(midpointLayer, source, NSPredicate(format: "kind == %@", "m"), 4, UIColor.white, orange, 2)
        midpoint.circleOpacity = NSExpression(forConstantValue: NSNumber(value: 0.8))
        style.addLayer(midpoint)
        style.addLayer(circles(vertexLayer, source, NSPredicate(format: "kind == %@", "v"), 6, UIColor.white, orange, 2))
        style.addLayer(circles(passLayer, source, NSPredicate(format: "kind == %@", "pass"), 5, UIColor.white, orange, 2.5))
        style.addLayer(circles(firstLayer, source, NSPredicate(format: "kind == %@", "a"), 6, green, orange, 2))
        style.addLayer(circles(lastLayer, source, NSPredicate(format: "kind == %@", "z"), 6, red, orange, 2))
        style.addLayer(circles(selectedLayer, source, NSPredicate(format: "kind == %@", "s"), 10, orange, orange, 2))
        style.addLayer(circles(passSelectedLayer, source, NSPredicate(format: "kind == %@", "pass-sel"), 8, orange, orange, 2))
        // The ends, the named stops and the points of interest: a disc of their colour with a white border, an orange
        // one when selected.
        let discs = ["start", "end", "named", "poi"] as NSArray
        let plain = NSPredicate(format: "kind IN %@ AND sel == NO", discs)
        let disc = circles(discLayer, source, plain, 10, UIColor.white, UIColor.white, 2)
        disc.circleColor = NSExpression(forKeyPath: "color")
        style.addLayer(disc)
        let picked = NSPredicate(format: "kind IN %@ AND sel == YES", discs)
        let discSelected = circles(discSelectedLayer, source, picked, 10, UIColor.white, orange, 3.5)
        discSelected.circleColor = NSExpression(forKeyPath: "color")
        style.addLayer(discSelected)
        let icons = MLNSymbolStyleLayer(identifier: "edit-handles-icon", source: source)
        icons.iconImageName = NSExpression(forKeyPath: "icon")
        icons.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        icons.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        style.addLayer(icons)
        let names = MLNSymbolStyleLayer(identifier: "edit-handles-name", source: source)
        names.text = NSExpression(forKeyPath: "name")
        names.textFontNames = NSExpression(forConstantValue: [GlyphServer.font])
        names.textFontSize = NSExpression(forConstantValue: NSNumber(value: 13))
        names.textAnchor = NSExpression(forConstantValue: "left")
        names.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: labelOffsetEms, dy: 0)))
        names.textColor = NSExpression(forConstantValue: UIColor.routeColor(0x1F1F1F))
        names.textHaloColor = NSExpression(forConstantValue: UIColor.white)
        names.textHaloWidth = NSExpression(forConstantValue: NSNumber(value: 1.6))
        names.textAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: false))
        style.addLayer(names)
    }

    private static func circles(
        _ id: String,
        _ source: MLNShapeSource,
        _ predicate: NSPredicate,
        _ radius: Double,
        _ fill: UIColor,
        _ stroke: UIColor,
        _ strokeWidth: Double
    ) -> MLNCircleStyleLayer {
        let layer = MLNCircleStyleLayer(identifier: id, source: source)
        layer.predicate = predicate
        layer.circleRadius = NSExpression(forConstantValue: NSNumber(value: radius))
        layer.circleColor = NSExpression(forConstantValue: fill)
        layer.circleStrokeColor = NSExpression(forConstantValue: stroke)
        layer.circleStrokeWidth = NSExpression(forConstantValue: NSNumber(value: strokeWidth))
        return layer
    }

    /// The letters of the ends (`letter-A`) and the waypoint icons of the named stops and points of interest.
    static func addIcons(_ style: MLNStyle, _ geoJson: String) {
        for name in iconNames(geoJson) where style.image(forName: name) == nil {
            let id = String(name.dropFirst(iconPrefix.count))
            var image: UIImage?
            if id.hasPrefix(RouteMarkers.letterPrefix) {
                image = letterImage(String(id.dropFirst(RouteMarkers.letterPrefix.count)))
            } else {
                image = glyphImage(id)
            }
            if let made = image {
                style.setImage(made, forName: name)
            }
        }
    }

    /// A white letter (or number) to put on the 20-point disc of an end.
    private static func letterImage(_ letter: String) -> UIImage {
        let side: CGFloat = 20
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { _ in
            let font = UIFont.systemFont(ofSize: letter.count > 1 ? 9 : 12, weight: .bold)
            let attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor.white]
            let text = letter as NSString
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: (side - size.width) / 2, y: (side - size.height) / 2), withAttributes: attributes)
        }
    }

    /// The white glyph of the waypoint icon `iconId`, 14 points wide.
    private static func glyphImage(_ iconId: String) -> UIImage? {
        let scale = max(1, Int(UIScreen.main.scale.rounded()))
        guard let image = Glyphs.image(Glyphs.waypointKey(id: iconId), size: 14, scale: scale, color: UIColor.white.cgColor)
        else { return nil }
        return UIImage(cgImage: image, scale: CGFloat(scale), orientation: .up)
    }

    // MARK: Cursor

    private static func installCursor(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNCircleStyleLayer(identifier: "edit-cursor-layer", source: source)
        layer.circleRadius = NSExpression(forConstantValue: NSNumber(value: 7))
        layer.circleColor = NSExpression(forConstantValue: UIColor.white)
        layer.circleStrokeColor = NSExpression(forConstantValue: orange)
        layer.circleStrokeWidth = NSExpression(forConstantValue: NSNumber(value: 3))
        style.addLayer(layer)
    }
}
