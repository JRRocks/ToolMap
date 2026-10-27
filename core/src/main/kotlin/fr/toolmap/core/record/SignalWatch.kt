package fr.toolmap.core.record

/**
 * Notices when the GNSS has stopped giving fixes while recording (the « Lost signal warning » setting). The recorder
 * calls [start] when it begins, [onFix] at each fix and [check] now and then; [check] and [onFix] answer only when the
 * state changes, so that the warning is raised once and the recovery once.
 */
class SignalWatch(
    private val lostAfterMillis: Long = DEFAULT_LOST_AFTER_MILLIS,
) {
    enum class Change { LOST, RECOVERED }

    private var lastFixMillis = 0L
    private var lost = false

    val isLost: Boolean get() = lost

    fun start(nowMillis: Long) {
        lastFixMillis = nowMillis
        lost = false
    }

    fun onFix(nowMillis: Long): Change? {
        lastFixMillis = nowMillis
        if (!lost) return null
        lost = false
        return Change.RECOVERED
    }

    fun check(nowMillis: Long): Change? {
        if (lost || nowMillis - lastFixMillis < lostAfterMillis) return null
        lost = true
        return Change.LOST
    }

    companion object {
        /** A minute without a fix: a tunnel is shorter, a phone in a bag is longer. */
        const val DEFAULT_LOST_AFTER_MILLIS = 60_000L
    }
}
