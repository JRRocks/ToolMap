package fr.toolmap.data.wizard

import fr.toolmap.core.wizard.CapabilitiesDocument
import fr.toolmap.core.wizard.OgcService
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilitiesFetcherTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun address(path: String = "/wmts") = "http://${server.hostName}:${server.port}$path"

    private fun fixture(): ByteArray {
        val dir = requireNotNull(System.getProperty("fixtures.dir"))
        return File(dir, "wmts/ign-capabilities-excerpt.xml").readBytes()
    }

    private fun fetch(
        input: String,
        service: OgcService = OgcService.WMTS,
        settings: RequestSettings = RequestSettings(),
        fetcher: CapabilitiesFetcher = CapabilitiesFetcher(),
    ) = runBlocking { fetcher.fetch(input, service, settings) }

    private fun failure(
        input: String = address(),
        fetcher: CapabilitiesFetcher = CapabilitiesFetcher(),
    ) = assertFailsWith<CapabilitiesFetchException> { fetch(input, fetcher = fetcher) }

    @Test
    fun `the service address is completed and the layers are listed`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/xml").setBody(Buffer().write(fixture())))
        val fetched = fetch(address() + "?apikey=k")
        val request = server.takeRequest()
        assertEquals("/wmts?apikey=k&SERVICE=WMTS&REQUEST=GetCapabilities", request.path)
        assertEquals(address() + "?apikey=k&SERVICE=WMTS&REQUEST=GetCapabilities", fetched.url)
        val wmts = assertIs<CapabilitiesDocument.Wmts>(fetched.document).capabilities
        assertEquals(4, wmts.layers.size)
        assertEquals(3, wmts.usableLayers.size)
    }

    @Test
    fun `referer, user agent and headers go with the request`() {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        fetch(address(), settings = RequestSettings("https://ref.example/", "Caps-UA", listOf("X-A" to "1"), "c=1"))
        val request = server.takeRequest()
        assertEquals("https://ref.example/", request.getHeader("Referer"))
        assertEquals("Caps-UA", request.getHeader("User-Agent"))
        assertEquals("1", request.getHeader("X-A"))
        assertEquals("c=1", request.getHeader("Cookie"))
    }

    @Test
    fun `an http error carries its code and the start of the page`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden: bad key"))
        val e = failure()
        assertEquals(FetchFailure.HTTP, e.failure)
        assertEquals(403, e.httpCode)
        assertTrue(e.message!!.contains("bad key"), e.message)
    }

    @Test
    fun `an html page is not capabilities`() {
        server.enqueue(MockResponse().setBody("<html><body>Welcome</body></html>"))
        assertEquals(FetchFailure.NOT_CAPABILITIES, failure().failure)
    }

    @Test
    fun `the server's own error report is passed on`() {
        server.enqueue(
            MockResponse().setBody(
                """<ExceptionReport xmlns="http://www.opengis.net/ows/1.1"><Exception><ExceptionText>Key expired</ExceptionText></Exception></ExceptionReport>""",
            ),
        )
        val e = failure()
        assertEquals(FetchFailure.NOT_CAPABILITIES, e.failure)
        assertEquals("Key expired", e.message)
    }

    @Test
    fun `a huge answer is refused`() {
        server.enqueue(MockResponse().setBody(Buffer().write(fixture())))
        assertEquals(FetchFailure.TOO_LARGE, failure(fetcher = CapabilitiesFetcher(maxBytes = 1000)).failure)
    }

    @Test
    fun `bad addresses are refused before any request`() {
        for (input in listOf("", "ftp://x", "https://x/wmts?REQUEST=GetTile", "https://x/{\$z}/{\$x}/{\$y}")) {
            assertEquals(FetchFailure.BAD_URL, failure(input).failure, input)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an unreachable server is a network failure`() {
        val dead = address()
        server.shutdown()
        assertEquals(FetchFailure.NETWORK, failure(dead).failure)
    }
}
