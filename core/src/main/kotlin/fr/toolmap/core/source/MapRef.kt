package fr.toolmap.core.source

/**
 * A level of a multi-zoom map that shows another map of the catalogue instead of calling a server (ToolMap extension
 * of the `<server>` value, like [LevelDef.LOCAL_ONLY_SERVER]; ADR-031):
 * - `toolmap-map:ign-maps` shows the tiles of `ign-maps` at the displayed zoom;
 * - `toolmap-map:ign-maps?z=15` always uses its zoom-15 tiles, shrunk or enlarged to the displayed zoom
 *   ("forced zoom", what an AlpineQuest user gets by leaving zoom levels out of a `<zoom-values>` list).
 *
 * The tiles stay those of the referenced map: they are stored once, in its storage (no second download).
 */
data class MapRef(
    val sourceId: String,
    /** Zoom of the tiles to use whatever the displayed zoom; `null` = the displayed zoom. */
    val tileZoom: Int? = null,
) {
    init {
        require(sourceId.isNotEmpty()) { "A map reference needs a map id" }
        require(tileZoom == null || tileZoom in 0..ZoomSet.MAX_ZOOM) { "Forced zoom out of range: $tileZoom" }
    }

    /** The `<server>` value that stores this reference. */
    fun toServer(): String = PREFIX + encode(sourceId) + (tileZoom?.let { "?$ZOOM_KEY=$it" } ?: "")

    companion object {
        const val PREFIX = "toolmap-map:"
        private const val ZOOM_KEY = "z"
        private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-"
        private const val HEX_RADIX = 16

        fun isReference(server: String): Boolean = server.startsWith(PREFIX)

        /** The reference written in [server], or `null` when it is an ordinary URL (or a malformed reference). */
        fun parse(server: String): MapRef? {
            if (!isReference(server)) return null
            val body = server.removePrefix(PREFIX).trim()
            val id = decode(body.substringBefore('?')) ?: return null
            if (id.isEmpty()) return null
            var zoom: Int? = null
            val query = body.substringAfter('?', "")
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                if (key == ZOOM_KEY) zoom = value.toIntOrNull()?.takeIf { it in 0..ZoomSet.MAX_ZOOM } ?: return null
            }
            return MapRef(id, zoom)
        }

        private fun encode(id: String): String =
            buildString {
                for (byte in id.toByteArray(Charsets.UTF_8)) {
                    val c = (byte.toInt() and 0xFF).toChar()
                    if (c in SAFE) append(c) else append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }

        private fun decode(text: String): String? {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val value = text.substring(i + 1, i + 3).toIntOrNull(HEX_RADIX) ?: return null
                    out.write(value)
                    i += 3
                } else {
                    out.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
