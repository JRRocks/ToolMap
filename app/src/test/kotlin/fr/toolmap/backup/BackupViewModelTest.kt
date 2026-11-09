package fr.toolmap.backup

import android.app.Application
import fr.toolmap.R
import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupEntry
import fr.toolmap.core.backup.BackupManifest
import fr.toolmap.core.backup.BackupManifestCodec
import fr.toolmap.core.backup.RestoreReport
import fr.toolmap.core.backup.RestoreReportCodec
import fr.toolmap.data.backup.BackupHooks
import fr.toolmap.data.backup.BackupLocations
import fr.toolmap.data.backup.BackupService
import fr.toolmap.map.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var locations: BackupLocations
    private lateinit var service: BackupService
    private lateinit var vm: BackupViewModel

    // Appended from the IO thread that resumes the view model, read from the test thread.
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val root = temp.newFolder("device")
        val files = File(root, "files").also { it.mkdirs() }
        locations = BackupLocations(files, File(root, "databases/tracks.db"))
        File(files, "settings.json").writeText("{\"units\":\"IMPERIAL\"}")
        File(files, "sources").mkdirs()
        File(files, "sources/a.aqx").writeText("<aqx/>")
        File(files, "tiles").mkdirs()
        File(files, "tiles/osm.mbtiles").writeText("0123456789")
        service =
            BackupService(
                locations,
                appVersion = "0.1.0",
                hooks = BackupHooks(freezeDatabase = { it() }, checkpointTiles = {}, databaseRefusal = { null }),
                workDir = File(root, "cache"),
                clock = { 1_700_000_000_000L },
            )
        vm = BackupViewModel(service) { mapOf("osm.mbtiles" to ("OpenStreetMap" to "osm")) }
        scope.launch { vm.messages.collect { messages += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun waitFor(
        what: String,
        check: () -> Boolean,
    ) = runBlocking {
        try {
            withTimeout(TIMEOUT_MS) { while (!check()) delay(POLL_MS) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun idle() = waitFor("idle") { vm.ui.value.busy == null }

    /** A backup made by the service itself, as bytes. */
    private fun archive(includeTiles: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { service.create(out, includeTiles) }
        return out.toByteArray()
    }

    private fun lastMessage() = messages.last().resId

    @Test
    fun `saving writes an archive to the chosen place and says so`() {
        val out = ByteArrayOutputStream()
        vm.create(open = { out }, discard = { error("nothing to discard") })
        waitFor("backup created") { messages.isNotEmpty() }
        idle()

        assertEquals(R.string.backup_created, lastMessage())
        assertEquals(2, messages.last().args[0], "settings and one source")
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) names += (z.nextEntry ?: break).name
        }
        assertEquals(listOf("manifest.json", "settings.json", "sources/a.aqx"), names)
    }

    @Test
    fun `the screen knows each weight and each offline map, named, unticked at first (P33)`() {
        waitFor("read") { vm.ui.value.offlineMaps.isNotEmpty() }
        val ui = vm.ui.value
        assertEquals(listOf(OfflineMapFile("osm.mbtiles", "OpenStreetMap", "osm", 10L)), ui.offlineMaps)
        assertEquals(DATA_AREAS, ui.areas)
        assertEquals(emptySet(), ui.tiles)
        assertEquals(6L, ui.areaBytes[BackupArea.SOURCES])
        assertEquals(26L, ui.dataBytes, "settings (20) and one source (6)")
        assertEquals(0L, ui.tilesBytes)
    }

    @Test
    fun `only the ticked areas and offline maps go in the archive (P33)`() {
        waitFor("read") { vm.ui.value.offlineMaps.isNotEmpty() }
        vm.toggleAreas(setOf(BackupArea.SETTINGS))
        vm.toggleTiles("osm.mbtiles")
        assertEquals(10L, vm.ui.value.tilesBytes)
        val out = ByteArrayOutputStream()
        vm.create(open = { out }, discard = {})
        waitFor("backup created") { messages.isNotEmpty() }
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
            while (true) names += (z.nextEntry ?: break).name
        }
        assertEquals(listOf("manifest.json", "sources/a.aqx", "tiles/osm.mbtiles"), names)
    }

    @Test
    fun `a line that stands for several areas ticks them all, or unticks them all when all are`() {
        val three = setOf(BackupArea.ALERTS, BackupArea.PRINT, BackupArea.STATE)
        vm.toggleAreas(three)
        assertEquals(DATA_AREAS - three, vm.ui.value.areas)
        vm.toggleAreas(setOf(BackupArea.PRINT))
        vm.toggleAreas(three)
        assertEquals(DATA_AREAS, vm.ui.value.areas)
        // Nothing ticked: nothing to save.
        vm.toggleAreas(DATA_AREAS)
        assertFalse(vm.ui.value.canCreate)
        vm.create(open = { error("not to be opened") }, discard = {})
        assertNull(vm.ui.value.busy)
    }

    @Test
    fun `a restore of the ticked areas only leaves the others aside (P33)`() {
        val bytes = archive()
        vm.choose { ByteArrayInputStream(bytes) }
        waitFor("preview") { vm.ui.value.preview != null }
        assertEquals(DATA_AREAS, vm.ui.value.restoreAreas, "all it holds, ticked")
        vm.toggleRestore(setOf(BackupArea.SOURCES))
        vm.confirm()
        waitFor("pending") { vm.ui.value.pending != null }
        val pending = assertNotNull(vm.ui.value.pending)
        assertTrue(pending.covers(BackupArea.SETTINGS))
        assertFalse(pending.covers(BackupArea.SOURCES))
    }

    @Test
    fun `a backup that fails is discarded and the reason is given`() {
        var discarded = false
        val failing =
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
            }
        vm.create(open = { failing }, discard = { discarded = true })
        waitFor("failure reported") { messages.isNotEmpty() }
        idle()
        assertTrue(discarded, "the broken document is removed")
        assertEquals(R.string.backup_err_io, lastMessage())
        assertEquals("disk full", messages.last().args[0])
        assertNull(vm.ui.value.busy)
    }

    @Test
    fun `a place that cannot be opened is reported`() {
        vm.create(open = { null }, discard = {})
        waitFor("reported") { messages.isNotEmpty() }
        assertEquals(R.string.backup_cannot_open, lastMessage())
        vm.choose { null }
        waitFor("second report") { messages.size == 2 }
        assertEquals(R.string.backup_cannot_open, lastMessage())
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun `choosing a backup shows what is in it before anything is unpacked`() {
        val bytes = archive()
        vm.choose { ByteArrayInputStream(bytes) }
        waitFor("preview") { vm.ui.value.preview != null }
        val preview = assertNotNull(vm.ui.value.preview)
        assertTrue(preview.covers(BackupArea.SETTINGS))
        assertEquals(1, preview.fileCount(BackupArea.SOURCES))
        assertNull(vm.ui.value.pending)
        assertFalse(locations.stagingDir.exists())

        vm.cancelPreview()
        assertNull(vm.ui.value.preview)
        vm.confirm()
        assertNull(vm.ui.value.pending, "nothing chosen any more: nothing to confirm")
    }

    @Test
    fun `something that is not a backup gives a message and no preview`() {
        vm.choose { ByteArrayInputStream(ByteArray(300) { it.toByte() }) }
        waitFor("reported") { messages.isNotEmpty() }
        idle()
        assertEquals(R.string.backup_err_not_a_backup, lastMessage())
        assertNull(vm.ui.value.preview)
    }

    @Test
    fun `confirming unpacks the backup aside and waits for the restart`() {
        val bytes = archive()
        vm.choose { ByteArrayInputStream(bytes) }
        waitFor("preview") { vm.ui.value.preview != null }
        vm.confirm()
        waitFor("pending") { vm.ui.value.pending != null }
        idle()
        // The collector runs after the view model's coroutine (both unconfined): the message can land after `busy`.
        waitFor("staged reported") { messages.isNotEmpty() }

        assertNull(vm.ui.value.preview)
        assertEquals(R.string.backup_staged, lastMessage())
        assertEquals(service.pending(), vm.ui.value.pending)
        // The live data is untouched until the next start.
        assertEquals("{\"units\":\"IMPERIAL\"}", File(locations.filesDir, "settings.json").readText())

        vm.cancelPending()
        waitFor("cancelled") { vm.ui.value.pending == null }
        assertNull(service.pending())
        assertFalse(locations.stagingDir.exists())
    }

    @Test
    fun `an archive whose content is not what its manifest says is refused at the unpacking and leaves nothing`() {
        val manifest =
            BackupManifest(
                appVersion = "0.1.0",
                createdAtMillis = 1L,
                areas = listOf("settings"),
                entries =
                    listOf(
                        BackupEntry(
                            "settings.json",
                            2,
                            MessageDigest.getInstance("SHA-256").digest("{}".toByteArray()).joinToString("") {
                                "%02x".format(it)
                            },
                        ),
                    ),
            )
        val forged = ByteArrayOutputStream()
        ZipOutputStream(forged).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(BackupManifestCodec.encode(manifest).toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("settings.json"))
            z.write("{!".toByteArray())
            z.closeEntry()
        }
        vm.choose { ByteArrayInputStream(forged.toByteArray()) }
        waitFor("preview") { vm.ui.value.preview != null }
        vm.confirm()
        waitFor("failure") { messages.isNotEmpty() }
        idle()
        assertNull(vm.ui.value.pending)
        assertFalse(locations.stagingDir.exists())
        assertEquals(R.string.backup_err_corrupt, lastMessage())
        assertEquals("{\"units\":\"IMPERIAL\"}", File(locations.filesDir, "settings.json").readText())
    }

    @Test
    fun `the report of the last restore is shown until it is dismissed`() {
        val report = RestoreReport(ok = true, atMillis = 5L, backupMillis = 3L, files = 4)
        locations.reportFile.writeText(RestoreReportCodec.encode(report))
        vm.refresh()
        waitFor("report read") { vm.ui.value.report != null }
        assertEquals(report, vm.ui.value.report)
        vm.dismissReport()
        waitFor("dismissed") { vm.ui.value.report == null }
        assertNull(service.report())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
