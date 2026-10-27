package fr.toolmap.data.search

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.search.GeocodeParsers
import fr.toolmap.core.search.SearchMerge
import fr.toolmap.core.search.SearchResult
import fr.toolmap.core.search.SearchSource
import fr.toolmap.data.tiles.TileInterceptor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class GeocodeFailure {
    /** No answer: offline, DNS, timeout. */
    NETWORK,

    /** The service answered with an error status (`429` for Nominatim means "too many requests"). */
    HTTP,

    /** The answer was not what the service documents. */
    UNREADABLE,
}

data class ProviderFailure(
    val source: SearchSource,
    val reason: GeocodeFailure,
    val httpCode: Int? = null,
)

/** What came back: the places found, and the services that could not answer (the others still count). */
data class GeocodeOutcome(
    val results: List<SearchResult>,
    val failures: List<ProviderFailure>,
)

/** Looks places up by name; [GeocoderClient] is the real one, the screens' tests use a fake. */
interface Geocoder {
    /** Never throws except when the calling coroutine is cancelled. [near] (the map centre) favours close places. */
    suspend fun search(
        query: String,
        near: LonLat? = null,
        languageTag: String = "fr",
        limit: Int = GeocoderClient.DEFAULT_LIMIT,
    ): GeocodeOutcome
}

/**
 * Looks a place up by name in the IGN Géoplateforme geocoder (France: toponyms and addresses) and in OpenStreetMap
 * Nominatim (worldwide), at once and independently: one service being down or slow does not hide the other's answer.
 *
 * Nominatim's usage policy is followed to the letter: an identifying `User-Agent`, never more than one request a second
 * (calls are spaced by [minNominatimGapMillis]), no search while typing (the screen asks on submit only). The caller
 * must not call this in offline mode.
 */
class GeocoderClient(
    client: OkHttpClient = OkHttpClient(),
    private val userAgent: String = TileInterceptor.DEFAULT_USER_AGENT,
    private val ignUrl: String = IGN_URL,
    private val nominatimUrl: String = NOMINATIM_URL,
    private val minNominatimGapMillis: Long = NOMINATIM_GAP_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : Geocoder {
    private val client =
        client
            .newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private val nominatimTurn = Mutex()
    private var lastNominatimCall = Long.MIN_VALUE

    override suspend fun search(
        query: String,
        near: LonLat?,
        languageTag: String,
        limit: Int,
    ): GeocodeOutcome {
        val text = query.trim().take(MAX_QUERY_LENGTH)
        if (text.isEmpty()) return GeocodeOutcome(emptyList(), emptyList())
        return coroutineScope {
            val poi = async { ign(text, "poi", near, limit) }
            val address = async { ign(text, "address", near, limit) }
            val osm = async { nominatim(text, near, languageTag, limit) }
            val poiAnswer = poi.await()
            val addressAnswer = address.await()
            val osmAnswer = osm.await()
            // "12 rue ..." is an address; anything else is more likely a place, and places come first.
            val addressFirst = text.first().isDigit()
            val groups =
                if (addressFirst) {
                    listOf(addressAnswer, poiAnswer, osmAnswer)
                } else {
                    listOf(poiAnswer, osmAnswer, addressAnswer)
                }
            val failures = groups.mapNotNull { it.failure }.distinct()
            GeocodeOutcome(SearchMerge.merge(groups.map { it.results }, limit), failures)
        }
    }

    private class Answer(
        val results: List<SearchResult>,
        val failure: ProviderFailure? = null,
    )

    private suspend fun ign(
        query: String,
        index: String,
        near: LonLat?,
        limit: Int,
    ): Answer {
        val url =
            ignUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("index", index)
                addQueryParameter("limit", limit.toString())
                if (near != null) {
                    addQueryParameter("lat", near.lat.toString())
                    addQueryParameter("lon", near.lon.toString())
                }
            }.build()
        return fetch(url, SearchSource.IGN, GeocodeParsers::parseIgn)
    }

    private suspend fun nominatim(
        query: String,
        near: LonLat?,
        languageTag: String,
        limit: Int,
    ): Answer {
        val url =
            nominatimUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("format", "jsonv2")
                addQueryParameter("limit", limit.toString())
                addQueryParameter("accept-language", languageTag)
                if (near != null) {
                    // A hint, not a restriction (`bounded` stays off): places inside the box rank first.
                    val box =
                        "${near.lon - VIEWBOX_DEGREES},${near.lat + VIEWBOX_DEGREES}," +
                            "${near.lon + VIEWBOX_DEGREES},${near.lat - VIEWBOX_DEGREES}"
                    addQueryParameter("viewbox", box)
                }
            }.build()
        // One call a second at most, whatever the number of searches in flight.
        nominatimTurn.withLock {
            val wait = lastNominatimCall + minNominatimGapMillis - clock()
            if (lastNominatimCall != Long.MIN_VALUE && wait > 0) pause(wait)
            lastNominatimCall = clock()
        }
        return fetch(url, SearchSource.OSM, GeocodeParsers::parseNominatim)
    }

    private suspend fun fetch(
        url: HttpUrl,
        source: SearchSource,
        parse: (String) -> List<SearchResult>?,
    ): Answer {
        val request =
            Request.Builder().url(url).get()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return Answer(emptyList(), ProviderFailure(source, GeocodeFailure.HTTP, response.code))
                }
                val body = response.peekBody(MAX_BYTES).string()
                val results = parse(body)
                if (results == null) {
                    Answer(emptyList(), ProviderFailure(source, GeocodeFailure.UNREADABLE))
                } else {
                    Answer(results.map { it.copy(source = source) })
                }
            }
        } catch (e: IOException) {
            Answer(emptyList(), ProviderFailure(source, GeocodeFailure.NETWORK))
        }
    }

    private suspend fun Call.await(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response)
                    }
                },
            )
        }

    companion object {
        const val IGN_URL = "https://data.geopf.fr/geocodage/search"
        const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"

        /** Nominatim allows an average of one request a second; a little over, for a clock that is not exact. */
        const val NOMINATIM_GAP_MILLIS = 1_100L
        const val DEFAULT_LIMIT = 8
        private const val MAX_QUERY_LENGTH = 200
        private const val TIMEOUT_SECONDS = 10L
        private const val CALL_TIMEOUT_SECONDS = 20L
        private const val MAX_BYTES = 1_000_000L
        private const val VIEWBOX_DEGREES = 1.0
    }
}
