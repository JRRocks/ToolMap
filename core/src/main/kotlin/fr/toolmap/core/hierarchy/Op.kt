package fr.toolmap.core.hierarchy

/** The two posts of a unit (the ensemble or a group): its leader, and the one deputy who has the leader's rights. */
enum class Post(
    val code: Int,
) {
    LEADER(1),
    DEPUTY(2),
    ;

    companion object {
        fun of(code: Int): Post? = entries.firstOrNull { it.code == code }
    }
}

/** What an operation changes. Identifiers are 64-bit values, never 0; names follow [Names]. */
sealed interface Change {
    /** The line of the [RightsTable] this change is checked against; `null` for the creation of the ensemble. */
    val operation: Operation?

    /**
     * The first operation of an ensemble. Its author becomes a member named [creatorName] and the creator; every
     * post is vacant. The identifier of the ensemble is [OpAuth.ensembleId] of the author and of this change
     * ([nonce] makes it unique): nobody can write another first operation for it. [creatorKeys] are the keys of the
     * author: its identifier is the fingerprint of the signing one.
     */
    data class CreateEnsemble(
        val nonce: Long,
        val name: String,
        val creatorName: String,
        val creatorKeys: MemberKeys,
        val rules: HierarchyRules = HierarchyRules.DEFAULT,
    ) : Change {
        override val operation: Operation? get() = null
    }

    /**
     * Lets [member] in, without a group: it waits ([HierarchyState.isWaiting]) until someone assigns it. [keys] are
     * the keys its phone showed when it asked to come in; [member] is the fingerprint of the signing one.
     */
    data class AdmitMember(
        val member: Long,
        val name: String,
        val keys: MemberKeys,
    ) : Change {
        override val operation get() = Operation.ADMIT_MEMBER
    }

    /** Takes [member] out of the ensemble: out of its group, of its posts and of the administrators. */
    data class RemoveMember(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.REMOVE_MEMBER
    }

    data class AddAdmin(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.ADD_ADMIN
    }

    data class RemoveAdmin(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.REMOVE_ADMIN
    }

    /** A new group [group] under [parent] ([HierarchyState.ROOT]: directly under the ensemble). */
    data class CreateGroup(
        val group: Long,
        val name: String,
        val parent: Long = HierarchyState.ROOT,
    ) : Change {
        override val operation get() = Operation.CREATE_GROUP
    }

    data class RenameGroup(
        val group: Long,
        val name: String,
    ) : Change {
        override val operation get() = Operation.RENAME_GROUP
    }

    /** Deletes [group], which must have no group under it; its members stay in the ensemble and wait for a group. */
    data class DeleteGroup(
        val group: Long,
    ) : Change {
        override val operation get() = Operation.DELETE_GROUP
    }

    /** Puts [member] in [group], out of the one it was in (it leaves the post it held there): one group at most. */
    data class AssignMember(
        val member: Long,
        val group: Long,
    ) : Change {
        override val operation get() = Operation.ASSIGN_MEMBER
    }

    /** Takes [member] out of its group: it waits for another one. */
    data class UnassignMember(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.UNASSIGN_MEMBER
    }

    /**
     * Names [member] to [post] of [unit] ([HierarchyState.ROOT]: the ensemble), in place of whoever held it. For a
     * group, [member] must be in it. A member holds one post of a unit at most: named to the other one, it moves.
     */
    data class SetPost(
        val unit: Long,
        val post: Post,
        val member: Long,
    ) : Change {
        override val operation get() = operationOf(unit, post)
    }

    /** Leaves [post] of [unit] vacant. */
    data class ClearPost(
        val unit: Long,
        val post: Post,
    ) : Change {
        override val operation get() = operationOf(unit, post)
    }

    /** The creator hands the ensemble to [member], who becomes its creator; the author stays, as a member. */
    data class TransferCreator(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.TRANSFER_CREATOR
    }

    /** The creator ends the ensemble for everyone. Nothing applies after it. */
    data object Dissolve : Change {
        override val operation get() = Operation.DISSOLVE
    }

    companion object {
        internal fun operationOf(
            unit: Long,
            post: Post,
        ): Operation =
            when (post) {
                Post.LEADER -> {
                    if (unit == HierarchyState.ROOT) Operation.NAME_ENSEMBLE_LEADER else Operation.NAME_GROUP_LEADER
                }

                Post.DEPUTY -> {
                    if (unit == HierarchyState.ROOT) Operation.NAME_ENSEMBLE_DEPUTY else Operation.NAME_GROUP_DEPUTY
                }
            }
    }
}

/**
 * `true` when no phone would accept this change whatever the state: an identifier 0, a name [Names] refuses, keys
 * that are not keys or not the ones of the member they come with, limits the format cannot carry.
 */
fun Change.isMalformed(): Boolean =
    when (this) {
        is Change.CreateEnsemble -> {
            !Names.valid(name) || !Names.valid(creatorName) || !creatorKeys.valid || !rules.limits.valid
        }

        is Change.AdmitMember -> {
            member == 0L || !Names.valid(name) || !keys.valid || OpAuth.memberId(keys.signing) != member
        }

        is Change.RemoveMember -> {
            member == 0L
        }

        is Change.AddAdmin -> {
            member == 0L
        }

        is Change.RemoveAdmin -> {
            member == 0L
        }

        is Change.CreateGroup -> {
            group == 0L || !Names.valid(name)
        }

        is Change.RenameGroup -> {
            group == 0L || !Names.valid(name)
        }

        is Change.DeleteGroup -> {
            group == 0L
        }

        is Change.AssignMember -> {
            member == 0L || group == 0L
        }

        is Change.UnassignMember -> {
            member == 0L
        }

        is Change.SetPost -> {
            member == 0L
        }

        is Change.ClearPost -> {
            false
        }

        is Change.TransferCreator -> {
            member == 0L
        }

        Change.Dissolve -> {
            false
        }
    }

/**
 * The proof that an operation comes from its author: its public signing key ([P256.POINT_BYTES] bytes) and its
 * signature of the operation ([P256.SIGNATURE_BYTES] bytes).
 */
class OpProof(
    val publicKey: ByteArray,
    val signature: ByteArray,
)

/**
 * One change, as it travels and as it is kept: who asked for it ([author]), its number among the operations of this
 * author ([seq], from 1, without a gap) and the logical clock of the author when it asked ([clock], one more than
 * every operation it knew). The phones of an ensemble apply the operations in the order of ([clock], [author],
 * [seq]), whatever the order they received them in.
 *
 * Two operations are equal when they say the same thing; the [proof] does not count.
 */
class Op(
    val ensembleId: Long,
    val author: Long,
    val seq: Int,
    val clock: Long,
    val change: Change,
    val proof: OpProof? = null,
) {
    /** The bytes that are signed and compared: everything but the proof. */
    internal val content: ByteArray by lazy { HierarchyWire.opContent(this) }

    /** Whether the [proof] is there and is the author's ([OpAuth.verify]); checked once. */
    val authentic: Boolean by lazy { OpAuth.verify(this) }

    internal val isMalformed: Boolean by lazy {
        author == 0L ||
            seq < 1 ||
            clock !in 1..MAX_CLOCK ||
            change.isMalformed() ||
            // The first operation carries the keys of its own author.
            (change is Change.CreateEnsemble && OpAuth.memberId(change.creatorKeys.signing) != author)
    }

    override fun equals(other: Any?): Boolean =
        other is Op &&
            ensembleId == other.ensembleId &&
            author == other.author &&
            seq == other.seq &&
            clock == other.clock &&
            change == other.change

    override fun hashCode(): Int = 31 * (31 * author.hashCode() + seq) + clock.hashCode()

    override fun toString(): String = "Op(${java.lang.Long.toUnsignedString(author)}#$seq @$clock $change)"

    companion object {
        /** The clock travels on 4 bytes. */
        const val MAX_CLOCK = 0xFFFFFFFFL

        /** The order every phone applies the operations in. */
        val ORDER: Comparator<Op> =
            Comparator { a, b ->
                when {
                    a.clock != b.clock -> a.clock.compareTo(b.clock)
                    a.author != b.author -> java.lang.Long.compareUnsigned(a.author, b.author)
                    else -> a.seq.compareTo(b.seq)
                }
            }
    }
}
