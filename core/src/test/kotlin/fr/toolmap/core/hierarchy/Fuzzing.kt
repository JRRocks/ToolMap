package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import java.util.Random
import kotlin.test.fail

/*
 * The seeded fuzzing of the « Opération » decoders (P39.11, spec § 22). No new dependency: a simple generator with
 * own seeds. A decoder must only ever throw [WireException] (the format says so) or, for the ones that parse text,
 * [IllegalArgumentException] (Base45/Base64); anything else is a bug the fuzzing catches. Time and memory stay bounded:
 * the counts a decoder reads are capped, and the bytes it reads are bounded by the input (`ByteReader` checks before it
 * allocates), so a short input never makes a big allocation.
 */

/** One decoder under test: its [name] and what it does with bytes; it must only throw the allowed exceptions. */
internal class Target(
    val name: String,
    /** Allow [IllegalArgumentException] too (text decoders over Base45/Base64). */
    val allowIllegalArgument: Boolean = false,
    val decode: (ByteArray) -> Unit,
)

/** The decoders of the « Opération » mode, each a pure function of bytes (no network, no disk). */
internal fun hierarchyTargets(): List<Target> =
    listOf(
        Target("HierarchyWire.decodeOp") { HierarchyWire.decodeOp(it) },
        Target("HierarchyWire.decodeOps") { HierarchyWire.decodeOps(it) },
        Target("HierarchyWire.decodeSummary") { HierarchyWire.decodeSummary(it) },
        Target("HierarchyWire.decodeState") { HierarchyWire.decodeState(it) },
        Target("HierarchyWire.decodeKeyBatch") { HierarchyWire.decodeKeyBatch(it) },
        Target("OperationSummary.decode") { OperationSummary.decode(it) },
        Target("JoinRequests.decode") { JoinRequests.decode(it) },
        Target("InviteKeys.decode") { InviteKeys.decode(it) },
        Target("OperationLinkState.decode") { OperationLinkState.decode(it) },
        Target("OperationPayloads.readPosition") { OperationPayloads.readPosition(it) },
        Target("OperationPayloads.readMessage") { OperationPayloads.readMessage(it) },
        Target("OperationPayloads.readAlert") { OperationPayloads.readAlert(it) },
        Target("OperationInviteCodec.decodeBytes") { OperationInviteCodec.decodeBytes(it) },
        Target("NameBook.decode") { NameBook.decode(it) },
        Target("DissolutionProof.decode") { DissolutionProof.decode(it) },
        Target("DissolutionNotice.decode") { DissolutionNotice.decode(it) },
        Target("DissolutionTrace.decode") { DissolutionTrace.decode(it) },
        // Text invitations: a link or the Base45 of a QR; this one also catches IllegalArgumentException itself, so it
        // must never throw at all — a wrapper that would throw is the bug.
        Target("OperationInviteCodec.parseOrNull", allowIllegalArgument = true) {
            OperationInviteCodec.parseOrNull(String(it, Charsets.ISO_8859_1))
        },
        // The snapshot parts (sealed files), fed their raw inner bytes.
        Target("HierarchySnapshot.decodeOps") { HierarchySnapshot.decodeOps(it, FUZZ_ENSEMBLE) },
        Target("HierarchySnapshot.restoreKeys") { HierarchySnapshot.restoreKeys(KeyRing(FUZZ_ENSEMBLE, fuzzKeys), it) },
        Target("HierarchySnapshot.restoreNames") { HierarchySnapshot.restoreNames(NameBook(), it) },
        Target("HierarchySnapshot.restoreObjects") {
            HierarchySnapshot.restoreObjects(SharedObjects(FUZZ_ENSEMBLE), it)
        },
        Target("HierarchySnapshot.restoreMessages") {
            HierarchySnapshot.restoreMessages(MessageLog(FUZZ_ENSEMBLE), it)
        },
    )

internal const val FUZZ_ENSEMBLE = 0x0123456789ABCDEFL
internal val fuzzKeys: PhoneKeys = OpAuth.memoryKeys()

/**
 * Runs [target] on [bytes]: fails only when it throws something other than the allowed exceptions, or takes too long.
 * A timeout would show an unbounded loop or allocation; the decoders have none, so a generous bound catches a
 * regression without flaking.
 */
internal fun exercise(
    target: Target,
    bytes: ByteArray,
) {
    val start = System.nanoTime()
    try {
        target.decode(bytes)
    } catch (e: WireException) {
        // The format rejects it: the expected outcome for bad bytes.
    } catch (e: IllegalArgumentException) {
        if (!target.allowIllegalArgument) {
            fail("${target.name} threw IllegalArgumentException on ${bytes.size} bytes: ${e.message}")
        }
    } catch (e: Throwable) {
        fail("${target.name} threw ${e.javaClass.name} on ${bytes.size} bytes: ${e.message}")
    }
    val ms = (System.nanoTime() - start) / 1_000_000
    if (ms > MAX_DECODE_MS) {
        fail("${target.name} took $ms ms on ${bytes.size} bytes (a decoder must be bounded)")
    }
}

/** A decoder never runs longer than this on one input: far above what any bounded decode takes. */
private const val MAX_DECODE_MS = 2_000L

/** A valid corpus to mutate: encoded instances of every format, so a mutation starts from something that parses. */
internal fun fuzzCorpus(): List<ByteArray> {
    val a = OpAuth.memoryKeys()
    val b = OpAuth.memoryKeys()
    val replica = HierarchyReplica.create(a, Names.hide("Chef").hidden, Names.hide("Op").hidden, 7L)
    val ensembleId = replica.ensembleId
    val genesis = replica.ops().first()
    val admit =
        (
            replica.issue(
                Change.AdmitMember(b.memberId, Names.hide("B").hidden, b.public),
            ) as IssueResult.Issued
        ).op
    // The names (2026-11-16): B asks, A refuses, A asks for its own and renames B and the operation; the state then
    // carries a request and an answer.
    val ask =
        OpAuth.sign(Op(ensembleId, b.memberId, 1, 3, Change.RequestName(b.memberId, Names.hide("B2").hidden)), b)
    replica.add(ask)
    val named =
        listOf(
            Change.AnswerName(b.memberId, (ask.change as Change.RequestName).name, accept = false),
            Change.RequestName(a.memberId, Names.hide("Chef 2").hidden),
            Change.RenameMember(b.memberId, Names.hide("B3").hidden),
            Change.RenameEnsemble(Names.hide("Op 2").hidden),
            // The colour of a group (P39.14): the state then carries a colour too.
            Change.CreateGroup(0x77L, Names.hide("G").hidden),
            Change.SetGroupColor(0x77L, 0x14918C),
            Change.SetPositionInterval(30),
        ).map { (replica.issue(it) as IssueResult.Issued).op }
    // Signed but kept out of the replica, so its state still carries A's request.
    val last = named.last()
    val withdraw =
        OpAuth.sign(
            Op(ensembleId, a.memberId, last.seq + 1, last.clock + 1, Change.WithdrawName(a.memberId)),
            a,
        )
    val state = replica.state
    val card = Names.hide("Op")
    val server = fr.toolmap.core.p2p.RelayConfig.of("https://example.invalid", "key")
    val invite =
        OperationInvite(1_700_000_000, ensembleId, ByteArray(16) { it.toByte() }, 1, ByteArray(32) { 3 }, card, server)
    val member = Member(b.memberId, Names.hide("B").hidden, b.public)
    val request = JoinRequests.make(b, ensembleId, Names.hide("B"), listOf(member), 1_700_000_000)
    val announcement = InviteKeys.announce(a, ensembleId, 1, ByteArray(32))
    val grant = InviteKeys.grant(a, announcement, ByteArray(32), listOf(member))
    val link =
        OperationLinkState(ensembleId, ByteArray(16), server, listOf(EpochKey(ByteArray(32), announcement)), card, null)
    val summary =
        OperationSummary(replica.summary(), 0L, 0, emptyMap())
    val fix = fr.toolmap.core.p2p.PeerFix(45.1, 5.5, timeSec = 100)
    val text = OperationText(1L, 100L, "x")
    val keyRing = KeyRing(ensembleId, a)
    val objects = SharedObjects(ensembleId)
    val names = NameBook().apply { learn(card) }
    val messages = MessageLog(ensembleId).apply { add(Channel.ENSEMBLE, LoggedMessage(b.memberId, 1L, 2L, 3L, "x")) }

    val corpus = ArrayList<ByteArray>()
    corpus += HierarchyWire.encodeOp(genesis)
    corpus += HierarchyWire.encodeOps(listOf(genesis, admit))
    corpus += HierarchyWire.encodeOps(listOf(ask) + named + withdraw)
    corpus += HierarchyWire.encodeSummary(replica.summary())
    corpus += HierarchyWire.encodeState(state)
    corpus += OperationSummary(replica.summary(), 7L, 1, mapOf(Channel.ENSEMBLE to 9L)).encode()
    corpus += OperationSummary(replica.summary(), 7L, 1, mapOf(Channel.ENSEMBLE to 9L), to = b.memberId).encode()
    corpus += request.encode()
    corpus += grant.encode()
    corpus += link.encode()
    corpus += OperationInviteCodec.encodeBytes(invite)
    corpus += OperationPayloads.position(fix)
    corpus += OperationPayloads.message(text)
    corpus += OperationPayloads.alert(OperationText(1L, 100L, "x", fix))
    corpus += NameBook.encode(listOf(card))
    corpus += HierarchySnapshot.encodeOps(replica.ops())
    corpus += HierarchySnapshot.encodeKeys(keyRing)
    corpus += HierarchySnapshot.encodeNames(names)
    corpus += HierarchySnapshot.encodeObjects(objects)
    corpus += HierarchySnapshot.encodeMessages(messages)
    corpus += summary.encode()
    // A whole dissolution proof (genesis + dissolution), for DissolutionProof/Notice/Trace.
    val dissolve = (replica.issue(Change.Dissolve) as IssueResult.Issued).op
    val proof = assertProof(DissolutionProof.of(replica))
    corpus += proof.encode()
    corpus += DissolutionNotice(100L, proof).encode()
    corpus +=
        DissolutionTrace(
            ensembleId,
            100L,
            42L,
            ByteArray(16),
            server,
            listOf(TraceKey(1, ByteArray(32))),
            proof,
        ).encode()
    return corpus
}

private fun assertProof(proof: DissolutionProof?): DissolutionProof = proof ?: error("no proof in the corpus")

/** One byte flipped, inserted, removed or a run overwritten in [bytes], chosen by [random]. */
internal fun mutate(
    bytes: ByteArray,
    random: Random,
): ByteArray {
    if (bytes.isEmpty()) return ByteArray(random.nextInt(8)).also(random::nextBytes)
    return when (random.nextInt(6)) {
        0 -> {
            bytes.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
        }

        1 -> {
            bytes.copyOf().also {
                it[random.nextInt(it.size)] =
                    (it[random.nextInt(it.size)].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
        }

        2 -> {
            bytes.copyOf(random.nextInt(bytes.size + 1))
        }

        // truncate
        3 -> { // insert a random byte
            val at = random.nextInt(bytes.size + 1)
            bytes.copyOfRange(0, at) + random.nextInt(256).toByte() + bytes.copyOfRange(at, bytes.size)
        }

        4 -> { // overwrite a short run (e.g. a length field pushed to the maximum)
            val out = bytes.copyOf()
            val at = random.nextInt(out.size)
            val len = minOf(out.size - at, 1 + random.nextInt(8))
            for (i in 0 until len) out[at + i] = (-1).toByte()
            out
        }

        else -> {
            bytes.copyOf().also { random.nextBytes(it) }
        } // same length, all random
    }
}

/** Whether the long fuzzing is asked for (`-Ptoolmap.hierarchy.fuzz=true`). */
internal fun longFuzzEnabled(): Boolean = System.getProperty("toolmap.hierarchy.fuzz") == "true"
