// Port of data/src/main/kotlin/fr/toolmap/data/tracks/NamespaceMerger.kt.

import Foundation

/// Raw XML fragments rely on the namespace prefixes of the file they were imported from. Exporting items of several
/// imports into one file merges those prefixes onto the root; where two imports use one prefix for different
/// namespaces, the fragments of the later one declare their own.
final class NamespaceMerger {
    private let library: TrackLibrary
    private(set) var merged: [String: String] = [:]
    private var byRoot: [Int64?: (own: [String: String], conflict: Bool)] = [:]

    init(_ library: TrackLibrary) {
        self.library = library
    }

    /// Runs `rewrite` with the function that fixes the fragments of an item of `folderId`.
    func adopt<T>(_ folderId: Int64?, _ rewrite: (([RawXml]) -> [RawXml]) -> T) throws -> T {
        let root = library.chain(folderId).last?.id
        let entry: (own: [String: String], conflict: Bool)
        if let known = byRoot[root] {
            entry = known
        } else {
            entry = try resolve(root)
            byRoot[root] = entry
        }
        let own = entry.own
        let conflict = entry.conflict
        return rewrite { fragments in
            conflict ? fragments.map { $0.declaring(own) } : fragments
        }
    }

    private func resolve(_ root: Int64?) throws -> (own: [String: String], conflict: Bool) {
        guard let rootId = root else {
            // Top of the library (drawn, recorded, or moved out of its import): every import's prefixes are offered
            // and each fragment declares the ones it uses itself.
            var all: [String: String] = [:]
            for folder in library.folders where folder.parentId == nil {
                let namespaces = try TrackJson.decodeNamespaces(folder.namespacesJson)
                for (prefix, uri) in namespaces where all[prefix] == nil {
                    all[prefix] = uri
                }
            }
            return (own: all, conflict: true)
        }
        let rootFolder = library.folders.first(where: { $0.id == rootId })
        let own = try TrackJson.decodeNamespaces(rootFolder?.namespacesJson)
        var clash = false
        for (prefix, uri) in own {
            if let existing = merged[prefix] {
                if existing != uri { clash = true }
            } else {
                merged[prefix] = uri
            }
        }
        return (own: own, conflict: clash)
    }
}
