package fr.toolmap.data.wizard

import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.wizard.ImageSniffer
import fr.toolmap.core.wizard.PreviewTile
import fr.toolmap.data.tiles.InMemoryTileStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.StaticSourceLookup
import fr.toolmap.data.tiles.TileHost
import fr.toolmap.data.tiles.TileInterceptor
import fr.toolmap.data.tiles.TileOutcome
import fr.toolmap.data.tiles.TileStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the test tile showed, in terms the wizard can explain to the user. */
enum class ProbeVerdict {
    /** A real image came back. */
    OK,

    /** 401 / 403: a missing or wrong key, Referer, cookie or header. */
    KEY_OR_REFERER,

    /** 404 from the server: no tile at this place (or a wrong layer / path). */
    NOT_FOUND,

    /** 400 and other client errors: the URL is probably wrong. */
    BAD_REQUEST,

    /** 429: too many requests. */
    RATE_LIMITED,

    /** 5xx from the server. */
    SERVER_ERROR,

    /** No answer: DNS, timeout, TLS... */
    NETWORK,

    /** `http://` refused by Android's clear-text policy. */
    CLEARTEXT_BLOCKED,

    /** HTTPS refused because Android does not trust the server's certificate (unknown authority, expired, wrong name). */
    CERTIFICATE_REJECTED,

    /** The server answered 200 with something that is not an image (an HTML error page, XML...). */
    NOT_AN_IMAGE,

    /** 200 with an empty body, or the server's "no tile" answer. */
    EMPTY,

    /** The URL template cannot be expanded (unknown placeholder, missing parameter) or is not a valid URL. */
    TEMPLATE_ERROR,

    /** The source has no tile at the zoom that was asked. */
    OUT_OF_RANGE,
}

class ProbeResult(
    val verdict: ProbeVerdict,
    val tile: PreviewTile,
    /** Status of the real server's answer; `null` when it never answered (network failure, template error). */
    val httpCode: Int?,
    val contentType: String?,
    /** The image, when [verdict] is [ProbeVerdict.OK]. */
    val image: ByteArray?,
    val sizeBytes: Int,
    val latencyMillis: Long,
    /** The address that was requested, secrets hidden. */
    val url: String?,
    /** Start of the server's own message, or the network error, with secrets hidden. */
    val detail: String?,
) {
    val isOk: Boolean get() = verdict == ProbeVerdict.OK
}

/**
 * Fetches one tile of a source the way the map would (same interceptor, so the same Referer, headers, URL building,
 * zoom rules) but with its own empty store and counters, and says what happened. Nothing is stored.
 */
class TileProbe(
    client: OkHttpClient = OkHttpClient(),
    private val defaultUserAgent: String = TileInterceptor.DEFAULT_USER_AGENT,
) {
    private val client =
        client
            .newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    suspend fun probe(
        source: TileSource,
        tile: PreviewTile,
    ): ProbeResult =
        withContext(Dispatchers.IO) {
            val stats = TileStats()
            val interceptor =
                TileInterceptor(
                    StaticSourceLookup(listOf(source)),
                    InMemoryTileStore(),
                    stats,
                    OfflineMode(false),
                    defaultUserAgent,
                    // The probe asks the server for this very tile: never drawn from a neighbouring zoom.
                    scaling = { ScalingPolicy.OFF },
                )
            val probeClient = client.newBuilder().addInterceptor(interceptor).build()
            val request = Request.Builder().url(tileUrl(source.id, tile)).get().build()
            val call = probeClient.newCall(request)
            currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
            val started = System.nanoTime()
            try {
                call.execute().use { response ->
                    val bytes = runCatching { response.body?.bytes() }.getOrNull() ?: ByteArray(0)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    interpret(source, tile, response.code, response.header("Content-Type"), bytes, elapsed, stats)
                }
            } catch (e: IOException) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                val message = "${e.javaClass.simpleName}: ${e.message}"
                ProbeResult(
                    if (isCertificateError(message)) ProbeVerdict.CERTIFICATE_REJECTED else ProbeVerdict.NETWORK,
                    tile,
                    null,
                    null,
                    null,
                    0,
                    elapsed,
                    stats.snapshot().lastUrl,
                    hide(source, message),
                )
            }
        }

    private fun interpret(
        source: TileSource,
        tile: PreviewTile,
        code: Int,
        contentType: String?,
        bytes: ByteArray,
        elapsed: Long,
        stats: TileStats,
    ): ProbeResult {
        val snapshot = stats.snapshot()

        fun result(
            verdict: ProbeVerdict,
            detail: String? = null,
            image: ByteArray? = null,
        ) = ProbeResult(
            verdict,
            tile,
            snapshot.lastHttpCode,
            contentType,
            image,
            bytes.size,
            elapsed,
            snapshot.lastUrl,
            hide(source, detail),
        )

        val error = snapshot.lastError.orEmpty()
        return when {
            code == HTTP_OK && ImageSniffer.formatOf(bytes) != null -> {
                result(ProbeVerdict.OK, image = bytes)
            }

            snapshot.count(TileOutcome.OUT_OF_RANGE) > 0 -> {
                result(ProbeVerdict.OUT_OF_RANGE)
            }

            snapshot.count(TileOutcome.NETWORK_ERROR) > 0 && error.contains("CLEARTEXT", ignoreCase = true) -> {
                result(ProbeVerdict.CLEARTEXT_BLOCKED, error)
            }

            snapshot.count(TileOutcome.NETWORK_ERROR) > 0 && isCertificateError(error) -> {
                result(ProbeVerdict.CERTIFICATE_REJECTED, error)
            }

            snapshot.count(TileOutcome.NETWORK_ERROR) > 0 -> {
                result(ProbeVerdict.NETWORK, error)
            }

            snapshot.count(TileOutcome.REJECTED) > 0 && error.startsWith("not an image") -> {
                result(ProbeVerdict.NOT_AN_IMAGE, error)
            }

            snapshot.count(TileOutcome.REJECTED) > 0 -> {
                result(ProbeVerdict.TEMPLATE_ERROR, error)
            }

            snapshot.count(TileOutcome.HTTP_ERROR) > 0 && error == "empty tile" -> {
                result(ProbeVerdict.EMPTY)
            }

            code == HTTP_OK -> {
                result(ProbeVerdict.NOT_AN_IMAGE, "unrecognised image data")
            }

            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> {
                result(ProbeVerdict.KEY_OR_REFERER, excerpt(bytes))
            }

            code == HTTP_NOT_FOUND -> {
                result(ProbeVerdict.NOT_FOUND, excerpt(bytes))
            }

            code == HTTP_TOO_MANY -> {
                result(ProbeVerdict.RATE_LIMITED, excerpt(bytes))
            }

            code >= HTTP_SERVER_ERROR -> {
                result(ProbeVerdict.SERVER_ERROR, excerpt(bytes))
            }

            else -> {
                result(ProbeVerdict.BAD_REQUEST, excerpt(bytes))
            }
        }
    }

    /** First readable characters of a server's error page, markup removed. */
    private fun excerpt(bytes: ByteArray): String? =
        String(bytes, 0, minOf(bytes.size, EXCERPT_BYTES), Charsets.UTF_8)
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(EXCERPT_CHARS)
            .ifEmpty { null }

    /** The key a user typed must not come back in a message they might copy to a forum. */
    private fun hide(
        source: TileSource,
        text: String?,
    ): String? {
        var out = text ?: return null
        for (secret in source.params.values) if (secret.length >= MIN_SECRET) out = out.replace(secret, "***")
        return out
    }

    private fun tileUrl(
        sourceId: String,
        tile: PreviewTile,
    ): HttpUrl =
        HttpUrl
            .Builder()
            .scheme("https")
            .host(TileHost.HOST)
            .addPathSegment(sourceId)
            .addPathSegment(tile.z.toString())
            .addPathSegment(tile.x.toString())
            .addPathSegment(tile.y.toString())
            .build()

    internal companion object {
        /**
         * Whether a network error (`ClassName: message`, as the interceptor records it) is a refused certificate:
         * unknown authority, expired or not yet valid, or issued for another name. Other TLS failures stay
         * [ProbeVerdict.NETWORK].
         */
        fun isCertificateError(error: String): Boolean =
            CERTIFICATE_MARKERS.any { error.contains(it, ignoreCase = true) } ||
                (error.contains("SSLHandshakeException") && error.contains("cert", ignoreCase = true))

        private val CERTIFICATE_MARKERS =
            listOf(
                "CertPathValidatorException",
                "Trust anchor for certification path",
                "SSLPeerUnverifiedException",
                "CertificateExpiredException",
                "CertificateNotYetValidException",
            )

        const val TIMEOUT_SECONDS = 20L
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
        const val EXCERPT_BYTES = 2048
        const val EXCERPT_CHARS = 160
        const val MIN_SECRET = 4
    }
}
