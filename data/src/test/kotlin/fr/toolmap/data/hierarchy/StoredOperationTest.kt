package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.OpenResult
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.hierarchy.SealResult
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareSeal
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The storage of the « Opération » mode (P39.6, ADR-128) on the JVM, over a vault in memory. A « restart » is a new
 * [OperationStorage] over the same folder and the same vault: the Keystore's keys outlive the process.
 */
class StoredOperationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink
    private val vault = SoftwareKeyVault()
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

    private fun storage() = OperationStorage(root, vault)

    private fun restarted(id: String): StoredOperation = assertNotNull(storage().open(id), "identity not read again")

    private fun loaded(stored: StoredOperation): LoadResult.Loaded = assertIs<LoadResult.Loaded>(stored.load())

    @Test
    fun `round trip - an operation saved then read in a new process gives the same state, keys, names and objects`() {
        val stored = assertNotNull(storage().create())
        assertEquals(LoadResult.Empty, stored.load())
        val world = OperationWorld(stored.keys)
        assertEquals(SaveResult.Saved, stored.save(world.contents))
        assertEquals(world.ensembleId, stored.ensembleId)

        val again = restarted(stored.id)
        assertEquals(stored.self, again.self)
        assertContentEquals(stored.keys.public.signing, again.keys.public.signing)
        assertContentEquals(stored.keys.public.agreement, again.keys.public.agreement)
        val reloaded = loaded(again)
        assertEquals(emptySet(), reloaded.refused)
        assertSameContents(world.contents, reloaded.contents)

        // What was held still works: the names, the objects, a share sealed after the restart opens on C's phone.
        val contents = reloaded.contents
        assertEquals("Charlie", contents.names.nameOf(contents.replica.state, world.c))
        assertEquals("Golf", contents.names.nameOf(world.early.hidden))
        assertEquals(2, contents.objects.shown(Channel.unit(OperationWorld.G1)).size)
        val sealed =
            ShareSeal.seal(
                contents.replica.state,
                contents.ring,
                again.keys,
                ShareKind.MESSAGE,
                Channel.unit(OperationWorld.G1),
                "après redémarrage".toByteArray(),
            )
        val frame = assertIs<SealResult.Sealed>(sealed).frame
        val opened = ShareSeal.open(world.cContents.replica.state, world.cContents.ring, frame)
        assertEquals("après redémarrage", String(assertIs<OpenResult.Opened>(opened).share.payload))
        // And what this phone writes after the restart is signed by the same key in the vault.
        val renamed = contents.replica.issue(Change.RenameGroup(OperationWorld.G2, world.hidden("Groupe 1")))
        assertIs<IssueResult.Issued>(renamed)
        assertEquals(SaveResult.Saved, again.save(contents))
        assertSameContents(contents, loaded(restarted(stored.id)).contents)
    }

    @Test
    fun `round trip - keys in a sealed file (API below 31) work like keys in the vault`() {
        val fileVault = SoftwareKeyVault(agreesInHardware = false)
        val files = OperationStorage(root, fileVault)
        val stored = assertNotNull(files.create())
        assertFalse(stored.agreesInVault)
        assertTrue(File(root, "${stored.id}/${OperationStorage.AGREEMENT_FILE}").isFile)
        val world = OperationWorld(stored.keys)
        assertEquals(SaveResult.Saved, stored.save(world.contents))

        val again = assertNotNull(OperationStorage(root, fileVault).open(stored.id))
        assertFalse(again.agreesInVault)
        assertContentEquals(stored.keys.public.agreement, again.keys.public.agreement)
        val peer = P256.generate()
        assertContentEquals(
            P256.agree(peer.private, again.keys.public.agreement),
            again.keys.agree(P256.encode(peer.public)),
        )
        assertSameContents(world.contents, assertIs<LoadResult.Loaded>(again.load()).contents)
    }

    @Test
    fun `round trip - a phone that joins keeps its identity before it is let in, then everything after`() {
        val creator = assertNotNull(storage().create())
        val joiner = assertNotNull(storage().create())
        val world = OperationWorld(creator.keys, cKeys = joiner.keys)
        val waiting = joiner.join(world.ensembleId)
        assertEquals(SaveResult.Saved, joiner.save(waiting))
        val empty = loaded(restarted(joiner.id))
        assertEquals(0, empty.contents.replica.ops().size)

        assertEquals(SaveResult.Saved, joiner.save(world.cContents))
        assertSameContents(world.cContents, loaded(restarted(joiner.id)).contents)
    }

    @Test
    fun `round trip - every signature goes through the vault, and no file holds a name or a key in clear`() {
        val stored = assertNotNull(storage().create())
        val before = vault.signatures
        val world = OperationWorld(stored.keys)
        assertTrue(vault.signatures > before)
        stored.save(world.contents)
        val bytes = File(root, stored.id).listFiles().orEmpty().map { it.readBytes() }
        for (name in world.cards.keys + "Golf") {
            val needle = name.toByteArray()
            assertFalse(bytes.any { contains(it, needle) }, "name $name in clear on the disk")
        }
        val held = HierarchySnapshot.encodeKeys(world.contents.ring)
        // The content keys are in the plain bytes of the keys part; 32 of them must not be found in any file.
        val secretSample = held.copyOfRange(held.size / 2, held.size / 2 + 32)
        assertFalse(bytes.any { contains(it, secretSample) })
    }

    @Test
    fun `wipe - leaving wipes everything, keys of the vault first`() {
        val stored = assertNotNull(storage().create())
        stored.save(OperationWorld(stored.keys).contents)
        assertTrue(vault.aliases(OperationStorage.ALIAS_PREFIX + stored.id).isNotEmpty())
        stored.wipe()
        assertEquals(emptyList(), vault.aliases(OperationStorage.ALIAS_PREFIX + stored.id))
        assertFalse(File(root, stored.id).exists())
        assertNull(storage().open(stored.id))
        assertEquals(emptyList(), storage().ids())
        assertEquals(Ending.WIPED, assertIs<LoadResult.Ended>(stored.load()).ending)
        val anything = OperationContents.around(HierarchyReplica(1L, stored.self, stored.keys), stored.keys)
        assertEquals(Ending.WIPED, assertIs<SaveResult.Ended>(stored.save(anything)).ending)
    }

    @Test
    fun `wipe - a member taken out by the creator wipes everything at its next save`() {
        val creator = assertNotNull(storage().create())
        val member = assertNotNull(storage().create())
        val world = OperationWorld(creator.keys, cKeys = member.keys)
        assertEquals(SaveResult.Saved, member.save(world.cContents))
        world.must(Change.RemoveMember(world.c))
        world.cContents.replica.addAll(world.replica.ops())
        assertEquals(Ending.OUT, assertIs<SaveResult.Ended>(member.save(world.cContents)).ending)
        assertFalse(File(root, member.id).exists())
        assertEquals(emptyList(), vault.aliases(OperationStorage.ALIAS_PREFIX + member.id))
        // The creator's storage is untouched.
        assertEquals(SaveResult.Saved, creator.save(world.contents))
        assertSameContents(world.contents, loaded(restarted(creator.id)).contents)
    }

    @Test
    fun `wipe - a dissolved operation is wiped on every phone that learns it`() {
        val creator = assertNotNull(storage().create())
        val member = assertNotNull(storage().create())
        val world = OperationWorld(creator.keys, cKeys = member.keys)
        assertEquals(SaveResult.Saved, member.save(world.cContents))
        assertEquals(SaveResult.Saved, creator.save(world.contents))
        world.must(Change.Dissolve)
        assertEquals(Ending.DISSOLVED, assertIs<SaveResult.Ended>(creator.save(world.contents)).ending)
        assertFalse(File(root, creator.id).exists())

        // C's phone, after a restart, learns it from the network.
        val cRestarted = restarted(member.id)
        val cLoaded = loaded(cRestarted).contents
        cLoaded.replica.addAll(world.replica.ops())
        assertEquals(Ending.DISSOLVED, assertIs<SaveResult.Ended>(cRestarted.save(cLoaded)).ending)
        assertEquals(emptyList(), storage().ids())
        assertEquals(emptyList(), vault.aliases(OperationStorage.ALIAS_PREFIX))
    }

    @Test
    fun `wipe by channel - a member put back to waiting no longer holds anything of its group on the disk`() {
        val creator = assertNotNull(storage().create())
        val member = assertNotNull(storage().create())
        val world = OperationWorld(creator.keys, cKeys = member.keys)
        val c = world.cContents
        val group = Channel.unit(OperationWorld.G1)
        val oldKey = assertNotNull(c.ring.sealingKey(c.replica.state, group))
        val oldBatches = c.ring.batchesFor(c.replica.state).filter { it.channel == group }
        assertTrue(oldBatches.isNotEmpty())
        assertEquals(SaveResult.Saved, member.save(c))

        world.must(Change.UnassignMember(world.c))
        c.replica.addAll(world.replica.ops())
        assertTrue(c.replica.state.isWaiting(world.c))
        assertEquals(SaveResult.Saved, member.save(c))

        val back = loaded(restarted(member.id)).contents
        assertNull(back.ring.find(group, oldKey.id))
        assertEquals(emptyList(), back.objects.shown(group))
        val kept = HierarchySnapshot.encodeKeys(back.ring)
        for (batch in oldBatches) {
            assertFalse(contains(kept, HierarchyWire.encodeKeyBatch(batch)), "a batch that gives the old key back")
        }
        // The names are not tied to a channel: they stay.
        assertEquals("Charlie", back.names.nameOf(back.replica.state, world.c))
    }

    @Test
    fun `damage - a damaged, cut or missing part is refused, said, and the rest is read`() {
        for (part in StoredPart.entries) {
            for (damage in listOf("flip", "cut", "missing", "noise", "empty")) {
                problems.clear()
                val stored = assertNotNull(storage().create())
                val world = OperationWorld(stored.keys)
                assertEquals(SaveResult.Saved, stored.save(world.contents))
                val file = File(root, "${stored.id}/${part.file}")
                damageFile(file, damage)

                val reloaded = loaded(restarted(stored.id))
                assertEquals(setOf(part), reloaded.refused, "$part $damage")
                assertTrue(problems.isNotEmpty(), "$part $damage not said")
                val got = reloaded.contents
                if (part != StoredPart.OPS) assertEquals(world.replica.state, got.replica.state, "$part $damage")
                if (part != StoredPart.NAMES) {
                    assertContentEquals(
                        HierarchySnapshot.encodeNames(world.contents.names),
                        HierarchySnapshot.encodeNames(got.names),
                    )
                }

                // The operations come back through the network; the next save writes the part again.
                got.replica.addAll(world.replica.ops())
                assertEquals(SaveResult.Saved, restarted(stored.id).save(got))
                assertEquals(emptySet(), loaded(restarted(stored.id)).refused, "$part $damage rewritten")
                stored.wipe()
            }
        }
    }

    @Test
    fun `damage - a part of another operation, or of another part, is refused`() {
        val a = assertNotNull(storage().create())
        val b = assertNotNull(storage().create())
        a.save(OperationWorld(a.keys).contents)
        b.save(OperationWorld(b.keys).contents)
        File(root, "${a.id}/${StoredPart.OPS.file}").copyTo(File(root, "${b.id}/${StoredPart.OPS.file}"), true)
        File(root, "${b.id}/${StoredPart.KEYS.file}").copyTo(File(root, "${b.id}/${StoredPart.NAMES.file}"), true)
        assertEquals(setOf(StoredPart.OPS, StoredPart.NAMES), loaded(restarted(b.id)).refused)
        assertEquals(emptySet(), loaded(restarted(a.id)).refused)
    }

    @Test
    fun `damage - a damaged storage key refuses every part, never crashes, and starts again`() {
        val stored = assertNotNull(storage().create())
        val world = OperationWorld(stored.keys)
        stored.save(world.contents)
        damageFile(File(root, "${stored.id}/${StoredOperation.STORAGE_KEY_FILE}"), "flip")
        val reloaded = loaded(restarted(stored.id))
        assertEquals(StoredPart.entries.toSet(), reloaded.refused)
        reloaded.contents.replica.addAll(world.replica.ops())
        assertEquals(SaveResult.Saved, restarted(stored.id).save(reloaded.contents))
        assertEquals(emptySet(), loaded(restarted(stored.id)).refused)
    }

    @Test
    fun `damage - an identity that can no longer be read is refused cleanly, and wiped on demand`() {
        for (damage in listOf("header", "agreement", "lost-key", "missing-header")) {
            problems.clear()
            val fileVault = SoftwareKeyVault(agreesInHardware = damage != "agreement")
            val store = OperationStorage(root, fileVault)
            val stored = assertNotNull(store.create())
            stored.save(OperationWorld(stored.keys).contents)
            val dir = File(root, stored.id)
            when (damage) {
                "header" -> damageFile(File(dir, OperationStorage.IDENTITY_FILE), "flip")
                "missing-header" -> File(dir, OperationStorage.IDENTITY_FILE).delete()
                "agreement" -> damageFile(File(dir, OperationStorage.AGREEMENT_FILE), "flip")
                "lost-key" -> fileVault.lose(OperationStorage.alias(OperationStorage.ALIAS_PREFIX, stored.id, "sign"))
            }
            assertNull(OperationStorage(root, fileVault).open(stored.id), damage)
            assertTrue(problems.isNotEmpty(), "$damage not said")
            OperationStorage(root, fileVault).wipe(stored.id)
            assertFalse(dir.exists())
            assertEquals(emptyList(), fileVault.aliases(OperationStorage.ALIAS_PREFIX + stored.id))
        }
    }

    @Test
    fun `damage - a vault that refuses gives no identity, leaves nothing behind, and a load that waits`() {
        val stored = assertNotNull(storage().create())
        stored.save(OperationWorld(stored.keys).contents)
        vault.refusing = true
        assertNull(storage().create())
        assertEquals(listOf(stored.id), storage().ids())
        assertNull(storage().open(stored.id))
        assertFailsWith<KeyVaultException> { stored.keys.sign(ByteArray(4)) }
        assertNull(stored.keys.agree(P256.encode(P256.generate().public)))
        vault.refusing = false
        assertEquals(emptySet(), loaded(restarted(stored.id)).refused)
    }

    @Test
    fun `side by side - two operations of one phone share no key and never mix`() {
        val a = assertNotNull(storage().create())
        val b = assertNotNull(storage().create())
        assertNotEquals(a.self, b.self)
        val worldA = OperationWorld(a.keys, title = "Opération A")
        val worldB = OperationWorld(b.keys, title = "Opération B")
        a.save(worldA.contents)
        b.save(worldB.contents)
        assertEquals(listOf(a.id, b.id).sorted(), storage().ids())
        assertFailsWith<IllegalArgumentException> { a.save(worldB.contents) }
        assertSameContents(worldA.contents, loaded(restarted(a.id)).contents)
        assertSameContents(worldB.contents, loaded(restarted(b.id)).contents)
        a.wipe()
        assertSameContents(worldB.contents, loaded(restarted(b.id)).contents)
        assertTrue(vault.aliases(OperationStorage.ALIAS_PREFIX + b.id).isNotEmpty())
        assertEquals(listOf(b.id), storage().ids())
    }

    @Test
    fun `wipeAll - removes every operation and the keys left by a folder already gone, and nothing else`() {
        val a = assertNotNull(storage().create())
        val other = OperationStorage(temp.newFolder("other"), vault, "toolmap.hierarchy.check.")
        val kept = assertNotNull(other.create())
        val orphan = assertNotNull(storage().create())
        File(root, orphan.id).deleteRecursively()
        storage().wipeAll()
        assertEquals(emptyList(), storage().ids())
        assertEquals(emptyList(), vault.aliases(OperationStorage.ALIAS_PREFIX))
        assertNotNull(other.open(kept.id))
        assertNull(storage().open(a.id))
    }

    @Test
    fun `restore - stored operations are handed back in their order, faster and with the same result`() {
        val stored = assertNotNull(storage().create())
        val world = OperationWorld(stored.keys)
        // Two authors write in turns: in the order of HierarchyReplica.ops (by author), most operations of the
        // second come « in the past ».
        val admin = HierarchyReplica.restore(world.ensembleId, world.b, world.replica.ops(), world.bKeys)
        repeat(300) {
            val (one, two) = if (it % 2 == 0) "Groupe 2" to "Groupe 1" else "Groupe 1" to "Groupe 2"
            world.must(Change.RenameGroup(OperationWorld.G1, world.hidden(one)))
            admin.addAll(world.replica.ops())
            assertIs<IssueResult.Issued>(admin.issue(Change.RenameGroup(OperationWorld.G2, world.hidden(two))))
            world.replica.addAll(admin.ops())
        }
        val ops = world.replica.ops()
        val byAuthor = HierarchyReplica.restore(world.ensembleId, stored.self, ops, stored.keys)
        val inOrder = HierarchySnapshot.restoreReplica(world.ensembleId, stored.self, ops.shuffled(), stored.keys)
        assertEquals(byAuthor.state, inOrder.state)
        assertEquals(
            byAuthor.history().map { it.op to it.rejection },
            inOrder.history().map { it.op to it.rejection },
        )
        // Operations read back from bytes, as from the disk: each signature is checked again.
        val bytes = HierarchySnapshot.encodeOps(ops)

        fun fresh() = HierarchySnapshot.decodeOps(bytes, world.ensembleId)
        fresh().forEach { assertTrue(it.authentic) }
        val readOnly = fresh()
        val readTime = timeMs { readOnly.forEach { it.authentic } }
        val sorted = fresh()
        val sortTime = timeMs { HierarchySnapshot.restoreReplica(world.ensembleId, stored.self, sorted, stored.keys) }
        val byAuthors = fresh()
        val authorTime = timeMs { HierarchyReplica.restore(world.ensembleId, stored.self, byAuthors, stored.keys) }
        assertEquals(SaveResult.Saved, stored.save(world.contents))
        world.contents.ring.issue(world.replica.state, Channel.ENSEMBLE)
        val saveTime = timeMs { assertEquals(SaveResult.Saved, restarted(stored.id).save(world.contents)) }
        val loadTime = timeMs { assertSameContents(world.contents, loaded(restarted(stored.id)).contents) }
        val out = File(System.getProperty("toolmap.repo.dir") ?: ".", "data/build/hierarchy-store-cost.txt")
        out.parentFile.mkdirs()
        out.writeText(
            "Operations of one operation: ${ops.size}, on this JVM (not a phone)\n" +
                "signatures checked again: $readTime ms; restore in the order of the clocks (signatures included): " +
                "$sortTime ms; in the order of the authors: $authorTime ms\n" +
                "save after one change (keys part): $saveTime ms; load and compare: $loadTime ms\n",
        )
    }

    private fun damageFile(
        file: File,
        how: String,
    ) {
        val bytes = file.readBytes()
        when (how) {
            "flip" -> {
                val flipped = bytes.copyOf()
                flipped[flipped.size / 2] = (flipped[flipped.size / 2].toInt() xor 0x40).toByte()
                file.writeBytes(flipped)
            }

            "cut" -> {
                file.writeBytes(bytes.copyOf(bytes.size / 2))
            }

            "missing" -> {
                file.delete()
            }

            "noise" -> {
                file.writeBytes(ByteArray(bytes.size) { (it * 31 + 7).toByte() })
            }

            "empty" -> {
                file.writeBytes(ByteArray(0))
            }
        }
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private inline fun timeMs(work: () -> Unit): Long {
        val started = System.nanoTime()
        work()
        return (System.nanoTime() - started) / 1_000_000
    }
}
