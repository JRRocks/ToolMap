// Port of core/src/main/kotlin/fr/toolmap/core/xml/SafeSax.kt on Foundation's XMLParser.

import Foundation
#if canImport(FoundationXML)
    import FoundationXML
#endif

/// The document is not usable XML, or is one we refuse to read (Kotlin `XmlReadException`).
public struct XmlReadError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(message: String) {
        self.message = message
    }

    public var description: String { message }
}

/// The attributes of an element, as SAX gives them (Kotlin `org.xml.sax.Attributes`). XMLParser does not keep their
/// order: they are listed by qualified name.
public struct SaxAttributes: Sendable {
    public struct Entry: Sendable {
        public let qName: String
        public let localName: String
        public let uri: String
        public let value: String
    }

    public let entries: [Entry]

    public var count: Int { entries.count }

    /// `getValue(qName)`.
    public func value(_ qName: String) -> String? {
        entries.first { $0.qName == qName }?.value
    }

    /// `getValue(uri, localName)`.
    public func value(uri: String, localName: String) -> String? {
        entries.first { $0.uri == uri && $0.localName == localName }?.value
    }

    public func qName(_ index: Int) -> String { entries[index].qName }

    public func value(at index: Int) -> String { entries[index].value }
}

/// What a SAX reader receives (Kotlin `DefaultHandler`): override the events you need.
open class SaxHandler {
    public init() {}

    open func startPrefixMapping(prefix: String, uri: String) {}

    open func startElement(uri: String, localName: String, qName: String, attributes: SaxAttributes) {}

    open func characters(_ text: String) {}

    open func endElement(uri: String, localName: String, qName: String) {}
}

/// Namespace-aware SAX read of untrusted XML (capabilities, GPX, KML...). External entities and DTDs are never fetched;
/// a `<!DOCTYPE ... SYSTEM "...">` line is blanked out and a DOCTYPE with an internal subset or any entity declaration
/// is refused (XXE, entity expansion).
public enum SafeSax {
    private static let headerScan = 8192

    /// Reads `data` and sends its events to `handler`. Throws `XmlReadError`.
    public static func parse(_ data: Data, _ handler: SaxHandler) throws {
        let prepared = try neutralizeDoctype(data)
        let parser = XMLParser(data: prepared)
        parser.shouldProcessNamespaces = true
        parser.shouldReportNamespacePrefixes = true
        parser.shouldResolveExternalEntities = false
        let bridge = Bridge(handler)
        parser.delegate = bridge
        if !parser.parse() {
            let reason = parser.parserError?.localizedDescription ?? "unknown error"
            throw XmlReadError(message: "Not a readable XML document: \(reason)")
        }
    }

    /// Reads the file at `url`.
    public static func parse(contentsOf url: URL, _ handler: SaxHandler) throws {
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw XmlReadError(message: "Cannot read the document: \(error.localizedDescription)")
        }
        try parse(data, handler)
    }

    private static func neutralizeDoctype(_ data: Data) throws -> Data {
        let head = data.prefix(headerScan)
        // ISO-8859-1: one character per byte, so indices in the text are indices in the bytes.
        let text = String(head.map { Character(Unicode.Scalar($0)) })
        if text.range(of: "<!ENTITY", options: .caseInsensitive) != nil {
            throw XmlReadError(message: "Entity declarations are not allowed")
        }
        guard let start = text.range(of: "<!DOCTYPE", options: .caseInsensitive) else { return data }
        // Offsets in Unicode scalars, one per byte ("\r\n" would be one Character).
        let scalars = text.unicodeScalars
        let startOffset = scalars.distance(from: scalars.startIndex, to: start.lowerBound)
        let afterStart = text[start.lowerBound...]
        let endIndex = afterStart.firstIndex(of: ">")
        let subsetIndex = afterStart.firstIndex(of: "[")
        if endIndex == nil || (subsetIndex != nil && subsetIndex! < endIndex!) {
            throw XmlReadError(message: "DOCTYPE with an internal subset is not allowed")
        }
        let endOffset = scalars.distance(from: scalars.startIndex, to: endIndex!)
        var copy = data
        let base = copy.startIndex
        for i in startOffset ... endOffset {
            copy[base + i] = UInt8(ascii: " ")
        }
        return copy
    }

    /// XMLParser's delegate, turning its callbacks into SAX events.
    private final class Bridge: NSObject, XMLParserDelegate {
        private let handler: SaxHandler
        /// Namespaces in scope, innermost last, to find the namespace of a prefixed attribute.
        private var prefixes: [(prefix: String, uri: String)] = []

        init(_ handler: SaxHandler) {
            self.handler = handler
        }

        func parser(_ parser: XMLParser, didStartMappingPrefix prefix: String, toURI namespaceURI: String) {
            prefixes.append((prefix, namespaceURI))
            handler.startPrefixMapping(prefix: prefix, uri: namespaceURI)
        }

        func parser(_ parser: XMLParser, didEndMappingPrefix prefix: String) {
            if let index = prefixes.lastIndex(where: { $0.prefix == prefix }) {
                prefixes.remove(at: index)
            }
        }

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String] = [:]
        ) {
            var entries: [SaxAttributes.Entry] = []
            for name in attributeDict.keys.sorted() {
                let local: String
                let uri: String
                if let colon = name.firstIndex(of: ":") {
                    let prefix = String(name[..<colon])
                    local = String(name[name.index(after: colon)...])
                    uri = prefixes.last { $0.prefix == prefix }?.uri ?? ""
                } else {
                    local = name
                    uri = ""
                }
                entries.append(SaxAttributes.Entry(qName: name, localName: local, uri: uri, value: attributeDict[name] ?? ""))
            }
            handler.startElement(
                uri: namespaceURI ?? "",
                localName: elementName,
                qName: qName ?? elementName,
                attributes: SaxAttributes(entries: entries)
            )
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            handler.characters(string)
        }

        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
            handler.characters(String(decoding: CDATABlock, as: UTF8.self))
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?
        ) {
            handler.endElement(uri: namespaceURI ?? "", localName: elementName, qName: qName ?? elementName)
        }
    }
}
