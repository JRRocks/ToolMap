package fr.toolmap.data.wizard

import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.wizard.PreviewTile
import fr.toolmap.core.wizard.SourceDraft
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileProbeTest {
    private val server = MockWebServer()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val tile = PreviewTile(12, 2074, 1409)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    private fun url(query: String = "") = "http://${server.hostName}:${server.port}/tiles/{\$z}/{\$x}/{\$y}.png$query"

    private fun source(
        query: String = "",
        params: Map<String, String> = emptyMap(),
        level: (LevelDef) -> LevelDef = { it },
    ) = TileSource(
        id = "probe",
        name = "Probe",
        params = params,
        levels = listOf(level(LevelDef(ZoomSet.range(0, 18), listOf(url(query))))),
    )

    private fun probe(source: TileSource) = runBlocking { TileProbe().probe(source, tile) }

    private fun answer(response: MockResponse) = server.enqueue(response)

    private fun image(contentType: String = "image/png") =
        MockResponse().setHeader("Content-Type", contentType).setBody(Buffer().write(png))

    @Test
    fun `an image is a success and comes back with its details`() {
        answer(image())
        val result = probe(source())
        assertEquals(ProbeVerdict.OK, result.verdict)
        assertTrue(result.isOk)
        assertEquals(200, result.httpCode)
        assertEquals("image/png", result.contentType)
        assertContentEquals(png, result.image)
        assertEquals(png.size, result.sizeBytes)
        assertEquals(tile, result.tile)
        assertNotNull(result.url)
        assertEquals("/tiles/12/2074/1409.png", server.takeRequest().path)
    }

    @Test
    fun `the configured referer, user agent, headers and cookie are sent`() {
        answer(image())
        val result =
            probe(
                source {
                    it.copy(
                        referer = "https://data.geopf.fr/",
                        userAgent = "Probe-UA",
                        headers = listOf("X-Test" to "1"),
                        cookie = "session=abc",
                    )
                },
            )
        assertTrue(result.isOk)
        val request = server.takeRequest()
        assertEquals("https://data.geopf.fr/", request.getHeader("Referer"))
        assertEquals("Probe-UA", request.getHeader("User-Agent"))
        assertEquals("1", request.getHeader("X-Test"))
        assertEquals("session=abc", request.getHeader("Cookie"))
    }

    @Test
    fun `a missing referer shows as key or referer problem, then the referer fixes it`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.getHeader("Referer") == "https://allowed.example/") {
                        image()
                    } else {
                        MockResponse().setResponseCode(
                            403,
                        ).setBody("<html><body><h1>Forbidden</h1> bad referer</body></html>")
                    }
            }
        val denied = probe(source())
        assertEquals(ProbeVerdict.KEY_OR_REFERER, denied.verdict)
        assertEquals(403, denied.httpCode)
        assertEquals("Forbidden bad referer", denied.detail)
        assertNull(denied.image)
        assertEquals(ProbeVerdict.OK, probe(source { it.copy(referer = "https://allowed.example/") }).verdict)
    }

    @Test
    fun `the key never appears in the result`() {
        answer(MockResponse().setResponseCode(401).setBody("Invalid key sekret1234 for this service"))
        val result = probe(source("?apikey={\$param:apikey}", mapOf("apikey" to "sekret1234")))
        assertEquals(ProbeVerdict.KEY_OR_REFERER, result.verdict)
        assertEquals("Invalid key *** for this service", result.detail)
        assertFalse(result.url.orEmpty().contains("sekret1234"), result.url)
        assertTrue(result.url.orEmpty().contains("apikey=***"), result.url)
        // The real request did carry the key.
        assertEquals("/tiles/12/2074/1409.png?apikey=sekret1234", server.takeRequest().path)
    }

    @Test
    fun `http status codes are explained`() {
        val cases =
            mapOf(
                400 to ProbeVerdict.BAD_REQUEST,
                404 to ProbeVerdict.NOT_FOUND,
                410 to ProbeVerdict.BAD_REQUEST,
                429 to ProbeVerdict.RATE_LIMITED,
                500 to ProbeVerdict.SERVER_ERROR,
                503 to ProbeVerdict.SERVER_ERROR,
            )
        for ((code, verdict) in cases) {
            answer(MockResponse().setResponseCode(code).setBody("nope"))
            val result = probe(source())
            assertEquals(verdict, result.verdict, "HTTP $code")
            assertEquals(code, result.httpCode)
            assertEquals("nope", result.detail)
        }
    }

    @Test
    fun `a page that is not an image is reported as such`() {
        answer(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Login required</html>"))
        val html = probe(source())
        assertEquals(ProbeVerdict.NOT_AN_IMAGE, html.verdict)
        assertTrue(html.detail.orEmpty().contains("text/html"), html.detail)

        // Wrong content type but real image bytes: accepted, the bytes decide.
        answer(image("application/octet-stream"))
        assertEquals(ProbeVerdict.OK, probe(source()).verdict)

        // Image content type on HTML bytes: refused by the bytes check.
        answer(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("<html>no</html>"))
        assertEquals(ProbeVerdict.NOT_AN_IMAGE, probe(source()).verdict)
    }

    @Test
    fun `an empty answer is reported as empty`() {
        answer(MockResponse().setHeader("Content-Type", "image/png"))
        assertEquals(ProbeVerdict.EMPTY, probe(source()).verdict)
    }

    @Test
    fun `an unreachable server is a network problem`() {
        val dead = source()
        server.shutdown()
        val result = probe(dead)
        assertEquals(ProbeVerdict.NETWORK, result.verdict)
        assertNull(result.httpCode)
        assertTrue(result.detail.orEmpty().isNotEmpty())
    }

    @Test
    fun `a refused certificate is told apart from other network problems`() {
        // What Android says for a server signed by an authority it does not know (S2, ADR-066).
        val refusing =
            OkHttpClient
                .Builder()
                .addNetworkInterceptor {
                    throw SSLHandshakeException(
                        "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
                    )
                }.build()
        answer(image())
        val result = runBlocking { TileProbe(refusing).probe(source(), tile) }
        assertEquals(ProbeVerdict.CERTIFICATE_REJECTED, result.verdict)
        assertNull(result.image)
    }

    @Test
    fun `only certificate failures count as a refused certificate`() {
        assertTrue(TileProbe.isCertificateError("SSLPeerUnverifiedException: Hostname tiles.example not verified"))
        assertTrue(
            TileProbe.isCertificateError(
                "SSLHandshakeException: java.security.cert.CertificateExpiredException: NotAfter: Mon Oct 19 2026",
            ),
        )
        assertTrue(TileProbe.isCertificateError("SSLHandshakeException: Chain validation failed (bad certificate)"))
        assertFalse(TileProbe.isCertificateError("SSLHandshakeException: Handshake failed"))
        assertFalse(TileProbe.isCertificateError("UnknownHostException: Unable to resolve host \"tiles.example\""))
        assertFalse(TileProbe.isCertificateError("SocketTimeoutException: timeout"))
    }

    @Test
    fun `a broken template is a template error and nothing is requested`() {
        val result = probe(source("?k={\$param:missing}"))
        assertEquals(ProbeVerdict.TEMPLATE_ERROR, result.verdict)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a zoom the source does not serve is out of range`() {
        val narrow = source { it.copy(zooms = ZoomSet.range(0, 5)) }
        val result = runBlocking { TileProbe().probe(narrow, tile) }
        assertEquals(ProbeVerdict.OUT_OF_RANGE, result.verdict)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a draft turned into a source can be probed end to end`() {
        answer(image())
        val draft = SourceDraft(name = "Mine", urls = listOf(url()), zoomSpec = "0-18")
        assertTrue(draft.isValid)
        assertEquals(ProbeVerdict.OK, probe(draft.toSource()).verdict)
    }
}
