package fr.toolmap.data.download

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.TileSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** How hard a zone download may push a given tile server. */
data class BulkLimits(
    /** Most tiles one job may fetch from the source, or `null` for no limit. */
    val maxTiles: Long?,
    val maxThreads: Int,
    /** Pause after each network download, per worker. */
    val delayMillis: Long,
)

/**
 * Community tile servers forbid or strongly discourage bulk downloads (OpenStreetMap and OpenTopoMap usage
 * policies): downloads from them are capped to a small area and slowed down. Matched on the server host of the
 * source, so an imported copy of the same service is covered too.
 */
object BulkPolicy {
    private val restrictedHosts = listOf("openstreetmap.org", "openstreetmap.fr", "opentopomap.org")
    private val restricted =
        BulkLimits(maxTiles = RESTRICTED_MAX_TILES, maxThreads = 2, delayMillis = RESTRICTED_DELAY_MS)

    // Other servers: the source's own `<max-threads>` decides (6 for the Geoplateforme, ADR-058).
    private val normal = BulkLimits(maxTiles = null, maxThreads = AqxParser.MAX_THREADS, delayMillis = 0)

    fun limitsFor(source: TileSource): BulkLimits {
        val hosts =
            source.levels
                .flatMap { it.servers }
                .mapNotNull { template ->
                    // Templates hold {$x}-style placeholders: only the host part matters.
                    template.substringAfter("://", "").substringBefore('/').substringBefore(':').takeIf {
                        it.isNotEmpty()
                    }
                        ?: "https://$template".toHttpUrlOrNull()?.host
                }.map { it.lowercase() }
        return if (hosts.any { host ->
                restrictedHosts.any { host == it || host.endsWith(".$it") }
            }
        ) {
            restricted
        } else {
            normal
        }
    }

    const val RESTRICTED_MAX_TILES = 1_500L
    private const val RESTRICTED_DELAY_MS = 300L
}
