import MapLibre
import ToolMapCore
import UIKit

/// Tracks, routes and waypoints of the library (Android `TrackOverlay`, ADR-018): two GeoJSON sets fed by the core's
/// `TrackDisplay` through `LibraryController`. Lines: a light contour, the line itself (plain, or dashed for the
/// features whose `dashed` is true), direction arrows. Points: a soft shadow, a disc of the category colour with a white
/// border, the white glyph of the icon, the name on the right (« Noms sur la carte », ADR-062). Colour and width come
/// from each feature, and the « Repères » settings (size, contour, arrows, borders, shadows, names) are written into
/// the features by `styledLines` / `styledPoints`, so that a change of style is a change of data, never of layers.
enum TrackLayers {
    static let linesId = "track-lines"
    static let pointsId = "track-points"
    /// The layers that answer a tap (`LibraryController` opens the sheet of the item).
    static let solidLayer = "track-lines-solid"
    static let dashedLayer = "track-lines-dashed"
    static let pointsLayer = "track-points-disc"
    /// Prefix of the glyph images of the waypoints in the style (Android `GLYPH_PREFIX`).
    static let glyphPrefix = "tm-wp-"

    private static let casingLayer = "track-lines-casing"
    private static let arrowsLayer = "track-lines-arrows"
    private static let shadowLayer = "track-points-shadow"
    private static let iconLayer = "track-points-icon"
    private static let labelLayer = "track-points-label"
    private static let arrowImageName = "tm-arrow"

    private static let casingExtraWidth = 3.0
    private static let casingOpacity = 0.55
    private static let pointRadius = 10.0
    private static let pointStroke = 2.0
    private static let shadowExtraRadius = 1.0
    private static let shadowOpacity = 0.35
    private static let shadowBlur = 0.6
    private static let shadowOffsetY = 1.5
    private static let arrowSpacing = 90.0
    private static let arrowSide: CGFloat = 10
    private static let dashOn = 2.0
    private static let dashOff = 1.5
    private static let textSize = 13.0
    /// Text offset in ems: (radius + 4) / text size, the same at every placemark size (Android `MapLabels.sized`).
    private static let labelOffsetEms = 14.0 / 13.0

    private static let propertiesMarker = "\"properties\":{"
    private static let iconMarker = "\"icon\":\""

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(
            id: linesId,
            tappableLayers: [solidLayer, dashedLayer],
            install: TrackLayers.installLines
        ),
        FeatureLayerSet(
            id: pointsId,
            tappableLayers: [pointsLayer],
            install: TrackLayers.installPoints,
            prepare: TrackLayers.addGlyphs
        ),
    ]

    // MARK: Layers

    private static func installLines(_ style: MLNStyle, _ source: MLNShapeSource) {
        if style.image(forName: arrowImageName) == nil {
            style.setImage(arrowImage(), forName: arrowImageName)
        }
        let width = scaledWidth()

        let casing = MLNLineStyleLayer(identifier: casingLayer, source: source)
        casing.predicate = NSPredicate(format: "cont == YES")
        casing.lineColor = NSExpression(forConstantValue: UIColor.white)
        casing.lineOpacity = NSExpression(forConstantValue: NSNumber(value: casingOpacity))
        casing.lineWidth = NSExpression(forFunction: "add:to:", arguments: [width, NSExpression(forKeyPath: "cx")])
        casing.lineCap = NSExpression(forConstantValue: "round")
        casing.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(casing)

        let solid = MLNLineStyleLayer(identifier: solidLayer, source: source)
        solid.predicate = NSPredicate(format: "dashed == NO")
        solid.lineColor = NSExpression(forKeyPath: "color")
        solid.lineOpacity = NSExpression(forKeyPath: "opacity")
        solid.lineWidth = width
        solid.lineCap = NSExpression(forConstantValue: "round")
        solid.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(solid)

        // A dash pattern cannot follow the data: the dashed lines are a second layer with a fixed one.
        let dashed = MLNLineStyleLayer(identifier: dashedLayer, source: source)
        dashed.predicate = NSPredicate(format: "dashed == YES")
        dashed.lineColor = NSExpression(forKeyPath: "color")
        dashed.lineOpacity = NSExpression(forKeyPath: "opacity")
        dashed.lineWidth = width
        dashed.lineDashPattern = NSExpression(forConstantValue: [NSNumber(value: dashOn), NSNumber(value: dashOff)])
        dashed.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(dashed)

        let arrows = MLNSymbolStyleLayer(identifier: arrowsLayer, source: source)
        arrows.predicate = NSPredicate(format: "arrows == YES AND arr == YES")
        arrows.symbolPlacement = NSExpression(forConstantValue: "line")
        arrows.symbolSpacing = NSExpression(forConstantValue: NSNumber(value: arrowSpacing))
        arrows.iconImageName = NSExpression(forConstantValue: arrowImageName)
        arrows.iconScale = NSExpression(forKeyPath: "k")
        arrows.iconRotationAlignment = NSExpression(forConstantValue: "map")
        arrows.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        arrows.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        style.addLayer(arrows)
    }

    private static func installPoints(_ style: MLNStyle, _ source: MLNShapeSource) {
        // The shadow sits under the points, a little lower and blurred (« Ombres »).
        let shadow = MLNCircleStyleLayer(identifier: shadowLayer, source: source)
        shadow.predicate = NSPredicate(format: "shad == YES")
        shadow.circleRadius = NSExpression(forKeyPath: "sr")
        shadow.circleColor = NSExpression(forConstantValue: UIColor.black)
        shadow.circleOpacity = NSExpression(forConstantValue: NSNumber(value: shadowOpacity))
        shadow.circleBlur = NSExpression(forConstantValue: NSNumber(value: shadowBlur))
        shadow.circleTranslation = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: shadowOffsetY)))
        style.addLayer(shadow)

        let disc = MLNCircleStyleLayer(identifier: pointsLayer, source: source)
        disc.circleRadius = NSExpression(forKeyPath: "r")
        disc.circleColor = NSExpression(forKeyPath: "color")
        disc.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        disc.circleStrokeWidth = NSExpression(forKeyPath: "bw")
        style.addLayer(disc)

        // The white glyph of the waypoint's icon, on the disc of its category colour (P16.6).
        let icon = MLNSymbolStyleLayer(identifier: iconLayer, source: source)
        icon.iconImageName = NSExpression(forKeyPath: "icon")
        icon.iconScale = NSExpression(forKeyPath: "isz")
        icon.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        icon.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        style.addLayer(icon)

        // The name on the right of the marker, as in AlpineQuest; names that would overlap are left out.
        let label = MLNSymbolStyleLayer(identifier: labelLayer, source: source)
        label.predicate = NSPredicate(format: "lab == YES")
        label.text = NSExpression(forKeyPath: "name")
        label.textFontNames = NSExpression(forConstantValue: [GlyphServer.font])
        label.textFontSize = NSExpression(forKeyPath: "ts")
        label.textAnchor = NSExpression(forConstantValue: "left")
        label.textJustification = NSExpression(forConstantValue: "left")
        label.maximumTextWidth = NSExpression(forConstantValue: NSNumber(value: 12))
        label.textPadding = NSExpression(forConstantValue: NSNumber(value: 2))
        label.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: labelOffsetEms, dy: 0)))
        label.textColor = NSExpression(forConstantValue: UIColor(red: 0x1F / 255.0, green: 0x1F / 255.0, blue: 0x1F / 255.0, alpha: 1))
        label.textHaloColor = NSExpression(forConstantValue: UIColor.white)
        label.textHaloWidth = NSExpression(forConstantValue: NSNumber(value: 1.6))
        label.textHaloBlur = NSExpression(forConstantValue: NSNumber(value: 0.4))
        label.textAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: false))
        style.addLayer(label)
    }

    /// The width of a line times the placemark size written in its feature.
    private static func scaledWidth() -> NSExpression {
        NSExpression(
            forFunction: "multiply:by:",
            arguments: [NSExpression(forKeyPath: "width"), NSExpression(forKeyPath: "k")]
        )
    }

    /// Adds the glyph images the points name (`icon` of the GeoJSON, `tm-wp-<icon id>`) and the style lacks.
    private static func addGlyphs(_ style: MLNStyle, _ geoJson: String) {
        for name in imageNames(geoJson) where style.image(forName: name) == nil {
            let iconId = String(name.dropFirst(glyphPrefix.count))
            if let image = WaypointIconImages.glyph(iconId) {
                style.setImage(image, forName: name)
            }
        }
    }

    /// The values of the `icon` properties of a GeoJSON text.
    static func imageNames(_ geoJson: String) -> Set<String> {
        var names = Set<String>()
        var rest = geoJson.startIndex ..< geoJson.endIndex
        while let found = geoJson.range(of: iconMarker, range: rest) {
            guard let end = geoJson[found.upperBound...].firstIndex(of: "\"") else { break }
            names.insert(String(geoJson[found.upperBound ..< end]))
            rest = end ..< geoJson.endIndex
        }
        return names
    }

    // MARK: Data

    /// The lines of `TrackDisplay` with the « Repères » settings in each feature: `k` (size), `cx` (extra width of the
    /// contour), `cont` (contour shown), `arr` (arrows shown).
    static func styledLines(_ geoJson: String, _ marks: PlacemarkSettings) -> String {
        let k = marks.size.factor
        let extra = "\"k\":\(k),\"cx\":\(casingExtraWidth * k),\"cont\":\(marks.trackContour),\"arr\":\(marks.trackArrows),"
        return geoJson.replacingOccurrences(of: propertiesMarker, with: propertiesMarker + extra)
    }

    /// The points of `TrackDisplay` with the settings in each feature (`r` radius, `sr` shadow radius, `bw` border,
    /// `isz` icon scale, `ts` text size, `shad` and `lab` shown) and their image named `tm-wp-<icon id>`.
    static func styledPoints(_ geoJson: String, _ marks: PlacemarkSettings) -> String {
        let k = marks.size.factor
        let radius = pointRadius * k
        let border = marks.borders ? pointStroke : 0.0
        let shadowRadius = radius + shadowExtraRadius
        let fontSize = textSize * k
        let extra =
            "\"r\":\(radius),\"sr\":\(shadowRadius),\"bw\":\(border),\"isz\":\(k),\"ts\":\(fontSize),\"shad\":\(marks.shadows),\"lab\":\(marks.labels),"
        let styled = geoJson.replacingOccurrences(of: propertiesMarker, with: propertiesMarker + extra)
        return styled.replacingOccurrences(of: iconMarker, with: iconMarker + glyphPrefix)
    }

    // MARK: Taps

    /// The item a tapped feature stands for: lines carry the track id (negative for a route), points the waypoint id
    /// (negative for the markers of a computed route).
    static func ref(_ tap: FeatureTap) -> ItemRefKey? {
        guard let id = featureId(tap.properties["id"]) else { return nil }
        if tap.layerId == pointsLayer {
            return id < 0 ? ItemRefKey(kind: .route, id: -id) : ItemRefKey(kind: .waypoint, id: id)
        }
        return id < 0 ? ItemRefKey(kind: .route, id: -id) : ItemRefKey(kind: .track, id: id)
    }

    private static func featureId(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let text = value as? String { return Int64(text) }
        return nil
    }

    // MARK: Images

    /// A white arrow with a dark edge, readable on any line colour, pointing right (along the line).
    private static func arrowImage() -> UIImage {
        let side = arrowSide
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { _ in
            let path = UIBezierPath()
            path.move(to: CGPoint(x: side * 0.20, y: side * 0.22))
            path.addLine(to: CGPoint(x: side * 0.82, y: side * 0.50))
            path.addLine(to: CGPoint(x: side * 0.20, y: side * 0.78))
            path.addLine(to: CGPoint(x: side * 0.38, y: side * 0.50))
            path.close()
            path.lineWidth = side * 0.12
            path.lineJoinStyle = .round
            UIColor(white: 0, alpha: 0.8).setStroke()
            path.stroke()
            UIColor.white.setFill()
            path.fill()
        }
    }
}
