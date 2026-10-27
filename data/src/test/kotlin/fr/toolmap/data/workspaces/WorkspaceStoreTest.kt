package fr.toolmap.data.workspaces

import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.layers.Workspace
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
    fun `a built-in preset cannot be saved over`() {
        val store = WorkspaceStore(dir)
        assertFailsWith<IllegalArgumentException> { store.save(Workspaces.sortieSki) }
        assertTrue(store.workspaces.value.isEmpty())
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
        store.delete(Workspaces.randoMultiZoom.id)
        assertEquals(setOf(Workspaces.randoMultiZoom.id), store.hiddenBuiltins.value)
        assertEquals(Workspaces.builtin - Workspaces.randoMultiZoom + saved, store.favorites.value)
        val reopened = WorkspaceStore(dir)
        assertEquals(Workspaces.builtin - Workspaces.randoMultiZoom + saved, reopened.favorites.value)
        assertEquals(listOf(saved), reopened.workspaces.value)
        reopened.restoreBuiltins()
        assertEquals(Workspaces.builtin + saved, reopened.favorites.value)
        assertTrue(WorkspaceStore(dir).hiddenBuiltins.value.isEmpty())
    }

    @Test
    fun `the user's multi-zoom map replaces IGN topo as first favorite`() {
        assertEquals(Workspaces.randoMultiZoom, Workspaces.builtin.first())
        assertTrue(Workspaces.builtin.none { ws -> ws.layers.any { it.sourceId == "ign-maps" } })
    }
}
