package fr.toolmap.core.altitude

import fr.toolmap.core.settings.AltitudeMode

enum class AltitudeSource { BAROMETER, GPS }

/** The altitude to show and record, with where it comes from and how far off it may be (`null` when unknown). */
data class Altitude(
    val meters: Double,
    val source: AltitudeSource,
    val uncertaintyMeters: Double?,
)

/** Which altitude to give: the barometer's, the GPS's, or whichever is better. */
object AltitudeSelector {
    /** In automatic mode a barometer less sure than this gives way to the GPS. */
    const val MAX_AUTO_UNCERTAINTY_METERS = 30.0

    fun choose(
        mode: AltitudeMode,
        gpsMeters: Double?,
        gpsUncertaintyMeters: Double?,
        baro: BaroReading?,
    ): Altitude? {
        val fromBaro = baro?.let { Altitude(it.altitudeMeters, AltitudeSource.BAROMETER, it.uncertaintyMeters) }
        val fromGps = gpsMeters?.let { Altitude(it, AltitudeSource.GPS, gpsUncertaintyMeters) }
        return when (mode) {
            AltitudeMode.GPS -> {
                fromGps
            }

            AltitudeMode.BAROMETER -> {
                fromBaro
            }

            AltitudeMode.AUTO -> {
                when {
                    fromBaro == null -> fromGps
                    fromGps == null -> fromBaro
                    fromBaro.uncertaintyMeters!! <= MAX_AUTO_UNCERTAINTY_METERS -> fromBaro
                    else -> fromGps
                }
            }
        }
    }
}
