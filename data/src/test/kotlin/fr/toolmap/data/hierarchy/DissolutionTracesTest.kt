package fr.toolmap.data.hierarchy

import fr.toolmap.core.backup.BackupPaths
import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.DissolutionProof
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.EpochKey
import fr.toolmap.core.hierarchy.InviteKeys
import fr.toolmap.core.hierarchy.OpAuth
import fr.toolmap.core.hierarchy.OperationInvite
import fr.toolmap.core.hierarchy.OperationLinkState
import fr.toolmap.core.hierarchy.ProofVerdict
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.experimental.xor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the traces of dissolved operations are kept (P39.9, ADR-131): one sealed file per operation, its own key in
 * the vault, wiped by itself a week after the dissolution, and what a stored operation leaves when it is dissolved.
 */
class DissolutionTracesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink
    private val vault = SoftwareKeyVault()
    private val clock = AtomicLong(1_700_000_000_000L)
    private val day = 24 * 3600_000L
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Problems.sink = Problems.Sink { problems.add(it) }
        root = File(temp.newFolder("files"), OperationStorage.FOLDER)
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        assertTrue(problems.all { it.expected }, "damage is not a bug: ${problems.map { it.where to it.error }}")
    }

    private fun storage(prefix: String = OperationStorage.ALIAS_PREFIX) =
        OperationStorage(root, vault, prefix, clock = clock::get)

    private val tracePrefix = OperationStorage.traceAliasPrefix(OperationStorage.ALIAS_PREFIX)

    /** The link state of [world]'s operation, as the transport keeps it. */
    private fun link(world: OperationWorld): OperationLinkState {
        val key = ByteArray(OperationInvite.KEY_BYTES).also(SecureRandom()::nextBytes)
        val epoch = EpochKey(key, InviteKeys.announce(world.creatorKeys, world.ensembleId, 1, key))
        val room = ByteArray(OperationInvite.ROOM_BYTES).also(SecureRandom()::nextBytes)
        val server = RelayConfig.of("https://example.invalid", "key")
        return OperationLinkState(world.ensembleId, room, server, listOf(epoch), null, null)
    }

    private fun dissolvedTrace(): DissolutionTrace {
        val world = OperationWorld(OpAuth.memoryKeys())
        world.must(Change.Dissolve)
        val proof = assertNotNull(DissolutionProof.of(world.replica))
        return DissolutionTrace.of(proof, link(world), clock.get())
    }

    @Test
    fun `a trace is kept sealed, under a key of its own, and reads back`() {
        val traces = storage().traces
        val trace = dissolvedTrace()
        assertEquals(trace, traces.keep(trace, clock.get()))
        val back = traces.list(clock.get()).single()
        assertContentEquals(trace.encode(), back.encode())
        assertEquals(ProofVerdict.VALID, back.proof.check(trace.ensembleId))
        assertEquals(1, vault.aliases(tracePrefix).size)
        assertTrue(vault.aliases(OperationStorage.ALIAS_PREFIX).isEmpty(), "no key that looks like an operation's")
        assertTrue(storage().ids().isEmpty(), "a trace is no operation")
        val file = File(root, DissolutionTraces.FOLDER).listFiles().orEmpty().single()
        assertFalse(containsBytes(file.readBytes(), trace.room), "sealed")
        // Under the folder of the operations: in no backup (ADR-128, point 8).
        assertNull(BackupPaths.areaOf(file.relativeTo(root.parentFile.parentFile).invariantSeparatorsPath))
        // One per operation: the one kept first stays, with its date.
        clock.addAndGet(day)
        val later =
            DissolutionTrace(trace.ensembleId, clock.get(), 5L, trace.room, trace.server, trace.keys, trace.proof)
        assertEquals(trace.dissolvedAtMs, traces.keep(later, clock.get())?.dissolvedAtMs)
        assertEquals(1, traces.ids().size)
    }

    @Test
    fun `a trace goes by itself a week after the dissolution, key first`() {
        val traces = storage().traces
        val trace = dissolvedTrace()
        traces.keep(trace, clock.get())
        clock.addAndGet(6 * day)
        assertEquals(1, storage().traces.list(clock.get()).size, "day 6")
        clock.addAndGet(day)
        assertTrue(storage().traces.list(clock.get()).isEmpty(), "day 7")
        assertTrue(vault.aliases(tracePrefix).isEmpty())
        assertTrue(traces.ids().isEmpty())
        assertNull(traces.keep(trace, clock.get()), "a trace already a week old is not kept")
        assertTrue(problems.isEmpty(), "nothing to say: $problems")
    }

    @Test
    fun `a damaged trace, or one whose key is gone, is refused, said, and wiped`() {
        for (damage in listOf("flip", "cut", "key", "noise")) {
            problems.clear()
            val traces = storage().traces
            traces.keep(dissolvedTrace(), clock.get())
            val id = traces.ids().single()
            val file = File(root, "${DissolutionTraces.FOLDER}/$id.trace")
            when (damage) {
                "flip" -> file.writeBytes(file.readBytes().also { it[it.size / 2] = it[it.size / 2] xor 1 })
                "cut" -> file.writeBytes(file.readBytes().copyOf(20))
                "key" -> vault.delete(tracePrefix + id)
                else -> file.writeBytes(ByteArray(64) { 7 })
            }
            assertTrue(storage().traces.list(clock.get()).isEmpty(), damage)
            assertTrue(problems.any { it.where == "hierarchy.trace.read" }, "$damage not said")
            assertTrue(traces.ids().isEmpty() && vault.aliases(tracePrefix).isEmpty(), "$damage left something")
        }
    }

    @Test
    fun `a key without its file goes, a vault that refuses keeps nothing, and wipeAll wipes the traces too`() {
        val traces = storage().traces
        traces.keep(dissolvedTrace(), clock.get())
        File(root, DissolutionTraces.FOLDER).listFiles().orEmpty().forEach { it.delete() }
        assertTrue(traces.list(clock.get()).isEmpty())
        assertTrue(vault.aliases(tracePrefix).isEmpty(), "orphan key wiped")

        vault.refusing = true
        assertNull(traces.keep(dissolvedTrace(), clock.get()))
        vault.refusing = false
        assertTrue(traces.ids().isEmpty() && vault.aliases(tracePrefix).isEmpty(), "nothing left behind")

        val other = OperationStorage(temp.newFolder("other"), vault, "toolmap.hierarchy.check.", clock = clock::get)
        other.traces.keep(dissolvedTrace(), clock.get())
        traces.keep(dissolvedTrace(), clock.get())
        storage().wipeAll()
        assertTrue(storage().traces.ids().isEmpty() && vault.aliases(tracePrefix).isEmpty())
        assertFalse(File(root, DissolutionTraces.FOLDER).exists())
        assertEquals(1, other.traces.list(clock.get()).size, "another storage's trace is not touched")
    }

    @Test
    fun `a dissolved operation leaves its trace for a member then, nothing for one taken out before`() {
        val creator = assertNotNull(storage().create())
        val member = assertNotNull(storage().create())
        val world = OperationWorld(creator.keys, cKeys = member.keys)
        assertEquals(SaveResult.Saved, creator.save(world.contents))
        assertTrue(creator.saveLink(link(world).encode()))
        assertEquals(SaveResult.Saved, member.save(world.cContents))
        assertTrue(member.saveLink(link(world).encode()))

        world.must(Change.RemoveMember(world.c))
        world.must(Change.Dissolve)
        val ended = assertIs<SaveResult.Ended>(creator.save(world.contents))
        assertEquals(Ending.DISSOLVED, ended.ending)
        assertEquals(clock.get() + DissolutionTrace.KEPT_MS, ended.traceKeptUntilMs)
        assertFalse(File(root, creator.id).exists())

        // C learns, after a restart, that it was taken out and then the operation dissolved: no trace for it.
        val c = assertNotNull(storage().open(member.id))
        val loaded = assertIs<LoadResult.Loaded>(c.load()).contents
        loaded.replica.addAll(world.replica.ops())
        val cEnded = assertIs<SaveResult.Ended>(c.save(loaded))
        assertEquals(Ending.DISSOLVED, cEnded.ending)
        assertNull(cEnded.traceKeptUntilMs)
        assertEquals(1, storage().traces.list(clock.get()).size, "the creator's only")
        assertTrue(storage().ids().isEmpty())
    }

    private fun containsBytes(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
