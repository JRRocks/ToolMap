// Port of p2p/src/main/kotlin/fr/toolmap/p2p/ShareStore.kt (Android module :p2p).

import Foundation

/// The one file keeping the sharing settings and the group key between launches. Written atomically so that a kill in
/// the middle of a save leaves the previous version; a damaged file reads as "defaults, no group".
public final class ShareStore: @unchecked Sendable {
    private let file: URL
    private let seqFile: URL

    public init(_ file: URL) {
        self.file = file
        let name = file.lastPathComponent
        let stem = name.lastIndex(of: ".").map { String(name[..<$0]) } ?? name
        seqFile = file.deletingLastPathComponent().appendingPathComponent(stem + ".seq")
    }

    public func read() -> ShareState {
        guard let data = try? Data(contentsOf: file) else { return ShareState() }
        return ShareState.fromJson(String(decoding: data, as: UTF8.self))
    }

    /// The last message number kept by `writeSeq`, or `nil` (P21.5 m).
    public func readSeq() -> Int64? {
        guard let data = try? Data(contentsOf: seqFile) else { return nil }
        return Int64(kotlinTrim(String(decoding: data, as: UTF8.self)))
    }

    /// Keeps the last message number used, so that the next process starts above it (P21.5 m).
    public func writeSeq(_ seq: Int64) {
        try? FileManager.default.createDirectory(at: seqFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data(String(seq).utf8).write(to: seqFile)
    }

    public func write(_ state: ShareState) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = file.deletingLastPathComponent().appendingPathComponent(file.lastPathComponent + ".tmp")
        try Data(state.toJson().utf8).write(to: temp)
        if fm.fileExists(atPath: file.path) {
            _ = try fm.replaceItemAt(file, withItemAt: temp)
        } else {
            try fm.moveItem(at: temp, to: file)
        }
    }
}
