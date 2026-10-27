package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser

/** Sources shipped with the app, defined in the classpath resource `builtin-sources.aqx` (AQX syntax). */
object BuiltinSources {
    const val RESOURCE = "/builtin-sources.aqx"

    /** @throws IllegalStateException if the resource is missing or is not a valid AQX file (a packaging bug). */
    fun load(): List<TileSource> {
        val stream =
            BuiltinSources::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing resource $RESOURCE")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return AqxParser.parse(text).file.sources
    }
}
