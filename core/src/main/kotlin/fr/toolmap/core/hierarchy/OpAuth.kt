package fr.toolmap.core.hierarchy

import fr.toolmap.core.problem.catchExpected
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * The private keys of this phone, as the engine needs them: it never sees them. On Android the signing key may live
 * in the Keystore (it then hands out DER signatures: [P256.derToRaw]); the agreement key too on the versions that
 * can do a key agreement there, in a file sealed by the Keystore on the others.
 */
interface PhoneKeys {
    /** The two public keys, as they go into the operation that admits this phone. */
    val public: MemberKeys

    /** The signature of [message] by the signing key: `r | s`, [P256.SIGNATURE_BYTES] bytes. */
    fun sign(message: ByteArray): ByteArray

    /** The secret shared with the public agreement key [peer] (ECDH), 32 bytes; `null` when [peer] is not a key. */
    fun agree(peer: ByteArray): ByteArray?
}

/** The member identifier of the phone that holds these keys. */
val PhoneKeys.memberId: Long get() = OpAuth.memberId(public.signing)

/**
 * P-256 keys and signatures in one fixed form, with what the JVM and Android already provide (no dependency). A
 * public key is the point `0x04 | X (32) | Y (32)`, checked here to be on the curve; a signature is `r (32) | s (32)`.
 * One form only, and checked by this code rather than by the provider: every phone accepts or refuses the same
 * bytes, whatever its version of Android, which the agreement between phones needs (spec § 6).
 */
object P256 {
    const val POINT_BYTES = 65
    const val SIGNATURE_BYTES = 64
    const val SECRET_BYTES = 32
    private const val COORDINATE_BYTES = 32
    private const val UNCOMPRESSED: Byte = 4
    private const val DER_SEQUENCE = 0x30
    private const val DER_INTEGER = 0x02
    private const val CURVE = "secp256r1"
    private const val ECDSA = "SHA256withECDSA"

    private val spec: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec(CURVE))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    /** [key] as a point of [POINT_BYTES] bytes. */
    fun encode(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(UNCOMPRESSED) + fixed(w.affineX) + fixed(w.affineY)
    }

    /** Whether [bytes] are a point of the curve in the one form this format takes. */
    fun isPoint(bytes: ByteArray): Boolean = point(bytes) != null

    private fun point(bytes: ByteArray): ECPoint? {
        if (bytes.size != POINT_BYTES || bytes[0] != UNCOMPRESSED) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_BYTES))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_BYTES, POINT_BYTES))
        val curve = spec.curve
        val p = (curve.field as ECFieldFp).p
        if (x >= p || y >= p) return null
        val right = (x.pow(3) + curve.a * x + curve.b).mod(p)
        return if ((y * y).mod(p) == right) ECPoint(x, y) else null
    }

    private fun decode(bytes: ByteArray): PublicKey? =
        point(bytes)?.let { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(it, spec)) }

    /** The signature of [message] by [key], `r | s`. */
    fun sign(
        key: PrivateKey,
        message: ByteArray,
    ): ByteArray {
        val signature = Signature.getInstance(ECDSA)
        signature.initSign(key)
        signature.update(message)
        return derToRaw(signature.sign())
    }

    /** Whether [signature] (`r | s`) is the one of [message] by the key [publicKey] (a point). */
    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        val r = BigInteger(1, signature.copyOfRange(0, COORDINATE_BYTES))
        val s = BigInteger(1, signature.copyOfRange(COORDINATE_BYTES, SIGNATURE_BYTES))
        if (r.signum() <= 0 || s.signum() <= 0 || r >= spec.order || s >= spec.order) return false
        // Checked above: the provider is only given a point of the curve and a well-formed signature. What it
        // still refuses comes from outside all the same: refused, and said to whoever debugs.
        return catchExpected(
            "hierarchy.crypto.verify",
            GeneralSecurityException::class,
            IllegalArgumentException::class,
        ) {
            val key = decode(publicKey) ?: return false
            val verifier = Signature.getInstance(ECDSA)
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(rawToDer(r, s))
        }.getOrDefault(false)
    }

    /** The ECDH secret of [key] and the public key [peer] (a point), [SECRET_BYTES] bytes; `null` when no point. */
    fun agree(
        key: PrivateKey,
        peer: ByteArray,
    ): ByteArray? =
        catchExpected("hierarchy.crypto.agree", GeneralSecurityException::class, IllegalArgumentException::class) {
            val public = decode(peer) ?: return null
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(key)
            agreement.doPhase(public, true)
            agreement.generateSecret()
        }.getOrNull()

    /**
     * `r | s` from the DER signature a provider or the Keystore gives (`SEQUENCE { INTEGER r, INTEGER s }`). Throws
     * an [IllegalArgumentException] for anything else: these bytes come from this phone's own signer.
     */
    fun derToRaw(der: ByteArray): ByteArray {
        var at = 0

        fun byte(): Int {
            require(at < der.size) { "truncated signature" }
            return der[at++].toInt() and 0xFF
        }

        fun integer(): BigInteger {
            require(byte() == DER_INTEGER) { "not an integer" }
            val length = byte()
            require(length in 1..COORDINATE_BYTES + 1 && at + length <= der.size) { "bad integer length" }
            val value = BigInteger(der.copyOfRange(at, at + length))
            at += length
            require(value.signum() > 0) { "signature value not positive" }
            return value
        }
        require(byte() == DER_SEQUENCE) { "not a sequence" }
        require(byte() == der.size - 2) { "bad sequence length" }
        val r = integer()
        val s = integer()
        require(at == der.size) { "trailing bytes" }
        return fixed(r) + fixed(s)
    }

    private fun rawToDer(
        r: BigInteger,
        s: BigInteger,
    ): ByteArray {
        // Two's complement, shortest form: exactly what DER asks of a positive integer.
        val rBytes = r.toByteArray()
        val sBytes = s.toByteArray()
        val body =
            byteArrayOf(DER_INTEGER.toByte(), rBytes.size.toByte()) + rBytes +
                byteArrayOf(DER_INTEGER.toByte(), sBytes.size.toByte()) + sBytes
        return byteArrayOf(DER_SEQUENCE.toByte(), body.size.toByte()) + body
    }

    /** [value] on [COORDINATE_BYTES] bytes, big-endian. */
    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val start = if (bytes.size > COORDINATE_BYTES) bytes.size - COORDINATE_BYTES else 0
        require((0 until start).all { bytes[it].toInt() == 0 }) { "value too large" }
        return ByteArray(COORDINATE_BYTES - (bytes.size - start)) + bytes.copyOfRange(start, bytes.size)
    }
}

/**
 * Who wrote what (`docs/specs/group-hierarchy.md` § 8). A key shared by several phones only says « one of them wrote
 * this ». To say « this administrator wrote this », each phone has its own ECDSA P-256 key pair; its member
 * identifier is the fingerprint of its public key, so that an operation that carries the key and the signature proves
 * its author without any directory. Every operation of an ensemble is signed: an operation without a valid proof is
 * not kept ([HierarchyReplica.add]) and never applies ([HierarchyReducer.reduce]).
 *
 * The same key signs, under other labels, the content keys a phone hands out ([KeyBatch]) and what it shares
 * ([ShareSeal]): a signature made for one of the three is worth nothing for the two others.
 */
object OpAuth {
    private val MEMBER_TAG = "toolmap-hierarchy-member-v2".toByteArray(Charsets.US_ASCII)
    private val ENSEMBLE_TAG = "toolmap-hierarchy-ensemble-v2".toByteArray(Charsets.US_ASCII)
    private val OP_TAG = "toolmap-hierarchy-op-v2".toByteArray(Charsets.US_ASCII)

    /** The member identifier that goes with the public signing key [publicKey]: 8 bytes of its SHA-256, never 0. */
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

    /**
     * Keys kept in memory: the tests, and a phone until its keys live in the Keystore. [signing] and [agreement]
     * are two different pairs.
     */
    fun memoryKeys(
        signing: KeyPair = P256.generate(),
        agreement: KeyPair = P256.generate(),
    ): PhoneKeys =
        object : PhoneKeys {
            override val public = MemberKeys(P256.encode(signing.public), P256.encode(agreement.public))

            override fun sign(message: ByteArray): ByteArray = P256.sign(signing.private, message)

            override fun agree(peer: ByteArray): ByteArray? = P256.agree(agreement.private, peer)
        }

    /** [op] with the proof [keys] give for it. */
    fun sign(
        op: Op,
        keys: PhoneKeys,
    ): Op {
        val proof = OpProof(keys.public.signing, keys.sign(OP_TAG + op.content))
        return Op(op.ensembleId, op.author, op.seq, op.clock, op.change, proof)
    }

    /** `true` when [op] carries the public key its author's identifier is the fingerprint of, and its signature. */
    fun verify(op: Op): Boolean {
        val proof = op.proof ?: return false
        if (proof.publicKey.size != P256.POINT_BYTES || memberId(proof.publicKey) != op.author) return false
        return P256.verify(proof.publicKey, OP_TAG + op.content, proof.signature)
    }

    internal fun fingerprint(
        tag: ByteArray,
        data: ByteArray,
    ): Long {
        val id = sha256(tag, data).take(Long.SIZE_BYTES).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return if (id == 0L) 1L else id
    }

    internal fun sha256(
        tag: ByteArray,
        data: ByteArray,
    ): ByteArray = MessageDigest.getInstance("SHA-256").apply { update(tag) }.digest(data)

    internal fun longBytes(v: Long): ByteArray = ByteArray(Long.SIZE_BYTES) { (v ushr (56 - 8 * it)).toByte() }
}
