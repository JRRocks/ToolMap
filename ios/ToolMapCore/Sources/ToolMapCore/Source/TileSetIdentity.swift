// Port of core/src/main/kotlin/fr/toolmap/core/source/TileSetIdentity.kt (TileSetIdentity and StorageKeys).

import CryptoKit
import Foundation

/// Which tiles a level really serves, whatever map file describes it (ADR-031): ToolMap stores tiles by this identity,
/// and every map that serves the same tiles shares them.
///
/// The identity is built from the URL templates of the level, normalized so that only differences that change the
/// image count: `{$param:k}` values substituted then API keys dropped; scheme ignored; host lower-cased with a
/// load-balancing first label (`a.`, `b.`, `mt0.`, `khm1.`) turned into `*`; query parameters sorted, names and values
/// lower-cased; zoom levels left out. The path stays case-sensitive.
public enum TileSetIdentity {
    private static let keyNames: Set<String> = [
        "apikey", "api_key", "api-key", "key", "token", "access_token", "accesstoken", "appid", "app_id",
    ]
    private static let hashChars = 16
    private static let storagePrefix = "t-"
    /// Kotlin `^([a-z]|[a-z]{0,4}[0-9]{1,2}|\{\$[a-z]+\})$`.
    private static let rotatingLabel = try! NSRegularExpression(
        pattern: "^([a-z]|[a-z]{0,4}[0-9]{1,2}|\\{\\$[a-z]+\\})$"
    )

    /// Identity of the tiles of `level`, or nil for a level without a server (local package, map reference).
    public static func of(_ source: TileSource, _ level: LevelDef) -> String? {
        if !level.hasServer { return nil }
        let servers = Array(Set(level.servers.map { normalizeServer($0, params: source.params) })).sorted()
        let usesBbox = level.servers.contains { $0.contains("bbox}") }
        var text = servers.joined(separator: "|")
        text += "#\(level.tileSize)"
        if usesBbox { text += "#\(level.bboxCrs)" }
        return text
    }

    /// The identities of every level of `source` that has a server.
    public static func allOf(_ source: TileSource) -> Set<String> {
        Set(source.levels.compactMap { of(source, $0) })
    }

    /// Name of the storage of the tiles of `identity` (`t-` + 16 hexadecimal characters of its SHA-1).
    public static func storageIdOf(_ identity: String) -> String {
        let digest = Insecure.SHA1.hash(data: Data(identity.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return storagePrefix + String(hex.prefix(hashChars))
    }

    /// One URL template in normalized form (see the type comment).
    public static func normalizeServer(_ template: String, params: [String: String] = [:]) -> String {
        let expanded = substituteParams(kotlinTrim(template), params)
        let noScheme = removeScheme(expanded).substringBefore("#")
        let authorityEnd = noScheme.firstIndex { $0 == "/" || $0 == "?" } ?? noScheme.endIndex
        let authorityText = String(noScheme[..<authorityEnd])
        let authority = (authorityText.lastIndex(of: "@").map { String(authorityText[authorityText.index(after: $0)...]) }
            ?? authorityText).lowercased()
        let rest = String(noScheme[authorityEnd...])
        let path = rest.substringBefore("?")
        let query = rest.substringAfter("?", missing: "")
        return normalizeHost(authority) + path + normalizeQuery(query)
    }

    /// Replaces each `{$param:key}` that has a value; an unknown key is left as it is.
    private static func substituteParams(_ text: String, _ params: [String: String]) -> String {
        let scalars = Array(text.unicodeScalars)
        var out = String.UnicodeScalarView()
        let opening = Array("{$param:".unicodeScalars)
        var i = 0
        while i < scalars.count {
            if i + opening.count < scalars.count, Array(scalars[i ..< i + opening.count]) == opening,
               let close = scalars[(i + opening.count)...].firstIndex(of: "}"), close > i + opening.count
            {
                let key = String(String.UnicodeScalarView(scalars[(i + opening.count) ..< close]))
                if let value = params[key] {
                    out.append(contentsOf: value.unicodeScalars)
                } else {
                    out.append(contentsOf: scalars[i ... close])
                }
                i = close + 1
            } else {
                out.append(scalars[i])
                i += 1
            }
        }
        return String(out)
    }

    /// Kotlin `replaceFirst(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")`.
    private static func removeScheme(_ text: String) -> String {
        let scalars = Array(text.unicodeScalars)
        func isLetter(_ s: Unicode.Scalar) -> Bool { ("a" ... "z").contains(s) || ("A" ... "Z").contains(s) }
        guard let first = scalars.first, isLetter(first) else { return text }
        var i = 1
        while i < scalars.count, isLetter(scalars[i]) || ("0" ... "9").contains(scalars[i]) || "+.-".unicodeScalars.contains(scalars[i]) {
            i += 1
        }
        guard i + 2 < scalars.count, scalars[i] == ":", scalars[i + 1] == "/", scalars[i + 2] == "/" else { return text }
        return String(String.UnicodeScalarView(scalars[(i + 3)...]))
    }

    private static func normalizeHost(_ authority: String) -> String {
        let host = authority.substringBefore(":")
        let port = authority.substringAfter(":", missing: "")
        let labels = kotlinSplit(host, ".")
        let normalized: String
        if labels.count >= 3, matchesRotatingLabel(labels[0]) {
            normalized = (["*"] + labels.dropFirst()).joined(separator: ".")
        } else {
            normalized = host
        }
        return port.isEmpty ? normalized : "\(normalized):\(port)"
    }

    private static func matchesRotatingLabel(_ label: String) -> Bool {
        let range = NSRange(label.startIndex..., in: label)
        return rotatingLabel.firstMatch(in: label, range: range) != nil
    }

    private static func normalizeQuery(_ query: String) -> String {
        var pairs: [String] = []
        for pair in kotlinSplit(query, "&") where !pair.isEmpty {
            let name = pair.substringBefore("=").lowercased()
            let value = pair.substringAfter("=", missing: "").lowercased()
            let hasValue = pair.contains("=")
            if name.isEmpty || keyNames.contains(name) || (hasValue && value.isEmpty) { continue }
            pairs.append(hasValue ? "\(name)=\(value)" : name)
        }
        pairs.sort()
        return pairs.isEmpty ? "" : "?" + pairs.joined(separator: "&")
    }
}

/// Where the tiles of a map are stored, zoom by zoom (ADR-031): an ordinary level in the storage of its
/// `TileSetIdentity` (named after the built-in map that serves the same tiles, `t-<hash>` otherwise); a local package in
/// the map's own id; a `MapRef` level in the storage of the referenced map, nothing when the zoom is forced to another
/// level (those tiles are built from the referenced map's tiles, never stored).
public struct StorageKeys {
    /// Longest chain of maps referencing maps that is followed (a cycle stops here).
    public static let maxDepth = 4
    private static let storagePrefix = "t-"

    private let aliases: [String: String]

    public init(builtins: [TileSource]) {
        var aliases: [String: String] = [:]
        for source in builtins {
            for level in source.levels {
                guard let identity = TileSetIdentity.of(source, level) else { continue }
                if aliases[identity] == nil { aliases[identity] = source.id }
            }
        }
        self.aliases = aliases
    }

    /// Shared storages (`t-<hash>`) whose tiles a built-in map now serves, each mapped to that map's storage.
    public var takenOverByBuiltins: [String: String] {
        var out: [String: String] = [:]
        for (identity, id) in aliases {
            out[TileSetIdentity.storageIdOf(identity)] = id
        }
        return out
    }

    /// Storage of the tile of zoom `zoom` of `source`, or nil when such a tile is never stored.
    public func storageIdFor(_ source: TileSource, _ zoom: Int, lookup: (String) -> TileSource?) -> String? {
        resolve(source, zoom, lookup, 0)
    }

    /// Storage of `level` of `source` at its own tile zoom (nil for a reference).
    public func storageIdOf(_ source: TileSource, _ level: LevelDef) -> String? {
        if level.isLocalOnly { return source.id }
        if level.mapRef != nil { return nil }
        guard let identity = TileSetIdentity.of(source, level) else { return nil }
        return aliases[identity] ?? TileSetIdentity.storageIdOf(identity)
    }

    /// Every storage the tiles shown by `source` can come from (several for a multi-zoom map), in order.
    public func storageIdsOf(_ source: TileSource, lookup: (String) -> TileSource?) -> [String] {
        var out: [String] = []
        collect(source, lookup, &out, 0)
        return out
    }

    private func collect(_ source: TileSource, _ lookup: (String) -> TileSource?, _ out: inout [String], _ depth: Int) {
        if depth > StorageKeys.maxDepth { return }
        for level in source.levels {
            guard let reference = level.mapRef else {
                if let id = storageIdOf(source, level), !out.contains(id) { out.append(id) }
                continue
            }
            guard let member = lookup(reference.sourceId), member.id != source.id else { continue }
            if let forced = reference.tileZoom {
                if let id = resolve(member, forced, lookup, depth + 1), !out.contains(id) { out.append(id) }
            } else {
                collect(member, lookup, &out, depth + 1)
            }
        }
    }

    private func resolve(_ source: TileSource, _ zoom: Int, _ lookup: (String) -> TileSource?, _ depth: Int) -> String? {
        if depth > StorageKeys.maxDepth { return nil }
        guard let level = source.levelFor(zoom) else { return nil }
        guard let reference = level.mapRef else { return storageIdOf(source, level) }
        if let forced = reference.tileZoom, forced != zoom { return nil }
        guard let member = lookup(reference.sourceId) else { return nil }
        return resolve(member, zoom, lookup, depth + 1)
    }

    /// True when `storageId` names the storage of a built-in map or a shared tile set (not a map's own file).
    public func isSharedStorage(_ storageId: String) -> Bool {
        aliases.values.contains(storageId) || storageId.hasPrefix(StorageKeys.storagePrefix)
    }
}
