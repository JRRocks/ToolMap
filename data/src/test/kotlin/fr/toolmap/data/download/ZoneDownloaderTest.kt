package fr.toolmap.data.download

import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoomLimit
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TilePlanner
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.StaticSourceLookup
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileEntry
import fr.toolmap.data.tiles.TileInterceptor
import fr.toolmap.data.tiles.TileStats
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZoneDownloaderTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var store: MbTilesStore
    private val offline = OfflineMode()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private val requests = AtomicInteger()
    private val inFlight = AtomicInteger()
    private val maxInFlight = AtomicInteger()

    /** Per-test behaviour of the fake tile server: given z/x/y, the answer. */
    private var answer: (Int, Int, Int) -> MockResponse = { _, _, _ -> png() }
    private var serverDelayMillis = 0L

    private fun png() = MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png")

    private lateinit var source: TileSource

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.incrementAndGet()
                    val now = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { maxOf(it, now) }
                    try {
                        if (serverDelayMillis > 0) Thread.sleep(serverDelayMillis)
                        val parts =
                            request.path!!.removePrefix(
                                "/t/",
                            ).removeSuffix(".png").split('/').map(String::toInt)
                        return answer(parts[0], parts[1], parts[2])
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }
        server.start()
        dir = createTempDirectory("zone").toFile()
        source = sourceOf("s", ZoomSet.range(5, 12))
        store = MbTilesStore(dir)
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    /** The file holding the tiles of the map [id] (named after its server, ADR-031). */
    private fun stored(id: String) = store.fileFor(store.storageIdsOf(id).single())

    private fun sourceOf(
        id: String,
        zooms: ZoomSet,
        maxThreads: Int = 3,
        host: String = "${server.hostName}:${server.port}",
    ) = TileSource(
        id = id,
        name = id,
        levels = listOf(LevelDef(zooms, listOf("http://$host/t/{\$z}/{\$x}/{\$y}.png"), maxThreads = maxThreads)),
    )

    private fun downloader(
        vararg sources: TileSource,
        streak: Int = 25,
    ): ZoneDownloader {
        val lookup = StaticSourceLookup(sources.toList().ifEmpty { listOf(source) })
        store.close()
        store = MbTilesStore(dir, lookup)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(TileInterceptor(lookup, store, TileStats(), offline))
                .build()
        return ZoneDownloader(
            client,
            lookup,
            store,
            offline,
            failureStreakLimit = streak,
            retryDelaysMillis = emptyList(),
        )
    }

    private val paris = ZoneShape.Rectangle(2.20, 48.75, 2.50, 48.95)

    private fun request(
        minZoom: Int = 8,
        maxZoom: Int = 9,
        ids: List<String> = listOf("s"),
        shape: ZoneShape = ZoneShape.Rectangle(2.30, 48.80, 2.34, 48.84),
    ) = ZoneRequest("job", "test", ids, minZoom, maxZoom, shape)

    private fun run(
        d: ZoneDownloader,
        r: ZoneRequest,
        progress: (ZoneProgress) -> Unit = {},
    ) = runBlocking(Dispatchers.Default) { d.run(r, progress) }

    @Test
    fun `every tile of the zone is downloaded and stored, then a second run needs no network`() {
        val d = downloader()
        val r = request(minZoom = 11, maxZoom = 12)
        val expected = d.plan(r).total
        assertTrue(expected in 4..40, "unexpected tile count $expected")

        val first = assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(expected, first.progress.total)
        assertEquals(expected, first.progress.downloaded)
        assertEquals(expected * png.size, first.progress.bytes)
        assertEquals(expected, stored("s").tileCount())
        assertEquals(expected.toInt(), requests.get())

        val second = assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(expected, second.progress.alreadyStored)
        assertEquals(0L, second.progress.downloaded)
        assertEquals(expected.toInt(), requests.get(), "the second run must not touch the network")
    }

    @Test
    fun `a source only gets the zooms it declares`() {
        val d = downloader()
        val r = request(minZoom = 3, maxZoom = 14)
        val plan = d.plan(r)
        assertEquals((5..12).toSet(), plan.fetches.getValue("s"))
        // Zooms 3-4 and 13-14 are drawn from zooms 5 and 12 (ADR-031): they add no tile of their own.
        val perZoom = plan.perSource.getValue("s")
        assertEquals((3..14).toList(), perZoom.keys.toList())
        assertEquals(listOf(0L, 0L, 0L, 0L), listOf(3, 4, 13, 14).map(perZoom::getValue))
        assertTrue((5..12).all { perZoom.getValue(it) > 0 })
        assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals((5..12).toSet(), stored("s").tileCountByZoom().keys)
    }

    @Test
    fun `a zoom the map lacks is downloaded through the zoom it is drawn from`() {
        val d = downloader()
        // Only zoom 14 asked: it is enlarged from zoom 12, so zoom 12 is downloaded.
        val above = d.plan(request(minZoom = 14, maxZoom = 14))
        assertEquals(setOf(12), above.fetches.getValue("s"))
        assertTrue(above.perSource.getValue("s").getValue(14) > 0)
        // Zoom 3 is shrunk from zoom 5 (two levels, within the default three).
        assertEquals(setOf(5), d.plan(request(minZoom = 3, maxZoom = 3)).fetches.getValue("s"))
        // Zoom 1 is four levels away: nothing is shown there, nothing is downloaded.
        assertEquals(0L, d.plan(request(minZoom = 1, maxZoom = 1)).total)
    }

    @Test
    fun `two maps with the same server share their downloads`() {
        val twin = sourceOf("twin", ZoomSet.range(5, 12))
        val d = downloader(source, twin)
        val r = request(minZoom = 11, maxZoom = 12, ids = listOf("s", "twin"))
        val plan = d.plan(r)
        val single = d.plan(request(minZoom = 11, maxZoom = 12)).total
        assertEquals(single, plan.total)
        assertEquals(0L, plan.perSource.getValue("twin").values.sum())
        assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(single.toInt(), requests.get())
        assertEquals(store.storageIdsOf("s"), store.storageIdsOf("twin"))
        // Already stored for the twin too: a download of the twin alone fetches nothing.
        val again = assertIs<ZoneResult.Completed>(run(d, request(minZoom = 11, maxZoom = 12, ids = listOf("twin"))))
        assertEquals(single, again.progress.alreadyStored)
        assertEquals(single.toInt(), requests.get())
    }

    @Test
    fun `a multi-zoom map downloads the tiles of the maps it shows, at their forced zoom`() {
        val wide = sourceOf("wide", ZoomSet.range(5, 16))
        val multi =
            MultiZoomDraft(
                "Multi",
                listOf(
                    ZoomBand(8, 12, "wide"),
                    ZoomBand(13, 14, "wide", 15),
                ),
            ).toSource("mz", StaticSourceLookup(listOf(wide))::find)
        val d = downloader(wide, multi)
        val plan = d.plan(request(minZoom = 12, maxZoom = 14, ids = listOf("mz")))
        // Zoom 12 as is; zooms 13 and 14 both show the tiles of zoom 15, fetched once.
        assertEquals(mapOf("wide" to setOf(12, 15)), plan.fetches)
        assertEquals(0L, plan.perSource.getValue("mz").getValue(14))
        assertIs<ZoneResult.Completed>(run(d, request(minZoom = 12, maxZoom = 14, ids = listOf("mz"))))
        assertEquals(setOf(12, 15), stored("wide").tileCountByZoom().keys)
        // The multi-zoom map has no file of its own: it reads the one of the map it shows.
        assertEquals(store.storageIdsOf("wide"), store.storageIdsOf("mz"))
    }

    @Test
    fun `tiles the server does not have are counted as missing, not as failures`() {
        answer = { _, x, _ -> if (x % 2 == 0) MockResponse().setResponseCode(404) else png() }
        val d = downloader()
        val result = assertIs<ZoneResult.Completed>(run(d, request(minZoom = 12, maxZoom = 12, shape = paris)))
        assertTrue(result.progress.missing > 0)
        assertTrue(result.progress.downloaded > 0)
        assertEquals(0L, result.progress.failed)
        assertEquals(result.progress.total, result.progress.processed)
    }

    @Test
    fun `a run of failures interrupts the download and it can resume later`() {
        answer = { _, _, _ -> MockResponse().setResponseCode(500) }
        val d = downloader(streak = 4)
        val r = request(minZoom = 12, maxZoom = 12, shape = paris)
        val interrupted = assertIs<ZoneResult.Interrupted>(run(d, r))
        assertTrue(interrupted.progress.failed >= 4)
        assertTrue(interrupted.reason.contains("500"))

        answer = { _, _, _ -> png() }
        val resumed = assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(d.plan(r).total, stored("s").tileCount())
        assertEquals(0L, resumed.progress.failed)
    }

    @Test
    fun `nothing is downloaded in offline mode`() {
        val d = downloader()
        offline.set(true)
        val result = assertIs<ZoneResult.Refused>(run(d, request()))
        assertEquals(RefusalReason.OFFLINE, result.reason)
        assertEquals(0, requests.get())
        offline.set(false)
    }

    @Test
    fun `unknown sources and empty plans are refused`() {
        val d = downloader()
        assertEquals(
            RefusalReason.UNKNOWN_SOURCE,
            assertIs<ZoneResult.Refused>(run(d, request(ids = listOf("s", "nope")))).reason,
        )
        // Zooms 0..1 are more than three levels below the source's 5..12: nothing is shown there.
        assertEquals(
            RefusalReason.NOTHING_TO_DOWNLOAD,
            assertIs<ZoneResult.Refused>(run(d, request(minZoom = 0, maxZoom = 1))).reason,
        )
    }

    @Test
    fun `a layer zoom limit narrows the zooms a source is downloaded for`() {
        val d = downloader(sourceOf("s", ZoomSet.range(5, 12)))
        val open = request(minZoom = 8, maxZoom = 11)
        val limited = open.copy(zoomLimits = mapOf("s" to ZoomLimit(min = 9, max = 10)))
        assertEquals(setOf(8, 9, 10, 11), d.plan(open).perSource.getValue("s").keys)
        assertEquals(setOf(9, 10), d.plan(limited).perSource.getValue("s").keys)
        // A limit for another source changes nothing.
        assertEquals(
            setOf(8, 9, 10, 11),
            d.plan(open.copy(zoomLimits = mapOf("other" to ZoomLimit(1, 2)))).perSource.getValue("s").keys,
        )
    }

    @Test
    fun `a zoom limit that excludes every zoom leaves nothing to download`() {
        val d = downloader(sourceOf("s", ZoomSet.range(5, 12)))
        val result = run(d, request(minZoom = 8, maxZoom = 9).copy(zoomLimits = mapOf("s" to ZoomLimit(min = 11))))
        assertEquals(RefusalReason.NOTHING_TO_DOWNLOAD, assertIs<ZoneResult.Refused>(result).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a local only map is never downloaded`() {
        val local =
            TileSource(
                id = "pack",
                name = "pack",
                levels = listOf(LevelDef(ZoomSet.range(5, 12), listOf(LevelDef.LOCAL_ONLY_SERVER))),
            )
        val d = downloader(local)
        val result = run(d, request(ids = listOf("pack")))
        assertEquals(RefusalReason.NOTHING_TO_DOWNLOAD, assertIs<ZoneResult.Refused>(result).reason)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a zone that is too large is refused before any request`() {
        val d = downloader(sourceOf("s", ZoomSet.range(0, 18)))
        val big = request(minZoom = 16, maxZoom = 16, shape = ZoneShape.Rectangle(-10.0, 35.0, 30.0, 60.0))
        assertEquals(RefusalReason.TOO_MANY_TILES, assertIs<ZoneResult.Refused>(run(d, big)).reason)
        assertEquals(0, requests.get())
    }

    @Test
    fun `community servers are limited to a small area`() {
        val osm = sourceOf("osm", ZoomSet.range(0, 19), host = "tile.openstreetmap.org")
        val d = downloader(osm)
        val area = ZoneShape.Rectangle(2.0, 48.5, 3.0, 49.0)
        val result =
            assertIs<ZoneResult.Refused>(run(d, request(ids = listOf("osm"), minZoom = 14, maxZoom = 14, shape = area)))
        assertEquals(RefusalReason.RESTRICTED_SOURCE, result.reason)
        assertEquals(0, requests.get())
        assertEquals(BulkPolicy.RESTRICTED_MAX_TILES, BulkPolicy.limitsFor(osm).maxTiles)
        assertEquals(null, BulkPolicy.limitsFor(source).maxTiles)
        assertEquals(
            2,
            BulkPolicy.limitsFor(sourceOf("t", ZoomSet.range(0, 5), host = "a.tile.opentopomap.org")).maxThreads,
        )
    }

    @Test
    fun `tiles already stored do not count against a community server's limit`() {
        val osm = sourceOf("osm", ZoomSet.range(0, 19), host = "tile.openstreetmap.org")
        val d = downloader(osm)
        val area = ZoneShape.Rectangle(2.0, 48.5, 3.0, 49.0)
        val r = request(ids = listOf("osm"), minZoom = 14, maxZoom = 14, shape = area)
        val total = d.plan(r).total
        assertTrue(total > BulkPolicy.RESTRICTED_MAX_TILES, "unexpected tile count $total")
        val tiles = TilePlanner.tiles(area.toArea(), 14..14).toList()

        fun keep(part: List<TileId>) =
            store.putAll(
                "osm",
                part.map {
                    TileEntry(it.z, it.x, it.y, StoredTile(png, "image/png"))
                },
            )

        // All but a hundred tiles are on the device: only the hundred missing ones would be asked.
        keep(tiles.drop(100))
        assertEquals(null, d.restrictedIn(d.plan(r, withStored = true)))
        // Counted without the stored tiles, the zone is over the limit, as before.
        assertEquals(total, d.restrictedIn(d.plan(r))?.tiles)

        // Everything stored: the run is not refused, and asks nothing from the server.
        keep(tiles.take(100))
        val done = assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(total, done.progress.alreadyStored)
        assertEquals(0, requests.get())
    }

    @Test
    fun `at most max-threads downloads run at the same time`() {
        serverDelayMillis = 40
        val d = downloader(sourceOf("s", ZoomSet.range(5, 12), maxThreads = 2))
        assertIs<ZoneResult.Completed>(run(d, request(minZoom = 12, maxZoom = 12)))
        assertTrue(maxInFlight.get() in 1..2, "max simultaneous downloads was ${maxInFlight.get()}")
    }

    @Test
    fun `a source that asks for six connections gets six downloads at the same time`() {
        serverDelayMillis = 60
        val d = downloader(sourceOf("s", ZoomSet.range(5, 16), maxThreads = 6))
        assertIs<ZoneResult.Completed>(run(d, request(minZoom = 14, maxZoom = 14)))
        assertTrue(maxInFlight.get() in 4..6, "max simultaneous downloads was ${maxInFlight.get()}")
    }

    @Test
    fun `only the community servers are held to two connections and a pause`() {
        val geopf = sourceOf("ign", ZoomSet.range(0, 18), maxThreads = 6, host = "data.geopf.fr")
        assertEquals(6, minOf(geopf.levels.single().maxThreads, BulkPolicy.limitsFor(geopf).maxThreads))
        assertEquals(0L, BulkPolicy.limitsFor(geopf).delayMillis)
        for (host in listOf("tile.openstreetmap.org", "a.tile.openstreetmap.fr", "a.tile.opentopomap.org")) {
            val limits = BulkPolicy.limitsFor(sourceOf("c", ZoomSet.range(0, 18), maxThreads = 6, host = host))
            assertEquals(2, limits.maxThreads, host)
            assertEquals(300L, limits.delayMillis, host)
            assertEquals(1_500L, limits.maxTiles, host)
        }
    }

    @Test
    fun `cancelling keeps what was stored and a new run finishes the rest`() {
        serverDelayMillis = 25
        val d = downloader()
        val r = request(minZoom = 12, maxZoom = 12, shape = ZoneShape.Rectangle(2.20, 48.75, 2.50, 48.95))
        val total = d.plan(r).total
        runBlocking(Dispatchers.Default) {
            val job = launch(start = CoroutineStart.DEFAULT) { d.run(r) }
            withTimeout(WAIT_MILLIS) { while (requests.get() < 8) kotlinx.coroutines.delay(10) }
            job.cancelAndJoin()
        }
        val partial = stored("s").tileCount()
        assertTrue(partial in 1 until total, "partial=$partial total=$total")

        serverDelayMillis = 0
        val before = requests.get()
        val finished = assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(total, stored("s").tileCount())
        assertTrue(finished.progress.alreadyStored >= partial)
        assertTrue(requests.get() - before <= total - partial + 3, "re-downloaded stored tiles")
    }

    @Test
    fun `a corridor around a track downloads along the track only`() {
        val track = ZoneShape.Corridor(listOf(ZoneShape.Point(2.20, 48.75), ZoneShape.Point(2.50, 48.95)), 300.0)
        val d = downloader(sourceOf("s", ZoomSet.range(5, 16)))
        val r = request(minZoom = 14, maxZoom = 14, shape = track)
        val corridor = d.plan(r).total
        val box =
            d.plan(
                request(minZoom = 14, maxZoom = 14, shape = ZoneShape.Rectangle(2.20, 48.75, 2.50, 48.95)),
            ).total
        assertTrue(corridor in 1 until box / 2, "corridor=$corridor box=$box")
        assertIs<ZoneResult.Completed>(run(d, r))
        assertEquals(corridor, stored("s").tileCount())
    }

    @Test
    fun `progress is reported and ends complete`() {
        val d = downloader()
        val seen = java.util.Collections.synchronizedList(mutableListOf<ZoneProgress>())
        val result = assertIs<ZoneResult.Completed>(run(d, request(minZoom = 12, maxZoom = 12)) { seen += it })
        assertTrue(seen.isNotEmpty())
        assertEquals(result.progress, seen.last())
        assertEquals(1f, seen.last().fraction)
    }

    private companion object {
        /** A wait for stored tiles fails instead of hanging the build. */
        const val WAIT_MILLIS = 20_000L
    }
}
