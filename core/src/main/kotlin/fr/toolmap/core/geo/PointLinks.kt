package fr.toolmap.core.geo

import java.util.Locale

/** Where a position goes when it is handed to another app: the link to open, and the app that must open it, if any. */
data class PointTarget(
    val uri: String,
    /** The package that must open the link, or null: the system then offers the apps that can (ADR-044). */
    val packageName: String?,
)

/**
 * The links that hand a position to another app, for « Ouvrir avec » of the centre point (ADR-044). Waze has its own
 * link, which starts the guidance to the position; the other apps get the generic `geo:` link.
 */
object PointLinks {
    const val WAZE_PACKAGE = "com.waze"

    /** The link for [app]: Waze's own link for Waze, the generic one for any other app and for the system's choice. */
    fun target(
        app: String?,
        lat: Double,
        lon: Double,
    ): PointTarget =
        when (app) {
            WAZE_PACKAGE -> PointTarget("https://waze.com/ul?ll=${coordinates(lat, lon)}&navigate=yes", WAZE_PACKAGE)
            else -> PointTarget(geo(lat, lon), app)
        }

    /** `geo:<lat>,<lon>?q=<lat>,<lon>`: the position as a pin, for the apps that open geographic positions. */
    fun geo(
        lat: Double,
        lon: Double,
    ): String {
        val at = coordinates(lat, lon)
        return "geo:$at?q=$at"
    }

    /** Decimal degrees with a point, whatever the language of the phone: `48.500000,2.250000`. */
    private fun coordinates(
        lat: Double,
        lon: Double,
    ): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
}
