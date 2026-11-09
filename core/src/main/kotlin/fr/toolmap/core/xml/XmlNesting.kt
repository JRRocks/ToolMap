package fr.toolmap.core.xml

/**
 * How deep the elements of an XML text are nested, read from the text alone, before a parser that builds a tree meets
 * it: Android's own DOM builder calls itself once per level, and a few thousand elements inside one another (20 KB of
 * text) overflowed its stack and took the app down (P36 audit). The JDK's builder, the one of the unit tests, does not.
 */
object XmlNesting {
    /** Whether elements are nested deeper than [limit] somewhere in [text]. A text that is not XML is not judged. */
    fun exceeds(
        text: String,
        limit: Int,
    ): Boolean {
        var depth = 0
        var i = 0
        while (true) {
            val open = text.indexOf('<', i)
            if (open < 0) return false
            i =
                when {
                    text.startsWith("<!--", open) -> {
                        after(text, "-->", open + COMMENT_OPEN)
                    }

                    text.startsWith("<![CDATA[", open) -> {
                        after(text, "]]>", open + CDATA_OPEN)
                    }

                    text.startsWith("<?", open) -> {
                        after(text, "?>", open + 2)
                    }

                    text.startsWith("<!", open) -> {
                        after(text, ">", open + 2)
                    }

                    text.startsWith("</", open) -> {
                        after(text, ">", open + 2).also { depth-- }
                    }

                    else -> {
                        val close = endOfStartTag(text, open + 1)
                        if (close >= 0 && text[close - 1] != '/' && ++depth > limit) return true
                        close + 1
                    }
                }
            // What was opened does not end: the parser refuses the text.
            if (i <= 0) return false
        }
    }

    /** The index after the next [end] from [from], 0 when there is none. */
    private fun after(
        text: String,
        end: String,
        from: Int,
    ): Int = text.indexOf(end, from).let { if (it < 0) 0 else it + end.length }

    /** The index of the `>` that ends a start tag, the `>` of its quoted attribute values left aside; -1 when none. */
    private fun endOfStartTag(
        text: String,
        from: Int,
    ): Int {
        var quote = NONE
        for (j in from until text.length) {
            val c = text[j]
            when {
                quote != NONE -> if (c == quote) quote = NONE
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return j
            }
        }
        return -1
    }

    private const val NONE = '\u0000'
    private const val COMMENT_OPEN = 4
    private const val CDATA_OPEN = 9
}
