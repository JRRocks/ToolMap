package fr.toolmap.download

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material icons (filled) of the states of a download that `material-icons-core` lacks (P29.7). */
object DownloadIcons {
    /** « pause_circle »: a job paused. */
    val PauseCircle: ImageVector by lazy {
        icon(
            "PauseCircle",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM11,16L9,16L9,8h2v8zM15,16h-2L13,8h2v8z",
        )
    }

    /** « schedule »: a job waiting for its turn. */
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20" +
                "c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7L11,7v6l5.25,3.15 0.75,-1.23 " +
                "-4.5,-2.67z",
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
                viewportWidth = SIZE,
                viewportHeight = SIZE,
            )
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()

    private const val SIZE = 24f
}
