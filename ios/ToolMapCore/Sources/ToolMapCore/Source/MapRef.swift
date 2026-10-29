// Port of core/src/main/kotlin/fr/toolmap/core/source/MapRef.kt.

/// A level of a multi-zoom map that shows another map of the catalogue instead of calling a server (ToolMap
/// extension of the `<server>` value, like `LevelDef.localOnlyServer`; ADR-031):
/// - `toolmap-map:ign-maps` shows the tiles of `ign-maps` at the displayed zoom;
/// - `toolmap-map:ign-maps?z=15` always uses its zoom-15 tiles, shrunk or enlarged to the displayed zoom
///   ("forced zoom", what an AlpineQuest user gets by leaving zoom levels out of a `<zoom-values>` list).
///
/// The tiles stay those of the referenced map: they are stored once, in its storage (no second download).
public struct MapRef: Equatable, Hashable, Sendable {
    public static let prefix = "toolmap-map:"
    private static let zoomKey = "z"
    private static let safe = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-".utf8)
    private static let hexDigits = Array("0123456789ABCDEF".utf8)

    public let sourceId: String
    /// Zoom of the tiles to use whatever the displayed zoom; nil = the displayed zoom.
    public let tileZoom: Int?

    public init(sourceId: String, tileZoom: Int? = nil) throws {
        try require(!sourceId.isEmpty, "A map reference needs a map id")
        try require(
            tileZoom.map { (0 ... ZoomSet.maxZoom).contains($0) } ?? true,
            "Forced zoom out of range: \(tileZoom.map { "\($0)" } ?? "null")"
        )
        self.sourceId = sourceId
        self.tileZoom = tileZoom
    }

    /// The `<server>` value that stores this reference.
    public func toServer() -> String {
        MapRef.prefix + MapRef.encode(sourceId) + (tileZoom.map { "?\(MapRef.zoomKey)=\($0)" } ?? "")
    }

    public static func isReference(_ server: String) -> Bool { server.hasPrefix(prefix) }

    /// The reference written in `server`, or nil when it is an ordinary URL (or a malformed reference).
    public static func parse(_ server: String) -> MapRef? {
        guard isReference(server) else { return nil }
        let body = kotlinTrim(String(server.dropFirst(prefix.count)))
        guard let id = decode(body.substringBefore("?")), !id.isEmpty else { return nil }
        var zoom: Int?
        let query = body.substringAfter("?", missing: "")
        for pair in kotlinSplit(query, "&") {
            if pair.isEmpty { continue }
            let key = pair.substringBefore("=")
            let value = pair.substringAfter("=", missing: "")
            if key == zoomKey {
                guard let forced = kotlinInt(value), (0 ... ZoomSet.maxZoom).contains(forced) else { return nil }
                zoom = forced
            }
        }
        return try? MapRef(sourceId: id, tileZoom: zoom)
    }

    private static func encode(_ id: String) -> String {
        var out = ""
        for byte in id.utf8 {
            if safe.contains(byte) {
                out.unicodeScalars.append(Unicode.Scalar(byte))
            } else {
                out.append("%")
                out.unicodeScalars.append(Unicode.Scalar(hexDigits[Int(byte >> 4)]))
                out.unicodeScalars.append(Unicode.Scalar(hexDigits[Int(byte & 0x0F)]))
            }
        }
        return out
    }

    private static func decode(_ text: String) -> String? {
        var bytes: [UInt8] = []
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count {
            let scalar = scalars[i]
            if scalar == "%" {
                if i + 2 >= scalars.count { return nil }
                let hex = String(String.UnicodeScalarView(scalars[(i + 1) ... (i + 2)]))
                guard let value = Int(hex, radix: 16) else { return nil }
                bytes.append(UInt8(truncatingIfNeeded: value))
                i += 3
            } else {
                bytes.append(contentsOf: Array(String(scalar).utf8))
                i += 1
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
