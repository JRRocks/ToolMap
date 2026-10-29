// The glyphs of the waypoint icons, the activities and the share tokens, as Android draws them from its vector
// drawables (res/drawable/ic_wp_*, ic_act_*, ic_token_*). Their outlines are copied into the resource glyphs.json by
// ios/tools/glyphs.py: the two apps share no file at build time (ADR-100). Licences: Maki and Temaki CC0 1.0, Material
// Design Icons Apache 2.0, game-icons.net CC BY 3.0 (tokens, credited in the help screen); see ios/tools/glyphs.py.

#if canImport(CoreGraphics) && canImport(ImageIO)
    import CoreGraphics
    import Foundation

    /// Vector glyphs by key: `wp_<icon id>` (`WaypointCatalog`), `act_<activity>` (`TrackActivity`, in lower case),
    /// `token_<name>` (`MemberLook.icons`). They are drawn as images for the map (`MLNStyle.setImage`), the lists and
    /// the PDF, in one colour (white on Android), on an optional disc.
    public enum Glyphs {
        /// The package resource, `glyphs.json`.
        public static let resource = "glyphs"
        public static let waypointPrefix = "wp_"
        public static let activityPrefix = "act_"
        public static let tokenPrefix = "token_"

        /// The outline of a glyph: filled paths in a viewport of `width` × `height`, y counted downwards (as in SVG and
        /// Android). Each path is filled on its own, with its own rule; the glyph is their union.
        public struct Glyph: Equatable, Hashable, Sendable, Decodable {
            public struct PathData: Equatable, Hashable, Sendable, Decodable {
                /// SVG path data, read by `VectorPath`.
                public let data: String
                /// Fill rule: even-odd when true, non-zero otherwise (Android's default).
                public let evenOdd: Bool

                public init(data: String, evenOdd: Bool = false) {
                    self.data = data
                    self.evenOdd = evenOdd
                }

                private enum CodingKeys: String, CodingKey {
                    case data = "d"
                    case evenOdd
                }

                public init(from decoder: Decoder) throws {
                    let container = try decoder.container(keyedBy: CodingKeys.self)
                    data = try container.decode(String.self, forKey: .data)
                    evenOdd = try container.decodeIfPresent(Bool.self, forKey: .evenOdd) ?? false
                }
            }

            public let width: Double
            public let height: Double
            public let paths: [PathData]

            public init(width: Double, height: Double, paths: [PathData]) {
                self.width = width
                self.height = height
                self.paths = paths
            }

            private enum CodingKeys: String, CodingKey {
                case width = "w"
                case height = "h"
                case paths
            }
        }

        /// Read once, on first use; empty when the resource is missing (a packaging bug the tests catch).
        private static let table: [String: Glyph] = (try? load()) ?? [:]

        /// Every key, sorted.
        public static let keys: [String] = table.keys.sorted()

        /// The glyphs of the package resource. Throws when it is missing or not valid JSON.
        public static func load() throws -> [String: Glyph] {
            guard let url = Bundle.module.url(forResource: resource, withExtension: "json") else {
                throw ToolMapError.invalidArgument("Missing resource \(resource).json")
            }
            return try decode(Data(contentsOf: url))
        }

        /// Glyphs written as in glyphs.json: `{"wp_peak": {"w": 15, "h": 15, "paths": [{"d": "M…", "evenOdd": false}]}}`.
        public static func decode(_ data: Data) throws -> [String: Glyph] {
            try JSONDecoder().decode([String: Glyph].self, from: data)
        }

        public static func glyph(_ key: String) -> Glyph? {
            table[key]
        }

        /// The outline of `key` in its viewport (y downwards), all its paths in one; nil for an unknown key or path data
        /// that cannot be read. To draw it, prefer `image`: it fills each path with its own rule.
        public static func path(_ key: String) -> CGPath? {
            guard let glyph = table[key] else { return nil }
            let path = CGMutablePath()
            for part in glyph.paths {
                do {
                    try VectorPath.append(part.data, to: path)
                } catch {
                    return nil
                }
            }
            return path
        }

        /// The key of a waypoint icon, as Android's drawable `ic_wp_<id>`.
        public static func waypointKey(_ icon: WaypointCatalog.Icon) -> String {
            waypointKey(id: icon.id)
        }

        /// The key of the waypoint icon `id` (`WaypointCatalog.Icon.id`).
        public static func waypointKey(id: String) -> String {
            waypointPrefix + id
        }

        /// The key of an activity, as Android's drawable `ic_act_<name in lower case>`.
        public static func activityKey(_ activity: TrackActivity) -> String {
            activityPrefix + activity.rawValue.lowercased()
        }

        /// The key of the token `icon` of `MemberLook.icons`; nil for the initials, the picture or an unknown id.
        public static func tokenKey(_ icon: Int) -> String? {
            guard icon > MemberLook.initials, icon < MemberLook.icons.count else { return nil }
            return tokenPrefix + MemberLook.icons[icon]
        }

        /// The glyph `key` drawn in `color`, centred in a square of `size` points rendered at `scale` pixels per point
        /// (the image is `size × scale` pixels wide), with `padding` points left free on each side; under it, when
        /// `background` is given, a disc of that colour filling the square. Nil for an unknown key or an empty size.
        public static func image(
            _ key: String,
            size: Int,
            scale: Int,
            color: CGColor,
            background: CGColor? = nil,
            padding: Double = 0
        ) -> CGImage? {
            guard let glyph = table[key] else { return nil }
            return image(glyph, size: size, scale: scale, color: color, background: background, padding: padding)
        }

        /// `glyph` drawn as `image(_:size:scale:color:background:padding:)` draws a glyph of the resource.
        public static func image(
            _ glyph: Glyph,
            size: Int,
            scale: Int,
            color: CGColor,
            background: CGColor? = nil,
            padding: Double = 0
        ) -> CGImage? {
            guard size > 0, scale > 0, glyph.width > 0, glyph.height > 0 else { return nil }
            let side = size * scale
            guard let context = TileComposer.context(side) else { return nil }
            let pixels = CGFloat(side)
            context.clear(CGRect(x: 0, y: 0, width: pixels, height: pixels))
            if let background {
                context.setFillColor(background)
                context.fillEllipse(in: CGRect(x: 0, y: 0, width: pixels, height: pixels))
            }
            let inner = CGFloat((Double(size) - 2 * padding) * Double(scale))
            guard inner > 0 else { return context.makeImage() }
            let width = CGFloat(glyph.width)
            let height = CGFloat(glyph.height)
            let factor = Swift.min(inner / width, inner / height)
            let left = (pixels - width * factor) / 2
            let top = (pixels - height * factor) / 2
            // The viewport counts y downwards, CoreGraphics upwards: the glyph is turned over into place.
            context.translateBy(x: left, y: pixels - top)
            context.scaleBy(x: factor, y: -factor)
            context.setFillColor(color)
            for part in glyph.paths {
                guard let path = try? VectorPath.parse(part.data) else { return nil }
                context.addPath(path)
                context.fillPath(using: part.evenOdd ? .evenOdd : .winding)
            }
            return context.makeImage()
        }
    }
#endif
