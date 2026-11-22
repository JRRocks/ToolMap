package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException

/**
 * What a phone tells another about its copy of the operations: for each author, how many operations it holds without
 * a gap, and a fingerprint of them. The other answers with what is missing ([HierarchyReplica.missingFor]).
 */
data class Summary(
    val ensembleId: Long,
    val counts: Map<Long, Int>,
    val digest: Long,
)

/**
 * The binary format of the hierarchical groups (`docs/specs/group-hierarchy.md` § 7), big-endian like [Wire]. Every
 * block starts with `version (1) | kind (1)`. Not tied to a transport: these bytes are the payload a later message
 * type of the mesh will carry.
 *
 * Version 4 (P39.4, 2026-11-14): the rules carry the kinds of share that have one destination per person (the
 * alert). Version 3, the same day: names are hidden (a commitment of 32 bytes in place of the text), content keys
 * and shares name a channel (a lane and its scope) where version 2 named a unit, the rules carry two more kinds of
 * share, the role of who waits, the wider share reaches and the succession of the deputy. Versions 1 to 3 were
 * never deployed (private design branch): they are not read, a block of another version is refused as such.
 *
 * Still version 4, 2026-11-16 (names): five changes more (codes 15 to 19: rename a member, ask for a name, take the
 * request back, answer it, rename the ensemble), the rights and shares that go with them in the rules of a new
 * ensemble, and a state block that ends with the requests of a name and the last answers when it has some. A block
 * written before reads the same; a phone of before refuses the operations of a new ensemble (unknown rules).
 *
 * Still version 4, 2026-11-18 (the rhythm of the positions): one change more (code 20, the seconds on 4 bytes), the
 * right that goes with it in the rules of a new ensemble, and a flag of the state block followed by the rhythm when it
 * is not the minute. A block written before reads the same, with the minute; a phone of before refuses the operations
 * of a new ensemble (unknown rules), and so never meets this change.
 *
 * Decoding refuses with a [WireException], and nothing else, whatever the bytes: truncated, too long, unknown codes,
 * or an operation not written the one way this version writes it (the proof of an operation signs its exact bytes).
 */
object HierarchyWire {
    const val VERSION = 4

    /** The most operations in one block; a longer catch-up takes several exchanges. */
    const val MAX_BATCH = 512

    /** The most authors in a [Summary]. */
    const val MAX_AUTHORS = 4096

    /** `version | kind | ensemble | lane | scope | key | share kind | author`: the clear part of a share. */
    internal const val SHARE_HEADER_BYTES = 36
    private const val DISSOLVED = 1

    /** The state carries a rhythm of the positions other than [HierarchyState.DEFAULT_POSITION_INTERVAL_S]. */
    private const val POSITION_INTERVAL = 2

    /** The state carries the colour of at least one group (P39.14, L1). */
    private const val GROUP_COLORS = 4
    private const val DEPUTY_SUCCEEDS = 1

    enum class Kind(
        val code: Int,
    ) {
        OP(1),
        OPS(2),
        SUMMARY(3),
        STATE(4),

        /** A content key wrapped to its readers ([KeyBatch]). */
        KEYS(5),

        /** Something shared, sealed with a content key ([ShareSeal]). */
        SHARE(6),
    }

    /** The kind of [bytes], or `null` when they are not a block of this version. */
    fun kindOf(bytes: ByteArray): Kind? =
        if (bytes.size < 2 ||
            bytes[0].toInt() != VERSION
        ) {
            null
        } else {
            Kind.entries.firstOrNull { it.code == bytes[1].toInt() }
        }

    fun encodeOp(op: Op): ByteArray = header(Kind.OP).bytes(opBytes(op)).toByteArray()

    fun decodeOp(bytes: ByteArray): Op = readOp(open(bytes, Kind.OP).rest())

    fun encodeOps(ops: List<Op>): ByteArray {
        require(ops.size <= MAX_BATCH) { "too many operations for one block" }
        val w = header(Kind.OPS).u16(ops.size)
        for (op in ops) {
            val one = opBytes(op)
            w.u16(one.size).bytes(one)
        }
        return w.toByteArray()
    }

    /** [ops] as blocks of at most [MAX_BATCH] operations each. */
    fun encodeOpBlocks(ops: List<Op>): List<ByteArray> = ops.chunked(MAX_BATCH).map(::encodeOps)

    fun decodeOps(bytes: ByteArray): List<Op> {
        val r = open(bytes, Kind.OPS)
        val count = r.u16()
        if (count > MAX_BATCH) throw WireException("too many operations")
        val ops = List(count) { readOp(r.bytes(r.u16())) }
        if (r.remaining != 0) throw WireException("trailing data")
        return ops
    }

    fun encodeSummary(summary: Summary): ByteArray {
        require(summary.counts.size <= MAX_AUTHORS) { "too many authors for one summary" }
        val w = header(Kind.SUMMARY).u64(summary.ensembleId).u64(summary.digest).u16(summary.counts.size)
        for ((author, count) in summary.counts.entries.sortedWith(BY_KEY)) w.u64(author).u32(count.toLong())
        return w.toByteArray()
    }

    fun decodeSummary(bytes: ByteArray): Summary {
        val r = open(bytes, Kind.SUMMARY)
        val ensemble = r.u64()
        val digest = r.u64()
        val size = r.u16()
        if (size > MAX_AUTHORS) throw WireException("too many authors")
        val counts = LinkedHashMap<Long, Int>()
        repeat(size) {
            val author = r.u64()
            val count = r.u32()
            if (author == 0L || count !in 1..Int.MAX_VALUE || counts.put(author, count.toInt()) != null) {
                throw WireException("bad summary entry")
            }
        }
        if (r.remaining != 0) throw WireException("trailing data")
        return Summary(ensemble, counts, digest)
    }

    /** A whole state, members and groups ordered by identifier: the same state always gives the same bytes. */
    fun encodeState(state: HierarchyState): ByteArray {
        val w = header(Kind.STATE).u64(state.ensembleId)
        name(w, requireNotNull(state.name) { "no state to write before the ensemble exists" })
        val ownInterval = state.positionIntervalS != HierarchyState.DEFAULT_POSITION_INTERVAL_S
        val colored = state.groups.entries.filter { it.value.rgb != null }.sortedWith(BY_KEY)
        w.u8(
            (if (state.dissolved) DISSOLVED else 0) or (if (ownInterval) POSITION_INTERVAL else 0) or
                (if (colored.isNotEmpty()) GROUP_COLORS else 0),
        )
        // The rhythm of the positions (2026-11-18), only when it is not the minute: a state that has the minute is
        // written as before, and a block written before reads as a state that has it.
        if (ownInterval) w.i32(state.positionIntervalS)
        w.u64(state.creator ?: 0L).u64(state.leader ?: 0L).u64(state.deputy ?: 0L)
        rules(w, state.rules)
        w.u16(state.members.size)
        for ((id, member) in state.members.entries.sortedWith(BY_KEY)) {
            name(w.u64(id), member.name)
            keys(w, member.keys)
            w.u64(member.group ?: 0L).u8(if (id in state.admins) 1 else 0)
        }
        w.u16(state.groups.size)
        for ((id, group) in state.groups.entries.sortedWith(BY_KEY)) {
            name(w.u64(id).u64(group.parent), group.name).u64(group.leader ?: 0L).u64(group.deputy ?: 0L)
        }
        // The colours of the groups (2026-11-22), only when one has its own: a state without them is written as before,
        // and a block written before reads as a state without them.
        if (colored.isNotEmpty()) {
            w.u16(colored.size)
            for ((id, group) in colored) w.u64(id).i32(requireNotNull(group.rgb))
        }
        w.u16(state.deletedGroups.size)
        for (id in state.deletedGroups.sortedWith(UNSIGNED)) w.u64(id)
        // The requests of a new name and the last answers (2026-11-16), only when there is one: a state without
        // them is written as before, and a block written before reads as a state without them.
        if (state.nameRequests.isNotEmpty() || state.nameAnswers.isNotEmpty()) {
            w.u16(state.nameRequests.size)
            for ((id, requested) in state.nameRequests.entries.sortedWith(BY_KEY)) name(w.u64(id), requested)
            w.u16(state.nameAnswers.size)
            for ((id, answer) in state.nameAnswers.entries.sortedWith(BY_KEY)) {
                name(w.u64(id).u64(answer.by), answer.name).u8(if (answer.accepted) 1 else 0)
            }
        }
        return w.toByteArray()
    }

    fun decodeState(bytes: ByteArray): HierarchyState {
        val r = open(bytes, Kind.STATE)
        val ensemble = r.u64()
        val name = readName(r)
        val flags = r.u8()
        if (flags and (DISSOLVED or POSITION_INTERVAL or GROUP_COLORS).inv() != 0) {
            throw WireException("unknown state flag")
        }
        val interval = if (flags and POSITION_INTERVAL != 0) r.i32() else HierarchyState.DEFAULT_POSITION_INTERVAL_S
        // Written only when it is not the minute: the minute written is not the one form of a state.
        if (flags and POSITION_INTERVAL != 0 && interval == HierarchyState.DEFAULT_POSITION_INTERVAL_S) {
            throw WireException("default rhythm written")
        }
        if (interval !in HierarchyState.MIN_POSITION_INTERVAL_S..HierarchyState.MAX_POSITION_INTERVAL_S) {
            throw WireException("rhythm out of bounds")
        }
        val creator = r.u64().takeIf { it != 0L }
        val leader = r.u64().takeIf { it != 0L }
        val deputy = r.u64().takeIf { it != 0L }
        val rules = readRules(r)
        val members = LinkedHashMap<Long, Member>()
        val admins = LinkedHashSet<Long>()
        val memberCount = r.u16()
        if (memberCount > HierarchyLimits.FORMAT_MAX_MEMBERS) throw WireException("too many members")
        repeat(memberCount) {
            val id = r.u64()
            val memberName = readName(r)
            val memberKeys = readKeys(r)
            val member = Member(id, memberName, memberKeys, r.u64().takeIf { it != 0L })
            val admin = r.u8()
            if (admin > 1 || members.put(id, member) != null) throw WireException("bad member")
            if (admin == 1) admins.add(id)
        }
        val groups = LinkedHashMap<Long, Group>()
        val groupCount = r.u16()
        if (groupCount > HierarchyLimits.FORMAT_MAX_GROUPS) throw WireException("too many groups")
        repeat(groupCount) {
            val id = r.u64()
            val parent = r.u64()
            val groupName = readName(r)
            val group = Group(id, parent, groupName, r.u64().takeIf { it != 0L }, r.u64().takeIf { it != 0L })
            if (groups.put(id, group) != null) throw WireException("bad group")
        }
        if (flags and GROUP_COLORS != 0) {
            val colorCount = r.u16()
            // Written only when there is one: none written is not the one form of a state.
            if (colorCount !in 1..groups.size) throw WireException("bad number of group colours")
            repeat(colorCount) {
                val id = r.u64()
                val group = groups[id] ?: throw WireException("colour of a group that does not exist")
                val rgb = r.i32()
                if (group.rgb != null || rgb !in 0..Group.MAX_RGB) throw WireException("bad group colour")
                groups[id] = group.copy(rgb = rgb)
            }
        }
        val deleted = LinkedHashSet<Long>()
        repeat(r.u16()) { if (!deleted.add(r.u64())) throw WireException("bad deleted group") }
        val requests = LinkedHashMap<Long, HiddenName>()
        val answers = LinkedHashMap<Long, NameAnswer>()
        if (r.remaining != 0) {
            val requestCount = r.u16()
            if (requestCount > HierarchyLimits.FORMAT_MAX_MEMBERS) throw WireException("too many requests of a name")
            repeat(requestCount) {
                val id = r.u64()
                if (requests.put(id, readName(r)) != null) throw WireException("bad request of a name")
            }
            val answerCount = r.u16()
            if (answerCount > HierarchyLimits.FORMAT_MAX_MEMBERS) throw WireException("too many answers")
            repeat(answerCount) {
                val id = r.u64()
                val by = r.u64()
                val answered = readName(r)
                val accepted =
                    when (r.u8()) {
                        0 -> false
                        1 -> true
                        else -> throw WireException("bad answer")
                    }
                if (answers.put(id, NameAnswer(by, answered, accepted)) != null) throw WireException("bad answer")
            }
            // Written only when there is one: an empty tail is not the one form of a state.
            if (requests.isEmpty() && answers.isEmpty()) throw WireException("empty requests of a name")
        }
        if (r.remaining != 0) throw WireException("trailing data")
        val state =
            HierarchyState(
                ensembleId = ensemble,
                name = name,
                creator = creator,
                leader = leader,
                deputy = deputy,
                rules = rules,
                members = members,
                admins = admins,
                groups = groups,
                deletedGroups = deleted,
                dissolved = flags and DISSOLVED != 0,
                nameRequests = requests,
                nameAnswers = answers,
                positionIntervalS = interval,
            )
        state.inconsistency()?.let { throw WireException("inconsistent state: $it") }
        return state
    }

    /**
     * `ensemble (8) | lane (1) | scope (8) | readers (16) | key (8) | issuer (8) | ephemeral key (65) | n (2) |
     * n × (recipient (8), wrapped key (48)) | signature (64)`, recipients by identifier.
     */
    fun encodeKeyBatch(batch: KeyBatch): ByteArray =
        header(Kind.KEYS).bytes(keyBatchBody(batch)).bytes(batch.signature).toByteArray()

    fun decodeKeyBatch(bytes: ByteArray): KeyBatch {
        val r = open(bytes, Kind.KEYS)
        val ensemble = r.u64()
        val channel = readChannel(r)
        val readers = r.bytes(Readers.TAG_BYTES)
        val key = r.u64()
        val issuer = r.u64()
        val ephemeral = r.bytes(P256.POINT_BYTES)
        val count = r.u16()
        if (count !in 1..HierarchyLimits.FORMAT_MAX_MEMBERS) throw WireException("bad number of recipients")
        val wraps = List(count) { KeyBatch.Wrap(r.u64(), r.bytes(KeyBatch.WRAP_BYTES)) }
        val signature = r.bytes(P256.SIGNATURE_BYTES)
        if (r.remaining != 0) throw WireException("trailing data")
        val ordered = wraps.zipWithNext().all { (a, b) -> java.lang.Long.compareUnsigned(a.recipient, b.recipient) < 0 }
        if (issuer == 0L || !ordered || wraps.first().recipient == 0L) throw WireException("bad key batch")
        if (!P256.isPoint(ephemeral)) throw WireException("bad ephemeral key")
        return KeyBatch(ensemble, channel, readers, key, issuer, ephemeral, wraps, signature)
    }

    /** Everything of a key batch but its signature: what the issuer signs. */
    internal fun keyBatchBody(batch: KeyBatch): ByteArray {
        val w = channel(ByteWriter().u64(batch.ensembleId), batch.channel).bytes(batch.readersTag).u64(batch.keyId)
        w.u64(batch.issuer).bytes(batch.ephemeral).u16(batch.wraps.size)
        for (wrap in batch.wraps) w.u64(wrap.recipient).bytes(wrap.sealed)
        return w.toByteArray()
    }

    /** The clear part of a share: who shares what, where, under which key. Authenticated with the sealed part. */
    internal fun shareHeader(
        ensembleId: Long,
        channel: Channel,
        keyId: Long,
        kind: ShareKind,
        author: Long,
    ): ByteArray =
        channel(header(Kind.SHARE).u64(ensembleId), channel).u64(keyId).u8(kind.code).u64(author).toByteArray()

    internal class ShareHeader(
        val ensembleId: Long,
        val channel: Channel,
        val keyId: Long,
        val kind: ShareKind,
        val author: Long,
    )

    internal fun readShareHeader(bytes: ByteArray): ShareHeader {
        val r = open(bytes, Kind.SHARE)
        val ensemble = r.u64()
        val channel = readChannel(r)
        val key = r.u64()
        val kind = ShareKind.of(r.u8()) ?: throw WireException("unknown kind of share")
        val author = r.u64()
        if (author == 0L) throw WireException("share without an author")
        return ShareHeader(ensemble, channel, key, kind, author)
    }

    /** `ensemble (8) | author (8) | seq (4) | clock (4) | change code (1) | change`: what the author signs. */
    internal fun opContent(op: Op): ByteArray {
        val w = ByteWriter().u64(op.ensembleId).u64(op.author).u32(op.seq.toLong()).u32(op.clock)
        when (val c = op.change) {
            is Change.CreateEnsemble -> w.u8(CREATE_ENSEMBLE).bytes(genesisBytes(c))
            is Change.AdmitMember -> keys(name(w.u8(ADMIT_MEMBER).u64(c.member), c.name), c.keys)
            is Change.RemoveMember -> w.u8(REMOVE_MEMBER).u64(c.member)
            is Change.AddAdmin -> w.u8(ADD_ADMIN).u64(c.member)
            is Change.RemoveAdmin -> w.u8(REMOVE_ADMIN).u64(c.member)
            is Change.CreateGroup -> name(w.u8(CREATE_GROUP).u64(c.group).u64(c.parent), c.name)
            is Change.RenameGroup -> name(w.u8(RENAME_GROUP).u64(c.group), c.name)
            is Change.DeleteGroup -> w.u8(DELETE_GROUP).u64(c.group)
            is Change.AssignMember -> w.u8(ASSIGN_MEMBER).u64(c.member).u64(c.group)
            is Change.UnassignMember -> w.u8(UNASSIGN_MEMBER).u64(c.member)
            is Change.SetPost -> w.u8(SET_POST).u64(c.unit).u8(c.post.code).u64(c.member)
            is Change.ClearPost -> w.u8(CLEAR_POST).u64(c.unit).u8(c.post.code)
            is Change.TransferCreator -> w.u8(TRANSFER_CREATOR).u64(c.member)
            Change.Dissolve -> w.u8(DISSOLVE)
            is Change.RenameMember -> name(w.u8(RENAME_MEMBER).u64(c.member), c.name)
            is Change.RequestName -> name(w.u8(REQUEST_NAME).u64(c.member), c.name)
            is Change.WithdrawName -> w.u8(WITHDRAW_NAME).u64(c.member)
            is Change.AnswerName -> name(w.u8(ANSWER_NAME).u64(c.member), c.name).u8(if (c.accept) 1 else 0)
            is Change.RenameEnsemble -> name(w.u8(RENAME_ENSEMBLE), c.name)
            is Change.SetPositionInterval -> w.u8(SET_POSITION_INTERVAL).i32(c.seconds)
            is Change.SetGroupColor -> w.u8(SET_GROUP_COLOR).u64(c.group).i32(c.rgb)
        }
        return w.toByteArray()
    }

    /**
     * `nonce (8) | name | creator name | creator keys | rules`: with the creator, what the identifier of the
     * ensemble is made of.
     */
    internal fun genesisBytes(c: Change.CreateEnsemble): ByteArray {
        val w = name(name(ByteWriter().u64(c.nonce), c.name), c.creatorName)
        keys(w, c.creatorKeys)
        rules(w, c.rules)
        return w.toByteArray()
    }

    /** `content length (2) | content | signing key (65) | signature (64)`: an operation always carries its proof. */
    private fun opBytes(op: Op): ByteArray {
        val proof = requireNotNull(op.proof) { "an operation travels with its proof" }
        require(proof.publicKey.size == P256.POINT_BYTES && proof.signature.size == P256.SIGNATURE_BYTES) {
            "bad proof"
        }
        return ByteWriter().u16(
            op.content.size,
        ).bytes(op.content).bytes(proof.publicKey).bytes(proof.signature).toByteArray()
    }

    private fun readOp(bytes: ByteArray): Op {
        val outer = ByteReader(bytes)
        val content = outer.bytes(outer.u16())
        val proof = OpProof(outer.bytes(P256.POINT_BYTES), outer.bytes(P256.SIGNATURE_BYTES))
        if (outer.remaining != 0) throw WireException("trailing data after an operation")
        val r = ByteReader(content)
        val ensemble = r.u64()
        val author = r.u64()
        val seq = r.u32()
        val clock = r.u32()
        if (seq !in 1..Int.MAX_VALUE) throw WireException("bad operation number")
        val change = readChange(r)
        if (r.remaining != 0) throw WireException("trailing data in an operation")
        val op = Op(ensemble, author, seq.toInt(), clock, change, proof)
        if (op.isMalformed) throw WireException("malformed operation")
        // The proof signs these exact bytes: a second way to write the same operation is not this operation.
        if (!op.content.contentEquals(content)) throw WireException("operation not in its one form")
        return op
    }

    private fun readChange(r: ByteReader): Change =
        when (r.u8()) {
            CREATE_ENSEMBLE -> {
                val nonce = r.u64()
                val name = readName(r)
                val creatorName = readName(r)
                val creatorKeys = readKeys(r)
                Change.CreateEnsemble(nonce, name, creatorName, creatorKeys, readRules(r))
            }

            ADMIT_MEMBER -> {
                val member = r.u64()
                val name = readName(r)
                Change.AdmitMember(member, name, readKeys(r))
            }

            REMOVE_MEMBER -> {
                Change.RemoveMember(r.u64())
            }

            ADD_ADMIN -> {
                Change.AddAdmin(r.u64())
            }

            REMOVE_ADMIN -> {
                Change.RemoveAdmin(r.u64())
            }

            CREATE_GROUP -> {
                val group = r.u64()
                val parent = r.u64()
                Change.CreateGroup(group, readName(r), parent)
            }

            RENAME_GROUP -> {
                val group = r.u64()
                Change.RenameGroup(group, readName(r))
            }

            DELETE_GROUP -> {
                Change.DeleteGroup(r.u64())
            }

            ASSIGN_MEMBER -> {
                val member = r.u64()
                Change.AssignMember(member, r.u64())
            }

            UNASSIGN_MEMBER -> {
                Change.UnassignMember(r.u64())
            }

            SET_POST -> {
                val unit = r.u64()
                val post = readPost(r)
                Change.SetPost(unit, post, r.u64())
            }

            CLEAR_POST -> {
                val unit = r.u64()
                Change.ClearPost(unit, readPost(r))
            }

            TRANSFER_CREATOR -> {
                Change.TransferCreator(r.u64())
            }

            DISSOLVE -> {
                Change.Dissolve
            }

            RENAME_MEMBER -> {
                val member = r.u64()
                Change.RenameMember(member, readName(r))
            }

            REQUEST_NAME -> {
                val member = r.u64()
                Change.RequestName(member, readName(r))
            }

            WITHDRAW_NAME -> {
                Change.WithdrawName(r.u64())
            }

            ANSWER_NAME -> {
                val member = r.u64()
                val name = readName(r)
                when (r.u8()) {
                    0 -> Change.AnswerName(member, name, accept = false)
                    1 -> Change.AnswerName(member, name, accept = true)
                    else -> throw WireException("bad answer")
                }
            }

            RENAME_ENSEMBLE -> {
                Change.RenameEnsemble(readName(r))
            }

            SET_POSITION_INTERVAL -> {
                // Read whatever it is: a value out of the window is refused as malformed, as every phone does.
                Change.SetPositionInterval(r.i32())
            }

            SET_GROUP_COLOR -> {
                // Read whatever it is: a colour out of 24 bits is refused as malformed, as every phone does.
                val group = r.u64()
                Change.SetGroupColor(group, r.i32())
            }

            else -> {
                throw WireException("unknown change")
            }
        }

    private fun readPost(r: ByteReader): Post = Post.of(r.u8()) ?: throw WireException("unknown post")

    /** A hidden name: its commitment, 32 bytes. */
    private fun name(
        w: ByteWriter,
        name: HiddenName,
    ): ByteWriter {
        require(name.valid) { "bad hidden name" }
        return w.bytes(name.commitment)
    }

    private fun readName(r: ByteReader): HiddenName = HiddenName(r.bytes(HiddenName.BYTES))

    /** `lane (1) | scope (8)`. */
    private fun channel(
        w: ByteWriter,
        channel: Channel,
    ): ByteWriter = w.u8(channel.lane.code).u64(channel.scope)

    private fun readChannel(r: ByteReader): Channel {
        val lane = Lane.of(r.u8()) ?: throw WireException("unknown lane")
        val scope = r.u64()
        val rooted = lane == Lane.UNIT || lane == Lane.LEADERS
        if (scope == HierarchyState.ROOT && !rooted) throw WireException("lane of nothing")
        if (lane == Lane.LEADERS && scope != HierarchyState.ROOT) throw WireException("lane of the leaders of a group")
        return Channel(lane, scope)
    }

    /** `signing key (65) | agreement key (65)`. */
    private fun keys(
        w: ByteWriter,
        keys: MemberKeys,
    ) {
        require(keys.signing.size == P256.POINT_BYTES && keys.agreement.size == P256.POINT_BYTES) { "bad keys" }
        w.bytes(keys.signing).bytes(keys.agreement)
    }

    private fun readKeys(r: ByteReader): MemberKeys {
        val keys = MemberKeys(r.bytes(P256.POINT_BYTES), r.bytes(P256.POINT_BYTES))
        if (!keys.valid) throw WireException("keys that are not keys")
        return keys
    }

    /**
     * `n (1) | n × (operation, role, reach bits) | m (1) | m × (role, audience bits) |
     * k (1) | k × (kind of share, role, share reach bits) | signed kinds of share (1) |
     * kinds of share with one destination (1) | flags (1) | most members (2) | most groups (2) | most levels (1)`:
     * only the cells that are not empty, in the order of the codes. Flags: bit 0, the deputy takes the place of a
     * leader who goes.
     */
    private fun rules(
        w: ByteWriter,
        rules: HierarchyRules,
    ) {
        require(rules.limits.valid) { "limits the format cannot carry" }
        val rights =
            Operation.entries.flatMap { op ->
                Role.entries.map { role -> Triple(op, role, rules.rights.mask(op, role)) }
            }
        val granted = rights.filter { it.third != 0 }
        w.u8(granted.size)
        for ((op, role, mask) in granted) w.u8(op.code).u8(role.code).u8(mask)
        val seen = Role.entries.map { it to rules.visibility.mask(it) }.filter { it.second != 0 }
        w.u8(seen.size)
        for ((role, mask) in seen) w.u8(role.code).u8(mask)
        val shares =
            ShareKind.entries.flatMap { kind ->
                Role.entries.map { role -> Triple(kind, role, rules.shares.mask(kind, role)) }
            }
        val shared = shares.filter { it.third != 0 }
        w.u8(shared.size)
        for ((kind, role, mask) in shared) w.u8(kind.code).u8(role.code).u8(mask)
        w.u8(rules.signedShares.fold(0) { bits, kind -> bits or kind.bit })
        w.u8(rules.singleDestination.fold(0) { bits, kind -> bits or kind.bit })
        w.u8(if (rules.deputySucceeds) DEPUTY_SUCCEEDS else 0)
        w.u16(rules.limits.maxMembers).u16(rules.limits.maxGroups).u8(rules.limits.maxDepth)
    }

    private fun readRules(r: ByteReader): HierarchyRules {
        // A rule this version does not know cannot be applied the same way as on the phone that wrote it.
        var rights = RightsTable.NONE
        repeat(r.u8()) {
            val op = Operation.of(r.u8()) ?: throw WireException("unknown operation in the rights")
            val role = Role.of(r.u8()) ?: throw WireException("unknown role in the rights")
            val mask = r.u8()
            if (mask == 0 || mask and Reach.ALL_BITS.inv() != 0) throw WireException("unknown reach")
            rights = rights.withMask(op, role, mask)
        }
        var visibility = VisibilityTable.NONE
        repeat(r.u8()) {
            val role = Role.of(r.u8()) ?: throw WireException("unknown role in the visibility")
            val mask = r.u8()
            if (mask == 0 || mask and Audience.ALL_BITS.inv() != 0) throw WireException("unknown audience")
            visibility = visibility.withMask(role, mask)
        }
        var shares = ShareTable.NONE
        repeat(r.u8()) {
            val kind = ShareKind.of(r.u8()) ?: throw WireException("unknown kind of share in the rules")
            val role = Role.of(r.u8()) ?: throw WireException("unknown role in the shares")
            val mask = r.u8()
            if (mask == 0 || mask and ShareReach.ALL_BITS.inv() != 0) throw WireException("unknown share reach")
            shares = shares.withMask(kind, role, mask)
        }
        val signed = r.u8()
        if (signed and ShareKind.ALL_BITS.inv() != 0) throw WireException("unknown signed kind of share")
        val single = r.u8()
        if (single and ShareKind.ALL_BITS.inv() != 0) throw WireException("unknown kind of share with one destination")
        val flags = r.u8()
        if (flags and DEPUTY_SUCCEEDS.inv() != 0) throw WireException("unknown rule")
        val limits = HierarchyLimits(r.u16(), r.u16(), r.u8())
        if (!limits.valid) throw WireException("limits the format cannot carry")
        return HierarchyRules(
            rights = rights,
            visibility = visibility,
            shares = shares,
            limits = limits,
            signedShares = ShareKind.entries.filter { signed and it.bit != 0 }.toSet(),
            deputySucceeds = flags and DEPUTY_SUCCEEDS != 0,
            singleDestination = ShareKind.entries.filter { single and it.bit != 0 }.toSet(),
        )
    }

    private fun header(kind: Kind) = ByteWriter().u8(VERSION).u8(kind.code)

    private fun open(
        bytes: ByteArray,
        kind: Kind,
    ): ByteReader {
        if (bytes.size > MAX_BLOCK) throw WireException("block too large")
        val r = ByteReader(bytes)
        if (r.u8() != VERSION) throw WireException("unknown version")
        if (r.u8() != kind.code) throw WireException("not a ${kind.name.lowercase()} block")
        return r
    }

    internal val UNSIGNED: Comparator<Long> = Comparator { a, b -> java.lang.Long.compareUnsigned(a, b) }
    private val BY_KEY: Comparator<Map.Entry<Long, *>> = Comparator { a, b -> UNSIGNED.compare(a.key, b.key) }

    private const val MAX_BLOCK = 512 * 1024
    private const val CREATE_ENSEMBLE = 1
    private const val ADMIT_MEMBER = 2
    private const val REMOVE_MEMBER = 3
    private const val ADD_ADMIN = 4
    private const val REMOVE_ADMIN = 5
    private const val CREATE_GROUP = 6
    private const val RENAME_GROUP = 7
    private const val DELETE_GROUP = 8
    private const val ASSIGN_MEMBER = 9
    private const val UNASSIGN_MEMBER = 10
    private const val SET_POST = 11
    private const val CLEAR_POST = 12
    private const val TRANSFER_CREATOR = 13
    private const val DISSOLVE = 14

    // 2026-11-16, names (same version: a block written before carries none of them and reads as it did).
    private const val RENAME_MEMBER = 15
    private const val REQUEST_NAME = 16
    private const val WITHDRAW_NAME = 17
    private const val ANSWER_NAME = 18
    private const val RENAME_ENSEMBLE = 19

    // 2026-11-18, the rhythm of the positions (same version: a block written before carries none).
    private const val SET_POSITION_INTERVAL = 20

    // 2026-11-22, the colour of a group (P39.14, L1; same version: a block written before carries none).
    private const val SET_GROUP_COLOR = 21
}
