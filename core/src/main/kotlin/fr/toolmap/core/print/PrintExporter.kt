package fr.toolmap.core.print

import fr.toolmap.core.pdf.PdfImage
import fr.toolmap.core.pdf.PdfInfo
import fr.toolmap.core.pdf.PdfWriter
import java.io.OutputStream
import kotlin.math.min

/** One strip of a page's map, as an image ready for the PDF, and how many of its tiles could not be read. */
class RenderedBand(
    val image: PdfImage,
    val missingTiles: Int,
)

/**
 * What the platform does for the export: turn the tiles of a strip into pixels. The Android implementation reads the
 * tiles from local storage, composes the layers of the page plan with their opacity and colour settings, hatches what
 * is missing and compresses the result; tests plug in a drawing of their own.
 */
fun interface BandRenderer {
    /** Renders [band] of [page]'s raster ([Band.widthPx] columns from [Band.x0], [Band.heightPx] rows from [Band.y0]). */
    fun render(
        page: PrintPagePlan,
        band: Band,
    ): RenderedBand
}

/** How far the export has come, for a progress bar. */
data class PrintProgress(
    val pageIndex: Int,
    val pageCount: Int,
    val bandIndex: Int,
    val bandCount: Int,
) {
    /** 0 to 1 over the whole job, every page weighing the same. */
    val fraction: Double
        get() = if (pageCount == 0) 1.0 else (pageIndex + bandIndex.toDouble() / maxOf(bandCount, 1)) / pageCount
}

class PrintResult(
    val pages: Int,
    val bytes: Long,
    val missingTiles: Int,
)

/**
 * Writes the PDF of a [PrintPlan] page after page, a strip of the map at a time (so memory is bounded whatever the
 * paper), with the vector furniture of [PageComposer] over each. Running it on a background thread is the caller's
 * business; [ensureActive] is called between strips and should throw to cancel.
 */
class PrintExporter(
    private val renderer: BandRenderer,
    private val ensureActive: () -> Unit = {},
) {
    fun export(
        plan: PrintPlan,
        content: PrintContent,
        output: OutputStream,
        onProgress: (PrintProgress) -> Unit = {},
    ): PrintResult {
        require(plan.canExport) { "the plan cannot be exported: ${plan.issues}" }
        val template = plan.template
        val setup = template.page
        val counted = CountingOutput(output)
        val composer = PageComposer(plan, content)
        var missing = 0
        val writer =
            PdfWriter(
                counted,
                PdfInfo(
                    title = template.title,
                    subject = PrintFormat.scale(template.scale, content.texts),
                ),
            )
        writer.use {
            plan.pages.forEachIndexed { index, pp ->
                val page = writer.beginPage(setup.widthMm, setup.heightMm)
                val raster = pp.raster
                val mmPerPixelX = setup.frameWidthMm / raster.widthPx
                val mmPerPixelY = setup.frameHeightMm / raster.heightPx
                var pageMissing = 0
                for (i in 0 until raster.bandCount) {
                    ensureActive()
                    onProgress(PrintProgress(index, plan.pages.size, i, raster.bandCount))
                    for (piece in raster.pieces(raster.band(i))) {
                        // Each piece reaches into the next one below and to its right: a hairline between two
                        // images never shows.
                        val extended =
                            piece.copy(
                                heightPx = min(piece.heightPx + SEAM_ROWS, raster.heightPx - piece.y0),
                                widthPx = min(piece.widthPx + SEAM_ROWS, raster.widthPx - piece.x0),
                            )
                        val rendered = renderer.render(pp, extended)
                        pageMissing += rendered.missingTiles
                        page.save()
                        page.clipRect(setup.frameLeftMm, setup.frameTopMm, setup.frameWidthMm, setup.frameHeightMm)
                        page.image(
                            rendered.image,
                            setup.frameLeftMm + piece.x0 * mmPerPixelX,
                            setup.frameTopMm + piece.y0 * mmPerPixelY,
                            extended.widthPx * mmPerPixelX,
                            extended.heightPx * mmPerPixelY,
                        )
                        page.restore()
                    }
                }
                ensureActive()
                composer.drawOverlay(page, pp, plan.mapPages.size, pageMissing)
                missing += pageMissing
                writer.endPage()
            }
            onProgress(PrintProgress(plan.pages.size, plan.pages.size, 0, 1))
        }
        return PrintResult(plan.pages.size, counted.count, missing)
    }

    private class CountingOutput(
        private val target: OutputStream,
    ) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()

        override fun close() = target.close()
    }

    companion object {
        /** Rows (and columns) by which a piece is rendered beyond its own, to be covered by the next one. */
        const val SEAM_ROWS = 2
    }
}
