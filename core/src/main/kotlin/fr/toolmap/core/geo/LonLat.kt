package fr.toolmap.core.geo

/** A WGS84 position in decimal degrees. */
data class LonLat(
    val lon: Double,
    val lat: Double,
)

/** A WGS84 position plus a (tile) zoom level, as written in the AQX `<preview-location>` element. */
data class LonLatZoom(
    val lon: Double,
    val lat: Double,
    val zoom: Int,
)

/** An axis-aligned box. Units depend on the context (degrees for EPSG:4326, meters for EPSG:3857). */
data class Bounds(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
)

/** How a position is written on screen and in the dialogs. */
@kotlinx.serialization.Serializable
enum class CoordinateSystem { DD, DDM, DMS, UTM, MGRS, LAMBERT93 }
