// Kotlin's `copy(...)` of the data classes TileSource and LevelDef (source/TileSource.kt), which Swift structs with
// `let` fields do not have: copy the fields, change some, build again.

/// The fields of a `TileSource`, to change before building a new one: `source.withFields { $0.id = "x" }`.
struct TileSourceFields {
    var id: String
    var name: String
    var type: String?
    var isLayer: Bool
    var opacity: Int
    var copyright: String?
    var dataSource: String?
    var region: String?
    var outline: [LonLat]
    var previewLocation: LonLatZoom?
    var defaultLocation: LonLat?
    var params: [String: String]
    var levels: [LevelDef]
    var unsupportedReason: String?

    init(_ source: TileSource) {
        id = source.id
        name = source.name
        type = source.type
        isLayer = source.isLayer
        opacity = source.opacity
        copyright = source.copyright
        dataSource = source.dataSource
        region = source.region
        outline = source.outline
        previewLocation = source.previewLocation
        defaultLocation = source.defaultLocation
        params = source.params
        levels = source.levels
        unsupportedReason = source.unsupportedReason
    }

    func build() -> TileSource {
        TileSource(
            id: id,
            name: name,
            type: type,
            isLayer: isLayer,
            opacity: opacity,
            copyright: copyright,
            dataSource: dataSource,
            region: region,
            outline: outline,
            previewLocation: previewLocation,
            defaultLocation: defaultLocation,
            params: params,
            levels: levels,
            unsupportedReason: unsupportedReason
        )
    }
}

/// The fields of a `LevelDef`, to change before building a new one: `level.withFields { $0.servers = [url] }`.
struct LevelDefFields {
    var zooms: ZoomSet
    var servers: [String]
    var referer: String?
    var userAgent: String?
    var headers: [HttpHeader]
    var cookie: String?
    var updateDelay: String?
    var maxThreads: Int
    var insecure: Bool
    var tileSize: Int
    var clearColor: ClearColor?
    var keyUrl: String?
    var bboxCrs: String

    init(_ level: LevelDef) {
        zooms = level.zooms
        servers = level.servers
        referer = level.referer
        userAgent = level.userAgent
        headers = level.headers
        cookie = level.cookie
        updateDelay = level.updateDelay
        maxThreads = level.maxThreads
        insecure = level.insecure
        tileSize = level.tileSize
        clearColor = level.clearColor
        keyUrl = level.keyUrl
        bboxCrs = level.bboxCrs
    }

    func build() -> LevelDef {
        LevelDef(
            zooms: zooms,
            servers: servers,
            referer: referer,
            userAgent: userAgent,
            headers: headers,
            cookie: cookie,
            updateDelay: updateDelay,
            maxThreads: maxThreads,
            insecure: insecure,
            tileSize: tileSize,
            clearColor: clearColor,
            keyUrl: keyUrl,
            bboxCrs: bboxCrs
        )
    }
}

extension TileSource {
    /// Kotlin `copy(...)`: this source with the fields `change` sets.
    func withFields(_ change: (inout TileSourceFields) -> Void) -> TileSource {
        var fields = TileSourceFields(self)
        change(&fields)
        return fields.build()
    }
}

extension LevelDef {
    /// Kotlin `copy(...)`: this level with the fields `change` sets.
    func withFields(_ change: (inout LevelDefFields) -> Void) -> LevelDef {
        var fields = LevelDefFields(self)
        change(&fields)
        return fields.build()
    }
}
