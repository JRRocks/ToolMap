package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupEntry
import fr.toolmap.core.backup.BackupException
import fr.toolmap.core.backup.BackupFailure
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.BackupManifestCodec
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Device(
        root: File,
    ) {
        val filesDir = File(root, "files").also { it.mkdirs() }
        val databaseFile = File(root, "databases/tracks.db")
        val locations = BackupLocations(filesDir, databaseFile)
        var tilesCheckpointed = 0
        var frozen = 0
        val workDir = File(root, "cache")

        fun service(clock: () -> Long = { 1_700_000_000_000L }) =
            BackupService(
                locations,
                appVersion = "0.1.0",
                hooks =
                    BackupHooks(
                        freezeDatabase = { copy ->
                            frozen++
                            copy()
                        },
                        checkpointTiles = { tilesCheckpointed++ },
                        // The database of these tests is a few bytes of text, not SQLite.
                        databaseRefusal = { null },
                    ),
                workDir = workDir,
                clock = clock,
            )

        fun write(
            path: String,
            text: String,
        ) {
            val file = locations.fileFor(path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        fun read(path: String): String? = locations.fileFor(path).takeIf { it.isFile }?.readText()

        /** Everything under the device, by relative path, with content: to compare two states. */
        fun snapshot(): Map<String, String> =
            buildMap {
                filesDir.walkTopDown().filter {
                    it.isFile
                }.forEach { put("files/" + it.relativeTo(filesDir).path, it.readText()) }
                databaseFile.parentFile
                    ?.walkTopDown()
                    ?.filter { it.isFile }
                    ?.forEach { put("databases/" + it.name, it.readText()) }
            }
    }

    private fun device(name: String) = Device(temp.newFolder(name))

    private fun fullDevice(name: String): Device =
        device(name).also {
            it.write("tracks.db", "DB-$name")
            it.write("settings.json", "{\"units\":\"$name\"}")
            it.write("alerts.json", "{\"alerts\":\"$name\"}")
            it.write("state/workstate.json", "{\"state\":\"$name\"}")
            it.write("sources/one.aqx", "<aqx one $name/>")
            it.write("sources/Carte-ete.aqx", "<aqx two $name/>")
            it.write("workspaces/ws-1.json", "{\"ws\":\"$name\"}")
            it.write("workspaces/hidden-builtins.txt", "builtin-$name\n")
            it.write("print/saved.json", "{\"print\":\"$name\"}")
            it.write("tiles/osm.mbtiles", "TILES-$name")
            // What must never travel.
            it.write("p2p/share.json", "SECRET-$name")
            it.write("recording/session.json", "REC-$name")
            it.write("downloads/job.json", "JOB-$name")
            it.write("settings.json.tmp", "half")
            File(it.databaseFile.path + "-wal").writeText("wal-$name")
        }

    private fun backup(
        source: Device,
        includeTiles: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { source.service().create(out, includeTiles) }
        return out.toByteArray()
    }

    private fun names(zip: ByteArray): List<String> {
        val result = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                result += (z.nextEntry ?: break).name
            }
        }
        return result
    }

    private fun failure(block: suspend () -> Unit): BackupFailure =
        runCatching { runBlocking { block() } }.exceptionOrNull().let { (it as BackupException).failure }

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    /** A hand-made archive, for the cases a real backup never produces. */
    private fun craft(
        manifest: BackupManifest?,
        vararg files: Pair<String, String>,
        manifestFirst: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun putManifest() {
                if (manifest == null) return
                z.putNextEntry(ZipEntry("manifest.json"))
                z.write(BackupManifestCodec.encode(manifest).toByteArray())
                z.closeEntry()
            }
            if (manifestFirst) putManifest()
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            if (!manifestFirst) putManifest()
        }
        return out.toByteArray()
    }

    private fun manifestOf(vararg files: Pair<String, String>) =
        BackupManifest(
            appVersion = "0.1.0",
            createdAtMillis = 1L,
            areas = BackupArea.entries.filter { !it.optional }.map { it.id },
            entries = files.map { (path, text) -> BackupEntry(path, text.toByteArray().size.toLong(), sha(text)) },
        )

    @Test
    fun `a backup starts with its manifest and holds only what may travel`() {
        val source = fullDevice("a")
        val zip = backup(source)

        val listed = names(zip)
        assertEquals("manifest.json", listed.first())
        assertEquals(
            setOf(
                "tracks.db",
                "settings.json",
                "alerts.json",
                "state/workstate.json",
                "sources/one.aqx",
                "sources/Carte-ete.aqx",
                "workspaces/ws-1.json",
                "workspaces/hidden-builtins.txt",
                "print/saved.json",
            ),
            listed.drop(1).toSet(),
        )
        // The sharing key, the recording buffer, the download queue, temporary and journal files, and (not asked) tiles.
        assertTrue(listed.none { it.startsWith("p2p") || it.startsWith("recording") || it.startsWith("downloads") })
        assertTrue(listed.none { it.endsWith(".tmp") || it.endsWith("-wal") || it.startsWith("tiles") })
        assertEquals(0, source.tilesCheckpointed)
        val manifest = runBlocking { source.service().inspect(ByteArrayInputStream(zip)) }
        assertFalse(manifest.covers(BackupArea.TILES))
        assertEquals("0.1.0", manifest.appVersion)
        assertEquals(1_700_000_000_000L, manifest.createdAtMillis)
        assertEquals(sha("DB-a"), manifest.entries.first { it.path == "tracks.db" }.sha256)
    }

    @Test
    fun `offline maps come only when asked, after their logs are folded in`() {
        val source = fullDevice("a")
        val zip = backup(source, includeTiles = true)
        assertTrue("tiles/osm.mbtiles" in names(zip))
        assertEquals(1, source.tilesCheckpointed)
        assertEquals("TILES-a".length.toLong(), source.service().tilesBytes())
    }

    @Test
    fun `the database is copied while it is frozen and the copy is cleaned up`() {
        val source = fullDevice("a")
        backup(source)
        assertEquals(1, source.frozen)
        assertEquals(emptyList(), source.workDir.listFiles().orEmpty().toList())
    }

    @Test
    fun `a restore brings back exactly what was saved and nothing else`() {
        val saved = fullDevice("saved")
        val zip = backup(saved)
        val target = fullDevice("old")
        val before = target.snapshot()

        val manifest = runBlocking { target.service().stage(ByteArrayInputStream(zip)) }
        // Staging touches nothing that is live.
        assertEquals(
            before.filterKeys {
                !it.contains("restore-pending")
            },
            target.snapshot().filterKeys { !it.contains("restore-pending") },
        )
        assertEquals(manifest, target.service().pending())

        val report = assertNotNull(PendingRestore.apply(target.locations, clock = { 42L }))
        assertTrue(report.ok)
        assertEquals(manifest.entries.size, report.files)

        assertEquals("DB-saved", target.read("tracks.db"))
        assertEquals("{\"units\":\"saved\"}", target.read("settings.json"))
        assertEquals("{\"alerts\":\"saved\"}", target.read("alerts.json"))
        assertEquals("{\"state\":\"saved\"}", target.read("state/workstate.json"))
        assertEquals("<aqx one saved/>", target.read("sources/one.aqx"))
        assertEquals("<aqx two saved/>", target.read("sources/Carte-ete.aqx"))
        assertEquals("{\"ws\":\"saved\"}", target.read("workspaces/ws-1.json"))
        // Deleted built-in favorites stay deleted (ADR-052).
        assertEquals("builtin-saved\n", target.read("workspaces/hidden-builtins.txt"))
        assertEquals("{\"print\":\"saved\"}", target.read("print/saved.json"))
        // The old write-ahead log of the database would corrupt the restored one: it is gone.
        assertFalse(File(target.databaseFile.path + "-wal").exists())
        // Not covered by the backup (tiles were not asked for), or never part of one: left as they were.
        assertEquals("TILES-old", target.read("tiles/osm.mbtiles"))
        assertEquals("SECRET-old", target.read("p2p/share.json"))
        assertEquals("REC-old", target.read("recording/session.json"))
        assertEquals("JOB-old", target.read("downloads/job.json"))
        // Cleaned up, and the user can be told how it went.
        assertFalse(target.locations.stagingDir.exists())
        assertFalse(target.locations.undoDir.exists())
        assertEquals(report, target.service().report())
        target.service().clearReport()
        assertNull(target.service().report())
    }

    @Test
    fun `an area the backup covers but had no file in replaces the live one with nothing`() {
        val saved = device("saved").also { it.write("settings.json", "{}") }
        val zip = backup(saved)
        val target = fullDevice("old")

        runBlocking { target.service().stage(ByteArrayInputStream(zip)) }
        PendingRestore.apply(target.locations)

        assertEquals("{}", target.read("settings.json"))
        assertNull(target.read("sources/one.aqx"))
        assertNull(target.read("tracks.db"))
        assertNull(target.read("alerts.json"))
        assertEquals("SECRET-old", target.read("p2p/share.json"))
    }

    /** Merges by appending, to see which maps are merged without SQLite (`MbTilesMergeTest` checks the real merge). */
    private val appending =
        object : RestoreFileOps {
            override fun move(
                from: File,
                to: File,
            ) = RealRestoreFileOps.move(from, to)

            override fun mergeTiles(
                from: File,
                into: File,
            ) = into.appendText("+" + from.readText())
        }

    @Test
    fun `offline maps are merged into the phone's, a map it lacks is put in place, the others stay (ADR-106)`() {
        val saved = fullDevice("saved").also { it.write("tiles/topo.mbtiles", "TOPO-saved") }
        val zip = backup(saved, includeTiles = true)
        val target = fullDevice("old").also { it.write("tiles/mine.mbtiles", "MINE") }
        runBlocking { target.service().stage(ByteArrayInputStream(zip)) }
        assertTrue(assertNotNull(PendingRestore.apply(target.locations, appending)).ok)
        assertEquals("TILES-old+TILES-saved", target.read("tiles/osm.mbtiles"))
        assertEquals("TOPO-saved", target.read("tiles/topo.mbtiles"))
        assertEquals("MINE", target.read("tiles/mine.mbtiles"))
    }

    @Test
    fun `each area of data weighs its files, the database with its log, the offline maps apart (P33)`() {
        val service = fullDevice("A").service()
        val bytes = service.areaBytes()
        assertEquals(("DB-A".length + "wal-A".length).toLong(), bytes[BackupArea.DATABASE])
        assertEquals(("<aqx one A/>".length + "<aqx two A/>".length).toLong(), bytes[BackupArea.SOURCES])
        assertEquals("{\"units\":\"A\"}".length.toLong(), bytes[BackupArea.SETTINGS])
        assertFalse(BackupArea.TILES in bytes)
        assertEquals(listOf("osm.mbtiles" to "TILES-A".length.toLong()), service.tileFiles())
    }

    @Test
    fun `only the ticked areas and the chosen maps are saved, and the format says when maps are in (P33)`() {
        val source = fullDevice("a").also { it.write("tiles/topo.mbtiles", "TOPO") }
        assertEquals(listOf("osm.mbtiles" to 7L, "topo.mbtiles" to 4L), source.service().tileFiles())
        val out = ByteArrayOutputStream()
        val manifest =
            runBlocking {
                source.service().create(out, setOf(BackupArea.DATABASE, BackupArea.WORKSPACES), setOf("topo.mbtiles"))
            }
        assertEquals(listOf("database", "workspaces", "tiles"), manifest.areas)
        assertEquals(
            setOf("tracks.db", "workspaces/ws-1.json", "workspaces/hidden-builtins.txt", "tiles/topo.mbtiles"),
            names(out.toByteArray()).drop(1).toSet(),
        )
        assertEquals(BackupManifest.FORMAT, manifest.format)
        assertEquals(1, source.tilesCheckpointed)
        // Without offline maps, a backup any version reads.
        val plain =
            runBlocking { source.service().create(ByteArrayOutputStream(), setOf(BackupArea.SETTINGS), emptySet()) }
        assertEquals(listOf("settings"), plain.areas)
        assertEquals(BackupManifest.FORMAT_WITHOUT_TILES, plain.format)
    }

    @Test
    fun `a restore of the ticked areas leaves the others as they are on the phone (P33)`() {
        val zip = backup(fullDevice("saved"), includeTiles = true)
        val target = fullDevice("old")
        val manifest =
            runBlocking {
                target.service().stage(ByteArrayInputStream(zip), setOf(BackupArea.WORKSPACES, BackupArea.SETTINGS))
            }
        assertEquals(listOf("settings", "workspaces"), manifest.areas)
        assertEquals(manifest, target.service().pending())
        // What will not be restored is read past, not unpacked.
        assertFalse(File(target.locations.stagingDir, "payload/tracks.db").exists())
        assertFalse(File(target.locations.stagingDir, "payload/tiles/osm.mbtiles").exists())

        val report = assertNotNull(PendingRestore.apply(target.locations))
        assertTrue(report.ok)
        assertEquals(3, report.files)
        assertEquals("{\"units\":\"saved\"}", target.read("settings.json"))
        assertEquals("{\"ws\":\"saved\"}", target.read("workspaces/ws-1.json"))
        assertEquals("DB-old", target.read("tracks.db"))
        assertEquals("<aqx one old/>", target.read("sources/one.aqx"))
        assertEquals("{\"alerts\":\"old\"}", target.read("alerts.json"))
        assertEquals("TILES-old", target.read("tiles/osm.mbtiles"))
    }

    @Test
    fun `a backup of the format before the choice still restores, its offline maps merged (P33)`() {
        val files = arrayOf("settings.json" to "{\"units\":\"saved\"}", "tiles/osm.mbtiles" to "TILES-saved")
        // As the versions before P33 wrote it: format 1, every area, all of the maps.
        val manifest = manifestOf(*files).copy(format = 1, areas = BackupArea.entries.map { it.id })
        val target = fullDevice("old")
        runBlocking { target.service().stage(ByteArrayInputStream(craft(manifest, *files))) }

        assertTrue(assertNotNull(PendingRestore.apply(target.locations, appending)).ok)
        assertEquals("{\"units\":\"saved\"}", target.read("settings.json"))
        assertEquals("TILES-old+TILES-saved", target.read("tiles/osm.mbtiles"))
        // A data area it covers without a file is emptied, as before.
        assertNull(target.read("tracks.db"))
    }

    @Test
    fun `reading the manifest alone unpacks nothing`() {
        val zip = backup(fullDevice("a"))
        val target = device("b")
        val manifest = runBlocking { target.service().inspect(ByteArrayInputStream(zip)) }
        assertEquals(9, manifest.entries.size)
        assertFalse(target.locations.stagingDir.exists())
        assertNull(target.service().pending())
    }

    @Test
    fun `progress reaches the total on both sides`() {
        val source = fullDevice("a")
        val out = ByteArrayOutputStream()
        val written = ArrayList<Pair<Long, Long>>()
        runBlocking { source.service().create(out, false) { done, total -> written += done to total } }
        assertEquals(written.last().first, written.last().second)
        val target = device("b")
        val read = ArrayList<Pair<Long, Long>>()
        runBlocking {
            target.service().stage(ByteArrayInputStream(out.toByteArray())) { done, total ->
                read +=
                    done to total
            }
        }
        assertEquals(read.last().first, read.last().second)
        assertEquals(written.last(), read.last())
    }

    @Test
    fun `something that is not a backup is refused`() {
        val target = device("b")
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure { target.service().stage(ByteArrayInputStream(ByteArray(2000) { it.toByte() })) },
        )
        assertEquals(BackupFailure.NOT_A_BACKUP, failure { target.service().stage(ByteArrayInputStream(ByteArray(0))) })
        // A zip with no manifest, or a manifest that is not first.
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            null,
                            "a.txt" to "x",
                        ),
                    ),
                )
            },
        )
        val manifest = manifestOf("settings.json" to "{}")
        assertEquals(
            BackupFailure.NOT_A_BACKUP,
            failure {
                target.service().stage(
                    ByteArrayInputStream(craft(manifest, "settings.json" to "{}", manifestFirst = false)),
                )
            },
        )
        assertFalse(target.locations.stagingDir.exists())
    }

    @Test
    fun `a file that does not match its checksum is refused and nothing is left`() {
        val target = fullDevice("old")
        val before = target.snapshot()
        val manifest = manifestOf("settings.json" to "{\"units\":\"METRIC\"}")
        val zip = craft(manifest, "settings.json" to "{\"units\":\"EVIL\"!}")
        assertEquals(BackupFailure.CORRUPT, failure { target.service().stage(ByteArrayInputStream(zip)) })
        assertEquals(before, target.snapshot())
        assertNull(PendingRestore.apply(target.locations))
    }

    @Test
    fun `an entry bigger than declared, unlisted or missing is refused`() {
        val target = device("old")
        val manifest = manifestOf("settings.json" to "{}")
        assertEquals(
            BackupFailure.CORRUPT,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            manifest,
                            "settings.json" to "{}" + "x".repeat(5000),
                        ),
                    ),
                )
            },
        )
        assertEquals(
            BackupFailure.CORRUPT,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            manifest,
                            "settings.json" to "{}",
                            "alerts.json" to "{}",
                        ),
                    ),
                )
            },
        )
        assertEquals(BackupFailure.CORRUPT, failure { target.service().stage(ByteArrayInputStream(craft(manifest))) })
        assertFalse(target.locations.stagingDir.exists())
    }

    @Test
    fun `a path that climbs out of the folder never reaches the disk`() {
        val target = device("old")
        val outside = File(target.filesDir.parentFile, "evil.json")
        // Listed in the manifest: refused as soon as the manifest is read.
        val listed =
            manifestOf(
                "settings.json" to "{}",
            ).copy(entries = listOf(BackupEntry("../evil.json", 2, sha("{}"))))
        assertEquals(
            BackupFailure.UNSAFE,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            listed,
                            "../evil.json" to "{}",
                        ),
                    ),
                )
            },
        )
        // Not listed: refused when met.
        val manifest = manifestOf("settings.json" to "{}")
        assertEquals(
            BackupFailure.CORRUPT,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            manifest,
                            "settings.json" to "{}",
                            "../evil.json" to "{}",
                        ),
                    ),
                )
            },
        )
        // The sharing key is not an allowed path either, even listed.
        val key =
            manifestOf(
                "settings.json" to "{}",
            ).copy(entries = listOf(BackupEntry("p2p/share.json", 2, sha("{}"))))
        assertEquals(
            BackupFailure.UNSAFE,
            failure {
                target.service().stage(
                    ByteArrayInputStream(
                        craft(
                            key,
                            "p2p/share.json" to "{}",
                        ),
                    ),
                )
            },
        )
        assertFalse(outside.exists())
        assertFalse(File(target.filesDir, "p2p/share.json").exists())
        assertFalse(target.locations.stagingDir.exists())
    }

    @Test
    fun `a backup from a newer format is refused with its own reason`() {
        val target = device("old")
        val manifest = manifestOf().copy(format = BackupManifest.FORMAT + 1)
        assertEquals(
            BackupFailure.UNSUPPORTED_VERSION,
            failure {
                target.service().stage(ByteArrayInputStream(craft(manifest)))
            },
        )
    }

    @Test
    fun `a file that changes while it is being saved fails the backup instead of saving a mix`() {
        val source = fullDevice("a")
        // The clock is read between the two passes: that is when the file changes.
        val service =
            source.service {
                source.locations.fileFor("settings.json").appendText(" changed")
                1L
            }
        val failure = runCatching { runBlocking { service.create(ByteArrayOutputStream(), false) } }.exceptionOrNull()
        assertEquals(BackupFailure.CORRUPT, (failure as BackupException).failure)
    }

    @Test
    fun `a restore that fails half-way puts everything back`() {
        val saved = fullDevice("saved")
        val zip = backup(saved)
        val target = fullDevice("old")
        runBlocking { target.service().stage(ByteArrayInputStream(zip)) }
        val before = target.snapshot().filterKeys { !it.contains("restore-pending") }

        var moves = 0
        val failing =
            object : RestoreFileOps {
                override fun move(
                    from: File,
                    to: File,
                ) {
                    // Let a few areas switch, then fail.
                    if (++moves == 9) throw IOException("disk full")
                    RealRestoreFileOps.move(from, to)
                }
            }
        val report = assertNotNull(PendingRestore.apply(target.locations, failing))

        assertFalse(report.ok)
        assertEquals("disk full", report.error)
        // Everything is as it was; the only addition is the report that says so.
        assertEquals(
            before,
            target.snapshot().filterKeys {
                !it.contains("restore-pending") &&
                    !it.contains("restore-report")
            },
        )
        assertFalse(target.locations.stagingDir.exists())
        assertFalse(target.locations.undoDir.exists())
        assertEquals(report, target.service().report())
    }

    @Test
    fun `nothing waiting means nothing to do, and an unfinished unpacking is thrown away`() {
        val target = fullDevice("old")
        assertNull(PendingRestore.apply(target.locations))
        File(target.locations.stagingDir, "payload/settings.json").also {
            it.parentFile.mkdirs()
            it.writeText("half")
        }
        assertNull(PendingRestore.apply(target.locations))
        assertFalse(target.locations.stagingDir.exists())
        assertEquals("{\"units\":\"old\"}", target.read("settings.json"))
    }

    @Test
    fun `a restore waiting is replaced by a newer one`() {
        val first = backup(fullDevice("first"))
        val second = backup(fullDevice("second"))
        val target = fullDevice("old")
        runBlocking {
            target.service().stage(ByteArrayInputStream(first))
            target.service().stage(ByteArrayInputStream(second))
        }
        PendingRestore.apply(target.locations)
        assertEquals("DB-second", target.read("tracks.db"))
        runBlocking { target.service().stage(ByteArrayInputStream(first)) }
        target.service().discardPending()
        assertNull(target.service().pending())
        assertNull(PendingRestore.apply(target.locations))
    }
}
