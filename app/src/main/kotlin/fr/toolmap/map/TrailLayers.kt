package fr.toolmap.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** The last positions of the device, drawn as a thin line under the position marker (« Afficher les positions précédentes »). */
object TrailLayers {
    private const val SOURCE = "tm-trail"
    private const val LAYER = "tm-trail-layer"
    private const val COLOR = "#1e88e5"
    private const val OPACITY = 0.6f
    private const val WIDTH = 3f
    private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

    fun install(style: Style) {
        if (style.getSource(SOURCE) != null) return
        style.addSource(GeoJsonSource(SOURCE, EMPTY))
        style.addLayer(
            LineLayer(LAYER, SOURCE).withProperties(
                PropertyFactory.lineColor(COLOR),
                PropertyFactory.lineOpacity(OPACITY),
                PropertyFactory.lineWidth(WIDTH),
            ),
        )
    }

    /** [points] are latitude and longitude pairs, oldest first; fewer than two draw nothing. */
    fun show(
        style: Style,
        points: List<Pair<Double, Double>>,
    ) {
        val geoJson =
            if (points.size < 2) {
                EMPTY
            } else {
                val coordinates = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
                """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},""" +
                    """"geometry":{"type":"LineString","coordinates":[$coordinates]}}]}"""
            }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(geoJson)
    }
}
