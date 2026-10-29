// Port of core/src/main/kotlin/fr/toolmap/core/wizard/WmsCapabilities.kt.

import Foundation

public struct WmsLayer: Equatable, Hashable, Sendable {
    public let name: String
    public let title: String
    /// CRS codes the layer can be drawn in (own and inherited from parent layers).
    public let crs: Set<String>

    public init(name: String, title: String, crs: Set<String>) {
        self.name = name
        self.title = title
        self.crs = crs
    }

    /// ToolMap asks WMS servers for Web Mercator images: the one CRS whose axis order and units never surprise.
    public var supportsWebMercator: Bool { crs.contains { WmtsCapabilities.isWebMercator($0) } }
}

/// What a WMS server offers, reduced to what the add-a-map wizard needs.
public struct WmsCapabilities: Equatable, Hashable, Sendable {
    public let version: String
    public let title: String?
    public let formats: [String]
    public let getMapHref: String?
    public let layers: [WmsLayer]

    public init(version: String, title: String?, formats: [String], getMapHref: String?, layers: [WmsLayer]) {
        self.version = version
        self.title = title
        self.formats = formats
        self.getMapHref = getMapHref
        self.layers = layers
    }

    public var usableLayers: [WmsLayer] { layers.filter(\.supportsWebMercator) }

    /// Throws `CapabilitiesError` when the document is not readable or has no layer.
    public static func parse(_ data: Data) throws -> WmsCapabilities {
        let handler = Handler()
        try OgcXml.parse(data, handler)
        return try handler.result()
    }

    private final class LayerFrame {
        let inheritedCrs: Set<String>
        var name = ""
        var title = ""
        var crs = Set<String>()

        init(inheritedCrs: Set<String>) {
            self.inheritedCrs = inheritedCrs
        }
    }

    private final class Handler: PathHandler {
        private var version = ""
        private var title: String?
        private var formats: [String] = []
        private var getMapHref: String?
        private var layers: [WmsLayer] = []
        private var frames: [LayerFrame] = []
        private var inGetMap = false

        func result() throws -> WmsCapabilities {
            if layers.isEmpty { throw CapabilitiesError("No WMS layer found in the document") }
            return WmsCapabilities(
                version: version.isEmpty ? "1.3.0" : version,
                title: title,
                formats: formats,
                getMapHref: getMapHref,
                layers: layers
            )
        }

        override func start(_ name: String, _ attributes: SaxAttributes) {
            switch name {
            case "WMS_Capabilities", "WMT_MS_Capabilities":
                version = attributes.value("version") ?? ""
            case "GetMap":
                inGetMap = true
            case "OnlineResource":
                if inGetMap, getMapHref == nil { getMapHref = OgcXml.href(attributes) }
            case "Layer":
                let inherited = frames.last.map { $0.inheritedCrs.union($0.crs) } ?? Set<String>()
                frames.append(LayerFrame(inheritedCrs: inherited))
            default:
                break
            }
        }

        override func end(_ name: String, _ text: String) {
            let current = frames.last
            if name == "GetMap" {
                inGetMap = false
            } else if name == "Format", inGetMap {
                if !text.isEmpty { formats.append(text) }
            } else if name == "Title", parentIs("Service") {
                title = text.nilIfEmpty
            } else if let frame = current, name == "Name", parentIs("Layer") {
                frame.name = text
            } else if let frame = current, name == "Title", parentIs("Layer") {
                frame.title = text
            } else if let frame = current, name == "CRS" || name == "SRS", parentIs("Layer") {
                // WMS 1.1.1 may list several space-separated codes in one SRS element.
                for code in kotlinSplit(text, " ", "\n", "\t") where !code.isEmpty {
                    frame.crs.insert(code.uppercased())
                }
            } else if let frame = current, name == "Layer" {
                if !frame.name.isEmpty {
                    let layerTitle = frame.title.isEmpty ? frame.name : frame.title
                    let layerCrs = frame.inheritedCrs.union(frame.crs)
                    layers.append(WmsLayer(name: frame.name, title: layerTitle, crs: layerCrs))
                }
                frames.removeLast()
            }
        }
    }
}

/// What a WMS layer choice turns into: a `{$bbox}` URL template in Web Mercator.
public struct WmsSourceSpec: Equatable, Hashable, Sendable {
    public let template: String
    public let name: String
    public let bboxCrs: String

    public init(template: String, name: String, bboxCrs: String) {
        self.template = template
        self.name = name
        self.bboxCrs = bboxCrs
    }
}

public enum WmsSourceBuilder {
    /// The format to pick first: PNG (transparent) when offered, else the first one.
    public static func defaultFormat(_ caps: WmsCapabilities) -> String {
        caps.formats.first(where: { equalsIgnoringCase($0, "image/png") }) ?? caps.formats.first ?? "image/png"
    }

    /// Throws `ToolMapError` when the layer cannot be drawn in Web Mercator.
    public static func build(
        _ caps: WmsCapabilities,
        _ layer: WmsLayer,
        _ format: String,
        _ capabilitiesUrl: String,
        tileSize: Int = 256
    ) throws -> WmsSourceSpec {
        try require(layer.supportsWebMercator, "Layer \(layer.name) cannot be drawn in Web Mercator")
        let base = caps.getMapHref ?? capabilitiesUrl
        let path = base.substringBefore("?")
        let existing = kotlinSplit(base.substringAfter("?", missing: ""), "&").filter { !$0.isEmpty }
        let present = Set(existing.map { $0.substringBefore("=").lowercased() })
        let crsKey = caps.version.hasPrefix("1.3") ? "CRS" : "SRS"
        var wanted: [(key: String, value: String)] = [
            ("SERVICE", "WMS"),
            ("VERSION", caps.version),
            ("REQUEST", "GetMap"),
            ("LAYERS", OgcXml.formEncode(layer.name)),
            ("STYLES", ""),
            (crsKey, "EPSG:3857"),
            ("BBOX", "{$bbox}"),
            ("WIDTH", String(tileSize)),
            ("HEIGHT", String(tileSize)),
            ("FORMAT", OgcXml.formEncode(format)),
        ]
        if equalsIgnoringCase(format, "image/png") { wanted.append(("TRANSPARENT", "TRUE")) }
        let added = wanted.filter { !present.contains($0.key.lowercased()) }.map { $0.key + "=" + $0.value }
        return WmsSourceSpec(template: path + "?" + (existing + added).joined(separator: "&"), name: layer.title, bboxCrs: "EPSG:3857")
    }
}
