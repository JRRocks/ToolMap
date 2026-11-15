package fr.toolmap.core.hierarchy.sim

import org.junit.Assume
import java.io.File
import kotlin.test.Test

/**
 * The battery of tests by simulation of the « Opération » mode (P39.10, `docs/specs/group-hierarchy.md` § 21):
 * scenarios drawn from a seed, played by real [fr.toolmap.core.hierarchy.OperationNode]s on a simulated coordination
 * server and a rough network, then checked once the network has calmed down ([SimChecks]). The long series runs with
 * `-Ptoolmap.hierarchy.soak=true` (or a number of scenarios) and writes `core/build/hierarchy-soak.txt`; a failure is
 * shrunk to the shortest list of steps that still fails the same way, and given with its seed.
 */
class OperationSimulationTest {
    /** The long series: thousands of scenarios of every size; only with `-Ptoolmap.hierarchy.soak=…`. */
    @Test
    fun `the long series`() {
        val soak = System.getProperty("toolmap.hierarchy.soak").orEmpty()
        Assume.assumeTrue("long series only with -Ptoolmap.hierarchy.soak=true", soak.isNotEmpty() && soak != "false")
        val count = soak.toIntOrNull() ?: 2_000
        val out = File(System.getProperty("user.dir"), "build/hierarchy-soak.txt")
        val categories = LinkedHashMap<String, MutableList<SimResult>>()
        var steps = 0L
        var virtual = 0L
        val t0 = System.currentTimeMillis()
        // Three scenarios at once (each one is self-contained; the problems go to the thread's own: SimSink).
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val known = LinkedHashMap<String, Int>()
        var done = 0
        val futures =
            (0 until count).map { i ->
                pool.submit<SimResult> { Scenarios.run(0x50AC_0000L + i, SOAK_SETUPS[i % SOAK_SETUPS.size]) }
            }
        for (f in futures) {
            val result = f.get()
            done++
            steps += result.steps.size
            virtual += result.stats.virtualMs
            for ((k, v) in result.stats.known) known[k] = (known[k] ?: 0) + v
            for (cat in result.failures.map { it.category }.toSet()) categories.getOrPut(cat) { ArrayList() } += result
            if (done % 50 == 0) {
                val wall = System.currentTimeMillis() - t0
                out.writeText(summary(done, steps, virtual, wall, categories, known, shrink = false))
            }
        }
        pool.shutdown()
        out.writeText(summary(count, steps, virtual, System.currentTimeMillis() - t0, categories, known, shrink = true))
        println(out.readText())
    }

    /**
     * One scenario told in full, to look at a seed:
     * `-Ptoolmap.hierarchy.explore=<seed>:<phones>:<steps>[:calm|ordered][:nopos][:profile]`
     * (several separated by commas); writes `core/build/hierarchy-explore.txt`.
     */
    @Test
    fun `explore one scenario`() {
        val spec = System.getProperty("toolmap.hierarchy.explore").orEmpty()
        Assume.assumeTrue("only with -Ptoolmap.hierarchy.explore=…", spec.isNotEmpty())
        val out = StringBuilder()
        for (one in spec.split(',')) {
            val parts = one.split(':')
            val seed = java.lang.Long.decode(parts[0])
            val faults =
                when (parts.getOrNull(3)) {
                    "calm" -> Faults.CALM
                    "ordered" -> Faults.ORDERED_ROUGH
                    else -> Faults.ROUGH
                }
            val setup =
                SimSetup(
                    parts.getOrNull(1)?.toInt() ?: 8,
                    parts.getOrNull(2)?.toInt() ?: 100,
                    faults,
                    positions = "nopos" !in parts,
                )
            val profile = "profile" in parts
            val samples = HashMap<String, Int>()
            val target = Thread.currentThread()
            val sampler =
                if (!profile) {
                    null
                } else {
                    Thread {
                        while (!Thread.currentThread().isInterrupted) {
                            val frames = target.stackTrace
                            val top =
                                frames.firstOrNull { f ->
                                    f.className.startsWith("fr.toolmap") || f.className.startsWith("sun.security") ||
                                        f.className.startsWith("java.security") ||
                                        f.className.startsWith("javax.crypto")
                                }
                            val ours = frames.firstOrNull { it.className.startsWith("fr.toolmap") }
                            if (top != null) {
                                val here = "${top.className.substringAfterLast('.')}.${top.methodName}"
                                val key = "$here < ${ours?.className?.substringAfterLast('.')}.${ours?.methodName}"
                                samples[key] = (samples[key] ?: 0) + 1
                            }
                            try {
                                Thread.sleep(5)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }.also {
                        it.isDaemon = true
                        it.start()
                    }
                }
            val result = Scenarios.run(seed, setup)
            sampler?.interrupt()
            sampler?.join()
            if (profile) {
                val top = samples.entries.sortedByDescending { it.value }.take(25)
                out.appendLine("  profile (5 ms samples): " + top.joinToString("\n    ") { "${it.value} ${it.key}" })
            }
            out.appendLine(result.describe())
            val st = result.stats
            out.appendLine(
                "  ${st.virtualMs / 60_000} min simulated in ${st.wallMs} ms, cpu ${st.cpuNs / 1_000_000} ms, " +
                    "slowest call " +
                    "${st.slowestCallNs / 1_000_000} ms, ops ${st.opsIssued} " +
                    "(+${st.opsRefusedLocally} refused here), shares " +
                    "${st.sharesSent}, members ${st.members}, survivors ${st.survivors}, " +
                    "dissolved ${st.dissolved}, probes ${st.probes}",
            )
            out.appendLine(
                "  expected problems: ${st.expectedProblems} by phone ${st.problemsByPhone}, strayed ${st.strayed}, " +
                    "known cases ${st.known}",
            )
            for (line in result.phones) out.appendLine("  $line")
            for (line in result.trail) out.appendLine("    $line")
        }
        File(System.getProperty("user.dir"), "build/hierarchy-explore.txt").writeText(out.toString())
        println(out)
    }

    private fun summary(
        runs: Int,
        steps: Long,
        virtualMs: Long,
        wallMs: Long,
        categories: Map<String, List<SimResult>>,
        known: Map<String, Int>,
        shrink: Boolean,
    ): String =
        buildString {
            appendLine(
                "P39.10 long series: $runs scenarios, $steps steps, ${virtualMs / 3_600_000} h simulated, " +
                    "${wallMs / 1000} s",
            )
            appendLine("failures by category: ${categories.mapValues { it.value.size }}")
            appendLine("known cases left to the user (phones): $known")
            for ((cat, results) in categories) {
                val first = results.first()
                appendLine()
                val seed = java.lang.Long.toHexString(first.seed)
                appendLine("== $cat: ${results.size} scenarios; first: seed=0x$seed ${first.setup}")
                val shown = if (shrink) Scenarios.shrink(first, cat, budget = 120) else first
                appendLine(shown.describe())
                if (shrink) {
                    appendLine("  shortest failing scenario (${shown.steps.size} steps):")
                    for (line in shown.trail) appendLine("    $line")
                }
            }
        }

    companion object {
        val SOAK_SETUPS =
            listOf(
                SimSetup(phones = 5, steps = 80),
                SimSetup(phones = 8, steps = 120, faults = Faults.ORDERED_ROUGH),
                SimSetup(phones = 12, steps = 160),
                SimSetup(phones = 6, steps = 100, faults = Faults.CALM),
                SimSetup(phones = 18, steps = 160, positions = false),
                SimSetup(phones = 30, steps = 200, positions = false, faults = Faults.ORDERED_ROUGH),
            )
    }
}
