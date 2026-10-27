package fr.toolmap.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupModelTest {
    private val sha = "a".repeat(64)

    private fun manifest(
        areas: List<String> = listOf("database", "sources"),
        vararg entries: BackupEntry,
        format: Int = BackupManifest.FORMAT,
        app: String = BackupManifest.APP,
    ) = BackupManifest(format = format, app = app, appVersion = "0.1.0", areas = areas, entries = entries.toList())

    private fun failure(block: () -> Unit): BackupFailure = assertFailsWith<BackupException> { block() }.failure

    @Test
    fun `each allowed file belongs to one area`() {
        assertEquals(BackupArea.DATABASE, BackupPaths.areaOf("tracks.db"))
        assertEquals(BackupArea.SETTINGS, BackupPaths.areaOf("settings.json"))
        assertEquals(BackupArea.ALERTS, BackupPaths.areaOf("alerts.json"))
        assertEquals(BackupArea.STATE, BackupPaths.areaOf("state/workstate.json"))
        assertEquals(BackupArea.SOURCES, BackupPaths.areaOf("sources/ign-scan25.aqx"))
        assertEquals(BackupArea.WORKSPACES, BackupPaths.areaOf("workspaces/ws-1.json"))
        // The deleted built-in favorites: without it, a restore brought them back (ADR-052).
        assertEquals(BackupArea.WORKSPACES, BackupPaths.areaOf("workspaces/hidden-builtins.txt"))
        assertEquals(BackupArea.PRINT, BackupPaths.areaOf("print/saved.json"))
        assertEquals(BackupArea.TILES, BackupPaths.areaOf("tiles/osm.mbtiles"))
    }

    @Test
    fun `anything else is refused, the sharing key and the recording buffer included`() {
        val refused =
            listOf(
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
            )
        for (path in refused) assertNull(BackupPaths.areaOf(path), path)
    }

    @Test
    fun `a path that could leave its folder is unsafe`() {
        val unsafe =
            listOf(
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
                "sources/a\u0000.aqx",
                "sources/" + "x".repeat(201) + ".aqx",
            )
        for (path in unsafe) {
            assertFalse(BackupPaths.isSafe(path), "[$path]")
            assertNull(BackupPaths.areaOf(path), "[$path]")
        }
        assertTrue(BackupPaths.isSafe("sources/Carte été.aqx"))
    }

    @Test
    fun `a manifest survives its own encoding and counts files per area`() {
        val original =
            manifest(
                listOf("database", "sources", "tiles"),
                BackupEntry("tracks.db", 100, sha),
                BackupEntry("sources/a.aqx", 10, sha),
                BackupEntry("sources/b.aqx", 20, sha),
            )
        val back = BackupManifestCodec.decode(BackupManifestCodec.encode(original))
        assertEquals(original, back)
        assertEquals(130, back.totalBytes)
        assertEquals(2, back.fileCount(BackupArea.SOURCES))
        assertTrue(back.covers(BackupArea.TILES))
        assertFalse(back.covers(BackupArea.ALERTS))
    }

    @Test
    fun `a manifest from a newer format or another application is not applied`() {
        assertEquals(
            BackupFailure.UNSUPPORTED_VERSION,
            failure { BackupManifestCodec.decode(BackupManifestCodec.encode(manifest(format = 2))) },
        )
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure { BackupManifestCodec.decode(BackupManifestCodec.encode(manifest(app = "Other"))) },
        )
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { BackupManifestCodec.decode("not json") })
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { BackupManifestCodec.decode("{\"entries\": 3}") })
    }

    @Test
    fun `unknown keys are ignored so that a later minor addition still reads`() {
        val text = BackupManifestCodec.encode(manifest()).replace("{", "{\"future\":true,", ignoreCase = false)
        assertEquals(BackupManifest.FORMAT, BackupManifestCodec.decode(text).format)
    }

    @Test
    fun `entries must be allowed, unique, inside the covered areas and well formed`() {
        val ok = BackupEntry("sources/a.aqx", 1, sha)
        assertEquals(
            BackupFailure.UNSAFE,
            failure { BackupManifestCodec.validate(manifest(entries = arrayOf(BackupEntry("../x", 1, sha)))) },
        )
        assertEquals(
            BackupFailure.UNSAFE,
            failure {
                BackupManifestCodec.validate(manifest(entries = arrayOf(BackupEntry("p2p/share.json", 1, sha))))
            },
        )
        assertEquals(
            BackupFailure.CORRUPT,
            failure { BackupManifestCodec.validate(manifest(entries = arrayOf(ok, ok))) },
        )
        assertEquals(
            BackupFailure.CORRUPT,
            failure { BackupManifestCodec.validate(manifest(areas = listOf("database"), entries = arrayOf(ok))) },
        )
        assertEquals(
            BackupFailure.CORRUPT,
            failure {
                BackupManifestCodec.validate(manifest(entries = arrayOf(BackupEntry("sources/a.aqx", -1, sha))))
            },
        )
        assertEquals(
            BackupFailure.CORRUPT,
            failure {
                BackupManifestCodec.validate(manifest(entries = arrayOf(BackupEntry("sources/a.aqx", 1, "XYZ"))))
            },
        )
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure { BackupManifestCodec.validate(manifest(areas = listOf("database", "database"))) },
        )
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure { BackupManifestCodec.validate(manifest(areas = listOf("nope"))) },
        )
    }

    @Test
    fun `a restore report is tolerant of a damaged file`() {
        val report = RestoreReport(ok = true, atMillis = 5L, backupMillis = 3L, files = 12)
        assertEquals(report, RestoreReportCodec.decodeOrNull(RestoreReportCodec.encode(report)))
        assertNull(RestoreReportCodec.decodeOrNull(null))
        assertNull(RestoreReportCodec.decodeOrNull(""))
        assertNull(RestoreReportCodec.decodeOrNull("garbage"))
    }
}
