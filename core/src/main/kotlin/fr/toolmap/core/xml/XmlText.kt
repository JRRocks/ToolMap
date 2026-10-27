package fr.toolmap.core.xml

/** Escaping for the XML we write (GPX export, captured fragments). */
object XmlText {
    fun text(value: String): String = escape(value, quotes = false)

    fun attribute(value: String): String = escape(value, quotes = true)

    private fun escape(
        value: String,
        quotes: Boolean,
    ): String {
        if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it < ' ' }) return value
        val out = StringBuilder(value.length + 16)
        for (c in value) {
            when {
                c == '&' -> out.append("&amp;")

                c == '<' -> out.append("&lt;")

                c == '>' -> out.append("&gt;")

                c == '"' && quotes -> out.append("&quot;")

                // Control characters other than tab / newline / carriage return are not allowed in XML 1.0.
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> Unit

                c == '\n' && quotes -> out.append("&#10;")

                c == '\r' && quotes -> out.append("&#13;")

                c == '\t' && quotes -> out.append("&#9;")

                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
