package fr.toolmap.ui

/**
 * The screens opened one over the other, from the map ([root], always first) to the one shown (last). Back closes
 * the last one and shows the one it was opened from (P14.1, `performance.md` RS1). The functions return a new list,
 * so that the stack can be held in a saveable state.
 */
object BackStack {
    /**
     * Shows [screen] over the current one. A screen already open is not stacked twice: the screens over it close, so
     * that going round in circles does not make Back longer. The root clears the stack.
     */
    fun <T> open(
        stack: List<T>,
        screen: T,
        root: T,
    ): List<T> {
        if (screen == root) return listOf(root)
        val index = stack.indexOf(screen)
        return if (index >= 0) stack.take(index + 1) else stack + screen
    }

    /** Shows [screen] in place of the current one: Back then goes to the screen under it. */
    fun <T> replace(
        stack: List<T>,
        screen: T,
        root: T,
    ): List<T> = open(if (stack.size > 1) stack.dropLast(1) else stack, screen, root)

    /** Closes the screen shown; the root stays. */
    fun <T> back(stack: List<T>): List<T> = if (stack.size > 1) stack.dropLast(1) else stack
}
