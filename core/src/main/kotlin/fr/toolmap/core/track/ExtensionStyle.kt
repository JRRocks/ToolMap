package fr.toolmap.core.track

/**
 * Line colour that other apps write in GPX extensions: Garmin `gpxx:DisplayColor` (a colour name) and the
 * `color` element of OsmAnd and others (`#RRGGBB` or `#AARRGGBB`).
 */
internal object ExtensionStyle {
    private val GARMIN: Map<String, Int> =
        mapOf(
            "black" to 0xFF000000.toInt(),
            "darkred" to 0xFF8B0000.toInt(),
            "darkgreen" to 0xFF006400.toInt(),
            "darkyellow" to 0xFF808000.toInt(),
            "darkblue" to 0xFF00008B.toInt(),
            "darkmagenta" to 0xFF8B008B.toInt(),
            "darkcyan" to 0xFF008B8B.toInt(),
            "lightgray" to 0xFFD3D3D3.toInt(),
            "darkgray" to 0xFFA9A9A9.toInt(),
            "red" to 0xFFFF0000.toInt(),
            "green" to 0xFF00FF00.toInt(),
            "yellow" to 0xFFFFFF00.toInt(),
            "blue" to 0xFF0000FF.toInt(),
            "magenta" to 0xFFFF00FF.toInt(),
            "cyan" to 0xFF00FFFF.toInt(),
            "white" to 0xFFFFFFFF.toInt(),
        )

    private val COLOR_ELEMENT =
        Regex("""<(?:[\w.-]+:)?(?:DisplayColor|color)\b[^>]*>\s*([^<]+?)\s*</""", RegexOption.IGNORE_CASE)

    fun fromExtras(extras: List<RawXml>): PathStyle? {
        for (raw in extras) {
            if (raw.localName != "extensions") continue
            val value = COLOR_ELEMENT.find(raw.xml)?.groupValues?.get(1) ?: continue
            val color = parseColor(value) ?: continue
            return PathStyle(colorArgb = color)
        }
        return null
    }

    fun parseColor(value: String): Int? {
        val v = value.trim()
        GARMIN[v.lowercase()]?.let { return it }
        val hex = v.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val n = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000 or n).toInt() else n.toInt()
    }
}
