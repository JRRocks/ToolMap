package fr.toolmap.data.tiles

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MultiZoomDraft
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomBand
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.ScalingPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileInterceptorTest {
    private val server = MockWebServer()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private val store = InMemoryTileStore()

    /** Records what each composed tile was drawn from; its "image" is the number of tiles found. */
    private val composed = mutableListOf<List<TileKey>>()
    private val composer =
        TileComposer { recipe, leaf ->
            val found = recipe.leaves().filter { leaf(it) != null }
            composed += found.map { TileKey(it.source.id, it.tile.z, it.tile.x, it.tile.y) }
            if (found.isEmpty()) null else byteArrayOf(found.size.toByte())
        }
    private val stats = TileStats()
    private val offline = OfflineMode()

    @BeforeTest
    fun startServer() = server.start()

    @AfterTest
    fun stopServer() = server.shutdown()

    private fun template(path: String = "t") = "http://${server.hostName}:${server.port}/$path/{\$z}/{\$x}/{\$y}.png"

    private fun source(
        id: String = "s",
        zooms: ZoomSet = ZoomSet.range(0, 18),
        servers: List<String> = listOf(template()),
        params: Map<String, String> = emptyMap(),
        level: (LevelDef) -> LevelDef = { it },
    ) = TileSource(
        id = id,
        name = id,
        params = params,
        levels = listOf(level(LevelDef(zooms, servers))),
    )

    private fun client(
        vararg sources: TileSource,
        retry: Boolean = true,
        clock: () -> Long = System::currentTimeMillis,
        forced: ForcedRefresh = ForcedRefresh(),
        scaling: ScalingPolicy = ScalingPolicy(),
    ): OkHttpClient {
        val interceptor =
            TileInterceptor(
                StaticSourceLookup(sources.toList()),
                store,
                stats,
                offline,
                clock = clock,
                forcedRefresh = forced,
                scaling = { scaling },
                composer = composer,
            )
        return OkHttpClient
            .Builder()
            .addInterceptor(interceptor)
            .retryOnConnectionFailure(retry)
            .build()
    }

    private fun get(
        client: OkHttpClient,
        url: String,
    ): Response = client.newCall(Request.Builder().url(url).build()).execute()

    private fun tile(
        client: OkHttpClient,
        id: String = "s",
        z: Int = 3,
        x: Int = 4,
        y: Int = 5,
    ): Response = get(client, "https://${TileHost.HOST}/$id/$z/$x/$y")

    private fun pngResponse() = MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png")

    @Test
    fun `a miss is downloaded with the configured headers then served from the store`() {
        val src =
            source(level = {
                it.copy(
                    referer = "https://ref.example/",
                    userAgent = "UA-test",
                    headers = listOf("X-Test" to "1"),
                    cookie = "a=b",
                )
            })
        val client = client(src)
        server.enqueue(pngResponse())

        tile(client).use { response ->
            assertEquals(200, response.code)
            assertEquals("image/png", response.header("Content-Type"))
            assertContentEquals(png, response.body!!.bytes())
        }
        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/t/3/4/5.png", recorded.path)
        assertEquals("https://ref.example/", recorded.getHeader("Referer"))
        assertEquals("UA-test", recorded.getHeader("User-Agent"))
        assertEquals("1", recorded.getHeader("X-Test"))
        assertEquals("a=b", recorded.getHeader("Cookie"))

        tile(client).use { response ->
            assertEquals(200, response.code)
            assertContentEquals(png, response.body!!.bytes())
        }
        assertEquals(1, server.requestCount, "the second request must not reach the network")
        assertEquals(1, stats.snapshot().count(TileOutcome.NETWORK_OK))
        assertEquals(1, stats.snapshot().count(TileOutcome.STORE_HIT))
        assertNotNull(store.get(TileKey("s", 3, 4, 5)))
    }

    @Test
    fun `the default user agent identifies ToolMap`() {
        val client = client(source())
        server.enqueue(pngResponse())
        tile(client).close()
        val ua = server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("User-Agent")
        assertEquals(TileInterceptor.DEFAULT_USER_AGENT, ua)
        assertTrue(ua!!.startsWith("ToolMap/"))
    }

    @Test
    fun `offline mode answers 404 at once for a missing tile and still serves stored ones`() {
        val client = client(source())
        store.put(TileKey("s", 3, 4, 6), StoredTile(png, "image/png"))
        offline.set(true)

        tile(client, y = 5).use { assertEquals(404, it.code) }
        tile(client, y = 6).use { response ->
            assertEquals(200, response.code)
            assertContentEquals(png, response.body!!.bytes())
        }
        assertEquals(0, server.requestCount)
        assertEquals(1, stats.snapshot().count(TileOutcome.OFFLINE_MISS))
        assertEquals(1, stats.snapshot().count(TileOutcome.STORE_HIT))
    }

    @Test
    fun `a zoom too far from the levels answers 404 without network`() {
        val client = client(source(zooms = ZoomSet.range(6, 14)))
        // Four levels below zoom 6: beyond the three levels drawn by default.
        tile(client, z = 2, x = 0, y = 0).use { assertEquals(404, it.code) }
        assertEquals(0, server.requestCount)
        assertEquals(1, stats.snapshot().count(TileOutcome.OUT_OF_RANGE))
    }

    @Test
    fun `without overscaling a zoom outside the levels answers 404 without network`() {
        val client = client(source(zooms = ZoomSet.range(6, 14)), scaling = ScalingPolicy.OFF)
        tile(client, z = 5, x = 0, y = 0).use { assertEquals(404, it.code) }
        tile(client, z = 15, x = 0, y = 0).use { assertEquals(404, it.code) }
        assertEquals(0, server.requestCount)
        assertEquals(2, stats.snapshot().count(TileOutcome.OUT_OF_RANGE))
    }

    @Test
    fun `a zoom below the levels is drawn from the four tiles of the first zoom, which are stored`() {
        server.dispatcher = imageDispatcher()
        val client = client(source(zooms = ZoomSet.range(6, 14)))
        tile(client, z = 5, x = 3, y = 2).use { response ->
            assertEquals(200, response.code)
            assertEquals("image/png", response.header("Content-Type"))
            assertContentEquals(byteArrayOf(4), response.body!!.bytes())
        }
        val parts = setOf(TileKey("s", 6, 6, 4), TileKey("s", 6, 7, 4), TileKey("s", 6, 6, 5), TileKey("s", 6, 7, 5))
        assertEquals(parts, composed.single().toSet())
        assertEquals(4, server.requestCount)
        parts.forEach { assertNotNull(store.get(it)) }
        // The drawn tile itself is never stored: it is drawn again from the stored ones, without network.
        assertNull(store.get(TileKey("s", 5, 3, 2)))
        tile(client, z = 5, x = 3, y = 2).use { assertEquals(200, it.code) }
        assertEquals(4, server.requestCount)
        assertEquals(2, stats.snapshot().count(TileOutcome.COMPOSED))
    }

    @Test
    fun `a zoom above the levels is cut from the tile of the last zoom`() {
        server.dispatcher = imageDispatcher()
        val client = client(source(zooms = ZoomSet.range(6, 14)))
        tile(client, z = 16, x = 401, y = 802).use { assertEquals(200, it.code) }
        assertEquals(listOf(TileKey("s", 14, 100, 200)), composed.single())
        assertEquals("/t/14/100/200.png", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `a tile that cannot be drawn offline is a plain 404`() {
        val client = client(source(zooms = ZoomSet.range(6, 14)))
        offline.set(true)
        tile(client, z = 5, x = 0, y = 0).use { assertEquals(404, it.code) }
        assertEquals(0, server.requestCount)
        assertEquals(0, stats.snapshot().count(TileOutcome.COMPOSED))
    }

    @Test
    fun `a multi-zoom map serves the tiles of the map it shows, at the forced zoom`() {
        server.dispatcher = imageDispatcher()
        val base = source(id = "base", zooms = ZoomSet.range(0, 16))
        val multi =
            MultiZoomDraft("Multi", listOf(ZoomBand(0, 12, "base"), ZoomBand(13, 14, "base", 15)))
                .toSource("mz", StaticSourceLookup(listOf(base))::find)
        val client = client(base, multi)
        tile(client, id = "mz", z = 9, x = 10, y = 20).use { assertEquals(200, it.code) }
        assertEquals("/t/9/10/20.png", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        // Stored as a tile of the map that serves it: the base map finds it without network.
        tile(client, id = "base", z = 9, x = 10, y = 20).use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)
        // Zoom 14 shows the tiles of zoom 15, enlarged.
        tile(client, id = "mz", z = 14, x = 10, y = 20).use { assertEquals(200, it.code) }
        assertEquals(listOf(TileKey("base", 15, 20, 40)), composed.single().take(1))
        assertEquals(4, composed.single().size)
    }

    private fun imageDispatcher() =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = pngResponse()
        }

    @Test
    fun `unknown source and malformed urls are rejected`() {
        val client = client(source())
        tile(client, id = "nope").use { assertEquals(404, it.code) }
        get(client, "https://${TileHost.HOST}/s/3/4").use { assertEquals(400, it.code) }
        get(client, "https://${TileHost.HOST}/s/a/b/c").use { assertEquals(400, it.code) }
        get(client, "https://${TileHost.HOST}/s/3/-4/5").use { assertEquals(400, it.code) }
        assertEquals(0, server.requestCount)
        assertEquals(4, stats.snapshot().count(TileOutcome.REJECTED))
    }

    @Test
    fun `requests to other hosts go through untouched`() {
        val client = client(source())
        server.enqueue(MockResponse().setBody("hello"))
        get(client, "http://${server.hostName}:${server.port}/style.json").use {
            assertEquals("hello", it.body!!.string())
        }
        assertEquals(0, stats.snapshot().total)
    }

    @Test
    fun `an http error from the server passes through and is not stored`() {
        val client = client(source())
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403))
        tile(client).use { assertEquals(404, it.code) }
        tile(client).use { assertEquals(403, it.code) }
        assertEquals(2, server.requestCount, "failures must not be cached")
        assertNull(store.get(TileKey("s", 3, 4, 5)))
        assertEquals(2, stats.snapshot().count(TileOutcome.HTTP_ERROR))
        assertEquals(403, stats.snapshot().lastHttpCode)
    }

    @Test
    fun `a 200 answer that is not an image is refused`() {
        val client = client(source())
        server.enqueue(MockResponse().setBody("<ServiceException/>").addHeader("Content-Type", "text/xml"))
        tile(client).use { assertEquals(502, it.code) }
        assertNull(store.get(TileKey("s", 3, 4, 5)))
        assertEquals(1, stats.snapshot().count(TileOutcome.REJECTED))
        assertTrue(stats.snapshot().lastError.orEmpty().contains("text/xml"))
    }

    @Test
    fun `an empty 200 answer means no tile`() {
        val client = client(source())
        server.enqueue(MockResponse().addHeader("Content-Type", "image/png"))
        tile(client).use { assertEquals(404, it.code) }
        assertNull(store.get(TileKey("s", 3, 4, 5)))
    }

    @Test
    fun `a network failure becomes a 504`() {
        val client = client(source(), retry = false)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        tile(client).use { assertEquals(504, it.code) }
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.count(TileOutcome.NETWORK_ERROR))
        assertNotNull(snapshot.lastError)
        assertNull(store.get(TileKey("s", 3, 4, 5)))
    }

    @Test
    fun `several servers are used in turn`() {
        val client = client(source(servers = listOf(template("a"), template("b"))))
        repeat(4) { server.enqueue(pngResponse()) }
        repeat(4) { tile(client, x = it).close() }
        val paths = (0 until 4).map { server.takeRequest(1, TimeUnit.SECONDS)!!.path }
        assertEquals(listOf("/a/3/0/5.png", "/b/3/1/5.png", "/a/3/2/5.png", "/b/3/3/5.png"), paths)
    }

    @Test
    fun `max-threads limits simultaneous downloads per source`() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    maxSeen.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(80)
                    inFlight.decrementAndGet()
                    return pngResponse()
                }
            }
        val client = client(source(level = { it.copy(maxThreads = 2) }))
        val pool = Executors.newFixedThreadPool(6)
        try {
            val codes = (0 until 6).map { x -> pool.submit<Int> { tile(client, x = x).use { it.code } } }
            assertEquals(List(6) { 200 }, codes.map { it.get(20, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertTrue(maxSeen.get() in 1..2, "saw ${maxSeen.get()} simultaneous downloads")
    }

    @Test
    fun `a call cancelled before it runs never reaches the network`() {
        val client = client(source())
        val call = client.newCall(Request.Builder().url("https://${TileHost.HOST}/s/3/4/5").build())
        call.cancel()
        assertFailsWith<IOException> { call.execute() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a call cancelled while it waits for a download turn sends no request`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    Thread.sleep(400)
                    return pngResponse()
                }
            }
        val client = client(source(level = { it.copy(maxThreads = 1) }))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val busy = pool.submit<Int> { tile(client, x = 0).use { it.code } }
            Thread.sleep(150)
            val waiting = client.newCall(Request.Builder().url("https://${TileHost.HOST}/s/3/1/5").build())
            val result = pool.submit<Boolean> { runCatching { waiting.execute().close() }.isFailure }
            Thread.sleep(100)
            waiting.cancel()
            assertTrue(result.get(5, TimeUnit.SECONDS), "the cancelled call must fail")
            assertEquals(200, busy.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, server.requestCount, "only the first tile was downloaded")
    }

    @Test
    fun `the tiles asked for last are downloaded first`() {
        val order = CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    order += request.path.orEmpty()
                    Thread.sleep(400)
                    return pngResponse()
                }
            }
        val client = client(source(level = { it.copy(maxThreads = 1) }))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val codes =
                (0 until 4).map { x ->
                    pool.submit<Int> { tile(client, x = x).use { it.code } }.also { Thread.sleep(60) }
                }
            assertEquals(List(4) { 200 }, codes.map { it.get(10, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertEquals(listOf("/t/3/0/5.png", "/t/3/3/5.png", "/t/3/2/5.png", "/t/3/1/5.png"), order)
    }

    @Test
    fun `a stored tile is not held up by downloads waiting for their turn`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    Thread.sleep(200)
                    return pngResponse()
                }
            }
        store.put(TileKey("s", 3, 99, 5), StoredTile(png, "image/png"))
        val client = client(source(level = { it.copy(maxThreads = 1) }))
        val pool = Executors.newFixedThreadPool(12)
        try {
            (0 until 10).map { x -> pool.submit<Int> { tile(client, x = x).use { it.code } } }
            Thread.sleep(100)
            val started = System.nanoTime()
            tile(client, x = 99).use { assertEquals(200, it.code) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(elapsedMs < 150, "the stored tile waited $elapsedMs ms behind the network")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a zone download tile is handed to the batch, neither looked up nor written by the interceptor`() {
        val old = StoredTile(byteArrayOf(9, 9), "image/png")
        store.put(TileKey("s", 3, 4, 5), old)
        server.enqueue(pngResponse())
        val received = mutableListOf<Pair<TileKey, StoredTile>>()
        val request =
            Request
                .Builder()
                .url("https://${TileHost.HOST}/s/3/4/5")
                .tag(BulkFetch::class.java, BulkFetch { key, tile -> received += key to tile })
                .build()
        client(source()).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertContentEquals(png, response.body!!.bytes())
        }
        assertEquals(1, server.requestCount, "the downloader decides what to fetch, not the store")
        assertEquals(listOf(TileKey("s", 3, 4, 5)), received.map { it.first })
        assertContentEquals(png, received.single().second.bytes)
        assertEquals("image/png", received.single().second.contentType)
        assertContentEquals(old.bytes, store.get(TileKey("s", 3, 4, 5))!!.bytes, "the interceptor wrote nothing")
    }

    @Test
    fun `a missing param or an illegal header is a 502 and never reaches the network`() {
        val missingParam = source(id = "p", servers = listOf(template() + "?k={\$param:k}"))
        val badHeader = source(id = "h", level = { it.copy(headers = listOf("X-Bad" to "a\nb")) })
        val client = client(missingParam, badHeader)
        tile(client, id = "p").use { assertEquals(502, it.code) }
        tile(client, id = "h").use { assertEquals(502, it.code) }
        assertEquals(0, server.requestCount)
        assertEquals(2, stats.snapshot().count(TileOutcome.REJECTED))
    }

    @Test
    fun `credentials are masked in the statistics`() {
        val secret =
            source(servers = listOf(template() + "?apikey={\$param:key}"), params = mapOf("key" to "TOPSECRET"))
        val client = client(secret)
        server.enqueue(pngResponse())
        tile(client).close()
        assertEquals("TOPSECRET", server.takeRequest(1, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("apikey"))
        val lastUrl = stats.snapshot().lastUrl.orEmpty()
        assertTrue("apikey=***" in lastUrl, lastUrl)
        assertTrue("TOPSECRET" !in stats.snapshot().report())
    }

    private val day = TimeUnit.DAYS.toMillis(1)
    private val oldPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9, 9)

    private fun storeOld(ageDays: Long) {
        store.put(TileKey("s", 3, 4, 5), StoredTile(oldPng, "image/png", storedAtMillis = 1_000 * day - ageDays * day))
    }

    @Test
    fun `an expired tile is downloaded again and replaced in the store`() {
        storeOld(ageDays = 20)
        server.enqueue(pngResponse())
        val c = client(source(level = { it.copy(updateDelay = "2W") }), clock = { 1_000 * day })
        tile(c).use { assertContentEquals(png, it.body!!.bytes()) }
        assertEquals(1, server.requestCount)
        assertContentEquals(png, store.get(TileKey("s", 3, 4, 5))!!.bytes)
    }

    @Test
    fun `a tile younger than the update delay is served without network`() {
        storeOld(ageDays = 5)
        val c = client(source(level = { it.copy(updateDelay = "2W") }), clock = { 1_000 * day })
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expired tile is still served when the server fails`() {
        storeOld(ageDays = 20)
        server.enqueue(MockResponse().setResponseCode(500))
        val c = client(source(level = { it.copy(updateDelay = "2W") }), clock = { 1_000 * day })
        tile(c).use {
            assertEquals(200, it.code)
            assertContentEquals(oldPng, it.body!!.bytes())
        }
        assertEquals(1, server.requestCount)
        assertContentEquals(oldPng, store.get(TileKey("s", 3, 4, 5))!!.bytes)
    }

    @Test
    fun `an expired tile is served as is in offline mode`() {
        storeOld(ageDays = 400)
        offline.set(true)
        val c = client(source(level = { it.copy(updateDelay = "1D") }), clock = { 1_000 * day })
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a map in forced refresh gets each stored tile fetched again once`() {
        storeOld(ageDays = 1)
        server.enqueue(pngResponse())
        val forced = ForcedRefresh().apply { set("s", true) }
        val c = client(source(), clock = { 1_000 * day }, forced = forced)
        tile(c).use { assertContentEquals(png, it.body!!.bytes()) }
        assertEquals(1, server.requestCount)
        // The same tile asked again in this session comes from the store.
        tile(c).use { assertContentEquals(png, it.body!!.bytes()) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a forced map still serves its stored tile when the server fails`() {
        storeOld(ageDays = 1)
        server.enqueue(MockResponse().setResponseCode(500))
        val forced = ForcedRefresh().apply { set("s", true) }
        val c = client(source(), clock = { 1_000 * day }, forced = forced)
        tile(c).use {
            assertEquals(200, it.code)
            assertContentEquals(oldPng, it.body!!.bytes())
        }
    }

    @Test
    fun `forced refresh does nothing for other maps, once turned off, or offline`() {
        storeOld(ageDays = 1)
        val forced = ForcedRefresh().apply { set("other", true) }
        val c = client(source(), clock = { 1_000 * day }, forced = forced)
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        forced.set("s", true)
        forced.set("s", false)
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        forced.set("s", true)
        offline.set(true)
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `turning forced refresh on again fetches the tiles once more`() {
        storeOld(ageDays = 1)
        server.enqueue(pngResponse())
        server.enqueue(pngResponse())
        val forced = ForcedRefresh().apply { set("s", true) }
        val c = client(source(), clock = { 1_000 * day }, forced = forced)
        tile(c).use { it.body!!.bytes() }
        forced.set("s", false)
        forced.set("s", true)
        tile(c).use { it.body!!.bytes() }
        assertEquals(2, server.requestCount)
        assertTrue(forced.isForced("s"))
        assertEquals(setOf("s"), forced.sourceIds.value)
    }

    @Test
    fun `without update delay stored tiles never expire`() {
        storeOld(ageDays = 900)
        val c = client(source(), clock = { 1_000 * day })
        tile(c).use { assertContentEquals(oldPng, it.body!!.bytes()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a local only map answers 404 for a missing tile even online and never reaches the network`() {
        val client = client(source(servers = listOf(LevelDef.LOCAL_ONLY_SERVER)))
        store.put(TileKey("s", 3, 4, 6), StoredTile(png, "image/png", StoredTile.UNKNOWN_DATE_MILLIS))

        tile(client, y = 5).use { assertEquals(404, it.code) }
        tile(client, y = 6).use { response ->
            assertEquals(200, response.code)
            assertContentEquals(png, response.body!!.bytes())
        }
        assertEquals(0, server.requestCount)
        assertEquals(1, stats.snapshot().count(TileOutcome.OFFLINE_MISS))
    }
}
