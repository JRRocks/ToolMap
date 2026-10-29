// Port of core/src/test/kotlin/fr/toolmap/core/backup/BackupModelTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

private let sha = String(repeating: "a", count: 64)

private func manifest(
    areas: [String] = ["database", "sources"],
    entries: [BackupEntry] = [],
    format: Int = BackupManifest.currentFormat,
    app: String = BackupManifest.appName
) -> BackupManifest {
    BackupManifest(format: format, app: app, appVersion: "0.1.0", areas: areas, entries: entries)
}

/// The failure `block` throws, nil when it throws nothing or another error.
private func failure(_ block: () throws -> Void) -> BackupFailure? {
    do {
        try block()
        return nil
    } catch let error as BackupError {
        return error.failure
    } catch {
        return nil
    }
}

struct BackupModelTests {
    @Test("each allowed file belongs to one area")
    func allowedFiles() {
        #expect(BackupPaths.areaOf("tracks.db") == BackupArea.database)
        #expect(BackupPaths.areaOf("settings.json") == BackupArea.settings)
        #expect(BackupPaths.areaOf("alerts.json") == BackupArea.alerts)
        #expect(BackupPaths.areaOf("state/workstate.json") == BackupArea.state)
        #expect(BackupPaths.areaOf("sources/ign-scan25.aqx") == BackupArea.sources)
        #expect(BackupPaths.areaOf("workspaces/ws-1.json") == BackupArea.workspaces)
        // The deleted built-in favorites: without it, a restore brought them back (ADR-052).
        #expect(BackupPaths.areaOf("workspaces/hidden-builtins.txt") == BackupArea.workspaces)
        #expect(BackupPaths.areaOf("print/saved.json") == BackupArea.print)
        #expect(BackupPaths.areaOf("tiles/osm.mbtiles") == BackupArea.tiles)
    }

    @Test("anything else is refused, the sharing key and the recording buffer included")
    func refused() {
        let refused = [
            "p2p/share.json",
            "recording/session.json",
            "downloads/job.json",
            "tracks.db-wal",
            "tracks.db-shm",
            "sources/evil.sh",
            "sources/nested/a.aqx",
            "tiles/osm.mbtiles-wal",
            "unknown.json",
            "state/workstate.txt",
            "workspaces/notes.txt",
            "sources/hidden-builtins.txt",
            "manifest.json",
        ]
        for path in refused {
            #expect(BackupPaths.areaOf(path) == nil, "\(path)")
        }
    }

    @Test("a path that could leave its folder is unsafe")
    func unsafePaths() {
        let unsafe = [
            "../tracks.db",
            "sources/../../settings.json",
            "/etc/passwd",
            "sources//a.aqx",
            "sources/a.aqx/",
            "sources\\a.aqx",
            "./settings.json",
            "sources/./a.aqx",
            "sources/.hidden.aqx",
            "",
            "sources/a\u{0000}.aqx",
            "sources/" + String(repeating: "x", count: 201) + ".aqx",
        ]
        for path in unsafe {
            #expect(!BackupPaths.isSafe(path), "[\(path)]")
            #expect(BackupPaths.areaOf(path) == nil, "[\(path)]")
        }
        #expect(BackupPaths.isSafe("sources/Carte été.aqx"))
    }

    @Test("a manifest survives its own encoding and counts files per area")
    func manifestRoundTrip() throws {
        let original = manifest(
            areas: ["database", "sources", "tiles"],
            entries: [
                BackupEntry("tracks.db", 100, sha),
                BackupEntry("sources/a.aqx", 10, sha),
                BackupEntry("sources/b.aqx", 20, sha),
            ]
        )
        let back = try BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        #expect(back == original)
        #expect(back.totalBytes == 130)
        #expect(back.fileCount(.sources) == 2)
        #expect(back.covers(.tiles))
        #expect(!back.covers(.alerts))
    }

    @Test("a manifest from a newer format or another application is not applied")
    func newerOrOther() throws {
        let newer = try BackupManifestCodec.encode(manifest(format: 2))
        let other = try BackupManifestCodec.encode(manifest(app: "Other"))
        let newerFailure = failure { _ = try BackupManifestCodec.decode(newer) }
        let otherFailure = failure { _ = try BackupManifestCodec.decode(other) }
        let textFailure = failure { _ = try BackupManifestCodec.decode("not json") }
        let typeFailure = failure { _ = try BackupManifestCodec.decode("{\"entries\": 3}") }
        #expect(newerFailure == BackupFailure.unsupportedVersion)
        #expect(otherFailure == BackupFailure.notABackup)
        #expect(textFailure == BackupFailure.notABackup)
        #expect(typeFailure == BackupFailure.notABackup)
    }

    @Test("unknown keys are ignored so that a later minor addition still reads")
    func unknownKeys() throws {
        let text = try BackupManifestCodec.encode(manifest()).replacingOccurrences(of: "{", with: "{\"future\":true,")
        #expect(try BackupManifestCodec.decode(text).format == BackupManifest.currentFormat)
    }

    @Test("entries must be allowed, unique, inside the covered areas and well formed")
    func entriesChecked() {
        let ok = BackupEntry("sources/a.aqx", 1, sha)
        let outside = failure { try BackupManifestCodec.validate(manifest(entries: [BackupEntry("../x", 1, sha)])) }
        let sharing = failure {
            try BackupManifestCodec.validate(manifest(entries: [BackupEntry("p2p/share.json", 1, sha)]))
        }
        let twice = failure { try BackupManifestCodec.validate(manifest(entries: [ok, ok])) }
        let notCovered = failure { try BackupManifestCodec.validate(manifest(areas: ["database"], entries: [ok])) }
        let negative = failure {
            try BackupManifestCodec.validate(manifest(entries: [BackupEntry("sources/a.aqx", -1, sha)]))
        }
        let checksum = failure {
            try BackupManifestCodec.validate(manifest(entries: [BackupEntry("sources/a.aqx", 1, "XYZ")]))
        }
        let areaTwice = failure { try BackupManifestCodec.validate(manifest(areas: ["database", "database"])) }
        let unknownArea = failure { try BackupManifestCodec.validate(manifest(areas: ["nope"])) }
        #expect(outside == BackupFailure.unsafe)
        #expect(sharing == BackupFailure.unsafe)
        #expect(twice == BackupFailure.corrupt)
        #expect(notCovered == BackupFailure.corrupt)
        #expect(negative == BackupFailure.corrupt)
        #expect(checksum == BackupFailure.corrupt)
        #expect(areaTwice == BackupFailure.notABackup)
        #expect(unknownArea == BackupFailure.notABackup)
    }

    @Test("a restore report is tolerant of a damaged file")
    func restoreReport() throws {
        let report = RestoreReport(ok: true, atMillis: 5, backupMillis: 3, files: 12)
        #expect(try RestoreReportCodec.decodeOrNull(RestoreReportCodec.encode(report)) == report)
        #expect(RestoreReportCodec.decodeOrNull(nil) == nil)
        #expect(RestoreReportCodec.decodeOrNull("") == nil)
        #expect(RestoreReportCodec.decodeOrNull("garbage") == nil)
    }

    // Not in BackupModelTest.kt: the manifest of docs/specs/backup-format.md, as the Android app writes it.

    @Test("the manifest of the format specification is read")
    func specificationManifest() throws {
        let text = """
            {"format":1,"app":"ToolMap","appVersion":"0.4.0","createdAtMillis":1790000000000,\
            "areas":["database","settings","alerts","state","sources","workspaces","print"],\
            "entries":[{"path":"tracks.db","size":12288,"sha256":"\(sha)"}]}
            """
        let read = try BackupManifestCodec.decode(text)
        #expect(read.createdAtMillis == 1_790_000_000_000)
        #expect(read.entries == [BackupEntry("tracks.db", 12288, sha)])
        #expect(!read.covers(.tiles))
        let optionalAreas = BackupArea.allCases.filter { $0.optional }
        #expect(optionalAreas == [BackupArea.tiles])
        // A null is not a missing key: kotlinx.serialization refuses it, so does this port.
        let nullVersion = failure { _ = try BackupManifestCodec.decode(#"{"appVersion":null}"#) }
        #expect(nullVersion == BackupFailure.notABackup)
    }
}
