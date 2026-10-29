// Port of core/src/main/kotlin/fr/toolmap/core/wizard/CapabilitiesDocument.kt.

import Foundation

/// Which OGC tile service a capabilities document describes.
public enum OgcService: String, CaseIterable, Sendable {
    case wmts = "WMTS"
    case wms = "WMS"

    public var keyword: String { rawValue }
}

/// A capabilities document read from a server: the wizard shows its layers and builds a URL from the choice.
public enum CapabilitiesDocument: Equatable, Sendable {
    case wmts(WmtsCapabilities)
    case wms(WmsCapabilities)
}

public enum CapabilitiesReader {
    /// Kotlin `<\s*(?:[A-Za-z0-9_.-]+:)?([A-Za-z_][A-Za-z0-9_.-]*)`.
    private static let root = try! NSRegularExpression(
        pattern: "<[ \\t\\n\\x0B\\f\\r]*(?:[A-Za-z0-9_.-]+:)?([A-Za-z_][A-Za-z0-9_.-]*)"
    )
    private static let declarations = try! NSRegularExpression(
        pattern: "<\\?.*?\\?>",
        options: [.dotMatchesLineSeparators]
    )
    private static let comments = try! NSRegularExpression(pattern: "<!--.*?-->", options: [.dotMatchesLineSeparators])
    private static let doctypes = try! NSRegularExpression(pattern: "<!DOCTYPE[^>]*>", options: [.caseInsensitive])
    private static let headBytes = 4096
    private static let maxErrorText = 300

    /// Reads a WMTS or WMS capabilities document, whichever the root element says. Throws `CapabilitiesError` when it is
    /// neither; a server's own error report (`ExceptionReport`) is turned into the error message so the user sees why
    /// the request was refused.
    public static func read(_ data: Data) throws -> CapabilitiesDocument {
        guard let name = rootName(data) else {
            throw CapabilitiesError("The answer is not an XML document")
        }
        switch name {
        case "Capabilities":
            let capabilities = try WmtsCapabilities.parse(data)
            return .wmts(capabilities)
        case "WMS_Capabilities", "WMT_MS_Capabilities":
            let capabilities = try WmsCapabilities.parse(data)
            return .wms(capabilities)
        case "ExceptionReport", "ServiceExceptionReport":
            throw CapabilitiesError(serviceError(data))
        default:
            throw CapabilitiesError("Not a WMTS or WMS capabilities document (root element <\(name)>)")
        }
    }

    /// Name (without prefix) of the first element, skipping the XML declaration, comments and DOCTYPE.
    static func rootName(_ data: Data) -> String? {
        var head = String(decoding: data.prefix(headBytes), as: UTF8.self)
        head = replace(declarations, head)
        head = replace(comments, head)
        head = replace(doctypes, head)
        let range = NSRange(head.startIndex..., in: head)
        guard let match = root.firstMatch(in: head, options: [], range: range),
              let name = Range(match.range(at: 1), in: head)
        else { return nil }
        return String(head[name])
    }

    private static func replace(_ regex: NSRegularExpression, _ text: String) -> String {
        let range = NSRange(text.startIndex..., in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: " ")
    }

    private static func serviceError(_ data: Data) -> String {
        let handler = ExceptionTextHandler()
        _ = try? OgcXml.parse(data, handler)
        let joined = handler.texts.joined(separator: " / ")
        let text = joined.isEmpty ? "The server answered with an error report" : joined
        return String(text.prefix(maxErrorText))
    }

    /// Collects the texts of an `ExceptionReport` (WMTS) or a `ServiceExceptionReport` (WMS).
    private final class ExceptionTextHandler: PathHandler {
        var texts: [String] = []

        override func end(_ name: String, _ text: String) {
            if name == "ExceptionText" || name == "ServiceException", !text.isEmpty {
                texts.append(text)
            }
        }
    }
}

/// Why an address typed by the user cannot be used to read capabilities.
public enum CapabilitiesUrlProblem: String, CaseIterable, Sendable {
    case empty = "EMPTY"
    case notHttp = "NOT_HTTP"
    /// It is already a tile (`GetTile`) or map (`GetMap`) request, or a `{$z}` template: use it as a plain URL.
    case tileRequest = "TILE_REQUEST"
}

/// Kotlin: `CapabilitiesUrlException` (an `IllegalArgumentException` named after its problem).
public struct CapabilitiesUrlError: Error, Equatable, Sendable, CustomStringConvertible {
    public let problem: CapabilitiesUrlProblem

    public init(_ problem: CapabilitiesUrlProblem) {
        self.problem = problem
    }

    public var description: String { problem.rawValue }
}

public enum CapabilitiesUrl {
    /// The address to download the capabilities from. A bare service address (`https://host/wmts`) gets
    /// `SERVICE=WMTS&REQUEST=GetCapabilities` appended after its own parameters (an `apikey=` stays); an address that
    /// already asks for `GetCapabilities`, or points at a `.xml` document (REST WMTS), is kept as typed.
    public static func normalize(_ input: String, _ service: OgcService) throws -> String {
        let text = kotlinTrim(input)
        if text.isEmpty { throw CapabilitiesUrlError(.empty) }
        let lower = text.lowercased()
        if !lower.hasPrefix("http://"), !lower.hasPrefix("https://") {
            throw CapabilitiesUrlError(.notHttp)
        }
        let withoutFragment = text.substringBefore("#")
        let path = withoutFragment.substringBefore("?")
        let pairs = kotlinSplit(withoutFragment.substringAfter("?", missing: ""), "&").filter { !$0.isEmpty }
        let request = pairs.first(where: { equalsIgnoringCase($0.substringBefore("="), "request") })
        let requestValue = request.map({ $0.substringAfter("=", missing: "") })
        var asksTile = false
        if let value = requestValue {
            asksTile = equalsIgnoringCase(value, "GetTile") || equalsIgnoringCase(value, "GetMap")
        }
        if withoutFragment.contains("{$") || asksTile {
            throw CapabilitiesUrlError(.tileRequest)
        }
        if requestValue != nil || path.lowercased().hasSuffix(".xml") { return withoutFragment }
        let present = Set(pairs.map { $0.substringBefore("=").lowercased() })
        var added: [String] = []
        if !present.contains("service") { added.append("SERVICE=" + service.keyword) }
        added.append("REQUEST=GetCapabilities")
        if service == .wms, !present.contains("version") { added.append("VERSION=1.3.0") }
        return path + "?" + (pairs + added).joined(separator: "&")
    }
}

/// Recognises the image formats a tile server can answer with, from the first bytes.
public enum ImageSniffer {
    public enum Format: String, CaseIterable, Sendable {
        case png = "PNG"
        case jpeg = "JPEG"
        case webp = "WEBP"
        case gif = "GIF"

        public var mime: String {
            switch self {
            case .png: return "image/png"
            case .jpeg: return "image/jpeg"
            case .webp: return "image/webp"
            case .gif: return "image/gif"
            }
        }
    }

    public static func formatOf(_ data: Data) -> Format? {
        let count = data.count
        let head = [UInt8](data.prefix(12))
        func has(_ offset: Int, _ expected: [UInt8]) -> Bool {
            offset + expected.count <= head.count && Array(head[offset ..< offset + expected.count]) == expected
        }
        if count >= 8, has(0, [0x89, 0x50, 0x4E, 0x47]) { return .png }
        if count >= 3, has(0, [0xFF, 0xD8, 0xFF]) { return .jpeg }
        if count >= 12, has(0, [0x52, 0x49, 0x46, 0x46]), has(8, [0x57, 0x45, 0x42, 0x50]) { return .webp }
        if count >= 6, has(0, [0x47, 0x49, 0x46, 0x38]) { return .gif }
        return nil
    }
}
