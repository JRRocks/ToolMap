package fr.toolmap.share

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material icons (filled) of the sharing screen that `material-icons-core` lacks (P29.8, « Cordée »). */
object ShareIcons {
    /** « groups »: no group yet. */
    val Groups: ImageVector by lazy {
        icon(
            "Groups",
            "M12,12.75c1.63,0 3.07,0.39 4.24,0.9 1.08,0.48 1.76,1.56 1.76,2.73L18,18L6,18v-1.61c0,-1.18 0.68,-2.26 " +
                "1.76,-2.73 1.17,-0.52 2.61,-0.91 4.24,-0.91zM4,13c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 " +
                "0.9,2 2,2zM5.13,14.1c-0.37,-0.06 -0.74,-0.1 -1.13,-0.1 -0.99,0 -1.93,0.21 -2.78,0.58C0.48,14.9 0," +
                "15.62 0,16.43L0,18h4.5v-1.61c0,-0.83 0.23,-1.61 0.63,-2.29zM20,13c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 " +
                "-2,0.9 -2,2 0.9,2 2,2zM24,16.43c0,-0.81 -0.48,-1.53 -1.22,-1.85 -0.85,-0.37 -1.79,-0.58 -2.78,-0.58 " +
                "-0.39,0 -0.76,0.04 -1.13,0.1 0.4,0.68 0.63,1.46 0.63,2.29L19.5,18L24,18v-1.57zM12,6c1.66,0 3,1.34 " +
                "3,3s-1.34,3 -3,3 -3,-1.34 -3,-3 1.34,-3 3,-3z",
        )
    }

    /** « group_add »: « Créer un groupe et inviter ». */
    val GroupAdd: ImageVector by lazy {
        icon(
            "GroupAdd",
            "M8,10L5,10L5,7L3,7v3L0,10v2h3v3h2v-3h3v-2zM18,11c1.66,0 2.99,-1.34 2.99,-3S19.66,5 18,5c-0.32,0 " +
                "-0.63,0.05 -0.91,0.14 0.57,0.81 0.9,1.79 0.9,2.86s-0.34,2.04 -0.9,2.86c0.28,0.09 0.59,0.14 " +
                "0.91,0.14zM13,11c1.66,0 2.99,-1.34 2.99,-3S14.66,5 13,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM19.62," +
                "13.16c0.83,0.73 1.38,1.66 1.38,2.84v2h3v-2c0,-1.54 -2.37,-2.49 -4.38,-2.84zM13,13c-2,0 -6,1 -6,3v2" +
                "h12v-2c0,-2 -4,-3 -6,-3z",
        )
    }

    /** « person_add »: « Inviter ». */
    val PersonAdd: ImageVector by lazy {
        icon(
            "PersonAdd",
            "M15,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM6,10L6,7L4,7v3L1,10v2h3v3h2v-3h3" +
                "v-2L6,10zM15,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z",
        )
    }

    /** « chat »: the line of the messages. */
    val Chat: ImageVector by lazy {
        icon(
            "Chat",
            "M20,2L4,2c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 -2,-2zM6,9h12v2" +
                "L6,11L6,9zM14,14L6,14v-2h8v2zM18,8L6,8L6,6h12v2z",
        )
    }

    /** « emergency_share »: « Envoyer une alerte au groupe » and the alert in progress (P22). */
    val EmergencyShare: ImageVector by lazy {
        icon(
            "EmergencyShare",
            "M12,9c-3.31,0 -6,2.69 -6,6 0,4.5 6,9 6,9s6,-4.5 6,-9c0,-3.31 -2.69,-6 -6,-6zM12,17c-1.1,0 -2,-0.9 " +
                "-2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM7.05,7.05l1.41,1.41C9.37,7.56 10.63,7 12,7s2.63,0.56 " +
                "3.54,1.46l1.41,-1.41C15.68,5.78 13.93,5 12,5S8.32,5.78 7.05,7.05zM12,1C8.96,1 6.21,2.23 4.22," +
                "4.22l1.41,1.41C7.26,4.01 9.51,3 12,3s4.74,1.01 6.36,2.64l1.41,-1.41C17.79,2.23 15.04,1 12,1z",
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
