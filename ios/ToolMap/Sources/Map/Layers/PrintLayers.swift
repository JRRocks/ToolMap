import Foundation
import MapLibre
import ToolMapCore
import UIKit

/// The pages of a print along a track over the map (Android T3b, `PrintPagesOverlay`): each frame at its true ground
/// size, lightly filled and outlined in petrol, numbered in the order of the walk by a disc near its top left corner.
/// They follow from the track, the format and the scale, so they do not move; the map keeps its gestures. The single
/// frame of T3, which a finger moves, is drawn in SwiftUI (`PrintFrameOverlay`).
enum PrintLayers {
    /// Key in `AppModel.mapFeatures`, set by `PrintController`.
    static let id = "print"

    private static let fillId = "print-pages-fill"
    private static let lineId = "print-pages-line"
    private static let badgeId = "print-pages-badge"
    private static let badgePrefix = "print-page-"

    /// The petrol of the frames (Android `colorScheme.primary`).
    private static let petrol = UIColor(red: 0x16 / 255.0, green: 0x3A / 255.0, blue: 0x4A / 255.0, alpha: 1)

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(
            id: PrintLayers.id,
            install: { style, source in PrintLayers.install(style, source) },
            prepare: { style, text in PrintLayers.prepare(style, text) }
        ),
    ]

    private static func install(_ style: MLNStyle, _ source: MLNShapeSource) {
        let pages = NSPredicate(format: "kind == %@", "page")
        let fill = MLNFillStyleLayer(identifier: fillId, source: source)
        fill.predicate = pages
        fill.fillColor = NSExpression(forConstantValue: petrol)
        fill.fillOpacity = NSExpression(forConstantValue: NSNumber(value: 0.08))
        style.addLayer(fill)

        let line = MLNLineStyleLayer(identifier: lineId, source: source)
        line.predicate = pages
        line.lineColor = NSExpression(forConstantValue: petrol)
        line.lineWidth = NSExpression(forConstantValue: NSNumber(value: 2))
        style.addLayer(line)

        let badge = MLNSymbolStyleLayer(identifier: badgeId, source: source)
        badge.predicate = NSPredicate(format: "kind == %@", "badge")
        badge.iconImageName = NSExpression(forKeyPath: "icon")
        badge.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        badge.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        style.addLayer(badge)
    }

    /// The disc of each page number used by `text`, made once per style.
    private static func prepare(_ style: MLNStyle, _ text: String) {
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let features = root["features"] as? [[String: Any]]
        else { return }
        for feature in features {
            guard let properties = feature["properties"] as? [String: Any],
                  let name = properties["icon"] as? String,
                  name.hasPrefix(badgePrefix),
                  let number = Int(name.dropFirst(badgePrefix.count)),
                  style.image(forName: name) == nil
            else { continue }
            style.setImage(badgeImage(number), forName: name)
        }
    }

    /// The GeoJSON of the pages: a polygon per page and a point at its top left corner for its number.
    static func geoJson(_ pages: [[LonLat]]) -> String {
        var features: [[String: Any]] = []
        for (index, corners) in pages.enumerated() where corners.count == 4 {
            let number = index + 1
            var ring: [[Double]] = corners.map { [$0.lon, $0.lat] }
            ring.append([corners[0].lon, corners[0].lat])
            let pageProperties: [String: Any] = ["kind": "page", "number": "\(number)"]
            let polygon: [String: Any] = ["type": "Polygon", "coordinates": [ring]]
            let page: [String: Any] = ["type": "Feature", "properties": pageProperties, "geometry": polygon]
            features.append(page)
            let badgeProperties: [String: Any] = ["kind": "badge", "number": "\(number)", "icon": "\(badgePrefix)\(number)"]
            let point: [String: Any] = ["type": "Point", "coordinates": [corners[0].lon, corners[0].lat]]
            let badge: [String: Any] = ["type": "Feature", "properties": badgeProperties, "geometry": point]
            features.append(badge)
        }
        let collection: [String: Any] = ["type": "FeatureCollection", "features": features]
        guard let data = try? JSONSerialization.data(withJSONObject: collection),
              let text = String(data: data, encoding: .utf8)
        else { return FeatureLayerSet.empty }
        return text
    }

    /// A petrol disc with the white number, 12 points right of and below the corner the image is centred on (Android
    /// draws it along the two edges of the page from its corner): the image is transparent around it.
    private static func badgeImage(_ number: Int) -> UIImage {
        let inset: CGFloat = 12
        let radius: CGFloat = 9
        let side = 2 * (inset + radius + 2)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { _ in
            let center = CGPoint(x: side / 2 + inset, y: side / 2 + inset)
            let disc = CGRect(x: center.x - radius, y: center.y - radius, width: 2 * radius, height: 2 * radius)
            PrintLayers.petrol.setFill()
            UIBezierPath(ovalIn: disc).fill()
            let label = "\(number)" as NSString
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 11, weight: .bold),
                .foregroundColor: UIColor.white,
            ]
            let size = label.size(withAttributes: attributes)
            label.draw(at: CGPoint(x: center.x - size.width / 2, y: center.y - size.height / 2), withAttributes: attributes)
        }
    }
}
