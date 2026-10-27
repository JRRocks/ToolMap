package fr.toolmap.data.print

import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileId
import fr.toolmap.data.download.RefusalReason
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.download.ZoneResult
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.StaticSourceLookup
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileInterceptor
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.data.tiles.TileStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrintDataTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var store: MbTilesStore
    private val offline = OfflineMode()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val requests = AtomicInteger()
    private var answer: (Int, Int, Int) -> MockResponse = { _, _, _ -> png() }

    private fun png() = MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png")

    private fun sourceOf(
        id: String,
        host: String = "${server.hostName}:${server.port}",
    ) = TileSource(
        id = id,
        name = id,
        levels = listOf(LevelDef(ZoomSet.range(5, 14), listOf("http://$host/t/{\$z}/{\$x}/{\$y}.png"), maxThreads = 3)),
    )

    private lateinit var lookup: StaticSourceLookup
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    val p = request.path!!.removePrefix("/t/").removeSuffix(".png").split('/').map(String::toInt)
                    return answer(p[0], p[1], p[2])
                }
            }
        server.start()
        dir = createTempDirectory("print").toFile()
        // "osm.mirror" shares OpenStreetMap's host policy: bulk reads of it are not allowed.
        val free = sourceOf("free")
        val multi =
            MultiZoomDraft(
                "Multi",
                listOf(ZoomBand(5, 14, "free")),
            ).toSource("mz", StaticSourceLookup(listOf(free))::find)
        lookup = StaticSourceLookup(listOf(free, sourceOf("osm-like", "tile.openstreetmap.org"), multi))
        store = MbTilesStore(dir, lookup)
        client =
            OkHttpClient
                .Builder()
                .addInterceptor(TileInterceptor(lookup, store, TileStats(), offline))
                .build()
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    private val free get() = lookup.find("free")!!
    private val osmLike get() = lookup.find("osm-like")!!

    // --- tile fetcher

    @Test
    fun `a stored tile is read without the network and never expires`() {
        store.put(TileKey("free", 9, 1, 2), StoredTile(png, "image/png", storedAtMillis = 1L))
        val fetcher = HttpPrintTileFetcher(client, store, offline)
        val found = assertIs<TileFetch.Found>(fetcher.fetch(free, TileId(9, 1, 2)))
        assertEquals(png.size, found.bytes.size)
        assertEquals(0, requests.get())
    }

    @Test
    fun `a missing tile is fetched online and stored, a 404 is empty, a server error is unavailable`() {
        answer = { _, x, _ ->
            when (x) {
                1 -> MockResponse().setResponseCode(404)
                2 -> MockResponse().setResponseCode(500)
                else -> png()
            }
        }
        val fetcher = HttpPrintTileFetcher(client, store, offline)
        assertIs<TileFetch.Found>(fetcher.fetch(free, TileId(9, 0, 0)))
        assertNotNull(store.get(TileKey("free", 9, 0, 0)))
        assertIs<TileFetch.Empty>(fetcher.fetch(free, TileId(9, 1, 0)))
        assertIs<TileFetch.Unavailable>(fetcher.fetch(free, TileId(9, 2, 0)))
    }

    @Test
    fun `offline and restricted servers are never reached by a print`() {
        val fetcher = HttpPrintTileFetcher(client, store, offline)
        assertIs<TileFetch.Unavailable>(fetcher.fetch(osmLike, TileId(9, 0, 0)))
        offline.set(true)
        assertIs<TileFetch.Unavailable>(fetcher.fetch(free, TileId(9, 0, 0)))
        assertEquals(0, requests.get())
        // ...but what is stored is still read for them.
        store.put(TileKey("osm-like", 9, 0, 0), StoredTile(png, "image/png"))
        assertIs<TileFetch.Found>(fetcher.fetch(osmLike, TileId(9, 0, 0)))
    }

    // --- download of the listed tiles

    private fun downloader() = ZoneDownloader(client, lookup, store, offline, retryDelaysMillis = emptyList())

    private fun tiles(count: Int) = (0 until count).map { TileId(10, it, 3) }

    @Test
    fun `the tiles to download are those not stored yet`() {
        val d = downloader()
        store.put(TileKey("free", 10, 0, 3), StoredTile(png, "image/png"))
        val missing = d.missing(mapOf("free" to tiles(4)))
        assertEquals(listOf(TileId(10, 1, 3), TileId(10, 2, 3), TileId(10, 3, 3)), missing.getValue("free"))
        assertTrue(d.missing(mapOf("free" to listOf(TileId(10, 0, 3)))).isEmpty())
    }

    @Test
    fun `listed tiles are downloaded once and a second run has nothing left to do`() {
        val d = downloader()
        val progress = ArrayList<ZoneProgress>()
        val first = runBlocking(Dispatchers.Default) { d.runTiles(mapOf("free" to tiles(6)), progress::add) }
        val done = assertIs<ZoneResult.Completed>(first)
        assertEquals(6L, done.progress.downloaded)
        assertEquals(6, requests.get())
        assertEquals(6L, store.fileFor(store.storageIdsOf("free").single()).tileCount())
        assertTrue(progress.isNotEmpty())
        val second = runBlocking(Dispatchers.Default) { d.runTiles(mapOf("free" to tiles(6))) }
        assertEquals(RefusalReason.NOTHING_TO_DOWNLOAD, assertIs<ZoneResult.Refused>(second).reason)
        assertEquals(6, requests.get())
    }

    @Test
    fun `listed tiles of a multi-zoom map are downloaded for the map it shows`() {
        // Crash of 2026-10-18: the missing tiles are keyed by the member map ("free"), not by the printed one ("mz").
        val d = downloader()
        assertEquals(setOf("free"), d.missing(mapOf("mz" to tiles(3))).keys)
        val done =
            assertIs<ZoneResult.Completed>(runBlocking(Dispatchers.Default) { d.runTiles(mapOf("mz" to tiles(3))) })
        assertEquals(3L, done.progress.downloaded)
        assertEquals(3L, store.fileFor(store.storageIdsOf("free").single()).tileCount())
    }

    @Test
    fun `listed tiles are refused offline, for an unknown source and above a restricted server limit`() {
        val d = downloader()
        offline.set(true)
        assertEquals(
            RefusalReason.OFFLINE,
            assertIs<ZoneResult.Refused>(runBlocking { d.runTiles(mapOf("free" to tiles(2))) }).reason,
        )
        offline.set(false)
        assertEquals(
            RefusalReason.UNKNOWN_SOURCE,
            assertIs<ZoneResult.Refused>(runBlocking { d.runTiles(mapOf("nope" to tiles(2))) }).reason,
        )
        val many = (0 until 1_600).map { TileId(14, it, 5) }
        assertEquals(
            RefusalReason.RESTRICTED_SOURCE,
            assertIs<ZoneResult.Refused>(runBlocking { d.runTiles(mapOf("osm-like" to many)) }).reason,
        )
        assertEquals(0, requests.get())
    }

    // --- settings of the last print

    @Test
    fun `the settings of the last print survive a restart`() {
        val store = PrintTemplateStore(File(dir, "print"))
        assertEquals(PrintTemplate(), store.last())
        store.remember(PrintTemplate(scale = 100_000.0, title = "Dernier", grid = GridKind.MGRS))
        store.remember(PrintTemplate(scale = 50_000.0, title = "Encore", grid = GridKind.MGRS))

        val again = PrintTemplateStore(File(dir, "print"))
        assertEquals(50_000.0, again.last().scale)
        assertEquals("Encore", again.last().title)
        assertEquals(GridKind.MGRS, again.last().grid)
        assertTrue(!File(dir, "print/last.json.tmp").exists())
    }

    @Test
    fun `a damaged settings file gives defaults`() {
        File(dir, "print").mkdirs()
        File(dir, "print/last.json").writeText("{{{")
        assertEquals(PrintTemplate(), PrintTemplateStore(File(dir, "print")).last())
    }
}
