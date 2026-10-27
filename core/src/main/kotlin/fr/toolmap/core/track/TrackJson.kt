package fr.toolmap.core.track

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** JSON columns of the track database: small lists whose shape may grow, kept out of the schema. */
object TrackJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val waypoints = ListSerializer(Waypoint.serializer())
    private val links = ListSerializer(GpxLink.serializer())
    private val extras = ListSerializer(RawXml.serializer())
    private val namespaces = MapSerializer(String.serializer(), String.serializer())

    fun encodeWaypoints(list: List<Waypoint>): String = json.encodeToString(waypoints, list)

    fun decodeWaypoints(text: String?): List<Waypoint> = decode(text) { json.decodeFromString(waypoints, it) }

    fun encodeLinks(list: List<GpxLink>): String? = if (list.isEmpty()) null else json.encodeToString(links, list)

    fun decodeLinks(text: String?): List<GpxLink> = decode(text) { json.decodeFromString(links, it) }

    fun encodeExtras(list: List<RawXml>): String? = if (list.isEmpty()) null else json.encodeToString(extras, list)

    fun decodeExtras(text: String?): List<RawXml> = decode(text) { json.decodeFromString(extras, it) }

    fun encodeNamespaces(map: Map<String, String>): String? =
        if (map.isEmpty()) null else json.encodeToString(namespaces, map)

    fun decodeNamespaces(text: String?): Map<String, String> =
        if (text.isNullOrEmpty()) emptyMap() else json.decodeFromString(namespaces, text)

    private fun <T> decode(
        text: String?,
        read: (String) -> List<T>,
    ): List<T> = if (text.isNullOrEmpty()) emptyList() else read(text)
}
