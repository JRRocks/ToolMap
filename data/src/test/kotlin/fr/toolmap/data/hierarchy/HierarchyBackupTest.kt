package fr.toolmap.data.hierarchy

import fr.toolmap.core.backup.BackupArea
import fr.toolmap.core.backup.BackupPaths
import fr.toolmap.data.backup.BackupHooks
import fr.toolmap.data.backup.BackupLocations
import fr.toolmap.data.backup.BackupService
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Nothing of the « Opération » storage leaves the phone in a backup (P39.6, ADR-128): neither in the app's own
 * archive (`docs/specs/backup-format.md` § 3) nor in Android's backups (`data_extraction_rules.xml`,
 * `backup_rules.xml`).
 */
class HierarchyBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the app's backup of every area holds nothing of a stored operation`() {
        val filesDir = temp.newFolder("files")
        File(filesDir, "settings.json").writeText("{}")
        val vault = SoftwareKeyVault()
        val storage = OperationStorage(File(filesDir, OperationStorage.FOLDER), vault)
        val stored = assertNotNull(storage.create())
        stored.save(OperationWorld(stored.keys).contents)
        val below31 = OperationStorage(File(filesDir, OperationStorage.FOLDER), SoftwareKeyVault(false))
        val stored2 = assertNotNull(below31.create())
        stored2.save(OperationWorld(stored2.keys).contents)
        val ours = File(filesDir, "hierarchy").walkTopDown().filter { it.isFile }.toList()
        assertTrue(ours.size >= 10)
        for (file in ours) assertEquals(null, BackupPaths.areaOf(file.relativeTo(filesDir).invariantSeparatorsPath))

        val service =
            BackupService(
                BackupLocations(filesDir, File(temp.root, "databases/tracks.db")),
                appVersion = "0.1.0",
                hooks = BackupHooks(freezeDatabase = { it() }, checkpointTiles = {}, databaseRefusal = { null }),
                workDir = temp.newFolder("cache"),
            )
        val out = ByteArrayOutputStream()
        runBlocking { service.create(out, BackupArea.entries.toSet() - BackupArea.TILES, emptySet()) }
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertTrue("settings.json" in names)
        assertFalse(names.any { it.startsWith("hierarchy") }, "$names")
        val archive = out.toByteArray()
        for (file in ours) {
            val start = file.readBytes().copyOf(minOf(32, file.length().toInt()))
            assertFalse(contains(archive, start), "bytes of ${file.name} in the archive")
        }
    }

    @Test
    fun `Android's backups and device transfers exclude the storage, and the cloud gets nothing`() {
        val repo = File(assertNotNull(System.getProperty("toolmap.repo.dir"), "toolmap.repo.dir"))
        val xml = File(repo, "app/src/main/res/xml")
        val folder = OperationStorage.FOLDER.substringBefore('/')

        fun excluded(
            file: String,
            section: String?,
        ): List<Element> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, file))
            val scope = section?.let { doc.getElementsByTagName(it).item(0) as Element } ?: doc.documentElement
            val nodes = scope.getElementsByTagName("exclude")
            return List(nodes.length) { nodes.item(it) as Element }
        }

        fun excludes(
            rules: List<Element>,
            domain: String,
            path: String?,
        ) = rules.any { it.getAttribute("domain") == domain && (path == null || it.getAttribute("path") == path) }

        val transfer = excluded("data_extraction_rules.xml", "device-transfer")
        assertTrue(excludes(transfer, "file", folder), "device-to-device transfer takes $folder/")
        val cloud = excluded("data_extraction_rules.xml", "cloud-backup")
        assertTrue(cloud.any { it.getAttribute("domain") == "file" && !it.hasAttribute("path") }, "cloud takes files")
        val old = excluded("backup_rules.xml", null)
        assertTrue(excludes(old, "file", folder), "backup of Android 11 and older takes $folder/")
        val manifest = File(repo, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("android:allowBackup=\"false\"" in manifest)
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
