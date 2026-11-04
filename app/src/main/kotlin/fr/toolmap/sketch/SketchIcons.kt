package fr.toolmap.sketch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The Material icons of the sketch (P28): the tile, the hand, the pen and the eraser. */
object SketchIcons {
    /** A pencil over a scribble: the « Croquis » tile. */
    val Draw: ImageVector by lazy {
        icon(
            "Draw",
            "M18.85,10.39l1.06,-1.06c0.78,-0.78,0.78,-2.05,0,-2.83L18.5,5.09c-0.78,-0.78,-2.05,-0.78,-2.83,0" +
                "l-1.06,1.06L18.85,10.39zM13.19,7.56L4,16.76V21h4.24l9.19,-9.19L13.19,7.56z" +
                "M19,17.5c0,2.19,-2.54,3.5,-5,3.5c-0.55,0,-1,-0.45,-1,-1s0.45,-1,1,-1c1.54,0,3,-0.73,3,-1.5" +
                "c0,-0.47,-0.48,-0.87,-1.23,-1.2l1.48,-1.48C18.32,15.45,19,16.29,19,17.5z" +
                "M4.58,13.35C3.61,12.79,3,12.06,3,11c0,-1.8,1.89,-2.63,3.56,-3.36C7.59,7.18,9,6.56,9,6" +
                "c0,-0.41,-0.78,-1,-2,-1C5.74,5,5.2,5.61,5.17,5.64C4.82,6.05,4.19,6.1,3.77,5.76" +
                "C3.36,5.42,3.28,4.81,3.62,4.38C3.73,4.24,4.76,3,7,3c2.24,0,4,1.32,4,3c0,1.87,-1.93,2.72,-3.64,3.47" +
                "C6.42,9.88,5,10.5,5,11c0,0.31,0.43,0.6,1.07,0.86L4.58,13.35z",
        )
    }

    /** An open hand: the map moves under the finger. */
    val Hand: ImageVector by lazy {
        icon(
            "PanTool",
            "M23,5.5V20c0,2.2,-1.8,4,-4,4h-7.3c-1.08,0,-2.1,-0.43,-2.85,-1.19L1,14.83c0,0,1.26,-1.23,1.3,-1.25" +
                "c0.22,-0.19,0.49,-0.29,0.79,-0.29c0.22,0,0.42,0.06,0.6,0.16C3.73,13.46,8,15.91,8,15.91V4" +
                "c0,-0.83,0.67,-1.5,1.5,-1.5S11,3.17,11,4v7h1V1.5C12,0.67,12.67,0,13.5,0S15,0.67,15,1.5V11h1V2.5" +
                "C16,1.67,16.67,1,17.5,1S19,1.67,19,2.5V11h1V5.5C20,4.67,20.67,4,21.5,4S23,4.67,23,5.5z",
        )
    }

    /** A pen: the finger draws. */
    val Pen: ImageVector by lazy {
        icon(
            "Edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39,0.39,-1.02,0,-1.41" +
                "l-2.34,-2.34c-0.39,-0.39,-1.02,-0.39,-1.41,0l-1.83,1.83l3.75,3.75L20.71,7.04z",
        )
    }

    /** An eraser: the finger erases. */
    val Eraser: ImageVector by lazy {
        icon(
            "InkEraser",
            "M16.24,3.56l4.95,4.94c0.78,0.79,0.78,2.05,0,2.84L12,20.53c-1.56,1.56,-4.09,1.56,-5.66,0L2.81,17" +
                "c-0.78,-0.79,-0.78,-2.05,0,-2.84l10.6,-10.6C14.2,2.78,15.46,2.78,16.24,3.56z" +
                "M4.22,15.58l3.54,3.53c0.78,0.79,2.04,0.79,2.83,0l3.53,-3.53l-4.95,-4.95L4.22,15.58z",
        )
    }

    /** The outline of a pentagon: the shapes placed point by point (P28.4). */
    val Shape: ImageVector by lazy {
        icon(
            "Pentagon",
            "M12,2L22,9.3L18.2,21H5.8L2,9.3z" +
                "M12,4.5L4.35,10.05L7.25,19H16.75L19.65,10.05z",
        )
    }

    /** A broken line: « Ligne » of the shapes. */
    val Line: ImageVector by lazy {
        icon("ShowChart", "M3.5,18.49l6,-6.01l4,4L22,6.92l-1.41,-1.41l-7.09,7.97l-4,-4L2,16.99z")
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
