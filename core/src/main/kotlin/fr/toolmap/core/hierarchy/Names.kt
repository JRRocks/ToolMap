package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import java.security.SecureRandom

/** The names of ensembles, groups and members: what every phone accepts. */
object Names {
    /** In UTF-8 bytes, so that a name always fits its field of the binary format. */
    const val MAX_BYTES = 64

    fun valid(name: String): Boolean =
        name.isNotBlank() && name.none { it.isISOControl() } && name.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    /** [name] with a fresh salt: the card to keep and to share, whose [NameCard.hidden] goes into the operation. */
    fun hide(
        name: String,
        random: SecureRandom = SecureRandom(),
    ): NameCard = NameCard(ByteArray(NameCard.SALT_BYTES).also(random::nextBytes), name)

    /**
     * What is written on a marker of the map when the whole name is not ([NameLabel.INITIALS]): the first letter of
     * the first two words, or the first two letters of a single word, in capitals.
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters =
            when (words.size) {
                0 -> {
                    ""
                }

                1 -> {
                    words[0].take(
                        words[0].offsetByCodePoints(0, minOf(2, words[0].codePointCount(0, words[0].length))),
                    )
                }

                else -> {
                    words.take(2).joinToString("") { it.take(it.offsetByCodePoints(0, 1)) }
                }
            }
        return letters.uppercase()
    }
}

/**
 * A name as the operations carry it: a commitment (SHA-256 of a random salt and of the name) that says nothing of
 * the name. The operations are read by every phone that was let in, the ones that wait for a group too: those must
 * learn no name (the user's answer Q2). The name itself travels apart, sealed for who has access ([NameCard],
 * [NameBook]).
 */
class HiddenName(
    val commitment: ByteArray,
) {
    val valid: Boolean get() = commitment.size == BYTES

    override fun equals(other: Any?): Boolean = other is HiddenName && commitment.contentEquals(other.commitment)

    override fun hashCode(): Int = commitment.contentHashCode()

    override fun toString(): String = "HiddenName(" + commitment.take(4).joinToString("") { "%02x".format(it) } + "…)"

    companion object {
        const val BYTES = 32
    }
}

/**
 * What opens a [HiddenName]: the salt and the name. A card proves itself (its commitment is in an operation signed
 * by whoever had the right to write it), so any phone that holds one may pass it on.
 */
class NameCard(
    val salt: ByteArray,
    val name: String,
) {
    val valid: Boolean get() = salt.size == SALT_BYTES && Names.valid(name)

    val hidden: HiddenName by lazy { HiddenName(OpAuth.sha256(NAME_TAG, salt + name.toByteArray(Charsets.UTF_8))) }

    override fun equals(other: Any?): Boolean =
        other is NameCard && salt.contentEquals(other.salt) && name == other.name

    override fun hashCode(): Int = 31 * salt.contentHashCode() + name.hashCode()

    companion object {
        const val SALT_BYTES = 16
        private val NAME_TAG = "toolmap-hierarchy-name-v3".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The names one phone knows for one ensemble: the cards it made or received, by the hidden name they open. Knows
 * nothing of the network: the caller hands it the [ShareKind.ROSTER] shares it opened ([accept]) and shares the cards
 * of [cardsFor] with whoever arrives.
 *
 * A phone that waits for a group holds none but its own: the cards travel sealed with the key of the whole ensemble,
 * which was never wrapped to it.
 */
class NameBook {
    private val cards = LinkedHashMap<HiddenName, NameCard>()

    /** Keeps [card]; `false` when it is not a card every phone accepts, or when the book is full. */
    @Synchronized
    fun learn(card: NameCard): Boolean {
        if (!card.valid) return false
        if (card.hidden !in cards && cards.size >= MAX_CARDS) return false
        cards[card.hidden] = card
        return true
    }

    /** Takes in a [ShareKind.ROSTER] share once opened; how many of its cards are new. Anything else: 0. */
    @Synchronized
    fun accept(share: Share): Int {
        if (share.kind != ShareKind.ROSTER) return 0
        val received =
            try {
                decode(share.payload)
            } catch (e: WireException) {
                Problems.expected("hierarchy.names.decode", e)
                return 0
            }
        return received.count { it.hidden !in cards && learn(it) }
    }

    /** The name [hidden] stands for; `null` when this phone was not told. */
    @Synchronized
    fun nameOf(hidden: HiddenName?): String? = hidden?.let { cards[it]?.name }

    /** The name of [member] in [state], or `null`. */
    fun nameOf(
        state: HierarchyState,
        member: Long,
    ): String? = nameOf(state.members[member]?.name)

    /** The cards of the names [state] carries that this phone knows: what to share with who arrives. */
    @Synchronized
    fun cardsFor(state: HierarchyState): List<NameCard> = state.hiddenNames().mapNotNull { cards[it] }

    /** The names of [state] this phone does not know (yet). */
    @Synchronized
    fun missing(state: HierarchyState): List<HiddenName> = state.hiddenNames().filter { it !in cards }

    /** Forgets the cards of names [state] no longer carries. */
    @Synchronized
    fun prune(state: HierarchyState) {
        cards.keys.retainAll(state.hiddenNames())
    }

    /** Forgets everything: the phone left the ensemble, was put out of it or back to waiting, or it is dissolved. */
    @Synchronized
    fun clear() = cards.clear()

    companion object {
        /** More than the names an ensemble can carry: a card may come a little before its operation. */
        const val MAX_CARDS = 2 * (HierarchyLimits.FORMAT_MAX_MEMBERS + HierarchyLimits.FORMAT_MAX_GROUPS + 1)

        /** The payload of a [ShareKind.ROSTER] share: `n (2) | n × (salt (16), name)`. */
        fun encode(cards: List<NameCard>): ByteArray {
            require(cards.size <= MAX_CARDS_PER_SHARE) { "too many cards for one share" }
            val w = ByteWriter().u16(cards.size)
            for (card in cards) w.bytes(card.salt).str(card.name, Names.MAX_BYTES)
            return w.toByteArray()
        }

        /** [cards] as payloads that each fit one share. */
        fun encodeAll(cards: List<NameCard>): List<ByteArray> = cards.chunked(MAX_CARDS_PER_SHARE).map(::encode)

        fun decode(payload: ByteArray): List<NameCard> {
            val r = ByteReader(payload)
            val count = r.u16()
            if (count > MAX_CARDS_PER_SHARE) throw WireException("too many cards")
            val cards =
                List(count) {
                    val salt = r.bytes(NameCard.SALT_BYTES)
                    NameCard(salt, r.str()).also { if (!it.valid) throw WireException("bad name card") }
                }
            if (r.remaining != 0) throw WireException("trailing data")
            return cards
        }

        /** A card is 81 bytes at most: this many always fit one share. */
        const val MAX_CARDS_PER_SHARE = 128
    }
}
