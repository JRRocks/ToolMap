package fr.toolmap.core.route

import fr.toolmap.core.track.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToLong

/**
 * Reads the GeoJSON BRouter gives with `trackFormat=json`: one feature, a LineString of `[lon, lat, ele]` (the
 * elevation is left out where BRouter has none) and string properties such as `track-length` and `total-time` (s).
 */
object BRouterJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** The way and its time, or `null` when the text is not such a document. */
    fun parse(text: String): LegResult.Ok? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val feature = (root["features"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val geometry = feature["geometry"] as? JsonObject ?: return null
        val coordinates = geometry["coordinates"] as? JsonArray ?: return null
        val points =
            coordinates.mapNotNull { c ->
                val v = runCatching { c.jsonArray }.getOrNull() ?: return@mapNotNull null
                val lon = v.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = v.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                TrackPoint(lat, lon, v.getOrNull(2)?.jsonPrimitive?.doubleOrNull)
            }
        if (points.isEmpty()) return null
        val properties = feature["properties"] as? JsonObject
        val seconds =
            properties
                ?.get("total-time")
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.trim()
                ?.toDoubleOrNull()
        return LegResult.Ok(points, seconds?.let { (it * MILLIS_PER_SECOND).roundToLong() })
    }

    private const val MILLIS_PER_SECOND = 1000.0
}

/** Sorts the messages BRouter gives instead of a way (texts of `RoutingEngine` and `BRouterWorker`). */
object BRouterErrors {
    fun classify(message: String): RoutingError {
        val m = message.lowercase()
        return when {
            "does not exist" in m && "profile" in m -> RoutingError.UNKNOWN_PROFILE
            ".rd5" in m || "datafile" in m || "segment directory" in m || "no routing data" in m -> RoutingError.NO_DATA
            "no track found" in m || "island detected" in m || "restricted area" in m -> RoutingError.NO_PATH
            "operation killed" in m || "timeout" in m || "timed out" in m -> RoutingError.TIMEOUT
            else -> RoutingError.OTHER
        }
    }
}
