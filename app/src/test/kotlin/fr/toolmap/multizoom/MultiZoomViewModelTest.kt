package fr.toolmap.multizoom

import android.app.Application
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.DraftIssue
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.data.sources.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MultiZoomViewModelTest {
    private lateinit var root: File
    private lateinit var repository: SourceRepository
    private lateinit var vm: MultiZoomViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = createTempDirectory("multizoom").toFile()
        repository = SourceRepository(File(root, "sources"), BuiltinSources.load())
        vm = MultiZoomViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        root.deleteRecursively()
    }

    private val editor get() = assertNotNull(vm.ui.value.editor)

    @Test
    fun `the list shows the multi-zoom maps and a new map is saved under a readable id`() {
        assertEquals(listOf("ign-topo25k"), vm.ui.value.maps.map { it.id })
        vm.startNew()
        assertEquals(listOf(ZoomBand(0, 18, "osm")), editor.draft.bands)
        assertEquals(listOf(DraftIssue.Kind.NO_NAME), editor.issues.map { it.kind })
        assertNull(vm.save())

        vm.setName("Rando été")
        assertTrue(editor.canSave)
        assertEquals("mz-rando-ete", vm.save())
        assertNull(vm.ui.value.editor)
        val saved = assertNotNull(repository.find("mz-rando-ete"))
        assertTrue(saved.isMultiZoom)
        assertTrue(repository.isImported(saved.id))
        assertEquals(listOf("ign-topo25k", "mz-rando-ete"), vm.ui.value.maps.map { it.id })
        // Kept as an ordinary .aqx file, read back unchanged after a restart.
        repository.reload()
        assertEquals(saved, repository.find("mz-rando-ete"))
    }

    @Test
    fun `bands are added after the last one and an overlap blocks saving`() {
        vm.startNew("Bandes")
        vm.setRange(0, 0, 11)
        vm.addBand()
        assertEquals(ZoomBand(12, 14, "osm"), editor.draft.bands[1])
        vm.setMap(1, "ign-scan25")
        assertTrue(editor.canSave)

        vm.setRange(1, 10, 16)
        assertEquals(listOf(DraftIssue.Kind.OVERLAP), editor.issues.map { it.kind })
        assertFalse(editor.canSave)
        assertNull(vm.save())
        vm.removeBand(1)
        assertTrue(editor.canSave)
    }

    @Test
    fun `a forced zoom starts on a zoom the map serves and follows the band`() {
        vm.startNew("IGN 25")
        vm.setMap(0, "ign-maps")
        vm.setRange(0, 13, 14)
        vm.setForced(0, true)
        assertEquals(13, editor.draft.bands[0].forcedZoom)
        vm.setForcedZoom(0, 15)
        assertEquals(ZoomBand(13, 14, "ign-maps", 15), editor.draft.bands[0])
        // Moved down to zooms 9-10: zoom 15 would be too many tiles for one, so it follows.
        vm.setRange(0, 9, 10)
        assertEquals(9 + MultiZoomViewModel.FORCE_REACH, editor.draft.bands[0].forcedZoom)
        vm.setForced(0, false)
        assertNull(editor.draft.bands[0].forcedZoom)
    }

    @Test
    fun `a built-in multi-zoom map is copied, an imported one is edited in place`() {
        vm.edit("ign-topo25k")
        assertNull(editor.editingId)
        assertEquals("IGN Topo 25k (zoom forcé) (copie)", editor.draft.name)
        val copy = assertNotNull(vm.save())
        assertTrue(copy != "ign-topo25k")
        assertEquals(repository.find("ign-topo25k")?.levels, repository.find(copy)?.levels)

        vm.edit(copy)
        assertEquals(copy, editor.editingId)
        vm.setName("Ma topo")
        assertEquals(copy, vm.save())
        assertEquals("Ma topo", repository.find(copy)?.name)
        assertEquals(2, vm.ui.value.maps.size)
    }

    @Test
    fun `a map imported from an aqx file becomes bands over the catalogue`() {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        val text = File(dir, "aqx/user/USR.PERSONNAL_MULTIZOOM.AQX").readText(Charsets.UTF_8)
        val imported = repository.import(repository.preview(text)).added.single()

        vm.derive(imported.id)
        assertNull(editor.editingId)
        assertEquals(
            listOf("osm", "opentopomap", "ign-scan25", "osm", "ign-ortho"),
            editor.draft.bands.map { it.mapId },
        )
        assertEquals(listOf(DraftIssue.Kind.GAP), editor.issues.map { it.kind })
        val id = assertNotNull(vm.save())
        assertTrue(assertNotNull(repository.find(id)).isMultiZoom)
    }

    @Test
    fun `a map cannot show itself and only imported maps can be deleted`() {
        vm.startNew("A")
        val a = assertNotNull(vm.save())
        vm.startNew("B")
        vm.setMap(0, a)
        val b = assertNotNull(vm.save())
        assertEquals(listOf(b), vm.usersOf(a).map { it.id })

        vm.edit(a)
        vm.setMap(0, b)
        assertEquals(listOf(DraftIssue.Kind.LOOP), editor.issues.map { it.kind })
        assertNull(vm.save())
        vm.cancel()

        assertFalse(vm.delete("ign-topo25k"))
        assertTrue(vm.delete(b))
        assertNull(repository.find(b))
    }
}
