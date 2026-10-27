package fr.toolmap.core.aqx

import fr.toolmap.core.source.ClearColor
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource

/**
 * Writes sources as an `.aqx` document that [AqxParser] (and AlpineQuest) can read back: the "level" form
 * `<zoom-levels z="...">` with its settings as children. Only what the model keeps is written: a source
 * flagged unsupported (custom projection) loses its projection elements.
 */
object AqxWriter {
    const val VERSION = 11

    /**
     * @param redactedParams parameter names written with an empty value (API keys, when sharing a file).
     */
    fun write(
        sources: List<TileSource>,
        name: String? = null,
        description: String? = null,
        redactedParams: Set<String> = emptySet(),
    ): String =
        buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<aqx version="$VERSION">""")
            name?.let { appendLine("  <name>${escape(it)}</name>") }
            description?.let { appendLine("  <description>${escape(it)}</description>") }
            for (source in sources) {
                appendLine()
                writeSource(source, redactedParams)
            }
            appendLine("</aqx>")
        }

    private fun StringBuilder.writeSource(
        source: TileSource,
        redacted: Set<String>,
    ) {
        val attributes =
            buildString {
                append(""" id="${escape(source.id)}"""")
                source.type?.let { append(""" type="${escape(it)}"""") }
                if (source.isLayer) append(""" layer="true"""")
                if (source.opacity != 100) append(""" opacity="${source.opacity}"""")
            }
        appendLine("  <source$attributes>")
        appendLine("    <name>${escape(source.name)}</name>")
        source.copyright?.let { appendLine("    <copyright>${escape(it)}</copyright>") }
        source.dataSource?.let { appendLine("    <data-source>${escape(it)}</data-source>") }
        source.region?.let { appendLine("    <region>${escape(it)}</region>") }
        if (source.outline.isNotEmpty()) {
            val text = source.outline.joinToString(" ") { "${number(it.lon)},${number(it.lat)}" }
            appendLine("    <outline>$text</outline>")
        }
        source.previewLocation?.let {
            appendLine("    <preview-location>${number(it.lon)},${number(it.lat)},${it.zoom}</preview-location>")
        }
        source.defaultLocation?.let {
            appendLine("    <default-location>${number(it.lon)},${number(it.lat)}</default-location>")
        }
        for ((key, value) in source.params) {
            val shown = if (key in redacted) "" else value
            appendLine("""    <param name="${escape(key)}">${cdata(shown)}</param>""")
        }
        for (level in source.levels) writeLevel(level)
        appendLine("  </source>")
    }

    private fun StringBuilder.writeLevel(level: LevelDef) {
        val insecure = if (level.insecure) """ insecure="true"""" else ""
        appendLine("""    <zoom-levels z="${level.zooms.toSpec()}"$insecure>""")
        level.referer?.let { appendLine("      <referer>${cdata(it)}</referer>") }
        level.userAgent?.let { appendLine("      <user-agent>${cdata(it)}</user-agent>") }
        level.cookie?.let { appendLine("      <cookie>${cdata(it)}</cookie>") }
        for ((name, value) in level.headers) appendLine("      <header>${cdata("$name: $value")}</header>")
        level.updateDelay?.let { appendLine("      <update-delay>${escape(it)}</update-delay>") }
        if (level.maxThreads != LevelDef.DEFAULT_MAX_THREADS) {
            appendLine("      <max-threads>${level.maxThreads}</max-threads>")
        }
        if (level.tileSize != LevelDef.DEFAULT_TILE_SIZE) appendLine("      <tiles-size>${level.tileSize}</tiles-size>")
        level.clearColor?.let { appendLine("      ${clearColor(it)}") }
        level.keyUrl?.let { appendLine("      <key-url>${cdata(it)}</key-url>") }
        if (level.bboxCrs != LevelDef.CRS_WGS84) appendLine("      <bbox-crs-code>${level.bboxCrs}</bbox-crs-code>")
        for (server in level.servers) appendLine("      <server>${cdata(server)}</server>")
        appendLine("    </zoom-levels>")
    }

    private fun clearColor(color: ClearColor): String {
        fun range(r: IntRange) = if (r.first == r.last) "${r.first}" else "${r.first}-${r.last}"
        return """<clear-color r="${range(color.r)}" g="${range(color.g)}" b="${range(color.b)}"/>"""
    }

    /** Shortest decimal text that parses back to the same value. */
    private fun number(value: Double): String =
        if (value ==
            Math.rint(value)
        ) {
            value.toLong().toString()
        } else {
            value.toString()
        }

    private fun escape(text: String): String =
        buildString {
            for (c in text) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

    /** CDATA keeps URLs readable (`&` in query strings); an embedded `]]>` is split across two sections. */
    private fun cdata(text: String): String = "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>"
}
