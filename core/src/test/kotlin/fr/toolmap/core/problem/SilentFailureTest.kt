package fr.toolmap.core.problem

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule « aucune erreur silencieuse » (ADR-116, `CLAUDE.md` § Conventions): this test reads the main sources of every
 * module and rejects any failure the code catches without telling the person who debugs it.
 *
 * - `runCatching` is not used: [catchReported] or [catchExpected] say whether a failure is a bug or not.
 * - A `catch` block throws, hands the exception on (`Result.failure(e)`, `onError(e)`; a call to `Log` does not count),
 *   or calls `Problems.report` / `Problems.unexpected` / `Problems.expected`. A catch-all (`Exception`, `Throwable`,
 *   `RuntimeException`, `Error`) may hide a bug: it throws, hands on or reports, never only `Problems.expected`.
 * - A Flow `.catch { }` throws or calls `Problems`.
 * - The place given to `Problems`, `catchReported` and `catchExpected` is a literal such as `"tiles.decode"`.
 */
class SilentFailureTest {
    @Test
    fun `the checker knows a silent failure`() {
        fun problems(code: String) = SilentFailures.check("A.kt", code).map { it.substringAfter(": ") }

        assertEquals(1, problems("val x = runCatching { f() }.getOrNull()").size, "runCatching")
        assertEquals(1, problems("try { f() } catch (e: IOException) { null }").size, "swallowed")
        assertEquals(1, problems("try { f() } catch (_: IOException) { }").size, "empty")
        assertEquals(1, problems("try { f() } catch (e: IOException) { Log.w(TAG, \"x\", e) }").size, "logcat only")
        assertEquals(
            1,
            problems("try { f() } catch (e: Exception) { Problems.expected(\"a.b\", e) }").size,
            "catch-all",
        )
        assertEquals(1, problems("flow.catch { emit(0) }").size, "flow")
        assertEquals(1, problems("try { f() } catch (e: IOException) { Problems.expected(where, e) }").size, "place")
        assertEquals(1, problems("catchReported(\"a.\$b\") { f() }").size, "place built from data")
        assertEquals(1, problems("try { f() } catch (e: IOException) { show(e.message) }").size, "message only")

        assertEquals(
            emptyList(),
            problems("try { f() } catch (e: IOException) { Problems.expected(\"a.b\", e); null }"),
        )
        assertEquals(emptyList(), problems("try { f() } catch (e: Exception) { Problems.report(\"a.b\", e) }"))
        assertEquals(emptyList(), problems("try { f() } catch (e: CancellationException) { throw e }"))
        assertEquals(emptyList(), problems("try { f() } catch (e: IOException) { return Result.failure(e) }"))
        assertEquals(emptyList(), problems("try { f() } catch (e: Exception) {\n cont.resumeWithException(e)\n}"))
        assertEquals(emptyList(), problems("val a = catchReported(\"a.b\") { f() }.getOrNull()"))
        assertEquals(emptyList(), problems("catchExpected(\n    \"a.b\",\n    IOException::class,\n) { f() }"))
        assertEquals(emptyList(), problems("flow.catch { Problems.expected(\"a.b\", it); emit(0) }"))
        assertEquals(emptyList(), problems("val s = \"runCatching catch (e: X) { }\" // runCatching { }"))
        assertEquals(emptyList(), problems("/* try { } catch (e: X) { } */ val a = 1"))
        assertEquals(emptyList(), problems("val s = \"\"\"catch (e: X) { }\"\"\""))
        assertEquals(emptyList(), problems("fun catchUp() {}\nval c = 'c'"))
    }

    @Test
    fun `no failure of the app is silent`() {
        val dirs = requireNotNull(System.getProperty("main.sources")) { "main.sources not set by Gradle" }
        val files =
            dirs
                .split(File.pathSeparator)
                .map(::File)
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue(files.size >= MIN_FILES, "only ${files.size} source files found: is the source path right?")
        val root = File(dirs.split(File.pathSeparator).first()).parentFile.parentFile.parentFile
        val problems =
            files
                .filterNot { it.name == "Problems.kt" && it.path.contains("core${File.separator}problem") }
                .flatMap { file -> SilentFailures.check(file.relativeTo(root).path, file.readText()) }
        assertTrue(problems.isEmpty(), "${problems.size} silent failures:\n" + problems.joinToString("\n"))
    }

    private companion object {
        const val MIN_FILES = 300
    }
}

/** The checker of [SilentFailureTest]: a light reading of Kotlin, enough for these few patterns. */
internal object SilentFailures {
    private val CATCH = Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*([^)]*)\)\s*\{""")
    private val FLOW_CATCH = Regex("""\.catch\s*\{""")
    private val RUN_CATCHING = Regex("""\brunCatching\b""")
    private val PROBLEMS_CALL = Regex("""\bProblems\s*\.\s*(report|unexpected|expected)\s*\(""")
    private val REPORT_CALL = Regex("""\bProblems\s*\.\s*(report|unexpected)\s*\(""")
    private val THROW = Regex("""\bthrow\b""")

    /** Calls that do not hand the exception on: logcat (the debugger may not look), and Problems (checked apart). */
    private val NOT_HANDING_ON = Regex("""\b(Log|Problems)\s*\.\s*\w+\s*\(""")
    private val PLACE_CALL =
        Regex("""(\bProblems\s*\.\s*(report|unexpected|expected)|\bcatchReported|\bcatchExpected)\s*\(""")
    private val PLACE = Regex("""^"[a-z][a-z0-9_]*(\.[a-z0-9_]+)*"""")
    private val CATCH_ALL = setOf("Exception", "Throwable", "RuntimeException", "Error")

    fun check(
        name: String,
        source: String,
    ): List<String> {
        val code = blank(source)
        val out = mutableListOf<String>()

        fun line(index: Int) = source.substring(0, index).count { it == '\n' } + 1

        fun add(
            index: Int,
            what: String,
        ) = out.add("$name:${line(index)}: $what")

        for (m in RUN_CATCHING.findAll(code)) add(m.range.first, "runCatching: use catchReported or catchExpected")
        for (m in CATCH.findAll(code)) {
            val variable = m.groupValues[1]
            val type = m.groupValues[2].trim().substringAfterLast('.')
            val body = body(code, m.range.last) ?: continue
            val passes = variable != "_" && passesOn(body, variable)
            val throws = THROW.containsMatchIn(body)
            when {
                throws || passes || REPORT_CALL.containsMatchIn(body) -> {
                    Unit
                }

                PROBLEMS_CALL.containsMatchIn(body) && type in CATCH_ALL -> {
                    add(m.range.first, "catch ($type) only expected: a catch-all may hide a bug, report it")
                }

                PROBLEMS_CALL.containsMatchIn(body) -> {
                    Unit
                }

                else -> {
                    add(m.range.first, "catch ($type) is silent: throw, hand it on or call Problems")
                }
            }
        }
        for (m in FLOW_CATCH.findAll(code)) {
            val body = body(code, m.range.last) ?: continue
            if (!THROW.containsMatchIn(body) && !PROBLEMS_CALL.containsMatchIn(body)) {
                add(m.range.first, ".catch { } is silent: call Problems")
            }
        }
        for (m in PLACE_CALL.findAll(code)) {
            // Read in the source itself: the blanked code has lost the literal.
            val rest = source.substring(m.range.last + 1).trimStart()
            if (!PLACE.containsMatchIn(rest)) add(m.range.first, "the place must be a literal like \"tiles.decode\"")
        }
        return out
    }

    /** The variable is used as a value (not only `e.message`), outside the calls to `Log` and `Problems`. */
    private fun passesOn(
        body: String,
        variable: String,
    ): Boolean {
        var text = body
        for (m in NOT_HANDING_ON.findAll(body).toList().asReversed()) {
            val end = closing(body, m.range.last, '(', ')') ?: continue
            text = text.substring(0, m.range.first) + " ".repeat(end - m.range.first + 1) + text.substring(end + 1)
        }
        return Regex("""(?<![\w.$])${Regex.escape(variable)}(?![\w])(?!\s*(\.|\?\.|::|!!))""").containsMatchIn(text)
    }

    /** The text between the `{` at [open] and its closing brace. */
    private fun body(
        code: String,
        open: Int,
    ): String? = closing(code, open, '{', '}')?.let { code.substring(open + 1, it) }

    private fun closing(
        code: String,
        open: Int,
        start: Char,
        end: Char,
    ): Int? {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                start -> depth++
                end -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** [source] with its comments and the contents of its string and char literals turned into spaces. */
    fun blank(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        val n = source.length

        fun clear(
            from: Int,
            to: Int,
        ) {
            for (k in from until minOf(to, n)) if (out[k] != '\n') out[k] = ' '
        }
        while (i < n) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) n else it }
                    clear(i, end)
                    i = end
                }

                source.startsWith("/*", i) -> {
                    var depth = 0
                    var k = i
                    while (k < n) {
                        if (source.startsWith("/*", k)) {
                            depth++
                            k += 2
                        } else if (source.startsWith("*/", k)) {
                            depth--
                            k += 2
                            if (depth == 0) break
                        } else {
                            k++
                        }
                    }
                    clear(i, k)
                    i = k
                }

                source.startsWith("\"\"\"", i) -> {
                    var end = source.indexOf("\"\"\"", i + 3)
                    end = if (end < 0) n else end + 3
                    while (end < n && source[end] == '"') end++
                    clear(i + 1, end - 1)
                    i = end
                }

                c == '"' -> {
                    var k = i + 1
                    while (k < n && source[k] != '"' && source[k] != '\n') k += if (source[k] == '\\') 2 else 1
                    clear(i + 1, k)
                    i = k + 1
                }

                c == '\'' -> {
                    var k = i + 1
                    while (k < n && source[k] != '\'' && source[k] != '\n') k += if (source[k] == '\\') 2 else 1
                    clear(i + 1, k)
                    i = k + 1
                }

                else -> {
                    i++
                }
            }
        }
        return out.toString()
    }
}
