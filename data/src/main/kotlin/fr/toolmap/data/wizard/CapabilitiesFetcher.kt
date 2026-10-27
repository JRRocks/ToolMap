package fr.toolmap.data.wizard

import fr.toolmap.core.wizard.CapabilitiesDocument
import fr.toolmap.core.wizard.CapabilitiesException
import fr.toolmap.core.wizard.CapabilitiesReader
import fr.toolmap.core.wizard.CapabilitiesUrl
import fr.toolmap.core.wizard.CapabilitiesUrlException
import fr.toolmap.core.wizard.OgcService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Why the capabilities could not be read. */
enum class FetchFailure {
    /** The typed address is empty, not http(s), or already a tile request. */
    BAD_URL,

    /** The server cannot be reached (DNS, timeout, TLS, clear-text blocked...). */
    NETWORK,

    /** The server answered with an HTTP error. */
    HTTP,

    /** The answer is bigger than the fetcher's limit ([CapabilitiesFetcher.MAX_BYTES] by default). */
    TOO_LARGE,

    /** The answer is not a WMTS or WMS capabilities document (or is the server's own error report). */
    NOT_CAPABILITIES,
}

class CapabilitiesFetchException(
    val failure: FetchFailure,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What was read, and the address it came from (the fallback base of the URL the wizard builds). */
data class FetchedCapabilities(
    val url: String,
    val document: CapabilitiesDocument,
)

/** Downloads and reads a WMTS / WMS capabilities document for the add-a-map wizard. */
class CapabilitiesFetcher(
    client: OkHttpClient = OkHttpClient(),
    private val defaultUserAgent: String = DEFAULT_USER_AGENT,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val client =
        client
            .newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    /** @throws CapabilitiesFetchException on every failure (never a raw network exception). */
    suspend fun fetch(
        input: String,
        service: OgcService,
        settings: RequestSettings = RequestSettings(),
    ): FetchedCapabilities =
        withContext(Dispatchers.IO) {
            val url =
                try {
                    CapabilitiesUrl.normalize(input, service)
                } catch (e: CapabilitiesUrlException) {
                    throw CapabilitiesFetchException(FetchFailure.BAD_URL, e.problem.name, cause = e)
                }
            val httpUrl =
                url.toHttpUrlOrNull()
                    ?: throw CapabilitiesFetchException(FetchFailure.BAD_URL, "NOT_HTTP")
            val request =
                settings.apply(
                    Request.Builder().url(httpUrl).get().header("Accept", "text/xml,application/xml,*/*"),
                    defaultUserAgent,
                )
                    ?.build()
                    ?: throw CapabilitiesFetchException(FetchFailure.BAD_URL, "invalid header")
            val call = client.newCall(request)
            currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
            val bytes = download(call)
            currentCoroutineContext().ensureActive()
            val document =
                try {
                    CapabilitiesReader.read(bytes)
                } catch (e: CapabilitiesException) {
                    throw CapabilitiesFetchException(
                        FetchFailure.NOT_CAPABILITIES,
                        e.message ?: "unreadable",
                        cause = e,
                    )
                }
            FetchedCapabilities(url, document)
        }

    private fun download(call: okhttp3.Call): ByteArray =
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) {
                    val excerpt = runCatching { r.peekBody(ERROR_EXCERPT).string() }.getOrDefault("")
                    throw CapabilitiesFetchException(
                        FetchFailure.HTTP,
                        "HTTP ${r.code} ${r.message} $excerpt".trim(),
                        r.code,
                    )
                }
                val body = r.body ?: throw CapabilitiesFetchException(FetchFailure.NOT_CAPABILITIES, "empty answer")
                if (body.contentLength() > maxBytes) throw tooLarge()
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(CHUNK)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                        if (buffer.size() > maxBytes) throw tooLarge()
                    }
                }
                buffer.toByteArray()
            }
        } catch (e: IOException) {
            throw CapabilitiesFetchException(FetchFailure.NETWORK, "${e.javaClass.simpleName}: ${e.message}", cause = e)
        }

    private fun tooLarge() = CapabilitiesFetchException(FetchFailure.TOO_LARGE, "more than $maxBytes bytes")

    companion object {
        const val MAX_BYTES = 25 * 1024 * 1024
        const val DEFAULT_USER_AGENT = "ToolMap/0.1 (+https://github.com/JRRocks/ToolMap)"
        private const val TIMEOUT_SECONDS = 30L
        private const val ERROR_EXCERPT = 300L
        private const val CHUNK = 16 * 1024
    }
}
