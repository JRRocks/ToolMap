package fr.toolmap.core.hierarchy

import fr.toolmap.core.problem.catchExpected
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/** Signs the bytes of an operation with the key of this phone; on Android the key may live in the Keystore. */
fun interface OpSigner {
    fun prove(content: ByteArray): OpProof
}

/**
 * Who wrote an operation (`docs/specs/group-hierarchy.md` § 8). The key of the group ([GroupCrypto]) only says « a
 * member of the group wrote this »: every member holds it. To say « this administrator wrote this », each phone has
 * its own ECDSA P-256 key pair, with what the JVM and Android already provide (no dependency); its member identifier
 * is the fingerprint of its public key, so that an operation that carries the key and the signature proves its author
 * without any directory.
 *
 * Nothing calls this yet: an ensemble asks for signed operations only when its rules say so
 * ([HierarchyRules.signedOps]).
 */
object OpAuth {
    private const val SIGNATURE = "SHA256withECDSA"
    private const val CURVE = "secp256r1"
    private val MEMBER_TAG = "toolmap-hierarchy-member-v1".toByteArray(Charsets.US_ASCII)
    private val ENSEMBLE_TAG = "toolmap-hierarchy-ensemble-v1".toByteArray(Charsets.US_ASCII)
    private val OP_TAG = "toolmap-hierarchy-op-v1".toByteArray(Charsets.US_ASCII)

    /** The member identifier that goes with [publicKey] (X.509 encoding): 8 bytes of its SHA-256, never 0. */
    fun memberId(publicKey: ByteArray): Long = fingerprint(MEMBER_TAG, publicKey)

    /**
     * The identifier of the ensemble [creator] creates with [genesis]: the fingerprint of both. An ensemble has one
     * possible first operation, and whoever was given the identifier knows its creator, name and rules are the ones
     * that were meant.
     */
    fun ensembleId(
        creator: Long,
        genesis: Change.CreateEnsemble,
    ): Long = fingerprint(ENSEMBLE_TAG, longBytes(creator) + HierarchyWire.genesisBytes(genesis))

    /** A new key pair kept in memory: tests, and phones until the Keystore is decided. */
    fun generateKey(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    fun signer(key: KeyPair): OpSigner =
        OpSigner { content ->
            val signature = Signature.getInstance(SIGNATURE)
            signature.initSign(key.private)
            signature.update(OP_TAG)
            signature.update(content)
            OpProof(key.public.encoded, signature.sign())
        }

    /** [op] with the proof [signer] gives for it. */
    fun sign(
        op: Op,
        signer: OpSigner,
    ): Op = Op(op.ensembleId, op.author, op.seq, op.clock, op.change, signer.prove(op.content))

    /** `true` when [op] carries the public key its author's identifier is the fingerprint of, and its signature. */
    fun verify(op: Op): Boolean {
        val proof = op.proof ?: return false
        if (memberId(proof.publicKey) != op.author) return false
        // A key or a signature that is not one comes from outside: refused, and said to whoever debugs.
        return catchExpected(
            "hierarchy.auth.verify",
            GeneralSecurityException::class,
            IllegalArgumentException::class,
        ) {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(proof.publicKey))
            val signature = Signature.getInstance(SIGNATURE)
            signature.initVerify(key)
            signature.update(OP_TAG)
            signature.update(op.content)
            signature.verify(proof.signature)
        }.getOrDefault(false)
    }

    private fun fingerprint(
        tag: ByteArray,
        data: ByteArray,
    ): Long {
        val hash = MessageDigest.getInstance("SHA-256").apply { update(tag) }.digest(data)
        val id = hash.take(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return if (id == 0L) 1L else id
    }

    internal fun longBytes(v: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (v ushr (56 - 8 * it)).toByte() }
}
