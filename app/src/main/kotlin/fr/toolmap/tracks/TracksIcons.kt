package fr.toolmap.tracks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons of the tracks screens that `material-icons-core` does not ship (P29.4): the folder of the list, the
 * groups of the detail, the arrow that points to an item. Material path data on the 24 x 24 grid.
 */
object TracksIcons {
    val Folder: ImageVector by lazy {
        icon("Folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    }

    /** « Tous les chiffres » of the detail. */
    val Analytics: ImageVector by lazy {
        icon(
            "Analytics",
            "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-5h2v5z " +
                "M13 17h-2v-3h2v3zm0-5h-2v-2h2v2zm4 5h-2V7h2v10z",
        )
    }

    /** « Simplifier… ». */
    val Compress: ImageVector by lazy {
        icon("Compress", "M8 19h3v3h2v-3h3l-4-4-4 4zm8-15h-3V1h-2v3H8l4 4 4-4zM4 9v2h16V9H4zm0 3h16v2H4z")
    }

    /** « Fusionner avec une autre trace… ». */
    val CallMerge: ImageVector by lazy {
        icon(
            "CallMerge",
            "M17 20.41 18.41 19 15 15.59 13.59 17 17 20.41zM7.5 8H11v5.59L5.59 19 7 20.41l6-6V8h3.5L12 3.5 7.5 8z",
        )
    }

    /** A straight arrow pointing up, turned by the bearing to an item (Material « north »). */
    val Arrow: ImageVector by lazy {
        icon("North", "M5 9l1.41 1.41L11 5.83V22h2V5.83l4.59 4.59L19 9l-7-7-7 7z")
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = SIZE.dp,
                defaultHeight = SIZE.dp,
                viewportWidth = GRID,
                viewportHeight = GRID,
            ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()

    private const val SIZE = 24
    private const val GRID = 24f
}
