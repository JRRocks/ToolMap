package fr.toolmap.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On a phone, `Regex` is backed by ICU, which is stricter than the JDK the tests run on: a pattern such as
 * `\{\$([^}]+)}` compiles here and throws `PatternSyntaxException` on Android (the start-up crash of 2026-10-03, in
 * `TileUrlBuilder`'s static initializer). This test reads the regular expressions written in the main sources of
 * every module and rejects what ICU may refuse.
 */
class RegexPortabilityTest {
    @Test
    fun `the checker knows what ICU refuses`() {
        assertNotNull(icuProblem("""\{\$([^}]+)}"""), "the pattern of the crash")
        assertNotNull(icuProblem("""\{[$]param:([^}]+)}"""))
        assertNotNull(icuProblem("""a{b"""), "a brace that is not a quantifier")
        assertNotNull(icuProblem("""[^}]"""), "braces are escaped even in a class (stricter than needed)")
        assertNotNull(icuProblem("""[a--b]"""), "ICU reads -- in a class as a set difference")
        assertNull(icuProblem("""\{\$([^\}]+)\}"""))
        assertNull(icuProblem("""\{[$]param:([^\}]+)\}"""))
        assertNull(icuProblem("""\d{1,5}\s*[DWMYdwmy]"""))
        assertNull(icuProblem("""[0-9a-f]{64}"""))
        assertNull(icuProblem("""^(?:MGRS)?\s*((?:\d\s*){0,10})$"""))
        assertNull(icuProblem("""[^\p{L}\p{N}._-]+"""))
        assertNull(icuProblem("""a{2,}"""))
        assertNull(icuProblem("""<!--.*?-->"""))
        assertNull(icuProblem("""[]a]"""), "a ] first in a class is a literal")
        assertNull(icuProblem("""\Q{}\E"""))
    }

    @Test
    fun `kotlin literals are read as the compiler reads them`() {
        val source =
            """
            val a = Regex(""${'"'}\{\$([^\}]+)\}""${'"'})
            val b = Regex("\\{\\\${'$'}x\\}", RegexOption.IGNORE_CASE)
            val c = Regex(
                "[\\\\/:*?\"<>|]+",
            )
            val d = "\\b${'$'}word\\b".toRegex()
            val e = Regex(""${'"'}module\s*=\s*"([^"]+)""${'"'}")
            """.trimIndent()
        assertEquals(
            listOf(
                1 to """\{\$([^\}]+)\}""",
                2 to """\{\${'$'}x\}""",
                3 to """[\\/:*?"<>|]+""",
                6 to """\bx\b""",
                7 to """module\s*=\s*"([^"]+)"""",
            ),
            regexLiterals(source).map { it.line to it.pattern },
        )
    }

    @Test
    fun `every regular expression of the app compiles on Android`() {
        val dirs = requireNotNull(System.getProperty("main.sources")) { "main.sources not set by Gradle" }
        val found =
            dirs
                .split(File.pathSeparator)
                .map(::File)
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
                .flatMap { file -> regexLiterals(file.readText()).map { file.name to it } }
        assertTrue(found.size >= MIN_PATTERNS, "only ${found.size} patterns found: is the source path right?")
        assertTrue(found.any { (file, _) -> file == "TileUrlBuilder.kt" })
        val problems =
            found.mapNotNull { (file, literal) ->
                icuProblem(literal.pattern)?.let { "$file:${literal.line}: $it in ${literal.pattern}" }
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private data class Literal(
        val line: Int,
        val pattern: String,
    )

    private companion object {
        const val MIN_PATTERNS = 30
        const val HEX = 16
        val QUANTIFIER = Regex("""\{\d+(?:,\d*)?\}""")
        const val RAW = """"{3}(.*?)"{3}(?!")"""
        const val ESCAPED = """"((?:[^"\\\n]|\\.)*)""""
        val CALL =
            Regex("""(?:\bRegex|\bPattern\.compile)\(\s*(?:$RAW|$ESCAPED)""", RegexOption.DOT_MATCHES_ALL)
        val TO_REGEX = Regex("""(?:"{3}([^\n]*?)"{3}(?!")|$ESCAPED)\s*\.toRegex\(""")

        /**
         * Why ICU may refuse [pattern], or null. Stricter than ICU: every brace that is neither a quantifier
         * (`{n}`, `{n,}`, `{n,m}`) nor part of an escape such as `\p{L}` must be escaped, even inside a class.
         */
        fun icuProblem(pattern: String): String? {
            var i = 0
            var classDepth = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\\' -> {
                        val next = pattern.getOrNull(i + 1) ?: return "a trailing backslash"
                        i = escapeEnd(pattern, i, next) ?: return "an unclosed \\$next{ at index $i"
                        continue
                    }

                    c == '[' -> {
                        classDepth++
                        i++
                        if (pattern.getOrNull(i) == '^') i++
                        if (pattern.getOrNull(i) == ']') i++ // a literal ] in both engines
                        continue
                    }

                    c == ']' && classDepth > 0 -> {
                        classDepth--
                    }

                    c == '-' && classDepth > 0 && pattern.getOrNull(i + 1) == '-' -> {
                        return "-- in a class at index $i"
                    }

                    c == '{' && classDepth == 0 && QUANTIFIER.matchAt(pattern, i) != null -> {
                        i = QUANTIFIER.matchAt(pattern, i)!!.range.last + 1
                        continue
                    }

                    c == '{' || c == '}' -> {
                        return "an unescaped $c at index $i"
                    }
                }
                i++
            }
            return null
        }

        /** Where the escape at [start] ends: one character, `\Q...\E`, `\p{...}` and the like; null when unclosed. */
        private fun escapeEnd(
            pattern: String,
            start: Int,
            next: Char,
        ): Int? =
            when {
                next == 'Q' -> {
                    pattern.indexOf("\\E", start + 2).let { if (it < 0) pattern.length else it + 2 }
                }

                next in "pPNx" && pattern.getOrNull(start + 2) == '{' -> {
                    pattern.indexOf('}', start + 3).takeIf { it >= 0 }?.plus(1)
                }

                else -> {
                    start + 2
                }
            }

        /** The patterns given as a string literal to `Regex(...)`, `Pattern.compile(...)` or `.toRegex()`. */
        fun regexLiterals(source: String): List<Literal> =
            (CALL.findAll(source) + TO_REGEX.findAll(source))
                .map { match ->
                    val raw = match.groups[1]?.value
                    val pattern = raw?.let { templates(it) } ?: escaped(match.groupValues[2])
                    Literal(source.substring(0, match.range.first).count { it == '\n' } + 1, pattern)
                }.sortedBy { it.line }
                .toList()

        /** A raw string as the compiler builds it; a template stands for a plain word. */
        fun templates(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) i = appendTemplateOrChar(text, i, out)
            return out.toString()
        }

        fun escaped(text: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                if (text[i] != '\\') {
                    i = appendTemplateOrChar(text, i, out)
                    continue
                }
                when (val next = text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'u' -> out.append(text.substring(i + 2, i + 6).toInt(HEX).toChar())
                    else -> out.append(next) // \\ \" \' \$
                }
                i += if (text[i + 1] == 'u') 6 else 2
            }
            return out.toString()
        }

        private fun appendTemplateOrChar(
            text: String,
            i: Int,
            out: StringBuilder,
        ): Int {
            val next = text.getOrNull(i + 1)
            return when {
                text[i] != '$' || next == null -> {
                    out.append(text[i])
                    i + 1
                }

                next == '{' -> {
                    val end = text.indexOf('}', i)
                    out.append(if (text.substring(i + 2, end) == "'$'") "$" else "x")
                    end + 1
                }

                next.isLetter() || next == '_' -> {
                    out.append('x')
                    var end = i + 1
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    end
                }

                else -> {
                    out.append('$')
                    i + 1
                }
            }
        }
    }
}
