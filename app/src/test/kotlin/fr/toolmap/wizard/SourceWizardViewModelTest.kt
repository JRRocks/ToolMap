package fr.toolmap.wizard

import android.app.Application
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.wizard.DraftIssue
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.data.wizard.CapabilitiesFetcher
import fr.toolmap.data.wizard.FetchFailure
import fr.toolmap.data.wizard.ProbeVerdict
import fr.toolmap.data.wizard.TileProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SourceWizardViewModelTest {
    private lateinit var root: File
    private lateinit var repository: SourceRepository
    private lateinit var store: MbTilesStore
    private lateinit var storage: StorageManager
    private lateinit var vm: SourceWizardViewModel
    private val server = MockWebServer()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    private val osm =
        TileSource(
            id = "osm",
            name = "OpenStreetMap",
            levels = listOf(LevelDef(ZoneRange, listOf("https://t/{\$z}/{\$x}/{\$y}.png"))),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        root = createTempDirectory("wizard").toFile()
        repository = SourceRepository(File(root, "sources"), listOf(osm))
        store = MbTilesStore(File(root, "tiles"), repository)
        storage = StorageManager(store, repository)
        vm =
            SourceWizardViewModel(
                RuntimeEnvironment.getApplication(),
                repository,
                storage,
                CapabilitiesFetcher(),
                TileProbe(),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
        store.close()
        root.deleteRecursively()
    }

    private fun address(path: String = "") = "http://${server.hostName}:${server.port}$path"

    private fun tileTemplate(query: String = "") = address("/tiles/{\$z}/{\$x}/{\$y}.png$query")

    private fun await(predicate: (WizardState) -> Boolean): WizardState =
        runBlocking { withTimeout(AWAIT_MS) { vm.state.first(predicate) } }

    private fun fixture(): ByteArray =
        File(requireNotNull(System.getProperty("fixtures.dir")), "wmts/ign-capabilities-excerpt.xml").readBytes()

    private fun fillXyz(
        name: String = "Ma carte",
        url: String = tileTemplate(),
    ) {
        vm.start(null, null)
        vm.chooseKind(WizardKind.XYZ)
        vm.setName(name)
        vm.setUrls(url)
    }

    @Test
    fun `steps follow one another and back stops at the first one`() {
        vm.start(null, null)
        assertEquals(WizardStep.TYPE, vm.state.value.step)
        vm.back()
        assertEquals(WizardStep.TYPE, vm.state.value.step)
        vm.chooseKind(WizardKind.XYZ)
        assertEquals(WizardStep.ADDRESS, vm.state.value.step)
        vm.setUrls(tileTemplate())
        listOf(WizardStep.ACCESS, WizardStep.ZOOMS, WizardStep.TEST, WizardStep.SAVE).forEach {
            vm.next()
            assertEquals(it, vm.state.value.step)
        }
        vm.next()
        assertEquals(WizardStep.SAVE, vm.state.value.step)
        vm.back()
        assertEquals(WizardStep.TEST, vm.state.value.step)
    }

    @Test
    fun `leaving the address step moves the api key to a parameter`() {
        fillXyz(url = tileTemplate("?apikey=sekret1234"))
        assertEquals("apikey" to "sekret1234", vm.detectedKey())
        assertTrue(DraftIssue.KEY_IN_URL in vm.state.value.issues)
        vm.next()
        val state = vm.state.value
        assertEquals(listOf("apikey"), state.keysMoved)
        assertEquals(mapOf("apikey" to "sekret1234"), state.draft.params)
        assertFalse(state.draft.urls.single().contains("sekret1234"))
        assertNull(vm.detectedKey())
    }

    @Test
    fun `wmts layer list fills the draft from the capabilities`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody(Buffer().write(fixture())))
        vm.start(null, null)
        vm.chooseKind(WizardKind.WMTS)
        vm.setServiceAddress(address("/wmts"))
        vm.readCapabilities()
        val loaded = await { it.capabilities is CapabilitiesState.Loaded }
        assertIs<CapabilitiesState.Loaded>(loaded.capabilities)
        assertEquals("/wmts?SERVICE=WMTS&REQUEST=GetCapabilities", server.takeRequest().path)

        val caps = (loaded.capabilities as CapabilitiesState.Loaded).fetched.document
        val wmts = (caps as fr.toolmap.core.wizard.CapabilitiesDocument.Wmts).capabilities
        vm.pickWmtsLayer(wmts.layers.first { it.id == "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2" })
        val draft = vm.state.value.draft
        assertEquals("Plan IGN", draft.name)
        assertEquals("0-19", draft.zoomSpec)
        assertEquals(256, draft.tileSize)
        assertEquals("IGN", draft.copyright)
        assertTrue(draft.urls.single().contains("LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2"))
        assertTrue(draft.urls.single().contains("TILEMATRIXSET=PM_0_19"))
        assertEquals("GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2", vm.state.value.choice?.layerId)
        assertTrue(draft.isValid, draft.validate().toString())
    }

    @Test
    fun `reading a bad address reports the failure and picks nothing`() {
        vm.start(null, null)
        vm.chooseKind(WizardKind.WMS)
        vm.setServiceAddress("https://x.example/wms?REQUEST=GetMap&LAYERS=a")
        vm.readCapabilities()
        val failed = await { it.capabilities is CapabilitiesState.Failed }
        assertEquals(FetchFailure.BAD_URL, (failed.capabilities as CapabilitiesState.Failed).error.failure)
        assertNull(failed.choice)
    }

    @Test
    fun `the test tile reports what the server did`() {
        fillXyz()
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png)))
        vm.runProbe()
        val done = await { it.probe is ProbeState.Done }
        assertEquals(ProbeVerdict.OK, (done.probe as ProbeState.Done).result.verdict)
        // A change to the draft clears a result that no longer describes it.
        vm.setReferer("https://allowed.example/")
        assertEquals(ProbeState.Idle, vm.state.value.probe)

        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        vm.runProbe()
        val denied = await { it.probe is ProbeState.Done }
        assertEquals(ProbeVerdict.KEY_OR_REFERER, (denied.probe as ProbeState.Done).result.verdict)
    }

    @Test
    fun `an invalid draft is neither tested nor saved`() {
        vm.start(null, null)
        vm.chooseKind(WizardKind.XYZ)
        vm.runProbe()
        assertEquals(ProbeState.Idle, vm.state.value.probe)
        assertNull(vm.save())
        assertNull(vm.aqxText())
        assertTrue(DraftIssue.NAME_EMPTY in vm.state.value.blockingIssues)
    }

    @Test
    fun `saving stores the map and a second one with the same name gets another id`() {
        fillXyz(name = "Ma carte")
        val first = assertNotNull(vm.save())
        assertEquals("ma-carte", first)
        assertEquals("Ma carte", repository.find(first)?.name)
        assertTrue(repository.isImported(first))

        fillXyz(name = "Ma carte")
        assertEquals("ma-carte-2", vm.save())
        // Saving the same wizard session again updates the same map instead of adding a third.
        assertEquals("ma-carte-2", vm.save())
        assertEquals(2, repository.sources.value.count { repository.isImported(it.id) })
    }

    @Test
    fun `editing keeps the id, and a changed address erases the stored tiles`() {
        fillXyz(name = "Edit me")
        val id = assertNotNull(vm.save())
        store.put(TileKey(id, 3, 1, 1), StoredTile(png, "image/png"))
        assertNotNull(store.get(TileKey(id, 3, 1, 1)))

        vm.start(id, LonLatZoom(2.0, 48.0, 10))
        val editing = vm.state.value
        assertEquals(id, editing.editingId)
        assertEquals(WizardStep.ADDRESS, editing.step)
        assertEquals("Edit me", editing.draft.name)
        vm.back()
        assertEquals(WizardStep.ADDRESS, vm.state.value.step, "no earlier step when editing")

        vm.setName("Renamed")
        assertFalse(vm.willPurgeStoredTiles(), "a new name does not touch the tiles")
        vm.setUrls(address("/other/{\$z}/{\$x}/{\$y}.png"))
        assertTrue(vm.willPurgeStoredTiles())
        assertEquals(id, vm.save())
        assertEquals("Renamed", repository.find(id)?.name)
        assertNull(store.get(TileKey(id, 3, 1, 1)))
    }

    @Test
    fun `a built in map is copied, not modified`() {
        vm.start("osm", null)
        val state = vm.state.value
        assertNull(state.editingId)
        assertEquals("", state.draft.id)
        assertEquals("OpenStreetMap (copie)", state.draft.name)
        val id = assertNotNull(vm.save())
        assertEquals("openstreetmap-copie", id)
        assertEquals("OpenStreetMap", repository.find("osm")?.name)
    }

    @Test
    fun `export hides the keys unless asked otherwise`() {
        fillXyz(url = tileTemplate("?apikey=sekret1234"))
        vm.next()
        val hidden = assertNotNull(vm.aqxText())
        assertFalse(hidden.contains("sekret1234"))
        assertTrue(hidden.contains("""<param name="apikey"><![CDATA[]]></param>"""), hidden)
        vm.setRedactKeys(false)
        assertTrue(assertNotNull(vm.aqxText()).contains("sekret1234"))
        assertEquals("ma-carte.aqx", vm.suggestedFileName())
    }

    @Test
    fun `the map centre becomes the test place`() {
        vm.start(null, LonLatZoom(-1.55, 47.2, 11))
        assertTrue(vm.hasMapCenter)
        vm.chooseKind(WizardKind.XYZ)
        vm.usePreviewHere()
        assertEquals(LonLatZoom(-1.55, 47.2, 11), vm.state.value.draft.previewLocation)
        vm.clearPreviewLocation()
        assertNull(vm.state.value.draft.previewLocation)
    }

    private companion object {
        val ZoneRange: ZoomSet = ZoomSet.range(0, 19)
        const val AWAIT_MS = 15_000L
    }
}
