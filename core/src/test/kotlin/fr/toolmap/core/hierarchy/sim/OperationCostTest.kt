package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Role
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import org.junit.Assume
import java.io.File
import java.security.Key
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.KeyAgreementSpi
import javax.crypto.SecretKey
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Counts what the JVM's provider is asked to do: signatures, verifications, key agreements (tests only). */
object CryptoCount {
    var signs = 0L
    var verifies = 0L
    var agreements = 0L

    /** Who is counted now (the phone whose node runs), and its counters. */
    var sink: ((String) -> Unit)? = null

    fun reset() {
        signs = 0
        verifies = 0
        agreements = 0
    }
}

class CountingSignature : SignatureSpi() {
    private val inner = Signature.getInstance("SHA256withECDSA", "SunEC")

    override fun engineInitVerify(publicKey: PublicKey) = inner.initVerify(publicKey)

    override fun engineInitSign(privateKey: PrivateKey) = inner.initSign(privateKey)

    override fun engineInitSign(
        privateKey: PrivateKey,
        random: SecureRandom,
    ) = inner.initSign(privateKey, random)

    override fun engineUpdate(b: Byte) = inner.update(b)

    override fun engineUpdate(
        b: ByteArray,
        off: Int,
        len: Int,
    ) = inner.update(b, off, len)

    override fun engineSign(): ByteArray {
        CryptoCount.signs++
        CryptoCount.sink?.invoke("sign")
        return inner.sign()
    }

    override fun engineVerify(sigBytes: ByteArray): Boolean {
        CryptoCount.verifies++
        CryptoCount.sink?.invoke("verify")
        return inner.verify(sigBytes)
    }

    @Deprecated("JCA")
    override fun engineSetParameter(
        param: String?,
        value: Any?,
    ) = Unit

    @Deprecated("JCA")
    override fun engineGetParameter(param: String?): Any? = null
}

class CountingAgreement : KeyAgreementSpi() {
    private val inner = KeyAgreement.getInstance("ECDH", "SunEC")

    override fun engineInit(
        key: Key,
        random: SecureRandom?,
    ) = inner.init(key, random ?: SecureRandom())

    override fun engineInit(
        key: Key,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?,
    ) = inner.init(key, params, random ?: SecureRandom())

    override fun engineDoPhase(
        key: Key,
        lastPhase: Boolean,
    ): Key? = inner.doPhase(key, lastPhase)

    override fun engineGenerateSecret(): ByteArray {
        CryptoCount.agreements++
        CryptoCount.sink?.invoke("agree")
        return inner.generateSecret()
    }

    override fun engineGenerateSecret(
        sharedSecret: ByteArray,
        offset: Int,
    ): Int {
        CryptoCount.agreements++
        CryptoCount.sink?.invoke("agree")
        return inner.generateSecret(sharedSecret, offset)
    }

    override fun engineGenerateSecret(algorithm: String): SecretKey {
        CryptoCount.agreements++
        CryptoCount.sink?.invoke("agree")
        return inner.generateSecret(algorithm)
    }
}

class CountingProvider : Provider("ToolMapCount", "1.0", "counts signatures and agreements (tests)") {
    init {
        putService(Service(this, "Signature", "SHA256withECDSA", CountingSignature::class.java.name, null, null))
        putService(Service(this, "KeyAgreement", "ECDH", CountingAgreement::class.java.name, null, null))
    }
}

/**
 * What the « Opération » mode costs (P39.10, `docs/specs/group-hierarchy.md` § 21): an operation of 5, 15 or 30
 * members in groups of five, each with a leader and a deputy, the creator leading it with an administrator; every
 * phone sends its position every 30 s (the default cadence of the sharing, `p2p-sharing.md` § 6) for ten minutes of the
 * simulated server. Counted per phone: frames and bytes written and received, writes on the server, signatures,
 * verifications and key agreements (by the JVM's provider), time spent in the node. `./gradlew check` measures 5
 * members for two minutes and checks the counts per position; the three sizes run with the long series
 * (`-Ptoolmap.hierarchy.soak=…`) and write `core/build/hierarchy-cost.txt`.
 */
class OperationCostTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink
    private val provider = CountingProvider()

    @BeforeTest
    fun setUp() {
        Problems.sink = Problems.Sink { problems.add(it) }
        Security.insertProviderAt(provider, 1)
    }

    @AfterTest
    fun tearDown() {
        Security.removeProvider(provider.name)
        CryptoCount.sink = null
        Problems.sink = previousSink
        assertTrue(problems.none { !it.expected }, "bugs reported: ${problems.filter { !it.expected }}")
    }

    private val server = RelayConfig.of("https://example.invalid", "key")

    class PhoneCost {
        var signs = 0L
        var verifies = 0L
        var agreements = 0L
    }

    class Cost(
        val members: Int,
        val minutes: Double,
        val lines: List<String>,
        val positionsSent: Long,
        val positionsHeard: Long,
        val serverPositionWrites: Long,
        val signsPerPosition: Double,
        val verifiesPerPositionHeard: Double,
    )

    /** Builds an operation of [n] members, then measures [minutes] of positions every [cadenceS] seconds. */
    private fun measure(
        n: Int,
        minutes: Int,
        cadenceS: Long = 30,
    ): Cost {
        val w = World(n.toLong() * 1_000)
        val phones = List(n) { i -> SimPhone(w, i, "P$i", KeyPool.keys(i, 70L + i), 0L, 500L + i, server) }
        val counts = phones.associate { it.label to PhoneCost() }
        CryptoCount.sink = { what ->
            val c = counts[w.current]
            if (c != null) {
                when (what) {
                    "sign" -> c.signs++
                    "verify" -> c.verifies++
                    else -> c.agreements++
                }
            }
        }
        val a = phones[0]
        a.create(Names.hide("Opération Mesure", SeededRandom(1)), Names.hide("Chef", SeededRandom(2)))
        w.runFor(2_000)
        for (p in phones.drop(1)) {
            val invite = requireNotNull(a.call { it.invitation(a.clock() / 1000) })
            p.join(invite, Names.hide("Membre ${p.index}", SeededRandom(p.index.toLong())))
            w.runFor(25_000)
            check(a.call { it.admit(p.id) } is IssueResult.Issued) { "${p.label} not let in" }
            w.runFor(5_000)
        }

        fun must(change: Change) = check(a.call { it.issue(change) } is IssueResult.Issued) { "$change refused" }
        val groups = (0 until (n + 4) / 5).map { g -> 0x100L + g }
        for (g in groups) {
            val card = Names.hide("Groupe $g", SeededRandom(g))
            must(Change.CreateGroup(g, card.hidden))
            a.call { it.learnAndShare(card) }
        }
        for ((i, p) in phones.withIndex()) must(Change.AssignMember(p.id, groups[i / 5]))
        for ((gi, g) in groups.withIndex()) {
            val inside = phones.drop(gi * 5).take(5)
            if (inside.size > 1) must(Change.SetPost(g, Post.LEADER, inside[1].id))
            if (inside.size > 2) must(Change.SetPost(g, Post.DEPUTY, inside[2].id))
        }
        must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, a.id))
        if (n > 3) must(Change.AddAdmin(phones[3].id))
        w.runFor(4 * 60_000L)
        // Every phone sends once, so that the keys of the operation are made before the measure.
        for (p in phones) p.call { it.sendPosition(PeerFix(45.0, 5.7, timeSec = p.clock() / 1000)) }
        w.runFor(60_000)
        // The measure.
        for (c in counts.values) {
            c.signs = 0
            c.verifies = 0
            c.agreements = 0
        }
        val before =
            phones.associate { p ->
                p.label to Triple(p.framesWritten, p.bytesWritten, p.framesReceived to p.bytesReceived)
            }
        val cpuBefore = phones.associate { it.label to it.cpuNs }
        val heardBefore = phones.sumOf { it.positionsHeard }
        val stats0 = w.stats.let { Triple(it.positionWrites, it.controlWrites, it.heartbeatWrites) }
        val cleanups0 = w.stats.cleanupWrites
        var sent = 0L
        val start = w.now
        val end = start + minutes * 60_000L
        val offsets = phones.associateWith { (it.index * 997L) % (cadenceS * 1000) }
        var t = start
        while (t < end) {
            for (p in phones) {
                val due = start + offsets.getValue(p) + ((t - start) / (cadenceS * 1000)) * cadenceS * 1000
                if (due in t until t + 1000) {
                    w.runUntil(due)
                    val fix = PeerFix(45.0 + sent * 1e-6, 5.7, timeSec = p.clock() / 1000 + sent)
                    if (p.call { it.sendPosition(fix) } == ShareOutcome.SENT) sent++
                }
            }
            t += 1000
            w.runUntil(t)
        }
        w.runUntil(end + 5_000)
        val heard = phones.sumOf { it.positionsHeard } - heardBefore
        val state = a.n.state
        val lines = ArrayList<String>()
        val perMin = 1.0 / minutes
        val byRole = LinkedHashMap<String, MutableList<String>>()
        for (p in phones) {
            val b = before.getValue(p.label)
            val c = counts.getValue(p.label)
            val roles = state.rolesOf(p.id)
            val role =
                when {
                    Role.CREATOR in roles -> "creator (chief)"
                    Role.ADMIN in roles -> "administrator"
                    Role.GROUP_LEADER in roles -> "group leader"
                    Role.GROUP_DEPUTY in roles -> "group deputy"
                    else -> "member"
                }
            val line =
                (
                    "%s %-15s out %.1f frames/min %.0f B/min, in %.1f frames/min %.0f B/min, " +
                        "%.1f sign/min %.1f verify/min %.1f agree/min, cpu %.1f ms/min"
                ).format(
                    p.label,
                    role,
                    (p.framesWritten - b.first) * perMin,
                    (p.bytesWritten - b.second) * perMin,
                    (p.framesReceived - b.third.first) * perMin,
                    (p.bytesReceived - b.third.second) * perMin,
                    c.signs * perMin,
                    c.verifies * perMin,
                    c.agreements * perMin,
                    (p.cpuNs - cpuBefore.getValue(p.label)) / 1e6 * perMin,
                )
            byRole.getOrPut(role) { ArrayList() } += line
        }
        for ((role, ls) in byRole) {
            lines += "  $role: ${ls.size}"
            lines += ls.take(2).map { "    $it" }
        }
        val signs = counts.values.sumOf { it.signs }
        val verifies = counts.values.sumOf { it.verifies }
        val agreements = counts.values.sumOf { it.agreements }
        val st = w.stats
        lines +=
            (
                "  server: %.1f position writes/min, %.1f control writes/min, %.1f heartbeats/min, " +
                    "%.1f cleanups/min for the room"
            ).format(
                (st.positionWrites - stats0.first) * perMin,
                (st.controlWrites - stats0.second) * perMin,
                (st.heartbeatWrites - stats0.third) * perMin,
                (st.cleanupWrites - cleanups0) * perMin,
            )
        lines +=
            (
                "  per position: %.2f signatures, %.2f verifications per phone that heard it, " +
                    "%.1f phones heard each; " +
                    "%d sent, %d heard, %d agreements in the room"
            ).format(
                signs.toDouble() / maxOf(1, sent),
                verifies.toDouble() / maxOf(1L, heard),
                heard.toDouble() / maxOf(1, sent),
                sent,
                heard,
                agreements,
            )
        val cpu = phones.sumOf { it.cpuNs - cpuBefore.getValue(it.label) }
        lines +=
            "  time in the nodes: %.0f µs per position sent and heard by every phone (sum of all phones)"
                .format(cpu / 1e3 / maxOf(1, sent))
        return Cost(
            n,
            minutes.toDouble(),
            lines,
            sent,
            heard,
            st.positionWrites - stats0.first,
            signs.toDouble() / maxOf(1, sent),
            verifies.toDouble() / maxOf(1L, heard),
        )
    }

    @Test
    fun `five members for two minutes, one signature per position and one verification per phone that hears it`() {
        val c = measure(5, 2)
        assertEquals(c.positionsSent, c.serverPositionWrites, "one write on the server per position")
        assertEquals(c.positionsSent * 4, c.positionsHeard, "every other member hears every position")
        val detail = c.lines.joinToString("\n")
        assertTrue(c.signsPerPosition in 1.0..1.2, "signatures per position: ${c.signsPerPosition}\n$detail")
        val perHeard = c.verifiesPerPositionHeard
        assertTrue(perHeard in 1.0..1.2, "verifications per position heard: $perHeard\n$detail")
    }

    /** The three sizes, ten minutes each: only with the long series. */
    @Test
    fun `the costs for 5, 15 and 30 members`() {
        val soak = System.getProperty("toolmap.hierarchy.soak").orEmpty()
        Assume.assumeTrue("only with -Ptoolmap.hierarchy.soak=…", soak.isNotEmpty() && soak != "false")
        val out = StringBuilder()
        for (n in listOf(5, 15, 30)) {
            val t0 = System.currentTimeMillis()
            val c = measure(n, 10)
            val wall = (System.currentTimeMillis() - t0) / 1000
            val minutes = c.minutes.toInt()
            out.appendLine("== $n members, positions every 30 s, $minutes min measured ($wall s of wall time)")
            for (l in c.lines) out.appendLine(l)
        }
        File(System.getProperty("user.dir"), "build/hierarchy-cost.txt").writeText(out.toString())
        println(out)
    }
}
