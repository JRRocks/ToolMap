package fr.toolmap.core.hierarchy

import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proof of the author of an operation (`docs/specs/group-hierarchy.md` § 8). */
class OpAuthTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    private val keyA = OpAuth.generateKey()
    private val keyB = OpAuth.generateKey()
    private val idA = OpAuth.memberId(keyA.public.encoded)
    private val idB = OpAuth.memberId(keyB.public.encoded)
    private val signed = HierarchyRules(signedOps = true)

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
    }

    private fun signedEnsemble(): HierarchyReplica =
        HierarchyReplica.create(idA, "Alpha", "Sortie signée", NONCE, signed, OpAuth.signer(keyA)).apply {
            must(Change.AdmitMember(idB, "Bravo"))
            must(Change.CreateGroup(G1, "Groupe 1"))
        }

    @Test
    fun `a member identifier is the fingerprint of its key`() {
        assertNotEquals(idA, idB)
        assertNotEquals(0, idA)
        assertEquals(idA, OpAuth.memberId(keyA.public.encoded))
        // The identifier of an ensemble depends on its creator and on everything its first operation says.
        val genesis = Change.CreateEnsemble(NONCE, "Sortie", "Alpha")
        val id = OpAuth.ensembleId(idA, genesis)
        assertEquals(id, OpAuth.ensembleId(idA, genesis.copy()))
        val others =
            listOf(
                OpAuth.ensembleId(idB, genesis),
                OpAuth.ensembleId(idA, genesis.copy(nonce = NONCE + 1)),
                OpAuth.ensembleId(idA, genesis.copy(name = "Sortie 2")),
                OpAuth.ensembleId(idA, genesis.copy(creatorName = "Alpha 2")),
                OpAuth.ensembleId(idA, genesis.copy(rules = signed)),
            )
        assertEquals(others.size + 1, (others + id).toSet().size)
    }

    @Test
    fun `an operation signed by its author is authentic, nothing else is`() {
        val op = Op(1, idA, 1, 1, Change.AddAdmin(idB))
        assertFalse(op.authentic, "no proof")
        val good = OpAuth.sign(op, OpAuth.signer(keyA))
        assertTrue(good.authentic)
        assertEquals(op, good, "the proof does not change what the operation says")
        val proof = good.proof!!
        // The same proof on another operation.
        assertFalse(Op(1, idA, 1, 1, Change.AddAdmin(idA), proof).authentic)
        assertFalse(Op(1, idA, 2, 1, Change.AddAdmin(idB), proof).authentic)
        assertFalse(Op(2, idA, 1, 1, Change.AddAdmin(idB), proof).authentic)
        // Signed by someone else, in the name of A: the key is not the one of A's identifier.
        assertFalse(OpAuth.sign(op, OpAuth.signer(keyB)).authentic)
        assertFalse(
            Op(
                1,
                idA,
                1,
                1,
                Change.AddAdmin(idB),
                OpProof(proof.publicKey, OpAuth.sign(op, OpAuth.signer(keyB)).proof!!.signature),
            ).authentic,
        )
        // A damaged signature.
        val damaged = proof.signature.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFalse(Op(1, idA, 1, 1, Change.AddAdmin(idB), OpProof(proof.publicKey, damaged)).authentic)
        // The proof survives the format.
        val back = HierarchyWire.decodeOp(HierarchyWire.encodeOp(good))
        assertTrue(back.authentic)
        assertEquals(good, back)
    }

    @Test
    fun `a key or a signature that is not one is refused and said to whoever debugs`() {
        val notAKey = byteArrayOf(1, 2, 3, 4)
        val author = OpAuth.memberId(notAKey)
        assertFalse(Op(1, author, 1, 1, Change.AddAdmin(idB), OpProof(notAKey, byteArrayOf(5, 6))).authentic)
        assertFalse(Op(1, idA, 1, 1, Change.AddAdmin(idB), OpProof(keyA.public.encoded, byteArrayOf(5, 6))).authentic)
        assertEquals(listOf("hierarchy.auth.verify", "hierarchy.auth.verify"), problems.map { it.where })
        assertTrue(problems.all { it.expected }, "bytes from outside are not a bug of ours")
    }

    @Test
    fun `an ensemble that asks for signed operations refuses the others on every phone`() {
        val a = signedEnsemble()
        assertTrue(a.ops().all { it.authentic })
        val b = HierarchyReplica.restore(a.ensembleId, idB, a.ops(), OpAuth.signer(keyB))
        assertSame(a, b)
        val clock = a.ops().maxOf { it.clock } + 1
        val seq = a.ops().size + 1
        // Written in the name of the administrator, without its key, or with another key.
        val forged = Op(a.ensembleId, idA, seq, clock, Change.AddAdmin(idB))
        val wrongKey = OpAuth.sign(Op(a.ensembleId, idA, seq + 1, clock + 1, Change.AddAdmin(idB)), OpAuth.signer(keyB))
        assertEquals(AddResult.ADDED, b.add(forged))
        assertEquals(AddResult.ADDED, b.add(wrongKey))
        assertEquals(Rejection.UNAUTHENTICATED, b.rejectionOf(forged))
        assertEquals(Rejection.UNAUTHENTICATED, b.rejectionOf(wrongKey))
        assertEquals(setOf(idA), b.state.admins)
        // The real operation under the same number takes the place of the forged one, whatever its bytes.
        val real = a.must(Change.AssignMember(idB, G1))
        assertEquals(seq, real.seq)
        assertEquals(AddResult.CONFLICT_REPLACED, b.add(real))
        assertEquals(AddResult.CONFLICT_KEPT, b.add(forged))
        assertNull(b.rejectionOf(real))
        assertEquals(G1, b.state.groupOf(idB)?.id)
        // A phone without a key cannot write in a signed ensemble; with its key B writes, within its rights.
        val keyless = HierarchyReplica.restore(a.ensembleId, idA, a.ops())
        assertEquals(Rejection.UNAUTHENTICATED, keyless.refused(Change.AddAdmin(idB)))
        val left = b.must(Change.RemoveMember(idB))
        assertTrue(left.authentic)
        assertEquals(Rejection.NOT_A_MEMBER, b.refused(Change.AdmitMember(idB, "Bravo")))
    }

    @Test
    fun `a copy stripped of its proof does not hide the operation`() {
        val a = signedEnsemble()
        val b = HierarchyReplica(a.ensembleId, idB, OpAuth.signer(keyB))
        val stripped = a.ops().map { Op(it.ensembleId, it.author, it.seq, it.clock, it.change) }
        b.addAll(stripped)
        assertFalse(b.state.created, "nothing is proven")
        assertNotEquals(a.summary().digest, b.summary().digest, "the fingerprint tells proven from not")
        // The catch-up brings the proofs.
        sync(a, b)
        assertSame(a, b)
        assertTrue(b.ops().all { it.authentic })
        // And a stripped copy that comes after changes nothing.
        assertEquals(0, b.addAll(stripped))
        assertSame(a, b)
    }

    @Test
    fun `an ensemble without signed operations takes them signed or not`() {
        val a = HierarchyReplica.create(idA, "Alpha", "Sortie", NONCE, signer = OpAuth.signer(keyA))
        a.must(Change.AdmitMember(idB, "Bravo"))
        val b = HierarchyReplica.restore(a.ensembleId, idB, a.ops())
        assertEquals(setOf(idA, idB), b.state.members.keys)
        assertEquals(Rejection.FORBIDDEN, b.refused(Change.AddAdmin(idB)))
        assertTrue(problems.isEmpty())
    }
}
