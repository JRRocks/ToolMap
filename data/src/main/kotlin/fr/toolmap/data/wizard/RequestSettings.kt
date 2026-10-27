package fr.toolmap.data.wizard

import fr.toolmap.core.source.LevelDef
import okhttp3.Request

/** The HTTP details a map server may require, shared by the capabilities request and the test tile. */
data class RequestSettings(
    val referer: String? = null,
    val userAgent: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val cookie: String? = null,
) {
    /** Adds the settings to [builder]; `null` when a header name or value has a character HTTP does not allow. */
    fun apply(
        builder: Request.Builder,
        defaultUserAgent: String,
    ): Request.Builder? =
        try {
            builder.header("User-Agent", userAgent ?: defaultUserAgent)
            referer?.let { builder.header("Referer", it) }
            headers.forEach { (name, value) -> builder.header(name, value) }
            cookie?.let { builder.header("Cookie", it) }
            builder
        } catch (e: IllegalArgumentException) {
            null
        }

    companion object {
        fun of(level: LevelDef) = RequestSettings(level.referer, level.userAgent, level.headers, level.cookie)
    }
}
