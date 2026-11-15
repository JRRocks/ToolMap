package fr.toolmap.data.hierarchy

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The check the emulator runs (`tools/emulator/hierarchy-operation.sh`), run here over a vault in memory. */
class HierarchyOperationCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the three phases pass, the dissolution leaves only its trace, and the last one wipes it`() {
        val vault = SoftwareKeyVault()
        val root = temp.newFolder()
        val lines = ArrayList<String>()
        assertTrue(HierarchyOperationCheck.run(root, vault, 34) { lines += it }, "$lines")
        assertTrue(HierarchyOperationCheck.run(root, vault, 34) { lines += it }, "$lines")
        assertTrue("hierarchy-operation PASS phase=create" in lines)
        assertTrue("hierarchy-operation PASS phase=reload" in lines, "$lines")
        assertTrue(lines.any { it.contains("3 messages in 2 threads") }, "$lines")
        // Between the second and the third phase: no key of the operation, one key of its trace, one file.
        assertEquals(emptyList(), vault.aliases(HierarchyOperationCheck.ALIAS_PREFIX))
        assertEquals(1, vault.aliases(HierarchyOperationCheck.TRACE_ALIAS_PREFIX).size)
        assertEquals(
            listOf(DissolutionTraces.FOLDER),
            root.walkTopDown().filter { it.isFile && it.name != "trace.txt" }.map { it.parentFile.name }.toList(),
        )
        assertTrue(HierarchyOperationCheck.run(root, vault, 34) { lines += it }, "$lines")
        assertTrue("hierarchy-operation PASS phase=trace" in lines, "$lines")
        assertEquals(3, lines.count { it == "hierarchy-operation done" })
        assertEquals(emptyList(), vault.aliases(HierarchyOperationCheck.ALIAS_PREFIX))
        assertEquals(emptyList(), vault.aliases(HierarchyOperationCheck.TRACE_ALIAS_PREFIX))
        assertEquals(emptyList(), root.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `a broken vault fails the check in one line, without a crash`() {
        val vault = SoftwareKeyVault().apply { refusing = true }
        val lines = ArrayList<String>()
        assertTrue(!HierarchyOperationCheck.run(temp.newFolder(), vault, 34) { lines += it })
        assertTrue(lines.any { it.startsWith("hierarchy-operation FAIL phase=create") }, "$lines")
        assertEquals("hierarchy-operation done", lines.last())
    }
}
