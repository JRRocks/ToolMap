package fr.toolmap.data.tiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How one tile request ended. */
enum class TileOutcome {
    /** Served from the local [TileStore]. */
    STORE_HIT,

    /** Downloaded from the real server (and stored). */
    NETWORK_OK,

    /** The real server answered with an error status (403, 404...). */
    HTTP_ERROR,

    /** No answer from the real server (DNS, timeout, reset...). */
    NETWORK_ERROR,

    /** Not in the store while offline mode is on. */
    OFFLINE_MISS,

    /** Zoom outside every level of the source. */
    OUT_OF_RANGE,

    /** Unusable request or response (unknown source, bad URL template, not an image...). */
    REJECTED,

    /** Drawn from tiles of another zoom or of another map (forced zoom, missing zoom; ADR-031), never stored. */
    COMPOSED,

    /** Cut out along an elevation band, or left out because the band misses the whole tile (P20), never stored. */
    MASKED,
}

data class TileStatsSnapshot(
    val counts: Map<TileOutcome, Int> = emptyMap(),
    /** Number of requests per requested tile zoom (the `z` of the URL). */
    val perZoom: Map<Int, Int> = emptyMap(),
    val lastZoom: Int? = null,
    val lastHttpCode: Int? = null,
    val lastError: String? = null,
    /** Last real URL requested, with secrets redacted. */
    val lastUrl: String? = null,
) {
    fun count(outcome: TileOutcome): Int = counts[outcome] ?: 0

    val total: Int get() = counts.values.sum()

    /** Plain-text report meant to be copied from the diagnostic screen and pasted in a bug report. */
    fun report(): String =
        buildString {
            appendLine("requests=$total")
            TileOutcome.entries.forEach { appendLine("${it.name.lowercase()}=${count(it)}") }
            appendLine("last_zoom=${lastZoom ?: "-"}")
            appendLine("zoom_histogram=${perZoom.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}")
            appendLine("last_http_code=${lastHttpCode ?: "-"}")
            appendLine("last_error=${lastError ?: "-"}")
            append("last_url=${lastUrl ?: "-"}")
        }
}

/** Live counters of what [TileInterceptor] did; observed by the diagnostic screen. Thread-safe. */
class TileStats {
    private val flow = MutableStateFlow(TileStatsSnapshot())
    val state: StateFlow<TileStatsSnapshot> = flow.asStateFlow()

    fun snapshot(): TileStatsSnapshot = flow.value

    fun reset() {
        flow.value = TileStatsSnapshot()
    }

    fun record(
        outcome: TileOutcome,
        z: Int? = null,
        url: String? = null,
        httpCode: Int? = null,
        error: String? = null,
    ) {
        flow.update { old ->
            old.copy(
                counts = old.counts + (outcome to old.count(outcome) + 1),
                perZoom = if (z == null) old.perZoom else old.perZoom + (z to (old.perZoom[z] ?: 0) + 1),
                lastZoom = z ?: old.lastZoom,
                lastHttpCode = httpCode ?: old.lastHttpCode,
                lastError = error ?: old.lastError,
                lastUrl = url?.let(::redactUrl) ?: old.lastUrl,
            )
        }
    }
}

private val SENSITIVE_QUERY_PARAM =
    Regex("""([?&;][^=&#]*(?:key|token|secret|password|pwd|auth)[^=&#]*)=[^&#]*""", RegexOption.IGNORE_CASE)

/** Hides the value of query parameters that look like credentials (`apikey=...`, `token=...`). */
fun redactUrl(url: String): String = SENSITIVE_QUERY_PARAM.replace(url) { "${it.groupValues[1]}=***" }
