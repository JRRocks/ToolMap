// Port of `CoordinateQuery` in core/src/main/kotlin/fr/toolmap/core/search/LocalSearch.kt. It needs the port of
// geo/CoordinateParser.kt (`CoordinateParser.parse(_:) -> ParsedCoordinate`, `.success(lat:lon:)`).

/// A search text that is a position (`44.8386, 5.5108`, `31T DQ 52482 11717`): go there.
public enum CoordinateQuery {
    public static func resolve(_ text: String) -> SearchResult? {
        guard case let .success(lat, lon) = CoordinateParser.parse(text) else { return nil }
        return SearchResult(title: "", subtitle: "", lat: lat, lon: lon, source: .coordinates)
    }
}
