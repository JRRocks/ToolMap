package fr.toolmap.core.source

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.LonLatZoom

/** Inclusive RGB ranges that are made transparent (AQX `<clear-color r="240-255" g=".." b=".."/>`). */
data class ClearColor(
    val r: IntRange,
    val g: IntRange,
    val b: IntRange,
)

/**
 * One block of zoom levels of a [TileSource] with its own servers and HTTP settings
 * (AQX `<level>` / `<zoom-levels>`).
 */
data class LevelDef(
    val zooms: ZoomSet,
    /** URL templates; several entries mean round-robin load balancing. */
    val servers: List<String>,
    val referer: String? = null,
    val userAgent: String? = null,
    /** Extra HTTP headers, in file order. */
    val headers: List<Pair<String, String>> = emptyList(),
    val cookie: String? = null,
    /** Cache validity as written in the file (`0D`, `2W`, `3M`, `1Y`); null means never expires. */
    val updateDelay: String? = null,
    val maxThreads: Int = DEFAULT_MAX_THREADS,
    /** Accept any HTTPS certificate. Dangerous: surfaced to the user as a warning. */
    val insecure: Boolean = false,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val clearColor: ClearColor? = null,
    val keyUrl: String? = null,
    /** CRS used by the `{$bbox}` placeholder. */
    val bboxCrs: String = CRS_WGS84,
) {
    /** True for a level that has no server: its tiles only exist in local storage (an imported `.mbtiles`). */
    val isLocalOnly: Boolean get() = servers.isNotEmpty() && servers.all { it.startsWith(LOCAL_ONLY_PREFIX) }

    /** The map this level shows instead of calling a server (multi-zoom maps, ADR-031), or `null`. */
    val mapRef: MapRef? by lazy(LazyThreadSafetyMode.PUBLICATION) { servers.singleOrNull()?.let(MapRef::parse) }

    /** True for an ordinary level whose tiles come from a server. */
    val hasServer: Boolean get() = !isLocalOnly && mapRef == null

    companion object {
        /** `<server>` value of a local-only level: not a URL, never requested. */
        const val LOCAL_ONLY_SERVER = "offline://stored-tiles"
        const val LOCAL_ONLY_PREFIX = "offline:"
        const val DEFAULT_MAX_THREADS = 3
        const val DEFAULT_TILE_SIZE = 256
        const val CRS_WGS84 = "EPSG:4326"
        const val CRS_WEB_MERCATOR = "EPSG:3857"
    }
}

/** A map source: a base map or an overlay, defined by one or more [LevelDef]s. */
data class TileSource(
    /** Unique id; also the storage key and the path segment of the fake tile host. */
    val id: String,
    val name: String,
    /** roads, topo, satellite, hybrid, nautical, aeronautical, historical, hillshade, contours, dem... (lower case). */
    val type: String? = null,
    /** True when the source is meant to be drawn over another map. */
    val isLayer: Boolean = false,
    /** Initial opacity, 0..100. */
    val opacity: Int = 100,
    val copyright: String? = null,
    val dataSource: String? = null,
    val region: String? = null,
    val outline: List<LonLat> = emptyList(),
    val previewLocation: LonLatZoom? = null,
    val defaultLocation: LonLat? = null,
    /** Values of `{$param:key}` placeholders. */
    val params: Map<String, String> = emptyMap(),
    val levels: List<LevelDef> = emptyList(),
    /** Non-null when the source was read but cannot be displayed (e.g. non Web Mercator projection). */
    val unsupportedReason: String? = null,
) {
    val isSupported: Boolean get() = unsupportedReason == null && levels.isNotEmpty()

    /** True for a map that only shows tiles stored on the device (imported package): nothing to download. */
    val isLocalOnly: Boolean get() = levels.isNotEmpty() && levels.all { it.isLocalOnly }

    /** True for a map assembled from other maps by zoom range (at least one level is a [MapRef]). */
    val isMultiZoom: Boolean get() = levels.any { it.mapRef != null }

    /** Lowest zoom served by any level. */
    val minZoom: Int get() = levels.minOf { it.zooms.min }

    /** Highest zoom served by any level. */
    val maxZoom: Int get() = levels.maxOf { it.zooms.max }

    /** The level that serves [zoom], or null when the source has no tile at this zoom. */
    fun levelFor(zoom: Int): LevelDef? = levels.firstOrNull { zoom in it.zooms }
}
