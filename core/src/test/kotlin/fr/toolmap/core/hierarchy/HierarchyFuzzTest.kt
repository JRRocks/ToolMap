package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.problem.Problems
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fuzzing of the « Opération » decoders (P39.11, spec § 22): random bytes and mutations of valid frames, so that no
 * decoder ever throws anything but the expected exceptions, in bounded time, and never makes a big allocation from a
 * length it read. The seeded part runs in every `./gradlew check` (fixed seeds, ~90 s at most all together); the long
 * part runs only with `-Ptoolmap.hierarchy.fuzz=true`.
 */
class HierarchyFuzzTest {
    private val bugs = ArrayList<String>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun quietProblems() {
        // The decoders say refused inputs to whoever debugs; the fuzzing makes thousands, so the sink is silenced and
        // only real bugs (unexpected problems) are kept.
        Problems.sink = Problems.Sink { if (!it.expected) bugs.add(it.where + ": " + it.error) }
    }

    @AfterTest
    fun restore() {
        Problems.sink = previousSink
        assertTrue(bugs.isEmpty(), "bugs reported while fuzzing: $bugs")
    }

    @Test
    fun `random bytes and mutations never make a decoder throw the wrong thing - seeded`() {
        val targets = hierarchyTargets()
        val corpus = fuzzCorpus()
        // Fixed seeds: a failure replays the same way. Kept small so check stays fast.
        for (seed in 1L..4L) {
            val random = Random(seed)
            repeat(ITERATIONS_PER_SEED) {
                // Half random bytes of a random length, half a mutation of a valid frame.
                val bytes =
                    if (random.nextBoolean()) {
                        ByteArray(random.nextInt(MAX_RANDOM_BYTES)).also(random::nextBytes)
                    } else {
                        mutate(corpus[random.nextInt(corpus.size)], random)
                    }
                for (target in targets) exercise(target, bytes)
            }
        }
    }

    @Test
    fun `every valid corpus frame and every one-byte change of it decodes or is refused cleanly - seeded`() {
        val targets = hierarchyTargets()
        for (frame in fuzzCorpus()) {
            // The frame itself, then every single-byte value change, then every truncation.
            for (target in targets) exercise(target, frame)
            for (i in frame.indices) {
                for (value in listOf(0, 1, 0x7F, 0x80, 0xFF)) {
                    val changed = frame.copyOf().also { it[i] = value.toByte() }
                    for (target in targets) exercise(target, changed)
                }
            }
            for (cut in frame.indices) {
                val truncated = frame.copyOf(cut)
                for (target in targets) exercise(target, truncated)
            }
        }
    }

    /**
     * A tiny input that announces a huge count must not make an allocation proportional to that count: the wire
     * decoders either cap the count tightly or read each element from the input (bounded by its length). This feeds
     * each length-prefixed decoder a few bytes whose length field is pushed to the maximum, and checks the decode is
     * refused at once, in little time and with little memory.
     */
    @Test
    fun `a short input with a huge announced length is refused without a big allocation`() {
        // Each case announces a count at the cap the decoder allows, then stops: the count is reached (no earlier
        // check cuts it off), so a decoder that pre-sized the list to it would add megabytes. A bounded one adds
        // almost nothing, because each item reads bytes the input does not have.
        val ens = OpAuth.longBytes(FUZZ_ENSEMBLE)
        val cap = ByteWriter().u32((1 shl 20).toLong()).toByteArray() // the MAX_COUNT of HierarchySnapshot
        val cases =
            listOf(
                "HierarchyWire.decodeOps" to byteArrayOf(4, 2, -1, -1), // u16 count = 65535, capped at MAX_BATCH
                "HierarchyWire.decodeSummary" to byteArrayOf(4, 3) + ByteArray(16) + byteArrayOf(-1, -1),
                "NameBook.decode" to byteArrayOf(-1, -1),
                "OperationSummary.decode" to shortSummaryWithHugeChannels(),
                "HierarchySnapshot.decodeOps" to byteArrayOf(1, 1) + cap,
                "HierarchySnapshot.restoreKeys" to byteArrayOf(1, 2) + ens + cap,
                "HierarchySnapshot.restoreNames" to byteArrayOf(1, 3) + cap,
                "HierarchySnapshot.restoreObjects" to byteArrayOf(1, 4) + ens + readChannelBytes() + cap,
                "HierarchySnapshot.restoreMessages" to byteArrayOf(1, 5) + ens + readChannelBytes() + cap,
            )
        val byName = hierarchyTargets().associateBy { it.name }
        val runtime = Runtime.getRuntime()
        for ((name, bytes) in cases) {
            val target = byName.getValue(name)
            runtime.gc()
            Thread.sleep(10)
            runtime.gc()
            val before = runtime.totalMemory() - runtime.freeMemory()
            val start = System.nanoTime()
            exercise(target, bytes)
            val ms = (System.nanoTime() - start) / 1_000_000
            val grew = (runtime.totalMemory() - runtime.freeMemory()) - before
            assertTrue(ms < 500, "$name took $ms ms on ${bytes.size} bytes")
            // A decode that pre-sizes to 2^20 would add megabytes; a bounded one adds almost nothing.
            assertTrue(grew < MAX_ALLOC_BYTES, "$name grew the heap by $grew bytes on a ${bytes.size}-byte input")
        }
    }

    /** A channel count of 1 then one group channel: so restoreObjects/restoreMessages reach the inner item count. */
    private fun readChannelBytes(): ByteArray = ByteWriter().u32(1L).toByteArray() + Channel.unit(0x1234L).bytes()

    @Test
    fun `the long fuzzing runs when asked`() {
        if (!longFuzzEnabled()) {
            println("Long hierarchy fuzzing skipped (pass -Ptoolmap.hierarchy.fuzz=true to run it).")
            return
        }
        val targets = hierarchyTargets()
        val corpus = fuzzCorpus()
        var runs = 0L
        for (seed in 1L..LONG_SEEDS) {
            val random = Random(seed)
            repeat(LONG_ITERATIONS_PER_SEED) {
                val bytes =
                    when (random.nextInt(3)) {
                        0 -> {
                            ByteArray(random.nextInt(MAX_RANDOM_BYTES)).also(random::nextBytes)
                        }

                        1 -> {
                            mutate(corpus[random.nextInt(corpus.size)], random)
                        }

                        else -> {
                            var b = corpus[random.nextInt(corpus.size)]
                            repeat(1 + random.nextInt(4)) { b = mutate(b, random) }
                            b
                        }
                    }
                for (target in targets) exercise(target, bytes)
                runs++
            }
        }
        println("Long hierarchy fuzzing: $runs inputs × ${targets.size} decoders.")
    }

    private fun shortSummaryWithHugeChannels(): ByteArray {
        // A real OperationSummary with an empty engine summary, then a channel count of 0xFFFF.
        val empty = HierarchyWire.encodeSummary(fuzzSummary())
        return ByteWriter().u16(empty.size).bytes(empty).u64(0L).u16(0).u16(0xFFFF).toByteArray()
    }

    private fun fuzzSummary(): Summary =
        HierarchyReplica.create(OpAuth.memoryKeys(), Names.hide("Chef").hidden, Names.hide("Op").hidden, 7L).summary()

    companion object {
        private const val ITERATIONS_PER_SEED = 1_500
        private const val MAX_RANDOM_BYTES = 600
        private const val LONG_SEEDS = 40L
        private const val LONG_ITERATIONS_PER_SEED = 25_000
        private const val MAX_ALLOC_BYTES = 2L * 1024 * 1024
    }
}
