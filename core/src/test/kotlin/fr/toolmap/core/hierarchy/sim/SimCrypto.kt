package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.MemberKeys
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.hierarchy.PhoneKeys
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.SplittableRandom

/**
 * A [SecureRandom] that gives the same bytes for the same seed, so that a simulated scenario replays bit for bit from
 * its seed (P39.10). Only for the tests: what it draws is predictable.
 */
class SeededRandom(
    seed: Long,
) : SecureRandom() {
    private val inner = SplittableRandom(seed)

    override fun nextBytes(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            var v = inner.nextLong()
            var k = 0
            while (k < Long.SIZE_BYTES && i < bytes.size) {
                bytes[i++] = v.toByte()
                v = v ushr 8
                k++
            }
        }
    }

    // Called by the constructor of java.util.Random before this class is set up: nothing to do, the seed is fixed.
    override fun setSeed(seed: Long) = Unit

    override fun setSeed(seed: ByteArray) = Unit

    override fun generateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also(::nextBytes)

    override fun getAlgorithm(): String = "seeded-test"
}

/** What one phone's keys did: signatures made, key agreements made (its own agreement key). */
class KeyCounters {
    var signatures = 0L
    var agreements = 0L
}

/**
 * The keys of a simulated phone: P-256 pairs drawn from a seed, and signatures made with a seeded nonce, so that the
 * same scenario writes the same bytes. Counts what it does.
 */
class SimKeys(
    private val signing: KeyPair,
    private val agreement: KeyPair,
    seed: Long,
) : PhoneKeys {
    val counters = KeyCounters()
    private val random = SeededRandom(seed)

    override val public = MemberKeys(P256.encode(signing.public), P256.encode(agreement.public))

    override fun sign(message: ByteArray): ByteArray {
        counters.signatures++
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(signing.private, random)
        s.update(message)
        return P256.derToRaw(s.sign())
    }

    override fun agree(peer: ByteArray): ByteArray? {
        counters.agreements++
        return P256.agree(agreement.private, peer)
    }

    /** The same agreement, not counted: what the checks do with this phone's key is not its work. */
    fun agreeQuietly(peer: ByteArray): ByteArray? = P256.agree(agreement.private, peer)
}

/**
 * Key pairs drawn once from fixed seeds and kept for the whole test run: generating P-256 keys for 30 phones in every
 * scenario would cost more than the scenario. A scenario picks its pairs through a permutation drawn from its seed, so
 * that the order of the member identifiers (which decides ties between operations) changes from one seed to another.
 */
object KeyPool {
    const val SIZE = 96
    private val pairs = ArrayList<KeyPair>()

    fun pair(index: Int): KeyPair =
        synchronized(pairs) {
            require(index in 0 until SIZE) { "key $index" }
            while (pairs.size <= index) pairs += generate(pairs.size)
            pairs[index]
        }

    private fun generate(index: Int): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SeededRandom(0x5EED_0000L + index))
        }.generateKeyPair()

    /** The keys of the phone in [slot] (0 until [SIZE] / 2), signing with nonces drawn from [nonceSeed]. */
    fun keys(
        slot: Int,
        nonceSeed: Long,
    ): SimKeys = SimKeys(pair(2 * slot), pair(2 * slot + 1), nonceSeed)
}
