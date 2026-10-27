package fr.toolmap.data.search

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.search.SearchSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeocoderClientTest {
    private val server = MockWebServer()
    private val requests = ArrayList<RecordedRequest>()
    private var ignPoi = IGN_POI
    private var ignAddress = IGN_ADDRESS
    private var nominatim = NOMINATIM
    private var nominatimCode = 200
    private var ignCode = 200

    @BeforeTest
    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path.startsWith("/geocodage") && request.requestUrl!!.queryParameter("index") == "poi" -> {
                            MockResponse().setResponseCode(ignCode).setBody(ignPoi)
                        }

                        path.startsWith("/geocodage") -> {
                            MockResponse().setResponseCode(ignCode).setBody(ignAddress)
                        }

                        else -> {
                            MockResponse().setResponseCode(nominatimCode).setBody(nominatim)
                        }
                    }
                }
            }
        server.start()
    }

    @AfterTest
    fun stop() = server.shutdown()

    private fun base(path: String) = "http://${server.hostName}:${server.port}$path"

    private fun client(
        clock: () -> Long = System::currentTimeMillis,
        pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        gap: Long = GeocoderClient.NOMINATIM_GAP_MILLIS,
    ) = GeocoderClient(
        userAgent = "ToolMap/test (+https://example.org)",
        ignUrl = base("/geocodage/search"),
        nominatimUrl = base("/nominatim/search"),
        minNominatimGapMillis = gap,
        clock = clock,
        pause = pause,
    )

    private fun requestTo(prefix: String) =
        synchronized(requests) {
            requests.filter { it.requestUrl!!.encodedPath.startsWith(prefix) }
        }

    @Test
    fun `each service gets the documented parameters and an identifying user agent`() {
        val outcome = runBlocking { client().search("Grand Veymont", near = LonLat(5.5, 44.8), languageTag = "fr") }
        assertEquals(3, requests.size, "places and addresses at the IGN, one call to Nominatim")
        val ign = requestTo("/geocodage").associateBy { it.requestUrl!!.queryParameter("index") }
        assertEquals(setOf("poi", "address"), ign.keys)
        for (request in ign.values) {
            assertEquals("Grand Veymont", request.requestUrl!!.queryParameter("q"))
            assertEquals("8", request.requestUrl!!.queryParameter("limit"))
            assertEquals("44.8", request.requestUrl!!.queryParameter("lat"))
            assertEquals("5.5", request.requestUrl!!.queryParameter("lon"))
        }
        val osm = requestTo("/nominatim").single()
        assertEquals("jsonv2", osm.requestUrl!!.queryParameter("format"))
        assertEquals("fr", osm.requestUrl!!.queryParameter("accept-language"))
        assertEquals("Grand Veymont", osm.requestUrl!!.queryParameter("q"))
        assertEquals("4.5,45.8,6.5,43.8", osm.requestUrl!!.queryParameter("viewbox"), "west,north,east,south")
        assertTrue(osm.requestUrl!!.queryParameter("bounded") == null, "a hint, not a restriction")
        for (request in requests) assertEquals("ToolMap/test (+https://example.org)", request.getHeader("User-Agent"))
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun `without a map centre no bias is sent`() {
        runBlocking { client().search("Chamonix") }
        for (request in requests) {
            val url = request.requestUrl!!
            assertTrue(url.queryParameter("lat") == null && url.queryParameter("viewbox") == null)
        }
    }

    @Test
    fun `places come first, then Nominatim, then addresses, and a place found twice is shown once`() {
        val outcome = runBlocking { client().search("Grand Veymont", near = null) }
        assertEquals(
            listOf(
                "le Grand Veymont" to SearchSource.IGN,
                "Parking du Grand Veymont" to SearchSource.IGN,
                "Allée du Grand Veymont" to SearchSource.IGN,
            ),
            outcome.results.map { it.title to it.source },
            "Nominatim's Grand Veymont is the IGN's one 20 m away: the first wins",
        )
    }

    @Test
    fun `a query that starts with a number is an address first`() {
        val outcome = runBlocking { client().search("12 rue du Lac") }
        assertEquals("Allée du Grand Veymont", outcome.results.first().title)
    }

    @Test
    fun `a service that fails is reported and the others still answer`() {
        nominatimCode = 429
        val outcome = runBlocking { client().search("Grand Veymont") }
        assertEquals(listOf(ProviderFailure(SearchSource.OSM, GeocodeFailure.HTTP, 429)), outcome.failures)
        assertTrue(outcome.results.isNotEmpty())
        assertTrue(outcome.results.all { it.source == SearchSource.IGN })

        nominatimCode = 200
        ignCode = 503
        val other = runBlocking { client().search("Grand Veymont") }
        assertEquals(
            listOf(ProviderFailure(SearchSource.IGN, GeocodeFailure.HTTP, 503)),
            other.failures,
            "two failing IGN calls make one failure",
        )
        assertEquals(listOf(SearchSource.OSM), other.results.map { it.source }.distinct())
    }

    @Test
    fun `an answer that is not JSON is unreadable, not a crash`() {
        ignPoi = "<html>502 Bad Gateway</html>"
        nominatim = "{\"not\":\"a list\"}"
        val outcome = runBlocking { client().search("Grand Veymont") }
        assertEquals(
            setOf(
                ProviderFailure(SearchSource.IGN, GeocodeFailure.UNREADABLE),
                ProviderFailure(SearchSource.OSM, GeocodeFailure.UNREADABLE),
            ),
            outcome.failures.toSet(),
        )
        assertEquals(
            listOf("Allée du Grand Veymont"),
            outcome.results.map {
                it.title
            },
            "the address index still answered",
        )
    }

    @Test
    fun `no network is a failure per service, never an exception`() {
        server.shutdown()
        val outcome = runBlocking { client().search("Grand Veymont") }
        assertTrue(outcome.results.isEmpty())
        assertEquals(
            setOf(
                ProviderFailure(SearchSource.IGN, GeocodeFailure.NETWORK),
                ProviderFailure(SearchSource.OSM, GeocodeFailure.NETWORK),
            ),
            outcome.failures.toSet(),
        )
    }

    @Test
    fun `a blank query asks nobody`() {
        val outcome = runBlocking { client().search("   ") }
        assertTrue(outcome.results.isEmpty() && outcome.failures.isEmpty())
        assertEquals(0, requests.size)
    }

    @Test
    fun `Nominatim is never called twice within the gap but the IGN is not slowed down`() {
        var now = 10_000L
        val pauses = ArrayList<Long>()
        val geocoder =
            client(clock = { now }, pause = {
                pauses += it
                now += it
            }, gap = 1_100L)
        runBlocking {
            geocoder.search("un")
            now += 300
            geocoder.search("deux")
            now += 5_000
            geocoder.search("trois")
        }
        assertEquals(listOf(800L), pauses, "the second search waits for the rest of the gap, the third does not wait")
        assertEquals(3, requestTo("/nominatim").size)
        assertEquals(6, requestTo("/geocodage").size)
    }

    @Test
    fun `cancelling the search cancels the calls`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { client().search("lent") }
            kotlinx.coroutines.delay(300)
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    private companion object {
        const val IGN_POI =
            """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[5.526604,44.869704]},"properties":{"name":["le Grand Veymont"],"toponym":"le Grand Veymont","category":["sommet"],"postcode":["38650"],"city":["Gresse-en-Vercors"],"_type":"poi"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[5.54946,44.893117]},"properties":{"name":["Parking du Grand Veymont"],"toponym":"Parking du Grand Veymont","category":["parking"],"postcode":["38650"],"city":["Gresse-en-Vercors"],"_type":"poi"}}
            ]}"""
        const val IGN_ADDRESS =
            """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[0.198638,47.961643]},"properties":{"name":"Allée du Grand Veymont","postcode":"72100","city":"Le Mans","_type":"address"}}
            ]}"""
        const val NOMINATIM =
            """[{"lat":"44.8697393","lon":"5.5267039","type":"peak","name":"Grand Veymont","display_name":"Grand Veymont, Gresse-en-Vercors, Isère, France","boundingbox":["44.8696893","44.8697893","5.5266539","5.5267539"]}]"""
    }
}
