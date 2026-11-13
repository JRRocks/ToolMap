package fr.toolmap.core.hierarchy

import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import java.math.BigInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proof of the author of an operation: every phone signs its changes (`docs/specs/group-hierarchy.md` § 8, R18). */
class OpAuthTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
    }

    @Test
    fun `a member identifier is the fingerprint of its signing key`() {
        assertNotEquals(A, B)
        assertNotEquals(0, A)
        assertEquals(A, OpAuth.memberId(publicOf(A).signing))
        assertEquals(A, keysOf(A).memberId)
        assertNotEquals(
            A,
            OpAuth.memberId(publicOf(A).agreement),
            "two keys per phone, the identifier is of the signing one",
        )
        // The identifier of an ensemble depends on its creator and on everything its first operation says.
        val first = genesis()
        val id = OpAuth.ensembleId(A, first)
        assertEquals(id, OpAuth.ensembleId(A, first.copy()))
        val others =
            listOf(
                OpAuth.ensembleId(B, first),
                OpAuth.ensembleId(A, first.copy(nonce = NONCE + 1)),
                OpAuth.ensembleId(A, first.copy(name = "Sortie 2")),
                OpAuth.ensembleId(A, first.copy(creatorName = "Alpha 2")),
                OpAuth.ensembleId(A, first.copy(creatorKeys = publicOf(B))),
                OpAuth.ensembleId(A, first.copy(rules = HierarchyRules(limits = HierarchyLimits(maxMembers = 31)))),
                OpAuth.ensembleId(A, first.copy(rules = HierarchyRules(signedShares = emptySet()))),
                OpAuth.ensembleId(A, first.copy(rules = HierarchyRules(shares = ShareTable.NONE))),
            )
        assertEquals(others.size + 1, (others + id).toSet().size)
    }

    @Test
    fun `R18 an operation signed by its author is authentic, nothing else is`() {
        val op = Op(1, A, 1, 1, Change.AddAdmin(B))
        assertFalse(op.authentic, "no proof")
        val good = OpAuth.sign(op, keysOf(A))
        assertTrue(good.authentic)
        assertEquals(op, good, "the proof does not change what the operation says")
        val proof = good.proof!!
        assertEquals(P256.POINT_BYTES, proof.publicKey.size)
        assertEquals(P256.SIGNATURE_BYTES, proof.signature.size)
        // The same proof on another operation.
        assertFalse(Op(1, A, 1, 1, Change.AddAdmin(A), proof).authentic)
        assertFalse(Op(1, A, 2, 1, Change.AddAdmin(B), proof).authentic)
        assertFalse(Op(2, A, 1, 1, Change.AddAdmin(B), proof).authentic)
        // Signed by someone else, in the name of A: the key is not the one of A's identifier.
        assertFalse(OpAuth.sign(op, keysOf(B)).authentic)
        assertFalse(
            Op(
                1,
                A,
                1,
                1,
                Change.AddAdmin(B),
                OpProof(proof.publicKey, OpAuth.sign(op, keysOf(B)).proof!!.signature),
            ).authentic,
        )
        // A damaged signature, in each half.
        for (at in listOf(0, 31, 32, 63)) {
            val damaged = proof.signature.copyOf().also { it[at] = (it[at] + 1).toByte() }
            assertFalse(Op(1, A, 1, 1, Change.AddAdmin(B), OpProof(proof.publicKey, damaged)).authentic, "byte $at")
        }
        // The proof survives the format.
        val back = HierarchyWire.decodeOp(HierarchyWire.encodeOp(good))
        assertTrue(back.authentic)
        assertEquals(good, back)
        assertTrue(problems.isEmpty(), "a wrong signature is not a failure of the code")
    }

    @Test
    fun `a key or a signature that is not one is refused, the same on every phone`() {
        val key = publicOf(A).signing
        assertTrue(P256.isPoint(key))
        // Not the length, not the form, not on the curve, coordinates out of the field.
        assertFalse(P256.isPoint(key.copyOf(64)))
        assertFalse(P256.isPoint(key + 0))
        assertFalse(P256.isPoint(key.copyOf().also { it[0] = 2 }))
        assertFalse(P256.isPoint(key.copyOf().also { it[40] = (it[40] + 1).toByte() }))
        assertFalse(P256.isPoint(byteArrayOf(4) + ByteArray(64)))
        assertFalse(P256.isPoint(byteArrayOf(4) + ByteArray(64) { 0xFF.toByte() }))
        val message = "message".toByteArray()
        val signature = keysOf(A).sign(message)
        assertTrue(P256.verify(key, message, signature))
        assertFalse(P256.verify(key, "autre".toByteArray(), signature))
        assertFalse(P256.verify(publicOf(B).signing, message, signature))
        assertFalse(P256.verify(key, message, signature.copyOf(63)))
        assertFalse(P256.verify(key, message, ByteArray(64)), "r and s are never zero")
        assertFalse(P256.verify(key, message, ByteArray(64) { 0xFF.toByte() }), "nor beyond the order of the curve")
        assertFalse(P256.verify(byteArrayOf(1, 2, 3, 4), message, signature))
        // An operation with such a proof is simply not authentic.
        val notAKey = ByteArray(P256.POINT_BYTES) { 7 }
        assertFalse(Op(1, OpAuth.memberId(notAKey), 1, 1, Change.AddAdmin(B), OpProof(notAKey, signature)).authentic)
        assertFalse(Op(1, A, 1, 1, Change.AddAdmin(B), OpProof(key, byteArrayOf(5, 6))).authentic)
        assertTrue(problems.isEmpty(), "checked before the provider is asked: nothing even to report")
    }

    @Test
    fun `signatures travel as r and s, whatever form the signer of the phone gives`() {
        // What a provider or the Keystore gives: SEQUENCE { INTEGER r, INTEGER s }, each as short as it can be.
        fun der(
            r: BigInteger,
            s: BigInteger,
        ): ByteArray {
            val body =
                byteArrayOf(2, r.toByteArray().size.toByte()) + r.toByteArray() +
                    byteArrayOf(2, s.toByteArray().size.toByte()) +
                    s.toByteArray()
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
        val small = BigInteger.valueOf(5)
        val high = BigInteger.ONE.shiftLeft(255).add(BigInteger.TEN)
        val raw = P256.derToRaw(der(small, high))
        assertEquals(64, raw.size)
        assertEquals(small, BigInteger(1, raw.copyOf(32)))
        assertEquals(high, BigInteger(1, raw.copyOfRange(32, 64)))
        for (bad in listOf(
            byteArrayOf(),
            byteArrayOf(0x30, 0),
            der(small, high) + 0,
            der(small, high).copyOf(10),
            der(BigInteger.ZERO, small),
        )) {
            assertFailsWith<IllegalArgumentException> { P256.derToRaw(bad) }
        }
        // The agreement key gives the same secret on both sides, and none with what is not a key.
        val ab = keysOf(A).agree(publicOf(B).agreement)
        assertEquals(P256.SECRET_BYTES, ab?.size)
        assertContentEquals(ab, keysOf(B).agree(publicOf(A).agreement))
        assertNull(keysOf(A).agree(ByteArray(P256.POINT_BYTES)))
    }

    @Test
    fun `R18 an unsigned or badly signed operation is not kept and never applies`() {
        val a = staffed()
        assertTrue(a.ops().all { it.authentic })
        val b = a.copyFor(B)
        val kept = b.ops().size
        val clock = a.ops().maxOf { it.clock } + 1
        val seq = a.ops().count { it.author == A } + 1
        // Written in the name of the creator: without a signature, with the signature of another, with a damaged one.
        val unsigned = Op(a.ensembleId, A, seq, clock, Change.AddAdmin(C))
        val wrongKey = OpAuth.sign(unsigned, keysOf(C))
        val real = OpAuth.sign(unsigned, keysOf(A))
        val damaged =
            Op(
                a.ensembleId,
                A,
                seq,
                clock,
                Change.AddAdmin(C),
                OpProof(
                    real.proof!!.publicKey,
                    real.proof!!.signature.copyOf().also {
                        it[5] =
                            (it[5] + 1).toByte()
                    },
                ),
            )
        // The signature of the creator, moved onto another operation.
        val moved = Op(a.ensembleId, A, seq, clock, Change.AddAdmin(D), real.proof)
        for (forged in listOf(unsigned, wrongKey, damaged, moved)) {
            assertEquals(AddResult.UNAUTHENTICATED, b.add(forged))
            assertEquals(Rejection.UNAUTHENTICATED, HierarchyReducer.reduce(b.state, forged).rejection)
        }
        assertEquals(kept, b.ops().size)
        assertEquals(setOf(B), b.state.admins)
        // Unsigned copies of real operations build nothing.
        val stripped = HierarchyReplica(a.ensembleId, D, keysOf(D))
        assertEquals(0, stripped.addAll(a.ops().map { Op(it.ensembleId, it.author, it.seq, it.clock, it.change) }))
        assertFalse(stripped.state.created)
        // The real one applies.
        assertEquals(AddResult.ADDED, b.add(real))
        assertEquals(setOf(B, C), b.state.admins)
        // A phone without its keys writes nothing.
        val keyless = HierarchyReplica.restore(a.ensembleId, A, a.ops())
        assertEquals(Rejection.UNAUTHENTICATED, keyless.refused(Change.AddAdmin(C)))
        assertFailsWith<IllegalArgumentException> { HierarchyReplica(a.ensembleId, A, keysOf(B)) }
    }

    @Test
    fun `R18 the key that signs is the one the member was admitted with`() {
        val a = staffed()
        val op = signedOp(a.ensembleId, B, a.ops().count { it.author == B } + 1, 50, Change.CreateGroup(G3, "Groupe 3"))
        assertNull(HierarchyReducer.reduce(a.state, op).rejection)
        // A state where B was admitted with another signing key: the signature, valid for B's identifier, is not
        // the one of the member this ensemble knows. (Two keys with one identifier: what a long computation finds.)
        val other = MemberKeys(publicOf(C).signing, publicOf(B).agreement)
        val state = a.state.copy(members = a.state.members + (B to a.state.members.getValue(B).copy(keys = other)))
        assertEquals(Rejection.UNAUTHENTICATED, HierarchyReducer.reduce(state, op).rejection)
    }
}
