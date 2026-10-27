package fr.toolmap.altitude

import android.content.Context
import fr.toolmap.core.geo.EgmGeoid
import fr.toolmap.data.altitude.GeoidModel

/**
 * The EGM96 geoid (NGA's public-domain grid, 15′ steps) read from the app's assets. The file is parsed on the first
 * use only; a missing or damaged file gives no undulation, so the altitudes stay as the receiver gives them.
 */
class AssetGeoidModel(
    context: Context,
) : GeoidModel {
    private val grid: EgmGeoid? by lazy {
        runCatching { context.assets.open(ASSET).use { EgmGeoid.parse(it.readBytes()) } }.getOrNull()
    }

    override fun undulation(
        lat: Double,
        lon: Double,
    ): Double? = grid?.undulation(lat, lon)

    private companion object {
        const val ASSET = "geoid/WW15MGH.DAC"
    }
}
