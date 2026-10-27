package fr.toolmap.core.print

import fr.toolmap.core.geo.ScaleBar
import java.util.Locale

/** One block of the graphic scale: from [startMm] to [endMm] on the paper, black or white. */
data class ScaleBarBlock(
    val startMm: Double,
    val endMm: Double,
    val black: Boolean,
)

/** A tick label of the graphic scale, [xMm] from the bar's left end. */
data class ScaleBarLabel(
    val xMm: Double,
    val text: String,
)

/** The graphic scale of a printed map: alternating black and white blocks and their labels. */
data class ScaleBarPlan(
    val totalMeters: Double,
    val widthMm: Double,
    val blocks: List<ScaleBarBlock>,
    val labels: List<ScaleBarLabel>,
) {
    companion object {
        val EMPTY = ScaleBarPlan(0.0, 0.0, emptyList(), emptyList())
    }
}

/** Builds the graphic scale: a round length that fits in [maxWidthMm] at 1:[scale], cut in even blocks. */
object ScaleBarPlanner {
    fun plan(
        scale: Double,
        maxWidthMm: Double,
        locale: Locale = Locale.getDefault(),
    ): ScaleBarPlan {
        if (!(scale > 0) || !(maxWidthMm > 0)) return ScaleBarPlan.EMPTY
        val total = ScaleBar.niceDistance(PrintMath.groundMeters(maxWidthMm, scale))
        if (total <= 0.0) return ScaleBarPlan.EMPTY
        val widthMm = total * 1000.0 / scale
        val blocksCount = blocksFor(total)
        val blockMeters = total / blocksCount
        val blocks =
            List(blocksCount) { i ->
                ScaleBarBlock(
                    i * blockMeters * 1000.0 / scale,
                    (i + 1) * blockMeters * 1000.0 / scale,
                    black =
                        i % 2 == 0,
                )
            }
        val inKm = total >= KM
        val labels =
            List(blocksCount + 1) { i ->
                val meters = i * blockMeters
                val value = if (inKm) meters / KM else meters
                val text = formatNumber(value, locale) + if (i == blocksCount) (if (inKm) " km" else " m") else ""
                ScaleBarLabel(meters * 1000.0 / scale, text)
            }
        return ScaleBarPlan(total, widthMm, blocks, labels)
    }

    /** 1 × 10^n → 5 blocks (0.2), 2 × 10^n → 4 blocks (0.5), 5 × 10^n → 5 blocks (1). */
    private fun blocksFor(total: Double): Int {
        val mantissa = total / Math.pow(10.0, Math.floor(Math.log10(total)))
        return if (mantissa < 1.5) {
            5
        } else if (mantissa < 3.5) {
            4
        } else {
            5
        }
    }

    private fun formatNumber(
        value: Double,
        locale: Locale,
    ): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded ==
            Math.floor(rounded)
        ) {
            rounded.toLong().toString()
        } else {
            String.format(locale, "%s", trim(rounded, locale))
        }
    }

    private fun trim(
        value: Double,
        locale: Locale,
    ): String = String.format(locale, "%.2f", value).trimEnd('0').trimEnd(',', '.')

    private const val KM = 1000.0
}
