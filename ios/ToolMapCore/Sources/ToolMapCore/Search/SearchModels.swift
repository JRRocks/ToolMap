// Port of core/src/main/kotlin/fr/toolmap/core/search/SearchModels.kt.
//
// `LibraryKind` (TRACK, ROUTE, WAYPOINT) is declared twice in Kotlin, in `search/SearchModels.kt` and in
// `track/LibraryOrder.kt`, with the same values. Swift has one name space per module: it is not declared here but
// comes from the port of `track/LibraryOrder.kt`.

/// Where a search result comes from.
public enum SearchSource: String, CaseIterable, Sendable {
    /// The text was a position: the result is that position.
    case coordinates = "COORDINATES"
    /// One of the user's own tracks, routes or waypoints.
    case library = "LIBRARY"
    /// IGN Géoplateforme geocoder (France: places, addresses).
    case ign = "IGN"
    /// OpenStreetMap Nominatim (worldwide).
    case osm = "OSM"
}

/// A place the user can go to, whatever found it.
public struct SearchResult: Equatable, Hashable, Sendable {
    public let title: String
    public let subtitle: String
    public let lat: Double
    public let lon: Double
    public let source: SearchSource
    /// What it is, in the words of the source (`sommet`, `peak`); nil when unknown.
    public let category: String?
    /// The extent to frame when the place is an area or a line; nil for a point.
    public let bounds: Bounds?
    public let libraryKind: LibraryKind?
    public let libraryId: Int64?

    public init(
        title: String,
        subtitle: String,
        lat: Double,
        lon: Double,
        source: SearchSource,
        category: String? = nil,
        bounds: Bounds? = nil,
        libraryKind: LibraryKind? = nil,
        libraryId: Int64? = nil
    ) {
        self.title = title
        self.subtitle = subtitle
        self.lat = lat
        self.lon = lon
        self.source = source
        self.category = category
        self.bounds = bounds
        self.libraryKind = libraryKind
        self.libraryId = libraryId
    }
}
