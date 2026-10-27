package fr.toolmap.draw

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols of the computed routes (P19) missing from `material-icons-core`, drawn from their paths. */
object RouteIcons {
    val FormatListNumbered: ImageVector by lazy {
        icon(
            "FormatListNumbered",
            "M2 17h2v.5H3v1h1v.5H2v1h3v-4H2v1zm1-9h1V4H2v1h1v3zm-1 3h1.8L2 13.1v.9h3v-1H3.2L5 10.9V10H2v1zm5-6v2h14V5H7zm0 14h14v-2H7v2zm0-6h14v-2H7v2z",
        )
    }

    val ExpandLess: ImageVector by lazy { icon("ExpandLess", "M12 8l-6 6 1.41 1.41L12 10.83l4.59 4.58L18 14z") }

    val ExpandMore: ImageVector by lazy { icon("ExpandMore", "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z") }

    val ArrowDropDown: ImageVector by lazy { icon("ArrowDropDown", "M7 10l5 5 5-5z") }

    val ChevronRight: ImageVector by lazy { icon("ChevronRight", "M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z") }

    val Straighten: ImageVector by lazy {
        icon(
            "Straighten",
            "M21 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 10H3V8h2v4h2V8h2v4h2V8h2v4h2V8h2v4h2V8h2v8z",
        )
    }

    val Start: ImageVector by lazy {
        icon("Start", "M15.59 7.41 18.17 10H8v2h10.17l-2.59 2.59L17 16l5-5-5-5-1.41 1.41zM6 6H4v12h2V6z")
    }

    val AddLocationAlt: ImageVector by lazy {
        icon(
            "AddLocationAlt",
            "M20 1v3h3v2h-3v3h-2V6h-3V4h3V1h2zm-8 12c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm2-9.75V7h3v3h2.92c.05.39.08.79.08 1.2 0 3.32-2.67 7.25-8 11.8-5.33-4.55-8-8.48-8-11.8C4 6.22 7.8 3 12 3c.68 0 1.35.08 2 .25z",
        )
    }

    val SwapVert: ImageVector by lazy {
        icon("SwapVert", "M16 17.01V10h-2v7.01h-3L15 21l4-3.99h-3zM9 3 5 6.99h3V14h2V6.99h3L9 3z")
    }

    val Loop: ImageVector by lazy {
        icon(
            "Loop",
            "M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z",
        )
    }

    val SyncAlt: ImageVector by lazy { icon("SyncAlt", "M18 12l4-4-4-4v3H3v2h15zM6 12l-4 4 4 4v-3h15v-2H6z") }

    val ShowChart: ImageVector by lazy {
        icon("ShowChart", "M3.5 18.49l6-6.01 4 4L22 6.92l-1.41-1.41-7.09 7.97-4-4L2 16.99z")
    }

    val DeleteSweep: ImageVector by lazy {
        icon(
            "DeleteSweep",
            "M15 16h4v2h-4zm0-8h7v2h-7zm0 4h6v2h-6zM3 18c0 1.1.9 2 2 2h6c1.1 0 2-.9 2-2V8H3v10zM14 5h-3l-1-1H6L5 5H2v2h12z",
        )
    }

    val Refresh: ImageVector by lazy {
        icon(
            "Refresh",
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
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
            ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
