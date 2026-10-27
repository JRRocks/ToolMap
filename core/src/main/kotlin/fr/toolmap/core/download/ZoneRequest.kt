package fr.toolmap.core.download

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.tiles.DownloadArea
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Serializable form of a [DownloadArea], stored with the download job so it can resume after a restart. */
@Serializable
sealed interface ZoneShape {
    @Serializable
    data class Rectangle(
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
    ) : ZoneShape

    @Serializable
    data class Polygon(
        val points: List<Point>,
    ) : ZoneShape

    @Serializable
    data class Corridor(
        val points: List<Point>,
        val radiusMeters: Double,
    ) : ZoneShape

    @Serializable
    data class Point(
        val lon: Double,
        val lat: Double,
    )

    fun toArea(): DownloadArea =
        when (this) {
            is Rectangle -> DownloadArea.Rectangle(Bounds(west, south, east, north))
            is Polygon -> DownloadArea.Polygon(points.map { LonLat(it.lon, it.lat) })
            is Corridor -> DownloadArea.Corridor(points.map { LonLat(it.lon, it.lat) }, radiusMeters)
        }

    companion object {
        fun of(area: DownloadArea): ZoneShape =
            when (area) {
                is DownloadArea.Rectangle -> {
                    Rectangle(area.bounds.minX, area.bounds.minY, area.bounds.maxX, area.bounds.maxY)
                }

                is DownloadArea.Polygon -> {
                    Polygon(area.ring.map { Point(it.lon, it.lat) })
                }

                is DownloadArea.Corridor -> {
                    Corridor(area.path.map { Point(it.lon, it.lat) }, area.radiusMeters)
                }
            }
    }
}

/** Zoom bounds one source is limited to in a download (a layer's display range); `null` = unbounded. */
@Serializable
data class ZoomLimit(
    val min: Int? = null,
    val max: Int? = null,
) {
    operator fun contains(zoom: Int): Boolean = (min == null || zoom >= min) && (max == null || zoom <= max)
}

/**
 * One zone download: which [sourceIds] to fetch over the [shape], for the tile zooms [minZoom]..[maxZoom]
 * (each source only gets the zooms it declares).
 */
@Serializable
data class ZoneRequest(
    val id: String,
    val name: String,
    val sourceIds: List<String>,
    val minZoom: Int,
    val maxZoom: Int,
    val shape: ZoneShape,
    val createdAtMillis: Long = 0L,
    /** Per-source zoom limits (layer display ranges), when the user chose to respect them. */
    val zoomLimits: Map<String, ZoomLimit> = emptyMap(),
) {
    init {
        require(sourceIds.isNotEmpty()) { "no source selected" }
        require(minZoom in 0..MAX_ZOOM && maxZoom in minZoom..MAX_ZOOM) { "invalid zoom range $minZoom..$maxZoom" }
    }

    val zooms: IntRange get() = minZoom..maxZoom

    companion object {
        const val MAX_ZOOM = 22
    }
}

/** Where a zone download stands; written to disk by the downloader so the screen and the notification agree. */
@Serializable
enum class ZoneStatus { QUEUED, RUNNING, PAUSED, INTERRUPTED, DONE, FAILED }

/** Counters of a zone download. All values are tile counts except [bytes]. */
@Serializable
data class ZoneProgress(
    val total: Long = 0,
    /** Downloaded now. */
    val downloaded: Long = 0,
    /** Already stored before the job reached them. */
    val alreadyStored: Long = 0,
    /** The server has no tile there (HTTP 404 / empty): normal at the edge of a map's coverage. */
    val missing: Long = 0,
    val failed: Long = 0,
    val bytes: Long = 0,
) {
    val processed: Long get() = downloaded + alreadyStored + missing + failed
    val fraction: Float get() = if (total == 0L) 1f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/** A job as persisted: the request, its status and the last progress. */
@Serializable
data class ZoneJob(
    val request: ZoneRequest,
    val status: ZoneStatus = ZoneStatus.QUEUED,
    val progress: ZoneProgress = ZoneProgress(),
    val message: String? = null,
    val updatedAtMillis: Long = 0L,
)

object ZoneJobCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(job: ZoneJob): String = json.encodeToString(ZoneJob.serializer(), job)

    /** `null` for a damaged file. */
    fun decodeOrNull(text: String): ZoneJob? =
        runCatching { json.decodeFromString(ZoneJob.serializer(), text) }.getOrNull()
}
