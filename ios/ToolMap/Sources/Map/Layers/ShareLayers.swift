import MapLibre
import ToolMapCore
import UIKit

/// The other members of the sharing group (Android `ShareLayers`): their recent trail as a line (unless « Tracé des
/// membres » is off) and their last position as a token on a disc of their colour (faded when old or lost), with a red
/// ring around those who sent an alert, and their name on the right (« Noms sur la carte », ADR-062). A tap on a marker
/// opens the member's sheet (`ShareController`). The GeoJSON comes from `ShareGeoJson` of the core.
enum ShareLayers {
    static let trailsId = "share-trails"
    static let membersId = "share-members"
    static let labelsId = "share-labels"
    static let markersLayer = "share-members-layer"
    static let oldMarkersLayer = "share-members-old-layer"
    private static let trailsLayer = "share-trails-layer"
    private static let sosLayer = "share-sos-layer"
    private static let labelsLayer = "share-labels-layer"
    /// Half of the 32-point marker plus a gap of 4 points, in ems of the 13-point text.
    private static let labelOffsetEms = 20.0 / 13.0

    static let sets: [FeatureLayerSet] = [
        FeatureLayerSet(id: trailsId, install: ShareLayers.installTrails),
        FeatureLayerSet(
            id: membersId,
            tappableLayers: [markersLayer, oldMarkersLayer],
            install: ShareLayers.installMembers,
            prepare: ShareLayers.addMarkerImages
        ),
        FeatureLayerSet(id: labelsId, install: ShareLayers.installLabels),
    ]

    static func installTrails(_ style: MLNStyle, _ source: MLNShapeSource) {
        let line = MLNLineStyleLayer(identifier: trailsLayer, source: source)
        line.lineColor = NSExpression(forKeyPath: "color")
        line.lineWidth = NSExpression(forConstantValue: NSNumber(value: 3))
        line.lineOpacity = NSExpression(forConstantValue: NSNumber(value: 0.8))
        line.lineCap = NSExpression(forConstantValue: "round")
        line.lineJoin = NSExpression(forConstantValue: "round")
        style.addLayer(line)
    }

    static func installMembers(_ style: MLNStyle, _ source: MLNShapeSource) {
        let ring = MLNCircleStyleLayer(identifier: sosLayer, source: source)
        ring.predicate = NSPredicate(format: "sos == YES")
        ring.circleRadius = NSExpression(forConstantValue: NSNumber(value: 18))
        ring.circleColor = NSExpression(forConstantValue: UIColor.clear)
        ring.circleStrokeColor = NSExpression(forConstantValue: UIColor(red: 0xE5 / 255.0, green: 0x39 / 255.0, blue: 0x35 / 255.0, alpha: 1))
        ring.circleStrokeWidth = NSExpression(forConstantValue: NSNumber(value: 4))
        style.addLayer(ring)
        // Old positions below the fresh ones.
        style.addLayer(marker(oldMarkersLayer, source, predicate: "old == YES", opacity: 0.45))
        style.addLayer(marker(markersLayer, source, predicate: "old == NO", opacity: 1))
    }

    private static func marker(_ id: String, _ source: MLNShapeSource, predicate: String, opacity: Double) -> MLNSymbolStyleLayer {
        let layer = MLNSymbolStyleLayer(identifier: id, source: source)
        layer.predicate = NSPredicate(format: predicate)
        layer.iconImageName = NSExpression(forKeyPath: "img")
        layer.iconAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: true))
        layer.iconIgnoresPlacement = NSExpression(forConstantValue: NSNumber(value: true))
        layer.iconOpacity = NSExpression(forConstantValue: NSNumber(value: opacity))
        return layer
    }

    /// Dark names with a white halo on the right of the markers (Android `MapLabels`); a name that would cover another
    /// one is not drawn.
    static func installLabels(_ style: MLNStyle, _ source: MLNShapeSource) {
        let layer = MLNSymbolStyleLayer(identifier: labelsLayer, source: source)
        layer.text = NSExpression(forKeyPath: "name")
        layer.textFontNames = NSExpression(forConstantValue: [GlyphServer.font])
        layer.textFontSize = NSExpression(forConstantValue: NSNumber(value: 13))
        layer.textAnchor = NSExpression(forConstantValue: "left")
        layer.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: labelOffsetEms, dy: 0)))
        layer.textColor = NSExpression(forConstantValue: UIColor(red: 0x1F / 255.0, green: 0x1F / 255.0, blue: 0x1F / 255.0, alpha: 1))
        layer.textHaloColor = NSExpression(forConstantValue: UIColor.white)
        layer.textHaloWidth = NSExpression(forConstantValue: NSNumber(value: 1.6))
        layer.textAllowsOverlap = NSExpression(forConstantValue: NSNumber(value: false))
        style.addLayer(layer)
    }

    /// Adds the marker images the members need (`img` of their GeoJSON), made by the controller beforehand.
    static func addMarkerImages(_ style: MLNStyle, _ geoJson: String) {
        for (key, image) in ShareMarkerImages.shared.all() where style.image(forName: key) == nil {
            style.setImage(image, forName: key)
        }
    }
}

/// The map markers of the members (Android `MemberMarkerBitmap`), by `MemberLook.imageKey`: a ring of the member's
/// colour around a contrasting disc, with its token or initials in its colour, or its own picture inside the ring.
final class ShareMarkerImages: @unchecked Sendable {
    static let shared = ShareMarkerImages()
    /// The marker is 32 points wide, as on Android (32 dp).
    static let side: CGFloat = 32
    private static let ring: CGFloat = 3

    private let lock = NSLock()
    private var images: [String: UIImage] = [:]

    /// Draws the image `key` once.
    func ensure(_ key: String, look: Int32, name: String, picture: [UInt8]?) {
        lock.lock()
        let known = images[key] != nil
        lock.unlock()
        if known { return }
        let image = ShareMarkerImages.draw(look: look, name: name, picture: picture)
        lock.lock()
        images[key] = image
        lock.unlock()
    }

    func all() -> [String: UIImage] {
        lock.lock()
        defer { lock.unlock() }
        return images
    }

    static func draw(look: Int32, name: String, picture: [UInt8]?) -> UIImage {
        let size = side
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { context in
            let cg = context.cgContext
            let c = size / 2
            let argb = MemberLook.argb(look)
            let color = ShareColors.uiColor(argb)
            cg.setFillColor(UIColor(white: 0, alpha: 0.4).cgColor)
            cg.fillEllipse(in: CGRect(x: 0, y: 0, width: size, height: size))
            let outer = c - 0.75
            cg.setFillColor(color.cgColor)
            cg.fillEllipse(in: CGRect(x: c - outer, y: c - outer, width: 2 * outer, height: 2 * outer))
            let inner = c - ring
            let innerRect = CGRect(x: c - inner, y: c - inner, width: 2 * inner, height: 2 * inner)
            cg.setFillColor(ShareColors.uiColor(MemberLook.backdrop(argb)).cgColor)
            cg.fillEllipse(in: innerRect)
            let icon = MemberLook.icon(look)
            if icon == MemberLook.picture, let photo = SharePicture.decode(picture) {
                cg.saveGState()
                cg.addEllipse(in: innerRect)
                cg.clip()
                photo.draw(in: innerRect)
                cg.restoreGState()
            } else if let token = ShareTokenArt.image(icon, argb, side: size * 0.56) {
                let half = size * 0.28
                token.draw(in: CGRect(x: c - half, y: c - half, width: 2 * half, height: 2 * half))
            } else {
                let text = MemberPalette.initials(name) as NSString
                let attributes: [NSAttributedString.Key: Any] = [
                    .font: UIFont.boldSystemFont(ofSize: size * 0.36),
                    .foregroundColor: color,
                ]
                let measured = text.size(withAttributes: attributes)
                text.draw(at: CGPoint(x: c - measured.width / 2, y: c - measured.height / 2), withAttributes: attributes)
            }
        }
    }
}
