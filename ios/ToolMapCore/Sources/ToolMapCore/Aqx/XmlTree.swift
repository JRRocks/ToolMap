// The DOM that core/src/main/kotlin/fr/toolmap/core/aqx/AqxParser.kt gets from the JVM `DocumentBuilder`
// (no namespaces, CDATA merged with the text, comments dropped), built here with Foundation's `XMLParser`.

import Foundation
#if canImport(FoundationXML)
    import FoundationXML
#endif

/// An element of a parsed document: its tag, its attributes and its content in document order.
final class XmlElement {
    enum Node {
        case element(XmlElement)
        case text(String)
    }

    let tagName: String
    let attributes: [String: String]
    fileprivate(set) var content: [Node] = []

    init(tagName: String, attributes: [String: String]) {
        self.tagName = tagName
        self.attributes = attributes
    }

    /// DOM `getAttribute`: the value, or an empty text when the attribute is missing.
    func attribute(_ name: String) -> String {
        attributes[name] ?? ""
    }

    /// The child elements, in document order.
    var elements: [XmlElement] {
        var result: [XmlElement] = []
        for node in content {
            if case let .element(element) = node { result.append(element) }
        }
        return result
    }

    /// DOM `textContent`: the text of the element and of all its descendants, in document order.
    var textContent: String {
        var text = ""
        appendText(to: &text)
        return text
    }

    private func appendText(to text: inout String) {
        for node in content {
            switch node {
            case let .text(value): text += value
            case let .element(element): element.appendText(to: &text)
            }
        }
    }
}


enum XmlTree {
    /// The root element of `text`. External entities are never resolved; the caller refuses DOCTYPEs beforehand.
    static func parse(_ text: String) throws -> XmlElement {
        let builder = TreeBuilder()
        let parser = XMLParser(data: Data(declaringUtf8(text).utf8))
        parser.shouldProcessNamespaces = false
        parser.shouldReportNamespacePrefixes = false
        parser.shouldResolveExternalEntities = false
        parser.delegate = builder
        let parsed = parser.parse()
        guard parsed, let root = builder.root else {
            throw XmlReadError(message: parser.parserError?.localizedDescription ?? "no root element")
        }
        return root
    }

    /// The text is handed to the parser as UTF-8: an XML declaration naming another encoding is changed to UTF-8,
    /// as the JVM parser reading characters ignores it.
    static func declaringUtf8(_ text: String) -> String {
        guard text.hasPrefix("<?xml"), let end = text.range(of: "?>") else { return text }
        let declaration = text[..<end.lowerBound]
        guard let name = declaration.range(of: "encoding") else { return text }
        var index = name.upperBound
        func skipSpaces() {
            while index < declaration.endIndex, declaration[index] == " " || declaration[index] == "\t" {
                index = declaration.index(after: index)
            }
        }
        skipSpaces()
        guard index < declaration.endIndex, declaration[index] == "=" else { return text }
        index = declaration.index(after: index)
        skipSpaces()
        guard index < declaration.endIndex, declaration[index] == "\"" || declaration[index] == "'" else { return text }
        let quote = declaration[index]
        let valueStart = declaration.index(after: index)
        guard let valueEnd = declaration[valueStart...].firstIndex(of: quote) else { return text }
        var result = text
        result.replaceSubrange(valueStart ..< valueEnd, with: "UTF-8")
        return result
    }
}

private final class TreeBuilder: NSObject, XMLParserDelegate {
    private var stack: [XmlElement] = []
    private(set) var root: XmlElement?

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let element = XmlElement(tagName: elementName, attributes: attributeDict)
        if let parent = stack.last {
            parent.content.append(.element(element))
        } else if root == nil {
            root = element
        }
        stack.append(element)
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        if !stack.isEmpty { stack.removeLast() }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        stack.last?.content.append(.text(string))
    }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        stack.last?.content.append(.text(String(decoding: CDATABlock, as: UTF8.self)))
    }
}
