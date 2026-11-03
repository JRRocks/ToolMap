package fr.toolmap.core.p2p

import kotlinx.serialization.Serializable

/**
 * A point shared with the group (P27, `docs/specs/p2p-sharing.md` § 12): it exists only in the group, every member keeps
 * it and gives it again to the members who arrive. Each change is a new [rev]: the author moves, renames and extends
 * it; any member removes it, which leaves a [removedBy] tombstone so that a member who missed the removal cannot bring
 * it back.
 */
@Serializable
data class GroupPoint(
    /** Chosen at random by the author. */
    val id: Long,
    val author: Long,
    /** 1 at creation, one more at each change. */
    val rev: Long,
    val lat: Double,
    val lon: Double,
    val altM: Int? = null,
    val name: String,
    /** The waypoint symbol (`Waypoint.sym`, catalogue of the icons); empty for the default one. */
    val symbol: String = "",
    /** Epoch seconds of the creation. */
    val createdSec: Long,
    /** Epoch seconds of this revision. */
    val updatedSec: Long = createdSec,
    /** Epoch seconds of the end; [WHILE_GROUP] keeps it until it is removed or the group empties. */
    val expiresSec: Long = WHILE_GROUP,
    /** Created with « Alerte »: rung by the members who are in the group at the time (P22), never by a replay. */
    val alert: Boolean = false,
    /** The member who removed it, [LIVE] while it is on. */
    val removedBy: Long = LIVE,
) {
    val removed: Boolean get() = removedBy != LIVE

    /** The member whose writing this revision is: the remover of a tombstone, else the author. */
    val writer: Long get() = if (removed) removedBy else author

    /**
     * Epoch seconds after which nobody needs it any more: its expiry, or [TOMBSTONE_KEEP_S] after the removal of a
     * point kept while the group exists; [WHILE_GROUP] for a point that is on until it is removed.
     */
    val endSec: Long
        get() =
            when {
                expiresSec != WHILE_GROUP -> expiresSec
                removed -> updatedSec + TOMBSTONE_KEEP_S
                else -> WHILE_GROUP
            }

    fun isOver(nowSec: Long): Boolean = endSec != WHILE_GROUP && nowSec >= endSec

    /** Seconds left before its end (« encore 42 min »), `null` for a point kept while the group exists. */
    fun remainingSec(nowSec: Long): Long? =
        if (expiresSec ==
            WHILE_GROUP
        ) {
            null
        } else {
            (expiresSec - nowSec).coerceAtLeast(0)
        }

    /** `true` when this revision replaces [known]: a later one, or the removal of the same one. */
    fun replaces(known: GroupPoint): Boolean =
        id == known.id && author == known.author && (rev > known.rev || (rev == known.rev && removed && !known.removed))

    internal fun write(w: ByteWriter) {
        w.u64(id).u64(author).u32(rev and MAX_U32)
        w.i32(Math.round(lat * COORD_SCALE).toInt()).i32(Math.round(lon * COORD_SCALE).toInt())
        w.i16(altM?.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()) ?: ALT_UNKNOWN)
        w.u32(createdSec).u32(updatedSec).u32(expiresSec).u64(removedBy).u8(if (alert) FLAG_ALERT else 0)
        w.str(name, MAX_NAME_BYTES).str(symbol, MAX_SYMBOL_BYTES)
    }

    override fun toString(): String = "GroupPoint(${MeshNode.hex(id)} r$rev${if (removed) " removed" else ""})"

    companion object {
        const val WHILE_GROUP = 0L
        const val LIVE = 0L

        /** How long a removal is remembered when the point had no end: a day. */
        const val TOMBSTONE_KEEP_S = 24 * 3600L
        const val MAX_NAME_BYTES = 64
        const val MAX_SYMBOL_BYTES = 40
        private const val COORD_SCALE = 1e7
        private const val ALT_UNKNOWN = Short.MIN_VALUE.toInt()
        private const val MAX_U32 = 0xFFFFFFFFL
        private const val FLAG_ALERT = 1

        internal fun read(r: ByteReader): GroupPoint {
            val id = r.u64()
            val author = r.u64()
            val rev = r.u32()
            val lat = r.i32() / COORD_SCALE
            val lon = r.i32() / COORD_SCALE
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw WireException("point out of range")
            val alt = r.i16()
            val created = r.u32()
            val updated = r.u32()
            val expires = r.u32()
            val removedBy = r.u64()
            // Unknown bits are left for later versions.
            val flags = r.u8()
            return GroupPoint(
                id = id,
                author = author,
                rev = rev,
                lat = lat,
                lon = lon,
                altM = alt.takeIf { it != ALT_UNKNOWN },
                name = r.str(),
                symbol = r.str(),
                createdSec = created,
                updatedSec = updated,
                expiresSec = expires,
                alert = flags and FLAG_ALERT != 0,
                removedBy = removedBy,
            )
        }
    }
}

/** How long a point of the group stays (P27): one of [PRESETS], a time typed in (P23), or while the group exists. */
sealed interface PointDuration {
    /** [GroupPoint.expiresSec] of a point made or extended at [nowSec]. */
    fun expiresSec(nowSec: Long): Long

    /** For [seconds], from 1 minute to [MAX_S]. */
    data class For(
        val seconds: Long,
    ) : PointDuration {
        init {
            require(seconds in MIN_S..MAX_S) { "duration out of range: $seconds s" }
        }

        override fun expiresSec(nowSec: Long): Long = nowSec + seconds
    }

    /** Until it is removed or the group empties. */
    data object WhileGroup : PointDuration {
        override fun expiresSec(nowSec: Long): Long = GroupPoint.WHILE_GROUP
    }

    companion object {
        const val MIN_S = 60L

        /** A week: past it, « Tant que le groupe existe » says it better. */
        const val MAX_S = 7 * 24 * 3600L

        /** 30 min, 1 h, 2 h, 4 h, 12 h, 24 h. */
        val PRESETS: List<For> = listOf(1800L, 3600L, 7200L, 14_400L, 43_200L, 86_400L).map(::For)
    }
}
