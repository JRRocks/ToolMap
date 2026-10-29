// Port of core/src/main/kotlin/fr/toolmap/core/aqx/AqxModel.kt.

/// A parsed `.aqx` file.
public struct AqxFile: Equatable, Sendable {
    public let version: Int?
    public let name: String?
    public let description: String?
    /// File-level `<param>` values (also copied into each source).
    public let params: [String: String]
    public let sources: [TileSource]
}

/// Something in the file that was ignored or only partly understood. The import still succeeds.
public struct AqxWarning: Equatable, Sendable {
    /// Where it happened, e.g. `source IGN_MAPS` or `file`.
    public let context: String
    public let message: String
}

public struct AqxParseResult: Equatable, Sendable {
    public let file: AqxFile
    public let warnings: [AqxWarning]
}

/// The file is not a usable AQX document (not XML, wrong root element, forbidden construct...).
/// Kotlin: `AqxParseException`.
public struct AqxParseError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var description: String { message }
}
