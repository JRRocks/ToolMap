package fr.toolmap.print

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.toolmap.core.print.FooterBlock
import fr.toolmap.core.print.PageSetup
import fr.toolmap.core.print.SheetLayout

/**
 * A sheet of the print in miniature (P17.8, variant E « Épreuve »): the paper, the map frame with its double line and a
 * few grid lines, and the boxes of the footer, all placed by the real layout ([PageSetup], [SheetLayout]); no tiles.
 * The colours are those of the printed sheet, the same by day and by night.
 */
@Composable
fun SheetThumbnail(
    setup: PageSetup,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(width, height)) { drawSheet(setup) }
}

private fun DrawScope.drawSheet(setup: PageSetup) {
    val k = size.width / setup.widthMm.toFloat()
    val hairline = 1.dp.toPx() / 2f
    drawRect(PAPER)
    drawRect(EDGE, style = Stroke(hairline))
    // The map frame, its grid and its double line.
    val left = setup.frameLeftMm.toFloat() * k
    val top = setup.frameTopMm.toFloat() * k
    val w = setup.frameWidthMm.toFloat() * k
    val h = setup.frameHeightMm.toFloat() * k
    if (w <= 0f || h <= 0f) return
    drawRect(MAP, Offset(left, top), Size(w, h))
    for (i in 1 until GRID_DIVISIONS) {
        val x = left + w * i / GRID_DIVISIONS
        val y = top + h * i / GRID_DIVISIONS
        drawLine(GRID, Offset(x, top), Offset(x, top + h), hairline)
        drawLine(GRID, Offset(left, y), Offset(left + w, y), hairline)
    }
    drawRect(INK, Offset(left, top), Size(w, h), style = Stroke(hairline * 2))
    val band = PageSetup.GRADUATION_MM.toFloat() * k
    drawRect(INK, Offset(left - band, top - band), Size(w + 2 * band, h + 2 * band), style = Stroke(hairline))
    // The boxes of the footer: the title in petrol, the scale bar, the north, the grid.
    val footerLeft = setup.marginMm.toFloat() * k
    val footerTop = setup.footerTopMm.toFloat() * k
    for (box in SheetLayout.place(setup.widthMm - 2 * setup.marginMm)) {
        val bx = footerLeft + box.xMm.toFloat() * k
        val by = footerTop + box.yMm.toFloat() * k
        val bw = box.widthMm.toFloat() * k
        val bh = box.heightMm.toFloat() * k
        drawRect(BOX, Offset(bx, by), Size(bw, bh), style = Stroke(hairline))
        val pad = bh * BOX_PAD
        when (box.block) {
            FooterBlock.TITLE -> {
                drawRect(TITLE, Offset(bx + pad, by + pad), Size(bw * TITLE_WIDTH, bh * TITLE_HEIGHT))
            }

            FooterBlock.SCALE -> {
                val step = (bw - 2 * pad) / SCALE_STEPS
                for (j in 0 until SCALE_STEPS) {
                    val cell = Size(step, bh * SCALE_HEIGHT)
                    val at = Offset(bx + pad + j * step, by + bh * SCALE_TOP)
                    if (j % 2 == 0) drawRect(INK, at, cell) else drawRect(INK, at, cell, style = Stroke(hairline))
                }
            }

            FooterBlock.NORTH -> {
                val cx = bx + bw / 2
                val arrow =
                    Path().apply {
                        moveTo(cx, by + pad)
                        lineTo(cx + bh * NORTH_HALF, by + bh - pad)
                        lineTo(cx, by + bh * NORTH_NOTCH)
                        lineTo(cx - bh * NORTH_HALF, by + bh - pad)
                        close()
                    }
                drawPath(arrow, INK)
            }

            FooterBlock.GRID -> {
                drawRect(GRID, Offset(bx + pad, by + pad), Size(bw * TITLE_HEIGHT * 2, bh * GRID_HEIGHT))
            }
        }
    }
}

/** The size a sheet is drawn at among the others: the same millimetres per dp for all, the custom one fitted. */
fun thumbnailSize(
    setup: PageSetup,
    fitted: Boolean,
): Pair<Dp, Dp> {
    val w = setup.widthMm
    val h = setup.heightMm
    val dpPerMm =
        if (fitted) {
            minOf(CUSTOM_MAX_WIDTH_DP / w, THUMB_LONG_SIDE_DP / h)
        } else {
            THUMB_LONG_SIDE_DP /
                A3_LONG_MM
        }
    return (w * dpPerMm).toFloat().dp to (h * dpPerMm).toFloat().dp
}

/** The orientations of the sheet, for the segmented button (`material-icons-core` has neither). */
object PrintIcons {
    val Portrait: ImageVector by lazy {
        icon(
            "CropPortrait",
            "M17 3H7c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H7V5h10v14z",
        )
    }

    val Landscape: ImageVector by lazy {
        icon(
            "CropLandscape",
            "M19 5H5c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 12H5V7h14v10z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            )
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}

private val PAPER = Color.White
private val EDGE = Color(0xFF9AA9AE)
private val MAP = Color(0xFFE4EBCF)
private val GRID = Color(0x991D3D8F)
private val INK = Color(0xFF1B2A30)
private val BOX = Color(0xFFC4CDD0)
private val TITLE = Color(0xFF163A4A)

private const val GRID_DIVISIONS = 4
private const val BOX_PAD = 0.15f
private const val TITLE_WIDTH = 0.6f
private const val TITLE_HEIGHT = 0.2f
private const val GRID_HEIGHT = 0.12f
private const val SCALE_STEPS = 4
private const val SCALE_TOP = 0.55f
private const val SCALE_HEIGHT = 0.15f
private const val NORTH_HALF = 0.18f
private const val NORTH_NOTCH = 0.7f

/** A3, the largest sheet offered, has its long side this tall; the others follow at the same scale. */
private const val THUMB_LONG_SIDE_DP = 74.0
private const val A3_LONG_MM = 420.0

/** A custom sheet is fitted in 96 × 74 dp, whatever its size. */
private const val CUSTOM_MAX_WIDTH_DP = 96.0
