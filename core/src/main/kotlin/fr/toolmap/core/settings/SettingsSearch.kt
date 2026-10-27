package fr.toolmap.core.settings

import fr.toolmap.core.search.SearchText
import java.text.Normalizer

/**
 * The search of « Réglages » (P29.9, « Loupe »): a setting is found when every word typed is in its title, its
 * explanation or the names of its options, whatever the case and the accents; the final « s » or « x » of a word typed
 * is not needed, so that « gants » finds « Mode gants » and « repère » finds « Repères et traces ».
 */
object SettingsSearch {
    /** The shortest word whose final « s » or « x » is taken for a plural: « gps » keeps its « s ». */
    private const val PLURAL_MIN_LENGTH = 4

    /** The words typed, folded, each without its plural mark; empty when nothing is typed. */
    fun terms(query: String): List<String> =
        SearchText
            .tokens(query)
            .map {
                if (it.length >= PLURAL_MIN_LENGTH &&
                    (it.endsWith('s') || it.endsWith('x'))
                ) {
                    it.dropLast(1)
                } else {
                    it
                }
            }
            .distinct()

    /** Whether every one of [terms] is in one of [texts]; never with no term. */
    fun matches(
        terms: List<String>,
        texts: List<String>,
    ): Boolean {
        if (terms.isEmpty()) return false
        val folded = texts.joinToString("\n") { fold(it) }
        return terms.all { it in folded }
    }

    /** Where [terms] are in [text], as ranges of its characters for the bold: sorted, merged when they touch. */
    fun highlights(
        text: String,
        terms: List<String>,
    ): List<IntRange> {
        val folded = fold(text)
        val found =
            terms
                .filter { it.isNotEmpty() }
                .flatMap { term ->
                    generateSequence(folded.indexOf(term).takeIf { it >= 0 }) { start ->
                        folded.indexOf(term, start + 1).takeIf { it >= 0 }
                    }.map { start -> start until start + term.length }.toList()
                }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (range in found) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * [text] in lower case without its accents (« É » is « e »), one character for one character, so that a place in
     * the folded text is the same place in [text].
     */
    fun fold(text: String): String =
        buildString(text.length) {
            for (c in text) append(if (c.code < ASCII) c.lowercaseChar() else foldChar(c))
        }

    private fun foldChar(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()

    private const val ASCII = 128
}
