package fr.toolmap.core.tiles

import fr.toolmap.core.geo.Bounds
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** A tile column (x) and row (y) in the XYZ scheme. */
data class TileCoord(
    val x: Int,
    val y: Int,
)

/** Web Mercator (EPSG:3857) tile maths, XYZ scheme (origin at the top-left, like OSM and Google). */
object TileMath {
    const val EARTH_RADIUS_M = 6_378_137.0
    const val ORIGIN_SHIFT_M = PI * EARTH_RADIUS_M

    /** Latitude at which the Web Mercator square is cut (rows 0 and n-1 end here). */
    const val MAX_LATITUDE = 85.0511287798

    /** Number of tiles along one axis at [zoom]. */
    fun tilesPerAxis(zoom: Int): Long {
        require(zoom in 0..30) { "zoom out of range: $zoom" }
        return 1L shl zoom
    }

    /** Bing quadkey of a tile, e.g. `quadKey(3, 5, 3) == "213"`. Empty at zoom 0. */
    fun quadKey(
        x: Int,
        y: Int,
        zoom: Int,
    ): String {
        requireTile(x, y, zoom)
        val key = StringBuilder(zoom)
        for (level in zoom downTo 1) {
            val mask = 1 shl (level - 1)
            var digit = 0
            if (x and mask != 0) digit += 1
            if (y and mask != 0) digit += 2
            key.append(digit)
        }
        return key.toString()
    }

    /** Row in the TMS scheme (origin at the bottom) for an XYZ row [y]. */
    fun tmsY(
        y: Int,
        zoom: Int,
    ): Int {
        requireTile(0, y, zoom)
        return (tilesPerAxis(zoom) - 1 - y).toInt()
    }

    /** Column and row (XYZ scheme) of the tile that contains the given WGS84 position. */
    fun tileAt(
        lon: Double,
        lat: Double,
        zoom: Int,
    ): TileCoord {
        val n = tilesPerAxis(zoom)
        val clampedLat = lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = floor((lon + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)
        val latRad = Math.toRadians(clampedLat)
        val y = floor((1 - asinh(tan(latRad)) / PI) / 2 * n).toLong().coerceIn(0, n - 1)
        return TileCoord(x.toInt(), y.toInt())
    }

    /** EPSG:3857 easting in meters of a longitude. */
    fun mercatorX(lon: Double): Double = EARTH_RADIUS_M * Math.toRadians(lon)

    /** EPSG:3857 northing in meters of a latitude (clamped to the Web Mercator limit). */
    fun mercatorY(lat: Double): Double =
        EARTH_RADIUS_M * asinh(tan(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))))

    /** Tile extent in degrees: x = longitude, y = latitude. */
    fun boundsLonLat(
        x: Int,
        y: Int,
        zoom: Int,
    ): Bounds {
        requireTile(x, y, zoom)
        val n = tilesPerAxis(zoom).toDouble()
        return Bounds(
            minX = x / n * 360.0 - 180.0,
            minY = latitudeOfRow(y + 1, n),
            maxX = (x + 1) / n * 360.0 - 180.0,
            maxY = latitudeOfRow(y, n),
        )
    }

    /** Tile extent in EPSG:3857 meters. */
    fun boundsMercator(
        x: Int,
        y: Int,
        zoom: Int,
    ): Bounds {
        requireTile(x, y, zoom)
        val size = 2 * ORIGIN_SHIFT_M / tilesPerAxis(zoom)
        return Bounds(
            minX = -ORIGIN_SHIFT_M + x * size,
            minY = ORIGIN_SHIFT_M - (y + 1) * size,
            maxX = -ORIGIN_SHIFT_M + (x + 1) * size,
            maxY = ORIGIN_SHIFT_M - y * size,
        )
    }

    /** Ground resolution in meters per pixel at [latitude] for tiles of [tileSize] pixels. */
    fun metersPerPixel(
        latitude: Double,
        zoom: Int,
        tileSize: Int = 256,
    ): Double = 2 * PI * EARTH_RADIUS_M * cos(Math.toRadians(latitude)) / (tileSize * 2.0.pow(zoom))

    private fun latitudeOfRow(
        row: Int,
        n: Double,
    ): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * row / n))))

    private fun requireTile(
        x: Int,
        y: Int,
        zoom: Int,
    ) {
        val n = tilesPerAxis(zoom)
        require(x in 0 until n && y in 0 until n) { "tile $zoom/$x/$y out of range" }
    }
}
