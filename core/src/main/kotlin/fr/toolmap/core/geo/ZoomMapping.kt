package fr.toolmap.core.geo

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Relation between the MapLibre camera zoom (512 dp world at zoom 0) and the zoom shown to the user, which is the
 * `z` of the 256 px tiles MapLibre draws at that camera (what AlpineQuest displays, "14/18").
 *
 * MapLibre picks the raster tiles of zoom `round(camera + log2(512 / tileSize))`, `tileSize` being the size given
 * to the raster source, and draws each tile on `tileSize` dp. Declared at its real 256 px, a tile pixel covers
 * `density` screen pixels (≈ 2.8 on a recent phone): the map is one or two zoom levels blurrier than AlpineQuest,
 * which draws a tile pixel on about one screen pixel. Declaring `256 / tileScale` instead makes MapLibre take the
 * tiles `log2(tileScale)` levels deeper and draw them that much smaller (ADR-032).
 *
 * @property tileScale how many times smaller than MapLibre's default a tile is drawn; 1 = MapLibre's default.
 */
class ZoomMapping private constructor(
    val tileScale: Double,
) {
    /** Size declared to MapLibre for the tiles of 256 px: the whole mapping derives from this integer. */
    private val declared256: Int =
        (STANDARD_TILE_PIXELS / tileScale).roundToInt().coerceIn(
            MIN_DECLARED,
            STANDARD_TILE_PIXELS,
        )

    /** Displayed zoom − camera zoom. */
    val offset: Double = log2(CAMERA_TILE_SIZE / declared256)

    fun toTileZoom(cameraZoom: Double): Double = cameraZoom + offset

    fun toCameraZoom(tileZoom: Double): Double = tileZoom - offset

    /** The tile zoom MapLibre requests at [cameraZoom] (it rounds for raster sources), for 256 px tiles. */
    fun requestedTileZoom(cameraZoom: Double): Int = Math.round(toTileZoom(cameraZoom)).toInt()

    /** The `tileSize` to give MapLibre for a raster source whose tiles are [pixels] px wide (256, 512…). */
    fun declaredTileSize(pixels: Int): Int =
        if (pixels == STANDARD_TILE_PIXELS) declared256 else max(MIN_DECLARED, (pixels / tileScale).roundToInt())

    override fun equals(other: Any?): Boolean = other is ZoomMapping && other.tileScale == tileScale

    override fun hashCode(): Int = tileScale.hashCode()

    override fun toString(): String = "ZoomMapping(tileScale=$tileScale, tileSize=$declared256)"

    companion object {
        private const val CAMERA_TILE_SIZE = 512.0
        private const val STANDARD_TILE_PIXELS = 256

        /** Smallest size declared to MapLibre (tileScale 16): far beyond any screen density. */
        private const val MIN_DECLARED = 16

        /** MapLibre's own size: a tile pixel on one dp, displayed zoom = camera + 1 (behaviour before ADR-032). */
        val STANDARD = ZoomMapping(1.0)

        /**
         * The mapping for a screen of [density] (physical pixels per dp) where a tile pixel should cover
         * [magnification] screen pixels: 1 = AlpineQuest's sharpness, [density] or more = MapLibre's default size.
         */
        fun forScreen(
            density: Double,
            magnification: Double,
        ): ZoomMapping {
            if (!(density > 0.0) || !(magnification > 0.0)) return STANDARD
            return ZoomMapping(max(1.0, density / magnification))
        }

        private fun log2(value: Double): Double = ln(value) / ln(2.0)
    }
}

/** The choices of the "map sharpness" setting: screen pixels covered by one tile pixel (ADR-032). */
object TileSharpness {
    /** One tile pixel on one screen pixel, as AlpineQuest draws its maps. */
    const val SHARP = 1.0

    /** MapLibre's default size (one tile pixel per dp), the only behaviour before ADR-032. */
    const val ANDROID_SIZE = 0.0

    private val MAGNIFICATIONS = listOf(SHARP, 1.5, 2.0)

    /**
     * What the settings offer on a screen of [density]: the enlargements smaller than it, then MapLibre's size
     * (which is [SHARP] itself on a screen of density 1).
     */
    fun choices(density: Double): List<Double> =
        MAGNIFICATIONS.filter { it == SHARP || it < density } + listOfNotNull(ANDROID_SIZE.takeIf { density > SHARP })
}
