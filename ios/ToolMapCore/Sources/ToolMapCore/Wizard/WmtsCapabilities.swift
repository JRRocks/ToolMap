// Port of core/src/main/kotlin/fr/toolmap/core/wizard/WmtsCapabilities.kt.

import Foundation

public struct WmtsStyle: Equatable, Hashable, Sendable {
    public let id: String
    public let isDefault: Bool

    public init(id: String, isDefault: Bool) {
        self.id = id
        self.isDefault = isDefault
    }
}

/// A layer's link to a tile matrix set, with the zooms the layer actually has in it (from its limits, if given).
public struct WmtsMatrixLink: Equatable, Hashable, Sendable {
    public let matrixSetId: String
    public let minZoom: Int?
    public let maxZoom: Int?

    public init(matrixSetId: String, minZoom: Int?, maxZoom: Int?) {
        self.matrixSetId = matrixSetId
        self.minZoom = minZoom
        self.maxZoom = maxZoom
    }
}

public struct WmtsResourceUrl: Equatable, Hashable, Sendable {
    public let format: String
    public let resourceType: String
    public let template: String

    public init(format: String, resourceType: String, template: String) {
        self.format = format
        self.resourceType = resourceType
        self.template = template
    }
}

public struct WmtsLayer: Equatable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let formats: [String]
    public let styles: [WmtsStyle]
    public let links: [WmtsMatrixLink]
    public let resourceUrls: [WmtsResourceUrl]

    public init(
        id: String,
        title: String,
        formats: [String],
        styles: [WmtsStyle],
        links: [WmtsMatrixLink],
        resourceUrls: [WmtsResourceUrl]
    ) {
        self.id = id
        self.title = title
        self.formats = formats
        self.styles = styles
        self.links = links
        self.resourceUrls = resourceUrls
    }
}

public struct WmtsMatrixSet: Equatable, Hashable, Sendable {
    public static let defaultTileSize = 256

    public let id: String
    public let crs: String
    public let tileSize: Int
    /// Zooms read from the numeric matrix identifiers (`0`...`19`), or nil when they are not numeric.
    public let zooms: ClosedRange<Int>?

    public init(id: String, crs: String, tileSize: Int, zooms: ClosedRange<Int>?) {
        self.id = id
        self.crs = crs
        self.tileSize = tileSize
        self.zooms = zooms
    }

    public var isWebMercator: Bool { WmtsCapabilities.isWebMercator(crs) }
}

/// What a WMTS server offers, reduced to what the add-a-map wizard needs.
public struct WmtsCapabilities: Equatable, Hashable, Sendable {
    private static let mercatorCodes = ["3857", "900913", "3785", "102100", "102113"]

    public let title: String?
    public let provider: String?
    /// Base URL of GetTile requests (KVP), when the server declares one.
    public let getTileHref: String?
    public let layers: [WmtsLayer]
    public let matrixSets: [String: WmtsMatrixSet]

    public init(
        title: String?,
        provider: String?,
        getTileHref: String?,
        layers: [WmtsLayer],
        matrixSets: [String: WmtsMatrixSet]
    ) {
        self.title = title
        self.provider = provider
        self.getTileHref = getTileHref
        self.layers = layers
        self.matrixSets = matrixSets
    }

    /// Links of `layer` to matrix sets ToolMap can display (Web Mercator).
    public func usableLinks(_ layer: WmtsLayer) -> [WmtsMatrixLink] {
        layer.links.filter { matrixSets[$0.matrixSetId]?.isWebMercator == true }
    }

    /// Layers that have at least one usable matrix set.
    public var usableLayers: [WmtsLayer] { layers.filter { !usableLinks($0).isEmpty } }

    public static func isWebMercator(_ crs: String) -> Bool {
        mercatorCodes.contains { crs.hasSuffix($0) }
    }

    /// Throws `CapabilitiesError` when the document is not readable or has neither layer nor matrix set.
    public static func parse(_ data: Data) throws -> WmtsCapabilities {
        let handler = Handler()
        try OgcXml.parse(data, handler)
        return try handler.result()
    }

    /// The zoom a matrix identifier stands for: `12`, `EPSG:3857:12`, `PM_12` -> 12.
    static func zoomOf(_ identifier: String) -> Int? {
        if let zoom = kotlinInt(identifier) { return zoom }
        return kotlinInt(afterLast(afterLast(identifier, ":"), "_"))
    }

    /// Kotlin `substringAfterLast(delimiter)`: the whole text when the delimiter is missing.
    private static func afterLast(_ text: String, _ delimiter: Character) -> String {
        guard let index = text.lastIndex(of: delimiter) else { return text }
        return String(text[text.index(after: index)...])
    }

    private final class LayerBuilder {
        var id = ""
        var title = ""
        var formats: [String] = []
        var styles: [WmtsStyle] = []
        var links: [WmtsMatrixLink] = []
        var resourceUrls: [WmtsResourceUrl] = []
        var styleId = ""
        var styleDefault = false
        var linkId = ""
        var linkZooms: [Int] = []

        func build() -> WmtsLayer {
            WmtsLayer(
                id: id,
                title: title.isEmpty ? id : title,
                formats: formats,
                styles: styles,
                links: links,
                resourceUrls: resourceUrls
            )
        }
    }

    private final class SetBuilder {
        var id = ""
        var crs = ""
        var tileSize = 0
        var zooms: [Int?] = []

        func build() -> WmtsMatrixSet {
            var range: ClosedRange<Int>?
            if !zooms.isEmpty, !zooms.contains(where: { $0 == nil }) {
                let values = zooms.compactMap { $0 }
                if let low = values.min(), let high = values.max() {
                    range = low ... high
                }
            }
            return WmtsMatrixSet(
                id: id,
                crs: crs,
                tileSize: tileSize > 0 ? tileSize : WmtsMatrixSet.defaultTileSize,
                zooms: range
            )
        }
    }

    private final class Handler: PathHandler {
        private var title: String?
        private var provider: String?
        private var getTileHref: String?
        private var operation: String?
        private var layers: [WmtsLayer] = []
        private var sets: [String: WmtsMatrixSet] = [:]
        private var layer: LayerBuilder?
        private var matrixSet: SetBuilder?

        func result() throws -> WmtsCapabilities {
            if layers.isEmpty, sets.isEmpty { throw CapabilitiesError("No WMTS layer found in the document") }
            return WmtsCapabilities(
                title: title,
                provider: provider,
                getTileHref: getTileHref,
                layers: layers,
                matrixSets: sets
            )
        }

        override func start(_ name: String, _ attributes: SaxAttributes) {
            switch name {
            case "Operation":
                operation = attributes.value("name")
            case "Get":
                if operation == "GetTile", getTileHref == nil { getTileHref = OgcXml.href(attributes) }
            case "Layer":
                if parentIs("Contents") { layer = LayerBuilder() }
            case "Style":
                layer?.styleDefault = attributes.value("isDefault") == "true"
            case "TileMatrixSet":
                if parentIs("Contents") { matrixSet = SetBuilder() }
            case "TileMatrixSetLink":
                if let builder = layer {
                    builder.linkId = ""
                    builder.linkZooms.removeAll()
                }
            case "ResourceURL":
                layer?.resourceUrls.append(
                    WmtsResourceUrl(
                        format: attributes.value("format") ?? "",
                        resourceType: attributes.value("resourceType") ?? "",
                        template: attributes.value("template") ?? ""
                    )
                )
            default:
                break
            }
        }

        override func end(_ name: String, _ text: String) {
            if name == "Operation" {
                operation = nil
            } else if name == "Title", parentIs("ServiceIdentification") {
                title = text.nilIfEmpty
            } else if name == "ProviderName" {
                provider = text.nilIfEmpty
            } else if let builder = layer {
                endInLayer(builder, name, text)
            } else if let builder = matrixSet {
                endInSet(builder, name, text)
            }
        }

        private func endInLayer(_ builder: LayerBuilder, _ name: String, _ text: String) {
            if name == "Identifier", parentIs("Layer") {
                builder.id = text
            } else if name == "Title", parentIs("Layer") {
                builder.title = text
            } else if name == "Format", parentIs("Layer") {
                if !text.isEmpty { builder.formats.append(text) }
            } else if name == "Identifier", parentIs("Style") {
                builder.styleId = text
            } else if name == "Style" {
                if !builder.styleId.isEmpty {
                    builder.styles.append(WmtsStyle(id: builder.styleId, isDefault: builder.styleDefault))
                    builder.styleId = ""
                }
            } else if name == "TileMatrixSet", parentIs("TileMatrixSetLink") {
                builder.linkId = text
            } else if name == "TileMatrix", parentIs("TileMatrixLimits") {
                if let zoom = WmtsCapabilities.zoomOf(text) { builder.linkZooms.append(zoom) }
            } else if name == "TileMatrixSetLink" {
                if !builder.linkId.isEmpty {
                    let link = WmtsMatrixLink(
                        matrixSetId: builder.linkId,
                        minZoom: builder.linkZooms.min(),
                        maxZoom: builder.linkZooms.max()
                    )
                    builder.links.append(link)
                }
            } else if name == "Layer", parentIs("Contents") {
                if !builder.id.isEmpty { layers.append(builder.build()) }
                layer = nil
            }
        }

        private func endInSet(_ builder: SetBuilder, _ name: String, _ text: String) {
            if name == "Identifier", parentIs("TileMatrixSet") {
                builder.id = text
            } else if name == "SupportedCRS" {
                builder.crs = text
            } else if name == "Identifier", parentIs("TileMatrix") {
                builder.zooms.append(WmtsCapabilities.zoomOf(text))
            } else if name == "TileWidth" {
                if builder.tileSize == 0 { builder.tileSize = kotlinInt(text) ?? 0 }
            } else if name == "TileMatrixSet", parentIs("Contents") {
                if !builder.id.isEmpty { sets[builder.id] = builder.build() }
                matrixSet = nil
            }
        }
    }
}

/// The user's choices among what a `WmtsCapabilities` offers.
public struct WmtsChoice: Equatable, Hashable, Sendable {
    public let layerId: String
    public let styleId: String
    public let matrixSetId: String
    public let format: String

    public init(layerId: String, styleId: String, matrixSetId: String, format: String) {
        self.layerId = layerId
        self.styleId = styleId
        self.matrixSetId = matrixSetId
        self.format = format
    }
}

/// What a `WmtsChoice` turns into: a URL template and the settings that go with it.
public struct WmtsSourceSpec: Equatable, Hashable, Sendable {
    public let template: String
    public let minZoom: Int
    public let maxZoom: Int
    public let tileSize: Int
    public let name: String
    public let copyright: String?
    /// True when the template came from a REST `ResourceURL` rather than a KVP query.
    public let rest: Bool

    public init(
        template: String,
        minZoom: Int,
        maxZoom: Int,
        tileSize: Int,
        name: String,
        copyright: String?,
        rest: Bool
    ) {
        self.template = template
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.tileSize = tileSize
        self.name = name
        self.copyright = copyright
        self.rest = rest
    }
}

public enum WmtsSourceBuilder {
    private static let defaultMaxZoom = 19

    /// A sensible starting choice for `layer`: default style, a Web Mercator set (`PM` first), first format.
    public static func defaultChoice(_ caps: WmtsCapabilities, _ layer: WmtsLayer) -> WmtsChoice? {
        let links = caps.usableLinks(layer)
        let preferred = links.first(where: { $0.matrixSetId == "PM" })
            ?? links.first(where: { $0.matrixSetId == "GoogleMapsCompatible" })
            ?? links.first
        guard let link = preferred else { return nil }
        let style = layer.styles.first(where: { $0.isDefault }) ?? layer.styles.first
        return WmtsChoice(
            layerId: layer.id,
            styleId: style?.id ?? "",
            matrixSetId: link.matrixSetId,
            format: layer.formats.first ?? "image/png"
        )
    }

    /// Builds the URL template for `choice`. `capabilitiesUrl` is the address the document was read from; it is the
    /// fallback base for KVP requests when the document does not declare one. Throws `ToolMapError` for an unknown
    /// layer or matrix set, or one that is not Web Mercator.
    public static func build(
        _ caps: WmtsCapabilities,
        _ choice: WmtsChoice,
        _ capabilitiesUrl: String
    ) throws -> WmtsSourceSpec {
        guard let layer = caps.layers.first(where: { $0.id == choice.layerId }) else {
            throw ToolMapError.invalidArgument("Unknown layer \(choice.layerId)")
        }
        guard let matrixSet = caps.matrixSets[choice.matrixSetId] else {
            throw ToolMapError.invalidArgument("Unknown matrix set \(choice.matrixSetId)")
        }
        try require(matrixSet.isWebMercator, "Matrix set \(matrixSet.id) is not Web Mercator (\(matrixSet.crs))")
        let link = layer.links.first { $0.matrixSetId == matrixSet.id }
        let setFirst = matrixSet.zooms?.lowerBound ?? 0
        let setLast = matrixSet.zooms?.upperBound ?? defaultMaxZoom
        let minZoom = Swift.max(link?.minZoom ?? setFirst, setFirst)
        let maxZoom = Swift.min(link?.maxZoom ?? setLast, setLast)

        let rest = layer.resourceUrls.first { $0.resourceType == "tile" && $0.format == choice.format }
        let template: String
        if let resource = rest {
            template = restTemplate(resource.template, choice)
        } else {
            template = kvpTemplate(caps.getTileHref ?? capabilitiesUrl, choice)
        }
        return WmtsSourceSpec(
            template: template,
            minZoom: minZoom,
            maxZoom: Swift.max(minZoom, maxZoom),
            tileSize: matrixSet.tileSize,
            name: layer.title,
            copyright: caps.provider,
            rest: rest != nil
        )
    }

    private static func restTemplate(_ template: String, _ choice: WmtsChoice) -> String {
        let replacements = [
            ("{Layer}", choice.layerId),
            ("{Style}", choice.styleId),
            ("{TileMatrixSet}", choice.matrixSetId),
            ("{TileMatrix}", "{$z}"),
            ("{TileRow}", "{$y}"),
            ("{TileCol}", "{$x}"),
        ]
        var text = template
        for (key, value) in replacements {
            text = text.replacingOccurrences(of: key, with: value, options: [.caseInsensitive])
        }
        return text
    }

    private static func kvpTemplate(_ base: String, _ choice: WmtsChoice) -> String {
        let path = base.substringBefore("?")
        let existing = kotlinSplit(base.substringAfter("?", missing: ""), "&").filter { !$0.isEmpty }
        let present = Set(existing.map { $0.substringBefore("=").lowercased() })
        let wanted: [(key: String, value: String)] = [
            ("SERVICE", "WMTS"),
            ("REQUEST", "GetTile"),
            ("VERSION", "1.0.0"),
            ("LAYER", OgcXml.formEncode(choice.layerId)),
            ("STYLE", OgcXml.formEncode(choice.styleId)),
            ("TILEMATRIXSET", OgcXml.formEncode(choice.matrixSetId)),
            ("TILEMATRIX", "{$z}"),
            ("TILEROW", "{$y}"),
            ("TILECOL", "{$x}"),
            ("FORMAT", OgcXml.formEncode(choice.format)),
        ]
        let added = wanted.filter { !present.contains($0.key.lowercased()) }.map { $0.key + "=" + $0.value }
        return path + "?" + (existing + added).joined(separator: "&")
    }
}
