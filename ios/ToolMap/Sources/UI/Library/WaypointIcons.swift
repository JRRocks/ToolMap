import SwiftUI
import ToolMapCore
import UIKit

/// The white glyphs of the waypoint icons (Android `WaypointGlyphs`), drawn once per icon and size from the outlines
/// of the core (`Glyphs`), and the letters of the ends of a computed route (`letter-A`). Thread-safe: the map, the
/// lists and the print ask for them.
final class WaypointIconImages: @unchecked Sendable {
    static let shared = WaypointIconImages()
    /// Size of a glyph on the map, in points (Android: 14 map units).
    static let mapPoints = 14

    private let lock = NSLock()
    private var images: [String: UIImage] = [:]

    /// The white glyph of the waypoint icon `iconId` (`WaypointCatalog.Icon.id`, or `letter-A`), at its map size;
    /// nil for an unknown icon.
    static func glyph(_ iconId: String) -> UIImage? {
        shared.image(iconId, points: mapPoints)
    }

    /// The white glyph of the waypoint icon `iconId` (`WaypointSymbols` icon id), without its disc, 48 pixels wide:
    /// the print draws it on the coloured disc of the page. Nil for an unknown icon.
    static func waypointGlyph(_ iconId: String) -> UIImage? {
        shared.pixels(iconId)
    }

    /// The white glyph of `iconId`, `points` wide.
    func image(_ iconId: String, points: Int) -> UIImage? {
        let key = "\(iconId)@\(points)"
        lock.lock()
        let known = images[key]
        lock.unlock()
        if let known { return known }
        guard let made = WaypointIconImages.draw(iconId, points: points) else { return nil }
        lock.lock()
        images[key] = made
        lock.unlock()
        return made
    }

    /// The glyph for the print: 48 × 48 pixels at scale 1.
    private func pixels(_ iconId: String) -> UIImage? {
        let key = "\(iconId)#print"
        lock.lock()
        let known = images[key]
        lock.unlock()
        if let known { return known }
        let made: UIImage?
        if iconId.hasPrefix(RouteMarkers.letterPrefix) {
            made = WaypointIconImages.letter(String(iconId.dropFirst(RouteMarkers.letterPrefix.count)), points: 48)
        } else {
            let glyphKey = Glyphs.waypointKey(id: iconId)
            let picture = Glyphs.image(glyphKey, size: 48, scale: 1, color: UIColor.white.cgColor)
            made = picture.map { UIImage(cgImage: $0, scale: 1, orientation: .up) }
        }
        guard let made else { return nil }
        lock.lock()
        images[key] = made
        lock.unlock()
        return made
    }

    private static func draw(_ iconId: String, points: Int) -> UIImage? {
        let scale = max(Int(UIScreen.main.scale.rounded()), 1)
        if iconId.hasPrefix(RouteMarkers.letterPrefix) {
            return letter(String(iconId.dropFirst(RouteMarkers.letterPrefix.count)), points: points)
        }
        let key = Glyphs.waypointKey(id: iconId)
        guard let picture = Glyphs.image(key, size: points, scale: scale, color: UIColor.white.cgColor) else {
            return nil
        }
        return UIImage(cgImage: picture, scale: CGFloat(scale), orientation: .up)
    }

    /// A white bold letter (or number), centred, for the start and the end of a computed route.
    private static func letter(_ text: String, points: Int) -> UIImage {
        let side = CGFloat(points)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: side, height: side))
        return renderer.image { _ in
            let size = side * (text.count > 1 ? 0.63 : 0.79)
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: size),
                .foregroundColor: UIColor.white,
            ]
            let string = text as NSString
            let measured = string.size(withAttributes: attributes)
            string.draw(
                at: CGPoint(x: (side - measured.width) / 2, y: (side - measured.height) / 2),
                withAttributes: attributes
            )
        }
    }
}

/// The icon of a waypoint as the map shows it: its white glyph on a disc of its category colour.
struct WaypointIconBadge: View {
    let icon: WaypointCatalog.Icon
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(LibraryColors.color(icon.colorArgb))
            if let image = WaypointIconImages.shared.image(icon.id, points: WaypointIconBadge.glyphPoints) {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: size * 0.62, height: size * 0.62)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    /// Drawn large enough for the biggest badge (48 points), shrunk for the others.
    private static let glyphPoints = 32
}

/// The mark of a track or a route: its icon in the colour of its line, in a pale disc.
struct LibraryLineMark: View {
    let systemImage: String
    let colorArgb: Int32
    var size: CGFloat = 40

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(LibraryColors.color(colorArgb))
            .frame(width: size, height: size)
            .background(Crete.tile, in: Circle())
            .accessibilityHidden(true)
    }
}
