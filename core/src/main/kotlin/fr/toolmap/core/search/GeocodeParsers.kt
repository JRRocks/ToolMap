package fr.toolmap.core.search

import fr.toolmap.core.geo.Bounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads the answers of the two geocoders into [SearchResult]s. A reply that is not what the service documents gives
 * `null` (the caller says "unreadable answer"); an entry missing its position or its name is skipped, the others are
 * kept: a service adding or dropping a field must not make the whole search fail.
 */
object GeocodeParsers {
    private const val MAX_LAT = 90.0
    private const val MAX_LON = 180.0
    private const val NOMINATIM_TITLE_PARTS = 3

    /** A box smaller than this on both sides (about 100 m) is a point with a margin: nothing worth framing. */
    private const val MIN_SPAN_DEGREES = 0.001

    /** `GET https://data.geopf.fr/geocodage/search`: a GeoJSON FeatureCollection, for the `poi` or `address` index. */
    fun parseIgn(text: String): List<SearchResult>? {
        val features = (parse(text) as? JsonObject)?.get("features") as? JsonArray ?: return null
        return features.mapNotNull { ignResult(it as? JsonObject) }
    }

    /** `GET https://nominatim.openstreetmap.org/search?format=jsonv2`: an array of places. */
    fun parseNominatim(text: String): List<SearchResult>? {
        val places = parse(text) as? JsonArray ?: return null
        return places.mapNotNull { nominatimResult(it as? JsonObject) }
    }

    private fun parse(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

    private fun ignResult(feature: JsonObject?): SearchResult? {
        feature ?: return null
        val coordinates = ((feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return null
        val lon = (coordinates.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (coordinates.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!valid(lat, lon)) return null
        val properties = feature["properties"] as? JsonObject ?: return null
        val isPoi = properties.strings("_type").firstOrNull() == "poi" || properties["toponym"] != null
        val title =
            properties.strings("toponym").firstOrNull()
                ?: properties.strings("name").firstOrNull()
                ?: properties.strings("label").firstOrNull()
                ?: return null
        val place =
            listOfNotNull(properties.strings("postcode").firstOrNull(), properties.strings("city").firstOrNull())
        val category = if (isPoi) properties.strings("category").firstOrNull() else null
        // The category is not repeated here: the list shows it in front of the subtitle, as for OpenStreetMap.
        val subtitle =
            place.joinToString(" ").takeIf { it.isNotBlank() }
                ?: properties.strings("context").firstOrNull().orEmpty()
        return SearchResult(title, subtitle, lat, lon, SearchSource.IGN, category)
    }

    private fun nominatimResult(place: JsonObject?): SearchResult? {
        place ?: return null
        val lat = place.number("lat") ?: return null
        val lon = place.number("lon") ?: return null
        if (!valid(lat, lon)) return null
        val display = place.strings("display_name").firstOrNull().orEmpty()
        val parts = display.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val title =
            place.strings("name").firstOrNull()?.takeIf { it.isNotBlank() } ?: parts.firstOrNull() ?: return null
        val rest = if (parts.firstOrNull() == title) parts.drop(1) else parts
        // The first few places say where it is, the last one says which country.
        val where =
            if (rest.size > NOMINATIM_TITLE_PARTS + 1) {
                rest.take(NOMINATIM_TITLE_PARTS) + rest.last()
            } else {
                rest
            }
        // In French since P29.10 (« Château fort », not « castle »).
        val category = OsmTypes.french(place.strings("type").firstOrNull())
        return SearchResult(
            title = title,
            subtitle = where.joinToString(", "),
            lat = lat,
            lon = lon,
            source = SearchSource.OSM,
            category = category,
            bounds = nominatimBounds(place),
        )
    }

    /** `boundingbox` is [south, north, west, east] as strings; the tiny box of a point or a building is dropped. */
    private fun nominatimBounds(place: JsonObject): Bounds? {
        val box =
            (place["boundingbox"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            }
        if (box == null || box.size != 4) return null
        val (south, north, west, east) = box
        if (!(valid(south, west) && valid(north, east))) return null
        val framed =
            east > west && north > south && (east - west >= MIN_SPAN_DEGREES || north - south >= MIN_SPAN_DEGREES)
        return if (framed) Bounds(west, south, east, north) else null
    }

    private fun valid(
        lat: Double,
        lon: Double,
    ): Boolean = lat in -MAX_LAT..MAX_LAT && lon in -MAX_LON..MAX_LON

    /** A field that is a string or an array of strings, as the IGN index for addresses and for places differ. */
    private fun JsonObject.strings(key: String): List<String> =
        when (val value = this[key]) {
            is JsonPrimitive -> listOfNotNull(value.contentOrNull)
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.let {
            it.doubleOrNull
                ?: it.contentOrNull?.toDoubleOrNull()
        }
}
