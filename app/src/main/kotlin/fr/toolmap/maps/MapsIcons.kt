package fr.toolmap.maps

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few icons the maps panels need that `material-icons-core` does not ship (eye, drop, layers, bookmark...).
 * Drawn on the usual 24 x 24 grid from Material path data; tinted by the `Icon` that shows them.
 */
object MapsIcons {
    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39 -6,-7.5 -11,-7.5z" +
                "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
                "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75" +
                " -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
                "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 " +
                "1.55,0 3.03,-0.3 4.38,-0.84" +
                "l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08" +
                "l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
                "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    /** A drop: the opacity of a map. */
    val Opacity: ImageVector by lazy {
        icon(
            "Opacity",
            "M17.66,8L12,2.35 6.34,8C4.78,9.56 4,11.64 4,13.64s0.78,4.11 2.34,5.67 3.61,2.35 5.66,2.35 " +
                "4.1,-0.79 5.66,-2.35" +
                "S20,15.64 20,13.64 19.22,9.56 17.66,8z" +
                "M6,14c0.01,-2 0.62,-3.27 1.76,-4.4L12,5.27l4.24,4.38C17.38,10.77 17.99,12 18,14H6z",
        )
    }

    val Layers: ImageVector by lazy {
        icon(
            "Layers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74z" +
                "M12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
        )
    }

    val Map: ImageVector by lazy {
        icon(
            "Map",
            "M20.5,3l-0.16,0.03L15,5.1 9,3 3.36,4.9c-0.21,0.07 -0.36,0.25 -0.36,0.48V20.5c0,0.28 0.22,0.5 0.5,0.5" +
                "l0.16,-0.03L9,18.9l6,2.1 5.64,-1.9c0.21,-0.07 0.36,-0.25 0.36,-0.48V3.5" +
                "c0,-0.28 -0.22,-0.5 -0.5,-0.5z" +
                "M15,19l-6,-2.11V5l6,2.11V19z",
        )
    }

    val Bookmark: ImageVector by lazy {
        icon("Bookmark", "M17,3L7,3c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3L19,5c0,-1.1 -0.9,-2 -2,-2z")
    }

    /** Corner brackets: the "select an area" tool. */
    val CropFree: ImageVector by lazy {
        icon(
            "CropFree",
            "M3,5v4h2L5,5h4L9,3L5,3c-1.1,0 -2,0.9 -2,2z" +
                "M5,15L3,15v4c0,1.1 0.9,2 2,2h4v-2L5,19v-4z" +
                "M19,19h-4v2h4c1.1,0 2,-0.9 2,-2v-4h-2v4z" +
                "M19,3h-4v2h4v4h2L21,5c0,-1.1 -0.9,-2 -2,-2z",
        )
    }

    val Help: ImageVector by lazy {
        icon(
            "Help",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M13,19h-2v-2h2v2z" +
                "M15.07,11.25l-0.9,0.92C13.45,12.9 13,13.5 13,15h-2v-0.5c0,-1.1 0.45,-2.1 1.17,-2.83l1.24,-1.26" +
                "c0.37,-0.36 0.59,-0.86 0.59,-1.41 0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2L8,9" +
                "c0,-2.21 1.79,-4 4,-4s4,1.79 4,4" +
                "c0,0.88 -0.36,1.68 -0.93,2.25z",
        )
    }

    /** « Télécharger une zone… » of the map menu (F4). */
    val Download: ImageVector by lazy { icon("Download", "M5,20h14v-2H5v2zM19,9h-4V3H9v6H5l7,7 7,-7z") }

    /** Six dots: the handle a layer is dragged by (P29.3). */
    val DragIndicator: ImageVector by lazy {
        icon(
            "DragIndicator",
            "M11 18c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm-2-8c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z " +
                "M9 4c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6 4c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2z " +
                "M15 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
        )
    }

    val ArrowUpward: ImageVector by lazy {
        icon("ArrowUpward", "m4 12 1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z")
    }

    val ArrowDownward: ImageVector by lazy {
        icon("ArrowDownward", "m20 12-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z")
    }

    /** Sliders: « Conditions » of a layer. */
    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10z" +
                "m-6-4h2V7h4V5h-4V3h-2v6z",
        )
    }

    /** A bookmark with a « + »: « Enregistrer l'affichage » as a favourite. */
    val BookmarkAdd: ImageVector by lazy {
        icon(
            "BookmarkAdd",
            "M21 7h-2v2h-2V7h-2V5h2V3h2v2h2v2zm-2 14-7-3-7 3V5c0-1.1.9-2 2-2h7" +
                "a5.002 5.002 0 0 0 5 7.9V21z",
        )
    }

    /** A circular arrow: « Réinitialiser » a condition, « Rétablir » the provided favourites. */
    val RestartAlt: ImageVector by lazy {
        icon(
            "RestartAlt",
            "M12 5V2L8 6l4 4V7c3.31 0 6 2.69 6 6 0 2.97-2.17 5.43-5 5.91v2.02" +
                "c3.95-.49 7-3.85 7-7.93 0-4.42-3.58-8-8-8z" +
                "m-6 8c0-1.65.67-3.15 1.76-4.24L6.34 7.34A8.014 8.014 0 0 0 4 13c0 4.08 3.05 7.44 7 7.93v-2.02" +
                "c-2.83-.48-5-2.94-5-5.91z",
        )
    }

    /** « Exporter en .aqx… » of the map menu (F4). */
    val IosShare: ImageVector by lazy {
        icon(
            "IosShare",
            "M16,5l-1.42,1.42 -1.59,-1.59V16h-1.98V4.83L9.42,6.42 8,5l4,-4 4,4z" +
                "M20,10v11c0,1.1 -0.9,2 -2,2H6c-1.11,0 -2,-0.9 -2,-2V10c0,-1.11 0.89,-2 2,-2h3v2H6v11h12V10h-3V8h3" +
                "c1.1,0 2,0.89 2,2z",
        )
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
            )
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()

    private const val SIZE = 24
    private const val GRID = 24f
}
