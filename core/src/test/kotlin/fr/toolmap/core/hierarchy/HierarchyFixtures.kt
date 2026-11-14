package fr.toolmap.core.hierarchy

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** One phone of the tests: its keys, and the member identifier that goes with them. */
internal class Phone(
    val keys: PhoneKeys,
) {
    val id: Long = keys.memberId
}

/**
 * The phones of the tests. A member identifier is the fingerprint of a key, so it cannot be chosen: the phones are
 * sorted by identifier, which gives the names below a known order whatever the keys drawn.
 */
private val PHONES: List<Phone> =
    List(12) { Phone(OpAuth.memoryKeys()) }.sortedWith { a, b -> java.lang.Long.compareUnsigned(a.id, b.id) }
private val BY_ID: Map<Long, Phone> = PHONES.associateBy { it.id }

/** Smaller than every other identifier of the tests. */
internal val FIRST = PHONES[0].id

/** Identifiers of the tests: A < B < C < D < E… in the order of the operations at equal clocks. */
internal val A = PHONES[1].id
internal val B = PHONES[2].id
internal val C = PHONES[3].id
internal val D = PHONES[4].id
internal val E = PHONES[5].id
internal val F = PHONES[6].id
internal val H = PHONES[7].id
internal val I = PHONES[8].id
internal val J = PHONES[9].id

/** Never admitted by the fixtures, and greater than every other identifier. */
internal val OUTSIDER = PHONES[11].id
internal const val G1 = 0x101L
internal const val G2 = 0x102L
internal const val G3 = 0x103L
internal const val NONCE = 7L
internal const val ROOT = HierarchyState.ROOT

internal fun keysOf(member: Long): PhoneKeys = BY_ID.getValue(member).keys

/** The card of [name] in the tests: always the same salt for the same name, so that two changes built apart agree. */
internal fun card(name: String): NameCard =
    NameCard(OpAuth.sha256("test-salt".toByteArray(), name.toByteArray()).copyOf(NameCard.SALT_BYTES), name)

/** [name] as the operations carry it. */
internal fun hidden(name: String): HiddenName = card(name).hidden

internal fun publicOf(member: Long): MemberKeys = keysOf(member).public

/** The admission of a phone of the tests, with its real keys. */
internal fun admit(
    member: Long,
    name: String = "Membre",
): Change.AdmitMember = Change.AdmitMember(member, hidden(name), publicOf(member))

internal fun leader(
    unit: Long,
    member: Long,
) = Change.SetPost(unit, Post.LEADER, member)

internal fun deputy(
    unit: Long,
    member: Long,
) = Change.SetPost(unit, Post.DEPUTY, member)

internal fun genesis(
    creator: Long = A,
    name: String = "Sortie du jour",
    rules: HierarchyRules = HierarchyRules.DEFAULT,
    nonce: Long = NONCE,
) = Change.CreateEnsemble(nonce, hidden(name), hidden("Alpha"), publicOf(creator), rules)

internal fun newEnsemble(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
    HierarchyReplica.create(keysOf(A), hidden("Alpha"), hidden("Sortie du jour"), NONCE, rules)

/** An operation written by hand and signed by its author: what a phone would not have written by itself. */
internal fun signedOp(
    ensembleId: Long,
    author: Long,
    seq: Int,
    clock: Long,
    change: Change,
): Op = OpAuth.sign(Op(ensembleId, author, seq, clock, change), keysOf(author))

/** The units whose own channel [author] may share [kind] into, nearest first. */
internal fun HierarchyState.shareUnits(
    author: Long,
    kind: ShareKind,
): List<Long> = unitsReadBy(author).filter { mayShare(author, kind, it) }

/** The identifiers of the readers of [channel]. */
internal fun HierarchyState.readerIds(channel: Channel): List<Long> = readersOf(channel).map { it.id }

/** Asks for [change] and expects it to be written. */
internal fun HierarchyReplica.must(change: Change): Op = assertIs<IssueResult.Issued>(issue(change), "$change").op

/** Asks for [change] and expects this phone to refuse it. */
internal fun HierarchyReplica.refused(change: Change): Rejection =
    assertIs<IssueResult.Refused>(issue(change), "$change").reason

/** The copy of [self], a phone that received everything [this] holds. */
internal fun HierarchyReplica.copyFor(self: Long): HierarchyReplica =
    HierarchyReplica.restore(ensembleId, self, ops(), keysOf(self))

/** Why [op] changed nothing on this phone; `null` when it applied. */
internal fun HierarchyReplica.rejectionOf(op: Op): Rejection? = history().single { it.op == op }.rejection

/** One catch-up between two phones, through the bytes of the format, until neither has anything to send. */
internal fun sync(
    a: HierarchyReplica,
    b: HierarchyReplica,
    max: Int = HierarchyWire.MAX_BATCH,
): Int {
    var rounds = 0
    while (true) {
        val toB = a.missingFor(HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(b.summary())), max)
        val toA = b.missingFor(HierarchyWire.decodeSummary(HierarchyWire.encodeSummary(a.summary())), max)
        if (toA.isEmpty() && toB.isEmpty()) return rounds
        for (block in HierarchyWire.encodeOpBlocks(toB)) b.addAll(HierarchyWire.decodeOps(block))
        for (block in HierarchyWire.encodeOpBlocks(toA)) a.addAll(HierarchyWire.decodeOps(block))
        rounds++
        check(rounds < 1000) { "the catch-up does not end" }
    }
}

/** Same state, same refusals, same fingerprint, and a state with nothing wrong. */
internal fun assertSame(
    expected: HierarchyReplica,
    actual: HierarchyReplica,
    message: String = "",
) {
    assertEquals(expected.state, actual.state, message)
    assertEquals(expected.summary(), actual.summary(), message)
    assertEquals(
        expected.history().map { it.op to it.rejection },
        actual.history().map { it.op to it.rejection },
        message,
    )
    assertNull(actual.state.inconsistency(), message)
}

/**
 * An ensemble with A (creator), B (administrator), C and D (admitted, waiting for a group), and the groups G1 and
 * G2. Every post is vacant.
 */
internal fun staffed(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
    newEnsemble(rules).apply {
        must(admit(B, "Bravo"))
        must(admit(C, "Charlie"))
        must(admit(D, "Delta"))
        must(Change.AddAdmin(B))
        must(Change.CreateGroup(G1, hidden("Groupe 1")))
        must(Change.CreateGroup(G2, hidden("Groupe 2")))
    }

/** [staffed], then C and D in G1, E and F in G2: everyone has a group but A and B. */
internal fun deployed(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
    staffed(rules).apply {
        must(admit(E, "Echo"))
        must(admit(F, "Foxtrot"))
        must(Change.AssignMember(C, G1))
        must(Change.AssignMember(D, G1))
        must(Change.AssignMember(E, G2))
        must(Change.AssignMember(F, G2))
    }

/** Rules with two levels of groups, to try what holds for sub-groups. */
internal val TWO_LEVELS = HierarchyRules(limits = HierarchyLimits(maxMembers = 30, maxGroups = 12, maxDepth = 2))

/** One phone as it will be once wired: its copy of the operations, the content keys, names and objects it holds. */
internal class Device(
    val id: Long,
    source: HierarchyReplica,
) {
    val replica: HierarchyReplica = source.copyFor(id)
    val ring = KeyRing(source.ensembleId, keysOf(id))
    val names = NameBook()
    val objects = SharedObjects(source.ensembleId)
    val state: HierarchyState get() = replica.state

    /** Receives [batch] as it travels: through the bytes of the format. */
    fun take(batch: KeyBatch): KeyOffer =
        ring.offer(HierarchyWire.decodeKeyBatch(HierarchyWire.encodeKeyBatch(batch)), replica)

    /** Hands out a key for [channel]; fails when this phone may not. */
    fun issue(channel: Channel): KeyBatch =
        requireNotNull(ring.issue(state, channel)) { "this phone does not read $channel" }

    fun issue(unit: Long): KeyBatch = issue(Channel.unit(unit))

    fun seal(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
    ): SealResult = ShareSeal.seal(state, ring, keysOf(id), kind, channel, payload)

    fun seal(
        kind: ShareKind,
        unit: Long,
        text: String,
    ): SealResult = seal(kind, Channel.unit(unit), text.toByteArray())

    /** Seals and expects a frame. */
    fun frame(
        kind: ShareKind,
        channel: Channel,
        payload: ByteArray,
    ): ByteArray = assertIs<SealResult.Sealed>(seal(kind, channel, payload), "$kind in $channel").frame

    fun frame(
        kind: ShareKind,
        channel: Channel,
        text: String,
    ): ByteArray = frame(kind, channel, text.toByteArray())

    fun frame(
        kind: ShareKind,
        unit: Long,
        text: String,
    ): ByteArray = frame(kind, Channel.unit(unit), text)

    /** Tries to seal and expects a refusal. */
    fun sealRefusal(
        kind: ShareKind,
        channel: Channel,
        text: String,
    ): ShareRefusal = assertIs<SealResult.Refused>(seal(kind, channel, text.toByteArray()), "$kind in $channel").reason

    fun sealRefusal(
        kind: ShareKind,
        unit: Long,
        text: String,
    ): ShareRefusal = sealRefusal(kind, Channel.unit(unit), text)

    fun open(frame: ByteArray): OpenResult = ShareSeal.open(state, ring, frame)

    /** Opens and expects the share that was sealed. */
    fun share(frame: ByteArray): Share = assertIs<OpenResult.Opened>(open(frame)).share

    /** Opens and expects the text that was sealed. */
    fun read(frame: ByteArray): String = String(share(frame).payload)

    /** Opens and expects a refusal. */
    fun refusal(frame: ByteArray): ShareRefusal = assertIs<OpenResult.Refused>(open(frame)).reason

    /** Opens a frame that carries a sketch or a waypoint and takes the record in. */
    fun takeObject(frame: ByteArray): ObjectVerdict = objects.accept(share(frame), replica::signingKeyOf)

    /** What this phone shows of [channel]: the bodies of the objects still there. */
    fun shown(channel: Channel): List<String> = objects.shown(channel).map { String(it.body) }
}

/** Several phones of one ensemble. */
internal class World(
    val source: HierarchyReplica,
    members: List<Long>,
) {
    val devices: Map<Long, Device> = members.associateWith { Device(it, source) }

    operator fun get(member: Long): Device = devices.getValue(member)

    /** Every phone but [away] hears of every operation, then tries again the key batches that waited. */
    fun spread(vararg away: Long) {
        val present = devices.values.filter { it.id !in away }
        for (device in present) sync(device.replica, present.first().replica)
        for (device in present) sync(device.replica, present.first().replica)
        for (device in present) device.ring.retry(device.replica)
    }

    /** [issuer] hands out a key for [channel]; every phone but [away] receives the batch. */
    fun handOut(
        issuer: Long,
        channel: Channel,
        vararg away: Long,
    ): KeyBatch {
        val batch = this[issuer].issue(channel)
        for (device in devices.values) if (device.id != issuer && device.id !in away) device.take(batch)
        return batch
    }

    fun handOut(
        issuer: Long,
        unit: Long,
        vararg away: Long,
    ): KeyBatch = handOut(issuer, Channel.unit(unit), *away)

    /** A key for every channel that has readers and none yet, each handed out by the first of its [keyIssuers]. */
    fun handOutMissing(vararg away: Long) {
        val state = devices.values.first { it.id !in away }.state
        for (channel in state.channels()) {
            val issuer = state.keyIssuers(channel).firstOrNull { it in devices && it !in away } ?: continue
            if (this[issuer].ring.sealingKey(this[issuer].state, channel) == null) handOut(issuer, channel, *away)
        }
    }

    /** Every phone receives [frame]: the ones that could open it. */
    fun openers(frame: ByteArray): Set<Long> =
        devices.values.filter { it.open(frame) is OpenResult.Opened }.map { it.id }.toSet()

    /** [sealer] shares [record] (its own, or one it sends again); every phone that opens the frame takes it in. */
    fun deliver(
        sealer: Long,
        record: SharedRecord,
        vararg away: Long,
    ): ByteArray {
        val frame = this[sealer].frame(record.kind, record.channel, SharedRecords.encode(record))
        for (device in devices.values) {
            if (device.id !in away && device.open(frame) is OpenResult.Opened) device.takeObject(frame)
        }
        return frame
    }

    /** [author] makes an object, or changes the one it made under [objectId], and shares it. */
    fun post(
        author: Long,
        kind: ShareKind,
        channel: Channel,
        objectId: Long,
        text: String,
        vararg away: Long,
    ): SharedRecord {
        val previous = this[author].objects.find(ObjectRef(channel, author, objectId))
        val record =
            SharedRecords.write(
                keysOf(author),
                source.ensembleId,
                kind,
                channel,
                objectId,
                text.toByteArray(),
                previous,
            )
        deliver(author, record, *away)
        return record
    }
}

/** The names the fixtures give: the ensemble, its two groups, its members. */
internal val NAMES =
    listOf("Sortie du jour", "Groupe 1", "Groupe 2", "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Hotel")

/**
 * The phones of [deployed] and of H, admitted and waiting: A creator, B administrator, C and D in G1, E and F in G2.
 * Keys handed out for the ensemble (by A), G1 (by C) and G2 (by E), then for every lane that has readers: down to
 * each group (by A), and the waiting lane of H (by H).
 */
internal fun world(rules: HierarchyRules = HierarchyRules.DEFAULT): World =
    World(deployed(rules).apply { must(admit(H, "Hotel")) }, listOf(A, B, C, D, E, F, H)).apply {
        handOut(A, ROOT)
        handOut(C, G1)
        handOut(E, G2)
        handOutMissing()
    }

/**
 * [world] with its posts: C leads G1 with D as deputy, E leads G2 where F stays a plain member; A is the creator and
 * B an administrator, both without a group; H waits. Every channel that has readers has its key.
 */
internal fun commanded(rules: HierarchyRules = HierarchyRules.DEFAULT): World =
    world(rules).apply {
        this[B].replica.must(leader(G1, C))
        this[B].replica.must(deputy(G1, D))
        this[B].replica.must(leader(G2, E))
        spread()
        handOutMissing()
    }
