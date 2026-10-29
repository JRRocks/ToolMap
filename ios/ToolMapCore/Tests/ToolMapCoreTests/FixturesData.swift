import Foundation

extension Fixtures {
    /// The file's bytes, as the Kotlin tests read them with `File(dir, path).readBytes()`.
    static func data(_ path: String) throws -> Data {
        try Data(contentsOf: directory.appendingPathComponent(path))
    }
}
