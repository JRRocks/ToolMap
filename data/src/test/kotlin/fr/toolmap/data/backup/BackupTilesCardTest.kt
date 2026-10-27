package fr.toolmap.data.backup

import fr.toolmap.core.backup.BackupException
import fr.toolmap.core.backup.BackupFailure
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The offline maps on a removable card: nothing is left out of a backup, nor created under a card that is out (ADR-042). */
class BackupTilesCardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun service(
        locations: BackupLocations,
        workDir: File,
    ) = BackupService(
        locations,
        appVersion = "0.1.0",
        hooks = BackupHooks(freezeDatabase = { copy -> copy() }, checkpointTiles = {}),
        workDir = workDir,
    )

    @Test
    fun `a backup with the offline maps is refused while their card is out, rather than left without them`() {
        val root = temp.newFolder("device")
        val locations =
            BackupLocations(
                File(root, "files").apply {
                    mkdirs()
                },
                File(root, "databases/tracks.db"),
                tilesReady = { false },
            )
        val failure =
            assertFailsWith<BackupException> {
                runBlocking {
                    service(locations, File(root, "cache")).create(ByteArrayOutputStream(), includeTiles = true)
                }
            }
        assertEquals(BackupFailure.TILES_UNAVAILABLE, failure.failure)
    }

    @Test
    fun `a backup without the offline maps does not need their card`() {
        val root = temp.newFolder("device")
        val locations =
            BackupLocations(
                File(root, "files").apply {
                    mkdirs()
                },
                File(root, "databases/tracks.db"),
                tilesReady = { false },
            )
        runBlocking { service(locations, File(root, "cache")).create(ByteArrayOutputStream(), includeTiles = false) }
    }

    @Test
    fun `a restore of the offline maps waits for their card, and creates nothing under it`() {
        val source = temp.newFolder("source")
        val sourceLocations =
            BackupLocations(File(source, "files").apply { mkdirs() }, File(source, "databases/tracks.db"))
        File(sourceLocations.tilesDir, "osm.mbtiles").apply {
            parentFile?.mkdirs()
            writeText("tiles")
        }
        val out = ByteArrayOutputStream()
        runBlocking { service(sourceLocations, File(source, "cache")).create(out, includeTiles = true) }

        val target = temp.newFolder("target")
        val card = File(temp.root, "card/tiles")
        val targetLocations =
            BackupLocations(
                File(target, "files").apply { mkdirs() },
                File(target, "databases/tracks.db"),
                tilesDir = card,
                tilesReady = { false },
            )
        runBlocking { service(targetLocations, File(target, "cache")).stage(ByteArrayInputStream(out.toByteArray())) }

        val report = assertNotNull(PendingRestore.apply(targetLocations))
        assertFalse(report.ok)
        assertFalse(File(temp.root, "card").exists(), "no folder is created under a card that is not mounted")
    }
}
