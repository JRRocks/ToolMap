package fr.toolmap.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** The radius of each watched waypoint: a translucent disc with a dashed edge. */
object AlertLayers {
    private const val SOURCE = "tm-a-circles"
    private const val FILL_LAYER = "tm-a-fill-layer"
    private const val LINE_LAYER = "tm-a-line-layer"
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
    private const val COLOR = "#ef6c00"
    private const val FILL_OPACITY = 0.12f
    private const val LINE_WIDTH = 2f
    private const val DASH_ON = 3f
    private const val DASH_OFF = 2f

    fun install(style: Style) {
        if (style.getSource(SOURCE) != null) return
        style.addSource(GeoJsonSource(SOURCE, EMPTY))
        style.addLayer(
            FillLayer(FILL_LAYER, SOURCE).withProperties(
                PropertyFactory.fillColor(COLOR),
                PropertyFactory.fillOpacity(FILL_OPACITY),
            ),
        )
        style.addLayer(
            LineLayer(LINE_LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor(COLOR),
                PropertyFactory.lineWidth(LINE_WIDTH),
                PropertyFactory.lineDasharray(arrayOf(DASH_ON, DASH_OFF)),
            ),
        )
    }

    fun show(
        style: Style,
        geoJson: String,
    ) {
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson)
    }
}
