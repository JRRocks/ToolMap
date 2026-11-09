package fr.toolmap.core.print

import fr.toolmap.core.pdf.PdfCrs
import fr.toolmap.core.pdf.PdfGeoViewport

/**
 * The georeference of a printed page (P37, `docs/specs/print-pdf.md` § 9): the map frame of [setup], on the ground where
 * [frame] puts it. The frame is a rectangle of the Web Mercator plane (`FrameProjection`), so its four corners in
 * EPSG:3857 place every pixel of the map exactly: QGIS, Global Mapper or Avenza Maps open the PDF at its place.
 */
object PrintGeo {
    fun viewport(
        setup: PageSetup,
        frame: FrameProjection,
        name: String = "Carte",
    ): PdfGeoViewport =
        PdfGeoViewport(
            leftMm = setup.frameLeftMm,
            topMm = setup.frameTopMm,
            widthMm = setup.frameWidthMm,
            heightMm = setup.frameHeightMm,
            topLeft = frame.toLonLat(0.0, 0.0),
            bottomLeft = frame.toLonLat(0.0, frame.frameHeightMm),
            bottomRight = frame.toLonLat(frame.frameWidthMm, frame.frameHeightMm),
            topRight = frame.toLonLat(frame.frameWidthMm, 0.0),
            crs = PdfCrs.WEB_MERCATOR,
            name = name,
        )
}
