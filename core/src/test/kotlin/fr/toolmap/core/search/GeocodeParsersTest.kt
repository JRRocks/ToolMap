package fr.toolmap.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeocodeParsersTest {
    // Replies captured from the services on 2026-10-02 (fields not used here dropped, the shape kept).
    private val ignPoi =
        """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[5.526604,44.869704]},"properties":{"name":["le Grand Veymont"],"toponym":"le Grand Veymont","category":["sommet","élément topographique ou forestier","détail orographique"],"postcode":["38650"],"citycode":["38186"],"depcode":["38"],"city":["Gresse-en-Vercors"],"extrafields":{"cleabs":"PAIOROGR0000000007456959"},"classification":4,"territory":"METROPOLE","score":0.7,"_type":"poi"}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[5.54946,44.893117]},"properties":{"name":["Parking du Grand Veymont"],"toponym":"Parking du Grand Veymont","category":["parking","transport"],"postcode":["38650"],"city":["Gresse-en-Vercors"],"_type":"poi"}}
        ],"query":"Grand Veymont"}"""

    private val ignAddress =
        """{"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[0.198638,47.961643]},"properties":{"label":"Allée du Grand Veymont 72100 Le Mans","score":0.69,"id":"72181_2467","name":"Allée du Grand Veymont","postcode":"72100","citycode":"72181","x":490968.22,"y":6766069.74,"city":"Le Mans","context":"72, Sarthe, Pays de la Loire","type":"street","importance":0.64,"street":"Allée du Grand Veymont","_type":"address"}}
        ],"query":"Grand Veymont"}"""

    private val nominatim =
        """[{"place_id":86930729,"licence":"Data © OpenStreetMap contributors, ODbL 1.0. http://osm.org/copyright","osm_type":"node","osm_id":1445505976,"lat":"44.8697393","lon":"5.5267039","category":"natural","type":"peak","place_rank":18,"importance":0.26,"addresstype":"peak","name":"Grand Veymont","display_name":"Grand Veymont, Gresse-en-Vercors, Grenoble, Isère, Auvergne-Rhône-Alpes, France métropolitaine, 38650, France","boundingbox":["44.8696893","44.8697893","5.5266539","5.5267539"]},
        {"place_id":1,"lat":"45.8326","lon":"6.8652","category":"natural","type":"peak","name":"Mont Blanc","display_name":"Mont Blanc, Saint-Gervais-les-Bains, Haute-Savoie, Auvergne-Rhône-Alpes, France métropolitaine, 74170, France","boundingbox":["45.8","45.9","6.8","6.9"]}]"""

    @Test
    fun `an IGN place gives its toponym, what it is and where it is`() {
        val results = assertNotNull(GeocodeParsers.parseIgn(ignPoi))
        assertEquals(2, results.size)
        val peak = results[0]
        assertEquals("le Grand Veymont", peak.title)
        assertEquals("38650 Gresse-en-Vercors", peak.subtitle, "the category is shown apart, not twice")
        assertEquals("sommet", peak.category)
        assertEquals(44.869704, peak.lat, 1e-9)
        assertEquals(5.526604, peak.lon, 1e-9, "GeoJSON gives longitude first")
        assertEquals(SearchSource.IGN, peak.source)
        assertNull(peak.bounds)
        assertEquals("38650 Gresse-en-Vercors", results[1].subtitle)
        assertEquals("parking", results[1].category)
    }

    @Test
    fun `an IGN address gives its name and its town, not an array`() {
        val result = assertNotNull(GeocodeParsers.parseIgn(ignAddress)).single()
        assertEquals("Allée du Grand Veymont", result.title)
        assertEquals("72100 Le Mans", result.subtitle)
        assertNull(result.category)
        assertEquals(47.961643, result.lat, 1e-9)
        assertEquals(0.198638, result.lon, 1e-9)
    }

    @Test
    fun `a Nominatim place keeps its name, the first places it is in and the country`() {
        val results = assertNotNull(GeocodeParsers.parseNominatim(nominatim))
        assertEquals(2, results.size)
        val peak = results[0]
        assertEquals("Grand Veymont", peak.title)
        assertEquals("Gresse-en-Vercors, Grenoble, Isère, France", peak.subtitle)
        assertEquals("peak", peak.category)
        assertEquals(44.8697393, peak.lat, 1e-9, "latitude and longitude come as strings")
        assertEquals(5.5267039, peak.lon, 1e-9)
        assertEquals(SearchSource.OSM, peak.source)
        assertNull(peak.bounds, "the 11 m box Nominatim gives a peak is not worth framing")
    }

    @Test
    fun `a Nominatim area keeps its bounding box in west south east north order`() {
        val area = assertNotNull(GeocodeParsers.parseNominatim(nominatim))[1].bounds
        assertNotNull(area)
        assertEquals(6.8, area.minX, 1e-9)
        assertEquals(45.8, area.minY, 1e-9)
        assertEquals(6.9, area.maxX, 1e-9)
        assertEquals(45.9, area.maxY, 1e-9)
    }

    @Test
    fun `an empty answer is an empty list and an unreadable one is null`() {
        assertEquals(
            emptyList(),
            GeocodeParsers.parseIgn("""{"type":"FeatureCollection","features":[],"query":"zzzz"}"""),
        )
        assertEquals(emptyList(), GeocodeParsers.parseNominatim("[]"))
        assertNull(GeocodeParsers.parseIgn("<html>502 Bad Gateway</html>"))
        assertNull(GeocodeParsers.parseNominatim("<html>502 Bad Gateway</html>"))
        assertNull(GeocodeParsers.parseIgn("[]"), "an array is not a FeatureCollection")
        assertNull(GeocodeParsers.parseNominatim("{}"), "an object is not a list of places")
        assertNull(GeocodeParsers.parseIgn(""))
    }

    @Test
    fun `entries without a position or with an impossible one are skipped and the others kept`() {
        val mixed =
            """{"features":[
            {"geometry":{"type":"Point","coordinates":[5.0]},"properties":{"name":"no latitude"}},
            {"geometry":{"type":"Point","coordinates":[500.0,44.0]},"properties":{"name":"longitude 500"}},
            {"geometry":null,"properties":{"name":"no geometry"}},
            {"geometry":{"type":"Point","coordinates":[5.0,44.0]},"properties":{}},
            "not an object",
            {"geometry":{"type":"Point","coordinates":[5.0,44.0]},"properties":{"label":"Only a label 38000 Grenoble"}}
            ]}"""
        val results = assertNotNull(GeocodeParsers.parseIgn(mixed))
        assertEquals(listOf("Only a label 38000 Grenoble"), results.map { it.title })

        val places =
            """[{"lat":"x","lon":"5","display_name":"Bad latitude"},
            {"lat":"95","lon":"5","display_name":"Latitude 95"},
            {"lat":"44","lon":"5"},
            {"lat":44.0,"lon":5.0,"display_name":"Numbers, Not Strings, Fine"}]"""
        val kept = assertNotNull(GeocodeParsers.parseNominatim(places))
        assertEquals(1, kept.size)
        assertEquals("Numbers", kept[0].title)
        assertEquals("Not Strings, Fine", kept[0].subtitle)
    }

    @Test
    fun `a long Nominatim address is shortened but never loses the country`() {
        val results =
            assertNotNull(
                GeocodeParsers.parseNominatim(
                    """[{"lat":"1","lon":"2","name":"X","display_name":"X, A, B, C, D, E, F, Country"}]""",
                ),
            )
        assertEquals("A, B, C, Country", results.single().subtitle)
        assertTrue(results.single().category == null)
    }
}
