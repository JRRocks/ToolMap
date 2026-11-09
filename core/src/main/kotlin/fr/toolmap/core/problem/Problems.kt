package fr.toolmap.core.problem

import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/**
 * No silent failure (ADR-116, `docs/specs/problems.md`): every error the code catches goes through here, so that the
 * person debugging always finds it, while the user only sees the fallback the caller chose.
 *
 * - [report]: an error the code did not expect, that is a bug. Logged for the debugger (event log, logcat) and sent to
 *   GitHub as an issue by the app's sink.
 * - [unexpected]: the same for a state the code thought impossible, without an exception.
 * - [expected]: a failure the code expects and handles (no network, a server that refuses, a file the user picked that
 *   is not a GPX). Logged for the debugger, no issue.
 *
 * [where] is a short stable name of the place, dotted and lowercase like the event names (`tiles.decode`): it names
 * the problem in the logs and in the issue. It is always a literal, never built from data (`SilentFailureTest`).
 * The context pairs go to the local logs only; the issue keeps the numbers, booleans and enum values among them.
 */
object Problems {
    /** Where problems go. The app installs its own; the default prints them (JVM tests, before the app is up). */
    fun interface Sink {
        fun take(problem: Problem)
    }

    /** The sink of the JVM tests and of the very first moments of the process: standard error. */
    val stderr: Sink = Sink { problem -> problem.printTo(System.err) }

    @Volatile
    var sink: Sink = stderr

    /** The clock of the problems; tests set their own. */
    @Volatile
    var clock: () -> Long = System::currentTimeMillis

    /** True while this thread is inside the sink: a failure of the sink itself must not loop back into it. */
    private val inSink = ThreadLocal<Boolean>()

    /** An error the code did not expect (a bug): logged and sent as a GitHub issue. */
    fun report(
        where: String,
        error: Throwable,
        vararg context: Pair<String, Any?>,
    ) = take(Problem(where, error, expected = false, context = context.toList()))

    /** A state the code thought impossible, without an exception: logged and sent as a GitHub issue. */
    fun unexpected(
        where: String,
        message: String,
        vararg context: Pair<String, Any?>,
    ) = take(Problem(where, UnexpectedState(message), expected = false, context = context.toList()))

    /** A failure the code expects and handles: logged for the debugger, no issue. */
    fun expected(
        where: String,
        error: Throwable,
        vararg context: Pair<String, Any?>,
    ) = take(Problem(where, error, expected = true, context = context.toList()))

    private fun take(problem: Problem) {
        if (inSink.get() == true) {
            // The sink failed on its own problem (disk full, log down): standard error is the last resort.
            stderr.take(problem)
            return
        }
        inSink.set(true)
        try {
            sink.take(problem)
        } catch (e: Throwable) {
            stderr.take(problem)
            stderr.take(Problem("problems.sink", e, expected = false, context = emptyList()))
        } finally {
            inSink.set(false)
        }
    }

    /** Builds [Problem.timeMs] and [Problem.thread] at the time of the call. */
    internal fun now(): Long = clock()
}

/** One problem as the sink receives it. */
class Problem(
    val where: String,
    val error: Throwable,
    val expected: Boolean,
    val context: List<Pair<String, Any?>>,
    val timeMs: Long = Problems.now(),
    val thread: String = Thread.currentThread().name,
) {
    fun printTo(out: java.io.PrintStream) {
        val kind = if (expected) "expected" else "UNEXPECTED"
        val fields = context.filter { it.second != null }.joinToString(" ") { "${it.first}=${it.second}" }
        out.println("ToolMap problem ($kind) at $where [$thread] $fields".trimEnd())
        error.printStackTrace(out)
    }
}

/** A state the code thought impossible ([Problems.unexpected]): its stack trace says where it was found. */
class UnexpectedState(
    message: String,
) : IllegalStateException(message)

/**
 * Like [runCatching], for a block whose failure would be a bug: the failure is [Problems.report]ed (logged and sent as
 * an issue) and returned, so that the caller can fall back (`.getOrNull()`, `.getOrDefault(…)`). A coroutine's
 * cancellation is thrown again, never swallowed.
 */
inline fun <T> catchReported(
    where: String,
    block: () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Problems.report(where, e)
        Result.failure(e)
    }

/**
 * Like [runCatching], for a block that may fail in ways the code expects: a failure of one of the [expected] types
 * ([first] and [more], subclasses included) is logged as [Problems.expected]; any other failure is a bug and is
 * [Problems.report]ed. Both are returned for the caller to fall back. A coroutine's cancellation is thrown again.
 */
inline fun <T> catchExpected(
    where: String,
    first: KClass<out Throwable>,
    vararg more: KClass<out Throwable>,
    block: () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (isOneOf(e, first, more)) Problems.expected(where, e) else Problems.report(where, e)
        Result.failure(e)
    }

/** True when [error] is an instance of [first] or of one of [more]. */
fun isOneOf(
    error: Throwable,
    first: KClass<out Throwable>,
    more: Array<out KClass<out Throwable>>,
): Boolean = first.isInstance(error) || more.any { it.isInstance(error) }
