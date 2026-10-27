package fr.toolmap.map

import android.hardware.GeomagneticField
import fr.toolmap.core.settings.NorthReference
import fr.toolmap.core.settings.NorthReferenceMath

/**
 * The angle between true north and the reference north the user chose (« Nord de référence », P11.8), at a position.
 * Magnetic declination from the Android geomagnetic model, grid convergence from the UTM zone.
 */
object NorthOffsets {
    fun offsetDegrees(
        north: NorthReference,
        lat: Double,
        lon: Double,
        timeMillis: Long = System.currentTimeMillis(),
    ): Double {
        if (north == NorthReference.TRUE) return 0.0
        val declination = declinationDegrees(lat, lon, timeMillis)
        return NorthReferenceMath.offsetDegrees(north, declination, NorthReferenceMath.utmConvergenceDegrees(lat, lon))
    }

    /** Magnetic declination in degrees, east positive; `0` near the poles where the model does not apply. */
    fun declinationDegrees(
        lat: Double,
        lon: Double,
        timeMillis: Long = System.currentTimeMillis(),
    ): Double = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, timeMillis).declination.toDouble()
}
