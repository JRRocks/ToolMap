package fr.toolmap.data.hierarchy

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The check the emulator runs (`tools/emulator/hierarchy-keys.sh`), run here over a vault in memory: both phases pass,
 * what they print is what the script reads, and the check never touches a real operation.
 */
class HierarchyKeysCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun runTwice(
        vault: KeyVault,
        api: Int,
    ): List<String> {
        val root = temp.newFolder()
        val lines = ArrayList<String>()
        assertTrue(HierarchyKeysCheck.run(root, vault, api) { lines += it }, "$lines")
        // A new process: what the first phase stored is read back by a new storage over the same vault.
        assertTrue(HierarchyKeysCheck.run(root, vault, api) { lines += it }, "$lines")
        assertEquals(emptyList(), vault.aliases(HierarchyKeysCheck.ALIAS_PREFIX))
        assertEquals(emptyList(), root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name })
        return lines
    }

    @Test
    fun `API 31 and above - both ways of keeping the agreement key pass both phases`() {
        val lines = runTwice(SoftwareKeyVault(agreesInHardware = true), 34)
        assertTrue("hierarchy-keys PASS phase=create" in lines)
        assertTrue("hierarchy-keys PASS phase=reload" in lines)
        assertEquals(2, lines.count { it == "hierarchy-keys done" })
        for (mode in listOf("keystore", "file")) {
            assertEquals(2, lines.count { it.startsWith("hierarchy-keys agreement=$mode sign median=") }, "$lines")
        }
    }

    @Test
    fun `API below 31 - the agreement key in a sealed file passes both phases`() {
        val lines = runTwice(SoftwareKeyVault(agreesInHardware = false), 28)
        assertTrue("hierarchy-keys PASS phase=reload" in lines)
        assertFalse(lines.any { it.contains("agreement=keystore") })
    }

    @Test
    fun `a broken vault fails the check, said in one line, without a crash`() {
        val vault = SoftwareKeyVault().apply { refusing = true }
        val lines = ArrayList<String>()
        assertFalse(HierarchyKeysCheck.run(temp.newFolder(), vault, 34) { lines += it })
        assertTrue(lines.any { it.startsWith("hierarchy-keys FAIL phase=create") }, "$lines")
        assertEquals("hierarchy-keys done", lines.last())
    }

    @Test
    fun `the check never wipes the keys of a real operation`() {
        val vault = SoftwareKeyVault()
        val real = OperationStorage(File(temp.newFolder("files"), OperationStorage.FOLDER), vault)
        val stored = assertNotNull(real.create())
        runTwice(vault, 34)
        assertNotNull(real.open(stored.id))
    }
}
