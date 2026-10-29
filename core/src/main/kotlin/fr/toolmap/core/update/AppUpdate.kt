package fr.toolmap.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The update of the app from its GitHub pre-releases (P31, ADR-094): the CI publishes the debug APK of every push on
 * `main` as a pre-release `debug-<sha7>` that carries `ToolMap-debug-<version>.apk` (docs/specs/release.md §1). The app
 * reads the list of releases, keeps the highest version and offers it when it is above its own.
 */

/** A version name `MAJOR.MINOR.PATCH` (ADR-040), compared number by number; a missing number counts as 0. */
data class AppVersion(
    val numbers: List<Int>,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(numbers.size, other.numbers.size)) {
            val diff = numbers.getOrElse(i) { 0 }.compareTo(other.numbers.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    override fun toString(): String = numbers.joinToString(".")

    companion object {
        /** `0.4.126` → its numbers; `null` for anything else (a suffix, an empty part, a negative number). */
        fun parse(text: String): AppVersion? {
            val parts = text.trim().split('.')
            if (parts.size !in 1..MAX_PARTS) return null
            val numbers = parts.map { part -> part.toIntOrNull()?.takeIf { part.all(Char::isDigit) } ?: return null }
            return AppVersion(numbers)
        }

        private const val MAX_PARTS = 4
    }
}

/** The APK of a published version: where to download it, its size, and when it was published (ISO 8601). */
data class PublishedApk(
    val version: AppVersion,
    val url: String,
    val sizeBytes: Long,
    val publishedAt: String?,
)

/** Reads the answer of the GitHub API (`GET /repos/<owner>/<repo>/releases`). */
object GitHubReleases {
    /** The releases of the public repository, newest first; ten are more than enough (the CI keeps one debug). */
    const val API_URL = "https://api.github.com/repos/JRRocks/ToolMap/releases?per_page=10"

    private val APK_NAME = Regex("""ToolMap-debug-(\d+(?:\.\d+)+)\.apk""")
    private val json = Json { ignoreUnknownKeys = true }

    /** The highest debug APK of [body], or `null` when there is none or the text is not a list of releases. */
    fun latestApk(body: String): PublishedApk? {
        val releases = runCatching { json.decodeFromString<List<Release>>(body) }.getOrNull() ?: return null
        return releases
            .asSequence()
            .filter { !it.draft && it.tag.startsWith(DEBUG_TAG) }
            .flatMap { release ->
                release.assets.asSequence().mapNotNull { asset ->
                    val version = APK_NAME.matchEntire(asset.name)?.groupValues?.get(1)?.let(AppVersion::parse)
                    version?.let { PublishedApk(it, asset.url, asset.size, release.publishedAt) }
                }
            }.maxByOrNull { it.version }
    }

    private const val DEBUG_TAG = "debug-"

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String = "",
        val draft: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0,
    )
}

/** What a check found, seen from the version [installed]. */
object UpdateDecision {
    /** [published] when it is strictly above [installed]; an app whose own version cannot be read is offered nothing. */
    fun offer(
        installed: String,
        published: PublishedApk?,
    ): PublishedApk? {
        val own = AppVersion.parse(installed) ?: return null
        return published?.takeIf { it.version > own && it.url.startsWith("https://") }
    }

    /** The room « Pas assez de place » asks for an APK of [bytes]: rounded up to 10 MB (« environ 90 Mo » for 87). */
    fun roomFor(bytes: Long): Long = (bytes.coerceAtLeast(1) + ROOM_STEP - 1) / ROOM_STEP * ROOM_STEP

    private const val ROOM_STEP = 10_000_000L
}
