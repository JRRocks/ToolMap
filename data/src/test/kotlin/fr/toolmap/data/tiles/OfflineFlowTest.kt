package fr.toolmap.data.tiles

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The whole offline story with the real store: browse online, then go offline and see the same tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineFlowTest {
    private val server = MockWebServer()
    private lateinit var dir: File
    private lateinit var store: MbTilesStore
    private val offline = OfflineMode()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    private lateinit var source: TileSource
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.start()
        dir = createTempDirectory("offline").toFile()
        source =
            TileSource(
                id = "s",
                name = "S",
                levels =
                    listOf(
                        LevelDef(
                            ZoomSet.range(0, 18),
                            listOf("http://${server.hostName}:${server.port}/t/{\$z}/{\$x}/{\$y}.png"),
                        ),
                    ),
            )
        val lookup = StaticSourceLookup(listOf(source))
        store = MbTilesStore(dir, lookup)
        client =
            OkHttpClient
                .Builder()
                .addInterceptor(CoverageInterceptor(store))
                .addInterceptor(TileInterceptor(lookup, store, TileStats(), offline))
                .build()
    }

    @After
    fun tearDown() {
        store.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun tile(
        x: Int,
        y: Int,
        z: Int = 12,
    ) = client.newCall(Request.Builder().url("https://${TileHost.HOST}/s/$z/$x/$y").build()).execute()

    @Test
    fun `tiles seen online are shown again offline and nothing leaves the device`() {
        repeat(4) { server.enqueue(MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png")) }
        for (x in 100..101) for (y in 200..201) tile(x, y).use { assertEquals(200, it.code) }
        assertEquals(4, server.requestCount)

        offline.set(true)
        for (x in 100..101) {
            for (y in 200..201) {
                tile(x, y).use {
                    assertEquals(200, it.code)
                    assertContentEquals(png, it.body!!.bytes())
                }
            }
        }
        tile(102, 200).use { assertEquals(404, it.code) }
        assertEquals(4, server.requestCount, "no request may be made in offline mode")
    }

    @Test
    fun `tiles are still there after the store is closed and reopened`() {
        server.enqueue(MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png"))
        tile(5, 6).use { assertEquals(200, it.code) }
        store.close()
        store = MbTilesStore(dir, StaticSourceLookup(listOf(source)))
        assertNotNull(store.get(TileKey("s", 12, 5, 6)))
    }

    @Test
    fun `the coverage overlay draws what is stored`() {
        server.enqueue(MockResponse().setBody(Buffer().write(png)).addHeader("Content-Type", "image/png"))
        tile(100, 200).use { assertEquals(200, it.code) }

        fun coverage(
            z: Int,
            x: Int,
            y: Int,
        ) = client.newCall(Request.Builder().url("https://${CoverageHost.HOST}/s/$z/$x/$y").build()).execute()

        coverage(12, 100, 200).use {
            assertEquals(200, it.code)
            assertEquals("image/png", it.header("Content-Type"))
            assertEquals("image/png", MbTilesFile.contentTypeOf(it.body!!.bytes()))
        }
        coverage(11, 50, 100).use { assertEquals(200, it.code) } // parent: partial
        coverage(12, 101, 200).use { assertEquals(404, it.code) }
        coverage(12, 100, 200).use { assertEquals(200, it.code) }
        client.newCall(Request.Builder().url("https://${CoverageHost.HOST}/unknown/12/1/1").build()).execute().use {
            assertEquals(404, it.code)
        }
        client.newCall(Request.Builder().url("https://${CoverageHost.HOST}/s/12/99999/1").build()).execute().use {
            assertEquals(400, it.code)
        }
        assertEquals(1, server.requestCount)
    }
}
