package fr.toolmap.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import fr.toolmap.core.geo.CoordinateFormat
import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.FormattedPosition
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.BearingFormat
import fr.toolmap.core.settings.BearingUnit
import fr.toolmap.core.settings.PressureFormat
import fr.toolmap.core.settings.PressureUnit
import fr.toolmap.core.settings.SlopeFormat
import fr.toolmap.core.settings.SlopeUnit
import fr.toolmap.core.settings.UnitSystem
import fr.toolmap.core.track.TrackFormat

/** Writes distances, heights, speeds, areas, angles, pressures and positions in the units the user chose. */
@Immutable
data class Formats(
    val units: UnitSystem = UnitSystem.METRIC,
    val coordinates: CoordinateSystem = CoordinateSystem.DD,
    val areaUnits: UnitSystem = units,
    val secondaryCoordinates: CoordinateSystem? = null,
    val bearing: BearingUnit = BearingUnit.DEGREES,
    val slope: SlopeUnit = SlopeUnit.PERCENT,
    val pressure: PressureUnit = PressureUnit.HPA,
) {
    constructor(settings: AppSettings) : this(
        units = settings.units,
        coordinates = settings.coordinates,
        areaUnits = settings.areaUnits,
        secondaryCoordinates = settings.secondaryCoordinates,
        bearing = settings.bearing,
        slope = settings.slope,
        pressure = settings.pressure,
    )

    fun distance(meters: Double): String = TrackFormat.distance(meters, units = units)

    fun elevation(meters: Double): String = TrackFormat.elevation(meters, units = units)

    fun speed(metersPerSecond: Double): String = TrackFormat.speed(metersPerSecond, units = units)

    fun area(squareMeters: Double): String = TrackFormat.area(squareMeters, units = areaUnits)

    /** An angle from the reference north, in the unit of « Bearing unit ». */
    fun bearing(degrees: Double): String = BearingFormat.format(degrees, bearing)

    /** A slope as a percentage, in the unit of « Slope unit ». */
    fun slope(percent: Double): String = SlopeFormat.format(percent, slope)

    /** An atmospheric pressure given in hectopascals, in the unit of « Pressure unit ». */
    fun pressure(hectopascals: Double): String = PressureFormat.format(hectopascals, pressure)

    fun position(
        lat: Double,
        lon: Double,
    ): FormattedPosition = CoordinateFormat.format(coordinates, lat, lon)

    /** The second format of the position box, when the user chose one (« Secondary loc. format »). */
    fun secondaryPosition(
        lat: Double,
        lon: Double,
    ): FormattedPosition? = secondaryCoordinates?.let { CoordinateFormat.format(it, lat, lon) }
}

/** The formats of the user's settings, provided once at the root of the app. */
val LocalFormats = staticCompositionLocalOf { Formats() }

/** The whole settings, for the places that need more than the units (the map grid, the alerts). */
val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
