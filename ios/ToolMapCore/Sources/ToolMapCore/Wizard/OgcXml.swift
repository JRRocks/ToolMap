// Port of core/src/main/kotlin/fr/toolmap/core/wizard/OgcXml.kt.

import Foundation

/// The document is not usable XML, or is one we refuse to read. Kotlin: `CapabilitiesException`.
public struct CapabilitiesError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// Streaming read of OGC capabilities documents (WMTS, WMS): they can be several megabytes (the IGN WMTS one is 3 MB
/// for 720 layers), so a SAX reader keeps only the few fields we need instead of a whole tree.
enum OgcXml {
    /// XLink namespace, for `xlink:href`.
    static let xlink = "http://www.w3.org/1999/xlink"

    static func parse(_ data: Data, _ handler: SaxHandler) throws {
        do {
            try SafeSax.parse(data, handler)
        } catch let error as XmlReadError {
            throw CapabilitiesError(error.message)
        }
    }

    /// `xlink:href` of an element, whichever way the document declares the namespace.
    static func href(_ attributes: SaxAttributes) -> String? {
        attributes.value(uri: xlink, localName: "href") ?? attributes.value("xlink:href")
    }

    /// Java `URLEncoder.encode(value, "UTF-8")` with `/` and `:` left readable (`image/png`, `EPSG:3857`): what the
    /// WMS and WMTS builders put in their query strings.
    static func formEncode(_ value: String) -> String {
        let hex = Array("0123456789ABCDEF".utf8)
        var out = String.UnicodeScalarView()
        for byte in value.utf8 {
            switch byte {
            case UInt8(ascii: "a") ... UInt8(ascii: "z"), UInt8(ascii: "A") ... UInt8(ascii: "Z"),
                 UInt8(ascii: "0") ... UInt8(ascii: "9"),
                 UInt8(ascii: "."), UInt8(ascii: "-"), UInt8(ascii: "*"), UInt8(ascii: "_"),
                 UInt8(ascii: "/"), UInt8(ascii: ":"):
                out.append(Unicode.Scalar(byte))
            case UInt8(ascii: " "):
                out.append("+")
            default:
                out.append("%")
                out.append(Unicode.Scalar(hex[Int(byte >> 4)]))
                out.append(Unicode.Scalar(hex[Int(byte & 0x0F)]))
            }
        }
        return String(out)
    }
}

/// Keeps track of the element path while a SAX read runs, and collects the character data of the current element.
/// Subclasses override `start` and `end`.
class PathHandler: SaxHandler {
    private static let maxText = 20_000

    var path: [String] = []
    private var text = ""
    private var textLength = 0

    /// Name of the element `up` levels above the current one (0 = current).
    func ancestor(_ up: Int) -> String? {
        let index = path.count - 1 - up
        return index >= 0 && index < path.count ? path[index] : nil
    }

    func parentIs(_ name: String) -> Bool { ancestor(1) == name }

    func start(_ name: String, _ attributes: SaxAttributes) {}

    func end(_ name: String, _ text: String) {}

    override func startElement(uri: String, localName: String, qName: String, attributes: SaxAttributes) {
        let name = localName.isEmpty ? qName.substringAfter(":", missing: qName) : localName
        path.append(name)
        text = ""
        textLength = 0
        start(name, attributes)
    }

    override func characters(_ chunk: String) {
        if textLength < PathHandler.maxText {
            text += chunk
            textLength += chunk.utf16.count
        }
    }

    override func endElement(uri: String, localName: String, qName: String) {
        guard let name = path.last else { return }
        end(name, kotlinTrim(text))
        text = ""
        textLength = 0
        path.removeLast()
    }
}
