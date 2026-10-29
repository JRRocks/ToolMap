// Port of core/src/main/kotlin/fr/toolmap/core/source/BuiltinSources.kt.

import Foundation

/// Sources shipped with the app, defined in the package resource `builtin-sources.aqx` (AQX syntax), a copy of the
/// Android resource of the same name.
public enum BuiltinSources {
    public static let resource = "builtin-sources"

    /// The resource text. Throws `ToolMapError` when it is missing (a packaging bug).
    public static func text() throws -> String {
        guard let url = Bundle.module.url(forResource: resource, withExtension: "aqx") else {
            throw ToolMapError.invalidArgument("Missing resource \(resource).aqx")
        }
        let data = try Data(contentsOf: url)
        return String(decoding: data, as: UTF8.self)
    }

    /// The built-in sources, in file order. Throws when the resource is missing or is not a valid AQX file.
    public static func load() throws -> [TileSource] {
        try AqxParser.parse(text()).file.sources
    }
}
