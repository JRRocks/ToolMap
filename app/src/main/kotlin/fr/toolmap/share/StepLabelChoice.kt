package fr.toolmap.share

/**
 * Where the labels under the steps of a slider stand, and which of them are written (P39.15, F8 « B poussé plus loin »,
 * validated on 2026-11-19: `docs/previews/p39-groupes/tour6/img/11-f8-b-pousse.png`, whose page
 * `tour6/etroit/b-plus.html` computes the same). Each label is centred on its notch, the first and the last within
 * [overhang] of the width. The first and the last are always written; between them, the first of these that leaves
 * [gap] between two written labels: every step; every other one (a step closer than two to the last is left out); the
 * middle one alone; none.
 * Pure arithmetic on widths, in any unit: the screen gives pixels, the tests dp.
 */
internal object StepLabelChoice {
    /** The left edge of each label of [widths] under a slider [width] wide whose end notches stand [inset] in. */
    fun place(
        width: Float,
        widths: List<Float>,
        inset: Float,
        overhang: Float,
    ): List<Float> {
        val last = (widths.size - 1).coerceAtLeast(1)
        val span = (width - 2 * inset).coerceAtLeast(0f)
        return widths.mapIndexed { i, w ->
            val centre = inset + span * i / last
            (centre - w / 2).coerceIn(-overhang, (width - w + overhang).coerceAtLeast(-overhang))
        }
    }

    /** Which labels are written: see [StepLabelChoice]. */
    fun shown(
        width: Float,
        widths: List<Float>,
        inset: Float,
        overhang: Float,
        gap: Float,
    ): List<Boolean> {
        val x = place(width, widths, inset, overhang)
        val last = widths.lastIndex
        val between =
            listOf<(Int) -> Boolean>(
                { true },
                { i -> i % 2 == 0 && last - i >= 2 },
                { i -> i == last / 2 },
            )
        for (keep in between) {
            val shown = List(widths.size) { i -> i == 0 || i == last || keep(i) }
            if (apart(x, widths, shown, gap)) return shown
        }
        return List(widths.size) { i -> i == 0 || i == last }
    }

    private fun apart(
        x: List<Float>,
        widths: List<Float>,
        shown: List<Boolean>,
        gap: Float,
    ): Boolean {
        var previous = -1
        for (i in widths.indices) {
            if (!shown[i]) continue
            if (previous >= 0 && x[previous] + widths[previous] + gap > x[i]) return false
            previous = i
        }
        return true
    }
}
