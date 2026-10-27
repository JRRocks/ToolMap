package fr.toolmap.core.print

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.tiles.TileMath

/** A point of the map frame, in millimeters from its top-left corner (x to the right, y downwards). */
data class FramePoint(
    val xMm: Double,
    val yMm: Double,
)

/**
 * Where things fall in the printed map frame: a [frameWidthMm] × [frameHeightMm] rectangle centred on
 * ([centerLat], [centerLon]), north up, drawn at 1:[scale] — exact at the centre latitude. The frame is a plain
 * rectangle of the Web Mercator plane, which is what the tiles are, so a tile maps to the paper without distortion.
 */
class FrameProjection(
    val centerLat: Double,
    val centerLon: Double,
    val scale: Double,
    val frameWidthMm: Double,
    val frameHeightMm: Double,
) {
    init {
        require(scale > 0 && frameWidthMm > 0 && frameHeightMm > 0) { "empty frame" }
    }

    /** Mercator meters of the plane under one millimeter of paper. */
    val mercatorPerMm: Double = PrintMath.groundMeters(1.0, scale) * PrintMath.mercatorFactor(centerLat)

    val centerX: Double = TileMath.mercatorX(centerLon)
    val centerY: Double = TileMath.mercatorY(centerLat)

    /** The frame in Mercator meters (EPSG:3857). */
    val mercatorBounds: Bounds =
        Bounds(
            minX = centerX - frameWidthMm / 2 * mercatorPerMm,
            minY = centerY - frameHeightMm / 2 * mercatorPerMm,
            maxX = centerX + frameWidthMm / 2 * mercatorPerMm,
            maxY = centerY + frameHeightMm / 2 * mercatorPerMm,
        )

    /** The frame as a box of degrees (the box that contains the frame; the frame is a bit narrower at its top). */
    val lonLatBounds: Bounds
        get() {
            val topLeft = unproject(mercatorBounds.minX, mercatorBounds.maxY)
            val bottomRight = unproject(mercatorBounds.maxX, mercatorBounds.minY)
            return Bounds(topLeft.lon, bottomRight.lat, bottomRight.lon, topLeft.lat)
        }

    fun toFrame(
        lon: Double,
        lat: Double,
    ): FramePoint =
        FramePoint(
            xMm = frameWidthMm / 2 + (TileMath.mercatorX(lon) - centerX) / mercatorPerMm,
            yMm = frameHeightMm / 2 - (TileMath.mercatorY(lat) - centerY) / mercatorPerMm,
        )

    fun toLonLat(
        xMm: Double,
        yMm: Double,
    ): LonLat =
        unproject(
            centerX + (xMm - frameWidthMm / 2) * mercatorPerMm,
            centerY - (yMm - frameHeightMm / 2) * mercatorPerMm,
        )

    /** True when the point falls inside the frame. */
    fun contains(point: FramePoint): Boolean = point.xMm in 0.0..frameWidthMm && point.yMm in 0.0..frameHeightMm

    private fun unproject(
        mx: Double,
        my: Double,
    ): LonLat {
        val lon = Math.toDegrees(mx / TileMath.EARTH_RADIUS_M)
        val lat = Math.toDegrees(2 * Math.atan(Math.exp(my / TileMath.EARTH_RADIUS_M)) - Math.PI / 2)
        return LonLat(lon, lat)
    }
}
