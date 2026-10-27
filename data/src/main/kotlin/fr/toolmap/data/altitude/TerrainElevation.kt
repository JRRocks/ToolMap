package fr.toolmap.data.altitude

import fr.toolmap.core.elevation.PngException
import fr.toolmap.core.elevation.TerrariumTile
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.data.elevation.ElevationCompleter
import fr.toolmap.data.elevation.ElevationTiles

/** The ground elevation at a position, above mean sea level; `null` when it cannot be known now (offline, no tile). */
fun interface TerrainElevation {
    suspend fun at(
        lat: Double,
        lon: Double,
    ): Double?
}

/**
 * The elevation model behind the Terrarium tiles. The last few decoded tiles are kept, so that a walk across one tile
 * asks for it once.
 */
class TerrariumElevation(
    private val tiles: ElevationTiles,
    private val zoom: Int = ElevationCompleter.DEFAULT_ZOOM,
) : TerrainElevation {
    private data class Key(
        val x: Int,
        val y: Int,
    )

    private val recent = LinkedHashMap<Key, TerrariumTile>()

    override suspend fun at(
        lat: Double,
        lon: Double,
    ): Double? {
        val tile = TileMath.tileAt(lon, lat, zoom)
        val key = Key(tile.x, tile.y)
        val decoded =
            synchronized(recent) { recent[key] } ?: fetch(key)?.also {
                synchronized(recent) {
                    recent[key] = it
                    while (recent.size > CACHED_TILES) recent.remove(recent.keys.first())
                }
            } ?: return null
        val (fx, fy) = TerrariumTile.fractionIn(lon, lat, zoom, tile.x, tile.y)
        return decoded.elevationAt(fx, fy)
    }

    private suspend fun fetch(key: Key): TerrariumTile? {
        val png = tiles.tile(zoom, key.x, key.y) ?: return null
        return try {
            TerrariumTile.fromPng(png)
        } catch (_: PngException) {
            null
        }
    }

    private companion object {
        const val CACHED_TILES = 4
    }
}
