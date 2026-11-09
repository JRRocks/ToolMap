package fr.toolmap.core.pdf

import fr.toolmap.core.geo.LonLat

/**
 * A projected coordinate system as a geospatial PDF names it (ISO 32000, Adobe extension level 3): its EPSG code and
 * its WKT, both written so that every reader finds one it knows (GDAL, hence QGIS, prefers the code; Global Mapper,
 * Acrobat and Avenza Maps read the WKT).
 */
class PdfCrs(
    val epsg: Int,
    val wkt: String,
) {
    companion object {
        /**
         * Web Mercator (EPSG:3857), the plane of the tiles and of the printed frame (`FrameProjection`): the frame is a
         * plain rectangle of it, so its four corners place every pixel exactly. The WKT is the one GDAL writes.
         */
        val WEB_MERCATOR =
            PdfCrs(
                3857,
                "PROJCS[\"WGS_1984_Web_Mercator_Auxiliary_Sphere\",GEOGCS[\"GCS_WGS_1984\"," +
                    "DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]]," +
                    "PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]," +
                    "PROJECTION[\"Mercator_Auxiliary_Sphere\"],PARAMETER[\"False_Easting\",0.0]," +
                    "PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",0.0]," +
                    "PARAMETER[\"Standard_Parallel_1\",0.0],PARAMETER[\"Auxiliary_Sphere_Type\",0.0]," +
                    "UNIT[\"Meter\",1.0]]",
            )
    }
}

/**
 * The georeference of a rectangle of a page, a « viewport » of ISO 32000: where it is on the page ([leftMm], [topMm],
 * [widthMm], [heightMm], from the top-left corner like the rest of [PdfPage]) and where its corners are on the ground
 * (WGS84 degrees). A GIS fits the rectangle on the ground from the four corners, in [crs]: QGIS (through GDAL) and
 * Global Mapper then open the PDF at its place, Acrobat shows the coordinates under the pointer (P37).
 */
class PdfGeoViewport(
    val leftMm: Double,
    val topMm: Double,
    val widthMm: Double,
    val heightMm: Double,
    val topLeft: LonLat,
    val bottomLeft: LonLat,
    val bottomRight: LonLat,
    val topRight: LonLat,
    val crs: PdfCrs = PdfCrs.WEB_MERCATOR,
    val name: String = "Carte",
) {
    init {
        require(widthMm > 0 && heightMm > 0) { "empty viewport" }
        for (corner in listOf(topLeft, bottomLeft, bottomRight, topRight)) {
            require(corner.lat in -90.0..90.0 && corner.lon in -180.0..180.0) { "corner off the globe: $corner" }
        }
    }
}
