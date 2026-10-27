package fr.toolmap.core.elevation

import fr.toolmap.core.tiles.TileMath
import kotlin.math.floor

/**
 * A decoded Terrarium elevation tile (AWS `elevation-tiles-prod`, Mapzen format): each pixel holds
 * `red * 256 + green + blue / 256 - 32768` meters.
 */
class TerrariumTile(
    val size: Int,
    private val elevations: FloatArray,
) {
    init {
        require(elevations.size == size * size) { "expected ${size * size} pixels, got ${elevations.size}" }
    }

    /** Elevation of the pixel at ([x], [y]). */
    fun pixel(
        x: Int,
        y: Int,
    ): Double = elevations[y.coerceIn(0, size - 1) * size + x.coerceIn(0, size - 1)].toDouble()

    /**
     * Elevation at a fraction ([fx], [fy]) of the tile, each 0..1 from the top-left corner, interpolated between the
     * four nearest pixel centres. Beyond the edge the border pixels are repeated (an error under one pixel).
     */
    fun elevationAt(
        fx: Double,
        fy: Double,
    ): Double {
        val px = fx * size - 0.5
        val py = fy * size - 0.5
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val tx = px - x0
        val ty = py - y0
        val top = pixel(x0, y0) * (1 - tx) + pixel(x0 + 1, y0) * tx
        val bottom = pixel(x0, y0 + 1) * (1 - tx) + pixel(x0 + 1, y0 + 1) * tx
        return top * (1 - ty) + bottom * ty
    }

    companion object {
        fun decodeElevation(
            red: Int,
            green: Int,
            blue: Int,
        ): Double = red * 256.0 + green + blue / 256.0 - 32768.0

        /** @throws PngException when [png] is not a PNG this decoder reads, or is not square. */
        fun fromPng(png: ByteArray): TerrariumTile {
            val image = PngDecoder.decode(png)
            if (image.width != image.height) throw PngException("Elevation tile is not square")
            if (image.channels < 3) throw PngException("Elevation tile has no colour channels")
            val values = FloatArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    values[y * image.width + x] =
                        decodeElevation(image.sample(x, y, 0), image.sample(x, y, 1), image.sample(x, y, 2)).toFloat()
                }
            }
            return TerrariumTile(image.width, values)
        }

        /** Fractions ([fx], [fy]) of the tile (x, y) at [zoom] where a WGS84 position falls. */
        fun fractionIn(
            lon: Double,
            lat: Double,
            zoom: Int,
            x: Int,
            y: Int,
        ): Pair<Double, Double> {
            val n = TileMath.tilesPerAxis(zoom).toDouble()
            val worldX = (lon + 180.0) / 360.0 * n
            val sin = Math.sin(Math.toRadians(lat.coerceIn(-TileMath.MAX_LATITUDE, TileMath.MAX_LATITUDE)))
            val worldY = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * n
            return (worldX - x) to (worldY - y)
        }
    }
}
