package fr.toolmap.data.workspaces

import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.WorkspaceCodec
import fr.toolmap.core.layers.Workspaces
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceStoreTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = createTempDirectory("workspaces").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun workspace(
        name: String,
        id: String = WorkspaceStore.newId(),
    ) = Workspace.of(id, name, LayerStack().add("osm").let { it.setZoomRange(it.layers.single().id, 4, 12) })

    @Test
    fun `a saved workspace is listed and survives a restart`() {
        val store = WorkspaceStore(dir)
        val saved = workspace("Rando")
        store.save(saved)
        assertEquals(listOf(saved), store.workspaces.value)
        assertEquals(listOf(saved), WorkspaceStore(dir).workspaces.value)
    }

    @Test
    fun `saving under an existing id replaces it`() {
        val store = WorkspaceStore(dir)
        val first = workspace("Ancien", id = "ws-1")
        store.save(first)
        store.save(first.copy(name = "Nouveau"))
        assertEquals(listOf("Nouveau"), store.workspaces.value.map { it.name })
        assertEquals(listOf("Nouveau"), WorkspaceStore(dir).workspaces.value.map { it.name })
    }

    @Test
    fun `a deleted workspace is gone for good`() {
        val store = WorkspaceStore(dir)
        val a = workspace("A")
        val b = workspace("B")
        store.save(a)
        store.save(b)
        store.delete(a.id)
        assertEquals(listOf(b), store.workspaces.value)
        assertEquals(listOf(b), WorkspaceStore(dir).workspaces.value)
    }

    @Test
    fun `a damaged file is ignored and the others still load`() {
        val store = WorkspaceStore(dir)
        store.save(workspace("Bon"))
        File(dir, "casse.json").writeText("{ this is not json")
        assertEquals(listOf("Bon"), WorkspaceStore(dir).workspaces.value.map { it.name })
    }

    @Test
    fun `a built-in preset is updated in place, survives a restart and its original comes back`() {
        val store = WorkspaceStore(dir)
        val mine = Workspaces.randoEte.copy(layers = Workspaces.randoEte.layers.dropLast(1))
        store.save(mine)
        assertEquals(listOf(mine, Workspaces.randoHiver), store.favorites.value)
        assertTrue(store.workspaces.value.isEmpty())
        val reopened = WorkspaceStore(dir)
        assertEquals(listOf(mine, Workspaces.randoHiver), reopened.favorites.value)
        // Deleted, then shown again: still the user's version.
        reopened.delete(mine.id)
        assertEquals(listOf(Workspaces.randoHiver), reopened.favorites.value)
        reopened.unhide(mine.id)
        assertEquals(mine, reopened.favorites.value.first())
        reopened.restoreOriginal(mine.id)
        assertEquals(Workspaces.builtin, reopened.favorites.value)
        assertEquals(Workspaces.builtin, WorkspaceStore(dir).favorites.value)
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test
    fun `saving a built-in preset as shipped puts back its original, an unknown one is refused`() {
        val store = WorkspaceStore(dir)
        store.save(Workspaces.randoHiver.copy(name = "Rando Hiver", layers = Workspaces.randoHiver.layers.take(2)))
        store.save(Workspaces.randoHiver)
        assertEquals(Workspaces.builtin, WorkspaceStore(dir).favorites.value)
        val unknown = Workspace("${Workspace.BUILTIN_PREFIX}gone", "Ancien", listOf())
        assertFailsWith<IllegalArgumentException> { store.save(unknown) }
        // A file of a preset no longer shipped is ignored.
        File(dir, "builtin-gone.json").writeText(WorkspaceCodec.encode(unknown))
        assertEquals(Workspaces.builtin, WorkspaceStore(dir).favorites.value)
        assertTrue(WorkspaceStore(dir).workspaces.value.isEmpty())
    }

    @Test
    fun `ids are unique and file names are safe`() {
        assertTrue(WorkspaceStore.newId() != WorkspaceStore.newId())
        val store = WorkspaceStore(dir)
        store.save(workspace("Chemin", id = "ws-../../etc/passwd"))
        assertTrue(dir.listFiles().orEmpty().all { it.parentFile == dir })
        assertEquals(1, WorkspaceStore(dir).workspaces.value.size)
    }

    @Test
    fun `a built-in favorite can be deleted, stays deleted after a restart and can be restored`() {
        val store = WorkspaceStore(dir)
        val saved = workspace("Perso")
        store.save(saved)
        assertEquals(Workspaces.builtin + saved, store.favorites.value)
        store.delete(Workspaces.randoEte.id)
        assertEquals(setOf(Workspaces.randoEte.id), store.hiddenBuiltins.value)
        assertEquals(Workspaces.builtin - Workspaces.randoEte + saved, store.favorites.value)
        val reopened = WorkspaceStore(dir)
        assertEquals(Workspaces.builtin - Workspaces.randoEte + saved, reopened.favorites.value)
        assertEquals(listOf(saved), reopened.workspaces.value)
        reopened.restoreBuiltins()
        assertEquals(Workspaces.builtin + saved, reopened.favorites.value)
        assertTrue(WorkspaceStore(dir).hiddenBuiltins.value.isEmpty())
    }

    @Test
    fun `the user's multi-zoom map replaces IGN topo as first favorite`() {
        assertEquals(Workspaces.randoEte, Workspaces.builtin.first())
        assertTrue(Workspaces.builtin.none { ws -> ws.layers.any { it.sourceId == "ign-maps" } })
    }
}
