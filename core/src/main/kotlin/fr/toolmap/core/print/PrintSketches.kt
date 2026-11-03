package fr.toolmap.core.print

import fr.toolmap.core.sketch.Sketch

/** The sketches a print reaches, for the count under « Croquis » in the print panel (P28.11). */
object PrintSketches {
    /** How many of [sketches] have a stroke in at least one of [frames]. */
    fun countIn(
        sketches: List<Sketch>,
        frames: List<FrameProjection>,
    ): Int = sketches.count { sketch -> frames.any { touches(sketch, it) } }

    /**
     * Whether a stroke of [sketch] reaches [frame]: the box of its control points, widened by half its width, meets the
     * frame. The control points hold the whole curve, so a stroke is never missed; a curve passing just by a corner may
     * count without showing, as the page itself only leaves out the strokes wholly outside.
     */
    fun touches(
        sketch: Sketch,
        frame: FrameProjection,
    ): Boolean {
        val bounds = frame.mercatorBounds
        return sketch.strokes.any { stroke ->
            val path = stroke.path
            val points = listOf(path.start) + path.curves.flatMap { listOf(it.c1, it.c2, it.end) }
            val margin = stroke.style.widthMeters / 2
            // The y of the sketch points south: the Mercator y is its opposite.
            points.maxOf { it.x } + margin >= bounds.minX &&
                points.minOf { it.x } - margin <= bounds.maxX &&
                -points.minOf { it.y } + margin >= bounds.minY &&
                -points.maxOf { it.y } - margin <= bounds.maxY
        }
    }
}
