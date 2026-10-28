package fr.toolmap.core.source

/**
 * The credits of the maps shown, on one short line (variant E of `docs/previews/p19-reticule.html`): each copyright
 * is cut at its separators, the « (c) », « Style : », « contributors » and licences in brackets are dropped,
 * OpenStreetMap becomes « OSM », and each name is kept once, in order: `© OSM · SRTM · OpenTopoMap · IGN`.
 */
object Credits {
    fun short(copyrights: List<String>): String {
        val names =
            copyrights
                .flatMap { it.split(SEPARATORS) }
                .map(::clean)
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
        return if (names.isEmpty()) "" else "© " + names.joinToString(" · ")
    }

    private fun clean(part: String): String {
        var s = part.replace(BRACKETS, " ").replace(NOISE, " ")
        s = s.replace(WHITESPACE, " ").trim()
        return if (s.equals("OpenStreetMap", ignoreCase = true)) "OSM" else s
    }

    private val SEPARATORS = Regex("[,|·;]")
    private val BRACKETS = Regex("\\([^)]*\\)")
    private val NOISE = Regex("(?i)©|\\bstyle\\s*:|\\bcontributors\\b|\\bcopyright\\b")
    private val WHITESPACE = Regex("\\s+")
}
