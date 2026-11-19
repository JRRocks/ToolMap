package fr.toolmap.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The minutes written under the slider of the rhythm (P39.15, F8 « B poussé plus loin », `tour6/img/11-f8-b-pousse`):
 * the cases of its page (`tour6/etroit/b-plus.html`), in dp. The slider is the width of the screen less 48 dp;
 * « 10 min » is 40 dp wide at the normal size of the text (the width the page calibrates on), the others in proportion.
 */
class StepLabelChoiceTest {
    private val labels = listOf("1 min", "2", "3", "4", "5 min", "6", "7", "8", "9", "10 min")

    /** The width of each label at [scale] times the normal size of the text, « 2 » in bold (the value of the page). */
    private fun widths(scale: Float) =
        labels.mapIndexed { i, label ->
            scale *
                when {
                    label == "10 min" -> TEN_MIN
                    label.endsWith(" min") -> ONE_MIN
                    i == 1 -> DIGIT_BOLD
                    else -> DIGIT
                }
        }

    private fun written(
        screen: Float,
        scale: Float = 1f,
    ): List<String> {
        val shown = StepLabelChoice.shown(screen - SHEET_PADDING, widths(scale), INSET, OVER, GAP)
        return labels.filterIndexed { i, _ -> shown[i] }
    }

    private val everyOther = listOf("1 min", "3", "5 min", "7", "10 min")

    @Test
    fun `every other minute at 320 and 360 dp, every minute from 384 dp, as the page`() {
        assertEquals(everyOther, written(320f))
        assertEquals(everyOther, written(360f))
        assertEquals(labels, written(384f))
        assertEquals(labels, written(411f))
    }

    @Test
    fun `at 320 dp with the text twice as large, 5 min alone between the ends`() {
        assertEquals(listOf("1 min", "5 min", "10 min"), written(320f, 2f))
    }

    @Test
    fun `the ends are always written, alone when even 5 min does not fit`() {
        assertEquals(listOf("1 min", "10 min"), written(240f, 2f))
        assertEquals(listOf("1 min", "10 min"), written(160f, 3f))
    }

    @Test
    fun `each label stays under its notch, the ends within 4 dp of the slider`() {
        val width = 411f - SHEET_PADDING
        val w = widths(1f)
        val x = StepLabelChoice.place(width, w, INSET, OVER)
        val span = width - 2 * INSET
        for (i in 1 until labels.lastIndex) assertEquals(INSET + span * i / labels.lastIndex, x[i] + w[i] / 2, 0.001f)
        assertEquals(-OVER, x.first())
        assertEquals(width - w.last() + OVER, x.last())
        assertTrue(x.zipWithNext().all { (a, b) -> a < b })
    }

    private companion object {
        const val SHEET_PADDING = 48f
        const val INSET = 10f
        const val OVER = 4f
        const val GAP = 4f
        const val TEN_MIN = 40f
        const val ONE_MIN = 32.6f
        const val DIGIT = 7.4f
        const val DIGIT_BOLD = 7.6f
    }
}
