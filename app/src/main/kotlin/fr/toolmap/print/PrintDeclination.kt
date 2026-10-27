package fr.toolmap.print

import android.hardware.GeomagneticField

/** Magnetic declination (east positive, degrees) at a place and time, from the World Magnetic Model Android ships. */
object PrintDeclination {
    fun at(
        lat: Double,
        lon: Double,
        timeMillis: Long = System.currentTimeMillis(),
    ): Double = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, timeMillis).declination.toDouble()
}
