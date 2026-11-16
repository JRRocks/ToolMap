package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.DissolutionProof
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.LoggedMessage
import fr.toolmap.core.hierarchy.MessageLog
import fr.toolmap.core.hierarchy.OperationLinkState
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attacks on the « Opération » storage (P39.11, § 22): the sealed files added by P39.7 and P39.9 (`link.sealed`,
 * `messages.sealed`) and every file of a stored operation, damaged, truncated or swapped between operations. A refusal
 * must be clean — read empty or `null`, said to whoever debugs (never a bug), the operation never read crosswise, and
 * nothing crashes. A seeded fuzz over every file is quick in `./gradlew check`; the long run is behind
 * `-Ptoolmap.hierarchy.fuzz=true`.
 *
 * The four parts, the identity, the storage key and the traces are already attacked in [StoredOperationTest] and
 * [DissolutionTracesTest]; here the new sealed files and a broad fuzz.
 */
class StorageAttackTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink
    private val vault = SoftwareKeyVault()
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Problems.sink = Problems.Sink { synchronized(problems) { problems.add(it) } }
        root = File(temp.newFolder("files"), OperationStorage.FOLDER)
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        synchronized(problems) { assertTrue(problems.none { !it.expected }, "damage is not a bug: $problems") }
    }

    private fun storage() = OperationStorage(root, vault)

    private fun restarted(id: String) = assertNotNull(storage().open(id), "identity not read again")

    /** A stored operation with every file written: the four parts, the link, and two threads of messages. */
    private fun fullOperation(): StoredOperation {
        val stored = assertNotNull(storage().create())
        val world = OperationWorld(stored.keys)
        assertEquals(SaveResult.Saved, stored.save(world.contents))
        assertTrue(stored.saveLink(linkBytes(world)))
        assertTrue(stored.saveMessages(messageBytes(world)))
        return stored
    }

    // The storage only seals and reads the link bytes; any valid OperationLinkState does for the round trip.
    private fun linkBytes(world: OperationWorld): ByteArray =
        OperationLinkState(world.ensembleId, ByteArray(16) { 3 }, null, emptyList(), null, null).encode()

    private fun messageBytes(world: OperationWorld): ByteArray {
        val log = MessageLog(world.ensembleId)
        log.add(Channel.ENSEMBLE, LoggedMessage(world.b, 1L, 10L, 11L, "rendez-vous 14 h"))
        log.add(Channel.unit(OperationWorld.G1), LoggedMessage(world.c, 2L, 12L, 13L, "en route"))
        return HierarchySnapshot.encodeMessages(log)
    }

    @Test
    fun `the link and the messages read back after a restart`() {
        val stored = fullOperation()
        val again = restarted(stored.id)
        assertEquals(LoadResult.Loaded::class, again.load()::class)
        assertNotNull(again.loadLink(), "the link reads back")
        assertNotNull(again.loadMessages(), "the messages read back")
    }

    @Test
    fun `a damaged or truncated link or messages file is refused, said, and the operation still loads`() {
        for (file in listOf(StoredOperation.LINK_FILE, StoredOperation.MESSAGES_FILE)) {
            for (how in listOf("flip", "cut", "noise")) {
                synchronized(problems) { problems.clear() }
                val stored = fullOperation()
                damage(File(root, "${stored.id}/$file"), how)
                val again = restarted(stored.id)
                // The operation itself still loads: the damage is in a file of its own.
                assertIs<LoadResult.Loaded>(again.load())
                if (file == StoredOperation.LINK_FILE) {
                    assertNull(again.loadLink(), "$file $how: no link read")
                } else {
                    assertNull(again.loadMessages(), "$file $how: no messages read")
                }
                synchronized(problems) { assertTrue(problems.isNotEmpty(), "$file $how not said") }
                stored.wipe()
            }
        }
    }

    @Test
    fun `a missing link or messages file reads as none, without saying anything`() {
        val stored = fullOperation()
        File(root, "${stored.id}/${StoredOperation.LINK_FILE}").delete()
        File(root, "${stored.id}/${StoredOperation.MESSAGES_FILE}").delete()
        val again = restarted(stored.id)
        assertIs<LoadResult.Loaded>(again.load())
        assertNull(again.loadLink())
        assertNull(again.loadMessages(), "a missing messages file (version 0.4.250) reads empty, silently")
        synchronized(problems) { assertTrue(problems.none { !it.expected }, "a missing file is not said as damage") }
    }

    @Test
    fun `a link or messages file swapped between two operations is refused`() {
        val a = fullOperation()
        val b = fullOperation()
        for (file in listOf(StoredOperation.LINK_FILE, StoredOperation.MESSAGES_FILE)) {
            File(root, "${a.id}/$file").copyTo(File(root, "${b.id}/$file"), overwrite = true)
        }
        val againB = restarted(b.id)
        assertIs<LoadResult.Loaded>(againB.load())
        // The aad binds each file to its operation and ensemble: B's phone reads neither A's link nor A's messages.
        assertNull(againB.loadLink(), "B does not read A's link")
        assertNull(againB.loadMessages(), "B does not read A's messages")
        synchronized(problems) { assertTrue(problems.isNotEmpty(), "the swap is said") }
        // A still reads its own.
        val againA = restarted(a.id)
        assertNotNull(againA.loadLink())
        assertNotNull(againA.loadMessages())
    }

    @Test
    fun `a trace file swapped between two operations is refused and wiped`() {
        val now = 1_700_000_000_000L
        val sa = OperationStorage(root, vault, clock = { now })
        val a = assertNotNull(sa.create())
        val b = assertNotNull(sa.create())
        a.save(OperationWorld(a.keys).contents)
        b.save(OperationWorld(b.keys).contents)
        val traceA = trace(a, now)
        val traceB = trace(b, now)
        assertNotNull(sa.traces.keep(traceA, now))
        assertNotNull(sa.traces.keep(traceB, now))
        val dir = File(root, DissolutionTraces.FOLDER)
        val files = dir.listFiles { f -> f.name.endsWith(".trace") }!!.sortedBy { it.name }
        assertEquals(2, files.size)
        // Swap the two trace file contents, keeping their names: each is now sealed with the aad of the other file.
        val bytes0 = files[0].readBytes()
        files[0].writeBytes(files[1].readBytes())
        files[1].writeBytes(bytes0)
        // Both are refused (the aad is bound to the file identifier) and wiped; nothing is read crosswise.
        assertTrue(
            OperationStorage(root, vault, clock = {
                now
            }).traces.list(now).isEmpty(),
            "no trace read after a swap",
        )
        synchronized(problems) { assertTrue(problems.isNotEmpty(), "the swap is said") }
    }

    @Test
    fun `a seeded fuzz over every file never crashes and never reads crosswise`() {
        fuzz(seeds = 1L..3L, iterations = ITERATIONS)
    }

    @Test
    fun `the long storage fuzz runs when asked`() {
        if (System.getProperty("toolmap.hierarchy.fuzz") != "true") {
            println("Long storage fuzzing skipped (pass -Ptoolmap.hierarchy.fuzz=true to run it).")
            return
        }
        fuzz(seeds = 1L..20L, iterations = 400)
    }

    private fun fuzz(
        seeds: LongRange,
        iterations: Int,
    ) {
        val stored = fullOperation()
        val ensembleId = assertNotNull(stored.ensembleId)
        val dir = File(root, stored.id)
        // A snapshot of every file, restored between iterations so the operation starts whole each time.
        val snapshot = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes() }
        assertTrue(snapshot.isNotEmpty())
        for (seed in seeds) {
            val random = Random(seed)
            repeat(iterations) {
                // Corrupt one or two files in place.
                val names = snapshot.keys.toList()
                repeat(1 + random.nextInt(2)) {
                    val name = names[random.nextInt(names.size)]
                    val file = File(dir, name)
                    val original = snapshot.getValue(name)
                    file.writeBytes(mutateBytes(original, random))
                }
                // Read everything: nothing throws out, nothing is a bug, and what loads is of this operation only.
                val storage = OperationStorage(root, vault)
                val opened = storage.open(stored.id)
                if (opened != null) {
                    when (val result = opened.load()) {
                        is LoadResult.Loaded -> {
                            assertEquals(ensembleId, result.contents.replica.ensembleId, "read crosswise")
                            opened.loadLink()
                            opened.loadMessages()
                        }

                        is LoadResult.Ended, LoadResult.Empty, LoadResult.Unavailable -> {
                            Unit
                        }
                    }
                }
                synchronized(problems) {
                    val bugs = problems.filter { !it.expected }
                    assertTrue(bugs.isEmpty(), "bug on seed $seed: $bugs")
                    problems.clear()
                }
                // Restore the folder for the next iteration.
                for ((name, bytes) in snapshot) File(dir, name).writeBytes(bytes)
            }
        }
    }

    private fun trace(
        stored: StoredOperation,
        now: Long,
    ): DissolutionTrace {
        val world = OperationWorld(stored.keys)
        val replica = world.replica
        replica.issue(fr.toolmap.core.hierarchy.Change.Dissolve)
        val proof = assertNotNull(DissolutionProof.of(replica), "proof")
        val key = ByteArray(32) { 5 }
        val epoch =
            fr.toolmap.core.hierarchy.EpochKey(
                key,
                fr.toolmap.core.hierarchy.InviteKeys.announce(stored.keys, replica.ensembleId, 1, key),
            )
        val link = OperationLinkState(replica.ensembleId, ByteArray(16) { 1 }, null, listOf(epoch), null, null)
        return DissolutionTrace.of(proof, link, now)
    }

    private fun damage(
        file: File,
        how: String,
    ) {
        val bytes = file.readBytes()
        when (how) {
            "flip" -> {
                file.writeBytes(
                    bytes.copyOf().also {
                        it[it.size / 2] =
                            (it[it.size / 2].toInt() xor 0x40).toByte()
                    },
                )
            }

            "cut" -> {
                file.writeBytes(bytes.copyOf(bytes.size / 2))
            }

            "noise" -> {
                file.writeBytes(ByteArray(bytes.size) { (it * 31 + 7).toByte() })
            }
        }
    }

    private fun mutateBytes(
        bytes: ByteArray,
        random: Random,
    ): ByteArray =
        when (random.nextInt(5)) {
            0 -> bytes.copyOf().also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
            1 -> bytes.copyOf(random.nextInt(bytes.size + 1))
            2 -> ByteArray(random.nextInt(bytes.size + 4)).also(random::nextBytes)
            3 -> ByteArray(0)
            else -> bytes.copyOf().also(random::nextBytes)
        }

    companion object {
        private const val ITERATIONS = 60
    }
}
