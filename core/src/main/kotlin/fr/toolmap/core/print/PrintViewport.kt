package fr.toolmap.core.print

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.tiles.TileMath

/** Where to put the camera: a centre and a zoom. */
data class CameraFit(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
)

/** How big the page frame looks on the screen: the frame is drawn over the map at its true ground size. */
object PrintViewport {
    /** Ground meters under the page frame at [scale]: millimeters on the paper times the scale denominator. */
    fun groundMeters(
        paperMm: Double,
        scale: Double,
    ): Double = paperMm / MM_PER_M * scale

    /** The frame's size in dp for a map at [cameraZoom] centred on [latitude], as (width, height). */
    fun frameDp(
        setup: PageSetup,
        scale: Double,
        latitude: Double,
        cameraZoom: Double,
    ): Pair<Double, Double> {
        val metersPerDp = ScaleBar.metersPerDp(latitude, cameraZoom)
        return groundMeters(setup.frameWidthMm, scale) / metersPerDp to
            groundMeters(setup.frameHeightMm, scale) / metersPerDp
    }

    /** The camera zoom at which the whole frame fits in [availableWidthDp] × [availableHeightDp] (the larger the better). */
    fun zoomToFit(
        setup: PageSetup,
        scale: Double,
        latitude: Double,
        availableWidthDp: Double,
        availableHeightDp: Double,
    ): Double {
        val metersPerDp =
            maxOf(
                groundMeters(setup.frameWidthMm, scale) / availableWidthDp,
                groundMeters(setup.frameHeightMm, scale) / availableHeightDp,
            )
        return ScaleBar.cameraZoomFor(latitude, metersPerDp)
    }

    /**
     * The camera that shows every point of [corners] (the pages of an atlas along a track, F9) in [availableWidthDp] ×
     * [availableHeightDp], north up; `null` without points.
     */
    fun fit(
        corners: List<LonLat>,
        availableWidthDp: Double,
        availableHeightDp: Double,
    ): CameraFit? {
        if (corners.isEmpty()) return null
        val xs = corners.map { TileMath.mercatorX(it.lon) }
        val ys = corners.map { TileMath.mercatorY(it.lat) }
        val centerX = (xs.min() + xs.max()) / 2
        val centerY = (ys.min() + ys.max()) / 2
        val lat = Math.toDegrees(2 * Math.atan(Math.exp(centerY / TileMath.EARTH_RADIUS_M)) - Math.PI / 2)
        val lon = Math.toDegrees(centerX / TileMath.EARTH_RADIUS_M)
        // Mercator meters times the cosine of the latitude are ground meters there, the unit of metersPerDp.
        val cos = Math.cos(Math.toRadians(lat))
        val metersPerDp =
            maxOf(
                maxOf(xs.max() - xs.min(), 1.0) * cos / availableWidthDp,
                maxOf(ys.max() - ys.min(), 1.0) * cos / availableHeightDp,
            )
        return CameraFit(lat, lon, ScaleBar.cameraZoomFor(lat, metersPerDp))
    }

    private const val MM_PER_M = 1000.0
}
