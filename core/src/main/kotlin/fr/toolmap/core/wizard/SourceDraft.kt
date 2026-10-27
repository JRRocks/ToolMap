package fr.toolmap.core.wizard

import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileMath
import java.util.Locale

/** Why a [SourceDraft] cannot be saved (errors) or deserves a second look (warnings). */
enum class DraftIssue(
    val isError: Boolean,
) {
    NAME_EMPTY(true),
    URL_EMPTY(true),
    URL_NOT_HTTP(true),
    NO_TILE_PLACEHOLDER(true),
    UNKNOWN_PLACEHOLDER(true),
    MISSING_PARAM(true),
    ZOOM_INVALID(true),
    TILE_SIZE_INVALID(true),
    UPDATE_DELAY_INVALID(true),
    HEADER_INVALID(true),
    THREADS_INVALID(true),
    OPACITY_INVALID(true),

    /** `http://` (not encrypted): works only because the app allows clear-text traffic; worth saying. */
    CLEARTEXT(false),

    /** An API key still sits in the URL instead of a parameter: it would be exported with the file. */
    KEY_IN_URL(false),
}

/**
 * What the add-a-map wizard collects, as plain text fields so that half-typed values can be kept and checked
 * ([validate]) before becoming a [TileSource] ([toSource]). One level only: the wizard creates and edits
 * single-level sources.
 */
data class SourceDraft(
    val id: String = "",
    val name: String = "",
    /** One URL template per server (several = round-robin). */
    val urls: List<String> = listOf(""),
    /** `{$param:key}` values: API keys and the like. */
    val params: Map<String, String> = emptyMap(),
    val type: String? = null,
    val isLayer: Boolean = false,
    /** 0..100. */
    val opacity: Int = 100,
    val copyright: String = "",
    /** AQX zoom syntax (`1-17`, `0-5,8`). */
    val zoomSpec: String = "0-18",
    val tileSize: Int = LevelDef.DEFAULT_TILE_SIZE,
    val referer: String = "",
    val userAgent: String = "",
    /** One `Name: value` per line. */
    val headersText: String = "",
    val cookie: String = "",
    /**
     * `insecure="true"` of an imported `.aqx`, kept so that a copy exports it again. Never applied and no longer
     * offered by the wizard: a server signed by a private authority works once the user installs it in Android
     * (`network_security_config.xml`, ADR-066).
     */
    val insecure: Boolean = false,
    val maxThreads: Int = LevelDef.DEFAULT_MAX_THREADS,
    /** `0D`, `2W`, `3M`, `1Y` or empty (never expires). */
    val updateDelay: String = "",
    val bboxCrs: String = LevelDef.CRS_WGS84,
    val previewLocation: LonLatZoom? = null,
) {
    /** Parsed `Name: value` lines; lines that do not fit are in [invalidHeaderLines]. */
    val headers: List<Pair<String, String>>
        get() = headerLines().first

    val invalidHeaderLines: List<String>
        get() = headerLines().second

    private fun headerLines(): Pair<List<Pair<String, String>>, List<String>> {
        val good = mutableListOf<Pair<String, String>>()
        val bad = mutableListOf<String>()
        for (raw in headersText.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            val name = line.substringBefore(':').trim()
            if (colon <= 0 ||
                !HEADER_NAME.matches(name)
            ) {
                bad += line
            } else {
                good += name to line.substring(colon + 1).trim()
            }
        }
        return good to bad
    }

    val zoomSet: ZoomSet? get() = runCatching { ZoomSet.parse(zoomSpec) }.getOrNull()

    fun validate(): List<DraftIssue> {
        val issues = LinkedHashSet<DraftIssue>()
        if (name.isBlank()) issues += DraftIssue.NAME_EMPTY
        val urlList = urls.map { it.trim() }.filter { it.isNotEmpty() }
        if (urlList.isEmpty()) issues += DraftIssue.URL_EMPTY
        for (url in urlList) checkUrl(url, issues)
        if (zoomSet == null) issues += DraftIssue.ZOOM_INVALID
        if (tileSize !in TILE_SIZES) issues += DraftIssue.TILE_SIZE_INVALID
        if (updateDelay.isNotBlank() &&
            !UPDATE_DELAY.matches(updateDelay.trim())
        ) {
            issues += DraftIssue.UPDATE_DELAY_INVALID
        }
        if (invalidHeaderLines.isNotEmpty()) issues += DraftIssue.HEADER_INVALID
        if (maxThreads !in 1..MAX_THREADS) issues += DraftIssue.THREADS_INVALID
        if (opacity !in 0..100) issues += DraftIssue.OPACITY_INVALID
        return issues.sortedBy { !it.isError }
    }

    val isValid: Boolean get() = validate().none { it.isError }

    private fun checkUrl(
        url: String,
        issues: MutableSet<DraftIssue>,
    ) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            issues += DraftIssue.URL_NOT_HTTP
        }
        if (url.startsWith("http://", ignoreCase = true)) issues += DraftIssue.CLEARTEXT
        val placeholders = PLACEHOLDER.findAll(url).map { it.groupValues[1] }.toList()
        val hasTilePlaceholder =
            ("z" in placeholders && "x" in placeholders && ("y" in placeholders || "!y" in placeholders)) ||
                ("q" in placeholders) ||
                ("bbox" in placeholders || "ibbox" in placeholders)
        if (!hasTilePlaceholder) issues += DraftIssue.NO_TILE_PLACEHOLDER
        for (name in placeholders) {
            when {
                name in KNOWN_PLACEHOLDERS -> {
                    Unit
                }

                name.startsWith("param:") -> {
                    if (name.removePrefix("param:") !in
                        params
                    ) {
                        issues += DraftIssue.MISSING_PARAM
                    }
                }

                else -> {
                    issues += DraftIssue.UNKNOWN_PLACEHOLDER
                }
            }
        }
        if (ApiKeyExtractor.extract(url).found) issues += DraftIssue.KEY_IN_URL
    }

    /** Moves API keys written in the URLs into [params] (see [ApiKeyExtractor]). Returns the new draft. */
    fun extractKeys(): SourceDraft {
        var known = params
        val rewritten =
            urls.map { url ->
                val extraction = ApiKeyExtractor.extract(url, known)
                known = known + extraction.params
                extraction.template
            }
        return copy(urls = rewritten, params = known)
    }

    /** @throws IllegalStateException when the draft has errors: call [validate] first. */
    fun toSource(): TileSource {
        check(isValid) { "Invalid draft: ${validate().filter { it.isError }}" }
        val level =
            LevelDef(
                zooms = ZoomSet.parse(zoomSpec),
                servers = urls.map { it.trim() }.filter { it.isNotEmpty() },
                referer = referer.trim().ifEmpty { null },
                userAgent = userAgent.trim().ifEmpty { null },
                headers = headers,
                cookie = cookie.trim().ifEmpty { null },
                updateDelay = updateDelay.trim().uppercase(Locale.ROOT).ifEmpty { null },
                maxThreads = maxThreads,
                insecure = insecure,
                tileSize = tileSize,
                bboxCrs = bboxCrs,
            )
        // Only parameters the URLs refer to are kept (a key left over from an edit is not written to files).
        val used = urls.flatMap { PARAM_REF.findAll(it).map { m -> m.groupValues[1] } }.toSet()
        return TileSource(
            id = id.ifBlank { SourceIds.fromName(name) },
            name = name.trim(),
            type = type?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null },
            isLayer = isLayer,
            opacity = opacity,
            copyright = copyright.trim().ifEmpty { null },
            params = params.filterKeys { it in used },
            levels = listOf(level),
            previewLocation = previewLocation,
        )
    }

    companion object {
        const val MAX_THREADS = 16
        val TILE_SIZES = listOf(256, 512)

        private val HEADER_NAME = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]+""")
        private val UPDATE_DELAY = Regex("""\d{1,5}\s*[DWMYdwmy]""")

        // Every brace escaped: Android's regex engine (ICU) rejects a bare `}` (RegexPortabilityTest).
        private val PLACEHOLDER = Regex("""\{\$([^\}]+)\}""")
        private val PARAM_REF = Regex("""\{[$]param:([^\}]+)\}""")
        private val KNOWN_PLACEHOLDERS = setOf("x", "y", "!y", "z", "q", "bbox", "ibbox")

        /** The draft that would produce [source] again; `null` when the wizard cannot represent it. */
        fun fromSource(source: TileSource): SourceDraft? {
            val level = source.levels.singleOrNull() ?: return null
            if (!source.isSupported || !level.hasServer || level.clearColor != null) {
                return null
            }
            return SourceDraft(
                id = source.id,
                name = source.name,
                urls = level.servers,
                params = source.params,
                type = source.type,
                isLayer = source.isLayer,
                opacity = source.opacity,
                copyright = source.copyright.orEmpty(),
                zoomSpec = level.zooms.toSpec(),
                tileSize = level.tileSize,
                referer = level.referer.orEmpty(),
                userAgent = level.userAgent.orEmpty(),
                headersText = level.headers.joinToString("\n") { "${it.first}: ${it.second}" },
                cookie = level.cookie.orEmpty(),
                insecure = level.insecure,
                maxThreads = level.maxThreads,
                updateDelay = level.updateDelay.orEmpty(),
                bboxCrs = level.bboxCrs,
                previewLocation = source.previewLocation,
            )
        }
    }
}

/** Ids of user-created sources: lower case, digits and dashes (they are path segments of the tile host). */
object SourceIds {
    fun fromName(name: String): String {
        val folded = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD).replace(MARKS, "")
        val slug = folded.lowercase(Locale.ROOT).replace(NOT_ID, "-").trim('-').take(MAX_LENGTH).trim('-')
        return slug.ifEmpty { "map" }
    }

    /** [base], or `base-2`, `base-3`... when [taken] already has it. */
    fun unique(
        base: String,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(base)) return base
        var n = 2
        while (taken("$base-$n")) n++
        return "$base-$n"
    }

    private val MARKS = Regex("\\p{M}+")
    private val NOT_ID = Regex("[^a-z0-9]+")
    private const val MAX_LENGTH = 40
}

/** A tile to fetch to check that a source answers: its address in the source's own tile grid. */
data class PreviewTile(
    val z: Int,
    val x: Int,
    val y: Int,
) {
    companion object {
        /** Paris, used when the source does not say where its data is. */
        val DEFAULT_LOCATION = LonLatZoom(2.35, 48.85, 12)

        /**
         * The tile at [location] (the source's `preview-location`, else Paris), at the zoom the source really serves
         * that is closest to the location's own zoom.
         */
        fun choose(
            zooms: ZoomSet,
            location: LonLatZoom?,
        ): PreviewTile {
            val place = location ?: DEFAULT_LOCATION
            val zoom = zooms.toList().minBy { Math.abs(it - place.zoom) }
            val tile = TileMath.tileAt(place.lon, place.lat, zoom)
            return PreviewTile(zoom, tile.x, tile.y)
        }
    }
}
