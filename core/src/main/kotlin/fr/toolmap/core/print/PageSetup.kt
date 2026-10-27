package fr.toolmap.core.print

import kotlinx.serialization.Serializable

/** Paper sizes in millimeters, portrait. */
@Serializable
enum class Paper(
    val widthMm: Double,
    val heightMm: Double,
) {
    A5(148.0, 210.0),
    A4(210.0, 297.0),
    A3(297.0, 420.0),
    A2(420.0, 594.0),
    LETTER(215.9, 279.4),
    LEGAL(215.9, 355.6),
    CUSTOM(210.0, 297.0),
    ;

    companion object {
        /** The formats offered first; the others are reached through the custom size (P17, Q2). */
        val OFFERED: List<Paper> = listOf(A5, A4, A3, LETTER, CUSTOM)
    }
}

/**
 * The look of the sheet around the map (P17, Q9): an IGN TOP 25 sheet, or the same with what NATO maps add for the
 * field (angles in mils, the grid reference box, the 100 km squares).
 */
@Serializable
enum class MapStyle { TOP25, NATO }

/**
 * The sheet: format, orientation, the printer margin and the style of the sheet. From the paper edge inwards: the
 * margin, a strip for the grid labels, the graduated band of the double frame, then the map; under the map, the same
 * band and strip, then the footer that holds title, scale, norths, grid and legend (docs/specs/print-pdf.md §6).
 */
@Serializable
data class PageSetup(
    val paper: Paper = Paper.A4,
    val landscape: Boolean = true,
    /** White border the printer may not reach, from the paper edge to the label strip. */
    val marginMm: Double = DEFAULT_MARGIN_MM,
    /** Used when [paper] is [Paper.CUSTOM] (portrait width and height). */
    val customWidthMm: Double = Paper.CUSTOM.widthMm,
    val customHeightMm: Double = Paper.CUSTOM.heightMm,
    val style: MapStyle = MapStyle.TOP25,
) {
    private val portraitWidth: Double get() = if (paper == Paper.CUSTOM) customWidthMm else paper.widthMm
    private val portraitHeight: Double get() = if (paper == Paper.CUSTOM) customHeightMm else paper.heightMm

    val widthMm: Double get() =
        if (landscape) maxOf(portraitWidth, portraitHeight) else minOf(portraitWidth, portraitHeight)
    val heightMm: Double get() =
        if (landscape) minOf(portraitWidth, portraitHeight) else maxOf(portraitWidth, portraitHeight)

    /** From the paper edge to the map, on the left, top and right sides. */
    val borderMm: Double get() = marginMm + LABEL_STRIP_MM + GRADUATION_MM

    /** The footer under the map: its rows of boxes and the credits line (see [SheetLayout]). */
    val footerHeightMm: Double get() = SheetLayout.footerHeight(widthMm - 2 * marginMm, style)

    /** The map frame: where it starts on the page and its size. */
    val frameLeftMm: Double get() = borderMm
    val frameTopMm: Double get() = borderMm
    val frameWidthMm: Double get() = widthMm - 2 * borderMm
    val frameHeightMm: Double get() = heightMm - 2 * borderMm - footerHeightMm

    /** Top edge of the footer, under the bottom label strip. */
    val footerTopMm: Double get() = heightMm - marginMm - footerHeightMm

    /** The page in PostScript points (1/72 inch), rounded as PDF page boxes are integers. */
    val widthPt: Int get() = Math.round(widthMm * PT_PER_MM).toInt()
    val heightPt: Int get() = Math.round(heightMm * PT_PER_MM).toInt()

    /** Problems that would make the frame unusable (empty list when fine). */
    fun issues(): List<PageIssue> =
        buildList {
            if (marginMm !in 0.0..MAX_MARGIN_MM) add(PageIssue.MARGIN_RANGE)
            if (paper == Paper.CUSTOM &&
                (customWidthMm !in MIN_CUSTOM_MM..MAX_CUSTOM_MM || customHeightMm !in MIN_CUSTOM_MM..MAX_CUSTOM_MM)
            ) {
                add(PageIssue.CUSTOM_SIZE)
            }
            if (!(frameWidthMm >= MIN_FRAME_MM && frameHeightMm >= MIN_FRAME_MM)) add(PageIssue.FRAME_TOO_SMALL)
        }

    companion object {
        const val DEFAULT_MARGIN_MM = 8.0
        const val MAX_MARGIN_MM = 50.0
        const val MIN_CUSTOM_MM = 50.0
        const val MAX_CUSTOM_MM = 1500.0
        const val MIN_FRAME_MM = 20.0

        /** Room for the grid labels between the margin and the frame. */
        const val LABEL_STRIP_MM = 7.0

        /** The band between the two lines of the frame, graduated in the other coordinate system. */
        const val GRADUATION_MM = 1.6

        /** Points per millimeter: 72 / 25.4. */
        const val PT_PER_MM = 72.0 / 25.4
    }
}

enum class PageIssue { MARGIN_RANGE, CUSTOM_SIZE, FRAME_TOO_SMALL }

/** The boxes of the footer, left to right (spec §6). */
enum class FooterBlock { TITLE, SCALE, NORTH, GRID, REFERENCE }

/** A box of the footer, in millimeters from the footer's top-left corner. */
data class PlacedBlock(
    val block: FooterBlock,
    val xMm: Double,
    val yMm: Double,
    val widthMm: Double,
    val heightMm: Double,
)

/**
 * How the footer is cut into boxes: fixed widths, the title box taking what is left of its row; the boxes go in rows
 * as wide as the sheet allows. Text keeps the same size whatever the paper, as on a printed map, so a narrow sheet
 * gets two rows rather than smaller letters.
 */
object SheetLayout {
    const val BLOCK_HEIGHT_MM = 22.0
    const val GAP_MM = 2.5
    const val PAD_MM = 1.5

    /** The line of credits (© of the sources, warnings) under the boxes. */
    const val CREDITS_MM = 3.6

    private const val TITLE_MIN_MM = 40.0

    // The NATO norths add the angles in mils: a wider box.
    private fun widthOf(
        block: FooterBlock,
        style: MapStyle,
    ): Double =
        when (block) {
            FooterBlock.TITLE -> TITLE_MIN_MM
            FooterBlock.SCALE -> 50.0
            FooterBlock.NORTH -> if (style == MapStyle.NATO) 54.0 else 46.0
            FooterBlock.GRID -> 46.0
            FooterBlock.REFERENCE -> 56.0
        }

    fun blocksOf(style: MapStyle): List<FooterBlock> =
        when (style) {
            MapStyle.TOP25 -> listOf(FooterBlock.TITLE, FooterBlock.SCALE, FooterBlock.NORTH, FooterBlock.GRID)
            MapStyle.NATO -> FooterBlock.entries.toList()
        }

    /** The boxes of a footer [widthMm] wide (the sheet minus its margins). */
    fun place(
        widthMm: Double,
        style: MapStyle,
    ): List<PlacedBlock> {
        val inner = widthMm - 2 * PAD_MM
        val rows = ArrayList<MutableList<FooterBlock>>()
        var used = 0.0
        for (block in blocksOf(style)) {
            val w = widthOf(block, style)
            val row = rows.lastOrNull()
            if (row == null || used + GAP_MM + w > inner) {
                rows += mutableListOf(block)
                used = w
            } else {
                row += block
                used += GAP_MM + w
            }
        }
        val out = ArrayList<PlacedBlock>()
        rows.forEachIndexed { r, row ->
            val fixed = row.sumOf { widthOf(it, style) } + GAP_MM * (row.size - 1)
            val spare = (inner - fixed).coerceAtLeast(0.0)
            var x = PAD_MM
            val y = PAD_MM + r * (BLOCK_HEIGHT_MM + GAP_MM)
            for (block in row) {
                val w = widthOf(block, style) + if (block == FooterBlock.TITLE) spare else 0.0
                out += PlacedBlock(block, x, y, w, BLOCK_HEIGHT_MM)
                x += w + GAP_MM
            }
        }
        return out
    }

    fun rows(
        widthMm: Double,
        style: MapStyle,
    ): Int = place(widthMm, style).map { it.yMm }.distinct().size

    fun footerHeight(
        widthMm: Double,
        style: MapStyle,
    ): Double {
        val rows = rows(widthMm, style)
        return 2 * PAD_MM + rows * BLOCK_HEIGHT_MM + (rows - 1) * GAP_MM + CREDITS_MM
    }
}
