package fr.toolmap.p2p

import fr.toolmap.core.p2p.GroupRecord
import fr.toolmap.core.p2p.ShareSettings
import fr.toolmap.core.p2p.ShareState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShareStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = ShareStore(File(folder.root, "p2p/share.json"))

    @Test
    fun `a missing file gives the defaults and no group`() {
        val state = store().read()
        assertNull(state.group)
        assertEquals(ShareSettings(), state.settings)
    }

    @Test
    fun `settings and group survive a write and a read`() {
        val group = GroupRecord.create()
        val written = ShareState(ShareSettings(name = "Alice", colorIndex = 3, intervalSec = 60), group)
        store().write(written)
        val read = store().read()
        assertEquals("Alice", read.settings.name)
        assertEquals(3, read.settings.colorIndex)
        assertEquals(60, read.settings.intervalSec)
        val g = assertNotNull(read.group)
        assertContentEquals(group.groupId, g.groupId)
        assertContentEquals(group.key, g.key)
        assertEquals(group.selfId, g.selfId)
    }

    @Test
    fun `a damaged file reads as the defaults`() {
        val file = File(folder.root, "p2p/share.json")
        file.parentFile!!.mkdirs()
        file.writeText("{ not json")
        val state = ShareStore(file).read()
        assertNull(state.group)
        assertEquals(ShareSettings(), state.settings)
    }

    @Test
    fun `a second write replaces the first and leaves no temporary file`() {
        val s = store()
        s.write(ShareState(ShareSettings(name = "A")))
        s.write(ShareState(ShareSettings(name = "B")))
        assertEquals("B", s.read().settings.name)
        assertEquals(listOf("share.json"), File(folder.root, "p2p").list()!!.toList())
    }
}
