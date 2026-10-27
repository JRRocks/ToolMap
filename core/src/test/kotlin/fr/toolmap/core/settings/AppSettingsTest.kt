package fr.toolmap.core.settings

import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.ScaleBar
import fr.toolmap.core.geo.TileSharpness
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.track.TrackFormat
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsTest {
    @Test
    fun `settings survive a round trip`() {
        val s =
            AppSettings(
                units = UnitSystem.IMPERIAL,
                coordinates = CoordinateSystem.MGRS,
                theme = ThemeMode.DARK,
                wallpaperColors = true,
                gloves = true,
                keepScreenOn = true,
                screenGrid = GridKind.UTM,
                altitude = AltitudeMode.BAROMETER,
                alerts = AlertSettings(proximityRadiusMeters = 250, offTrackMeters = 80, sound = false),
            )
        assertEquals(s, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)))
    }

    @Test
    fun `the map canvas, placemark, positioning and recorder choices survive a round trip`() {
        val s =
            AppSettings(
                hideStatusBar = false,
                showOverLockScreen = true,
                confirmExit = true,
                north = NorthReference.GRID,
                bearing = BearingUnit.MILS,
                slope = SlopeUnit.DEGREES,
                pressure = PressureUnit.INHG,
                heading = HeadingDisplay.BOTH,
                canvas =
                    MapCanvasSettings(
                        menusOpaque = true,
                        menusLeftHanded = true,
                        infoBoxSize = InfoBoxSize.LARGE,
                        infoBoxBackground = InfoBoxBackground.OPAQUE,
                        zoomButtons = false,
                        zoomStep = ZoomStep.HALF,
                        gridDensity = GridDensity.FINE,
                        gridStyle = GridStyle.DASHED,
                        longPress = LongPressAction.TARGET,
                        pinchZoom = false,
                        manualRotation = false,
                        perspective = false,
                        explorerAuto = false,
                    ),
                placemarks =
                    PlacemarkSettings(
                        size = PlacemarkSize.LARGE,
                        defaultColor = 0xFF00C853.toInt(),
                        trackArrows = false,
                        trackContour = false,
                        borders = false,
                        shadows = false,
                    ),
                positioning =
                    PositionSettings(
                        course = CourseDisplay.NONE,
                        autoRelock = true,
                        showTrail = false,
                        disableInBackground = false,
                        egm96 = false,
                    ),
                recorder =
                    RecorderSettings(
                        lostSignalWarning = true,
                        askActivity = false,
                        resetStatsOnSegment = false,
                        dashboardMode = true,
                    ),
            )
        assertEquals(s, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)))
    }

    @Test
    fun `a missing, empty or damaged file gives the defaults and never throws`() {
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault(null))
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault(""))
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault("{not json"))
        assertEquals(AppSettings(), AppSettingsCodec.decodeOrDefault("[1,2]"))
    }

    @Test
    fun `an older or newer file is still read`() {
        // A field this version does not know, and a value of an enum it does not know: the rest is kept.
        val text = """{"units":"IMPERIAL","theme":"SOLARIZED","future":{"a":1},"screenGrid":"MGRS"}"""
        val s = AppSettingsCodec.decodeOrDefault(text)
        assertEquals(UnitSystem.IMPERIAL, s.units)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(GridKind.MGRS, s.screenGrid)
        assertEquals(CoordinateSystem.DD, s.coordinates)
    }

    @Test
    fun `the coordinates box is shown by default and its choice survives a round trip`() {
        assertTrue(AppSettings().showCoordinates)
        assertTrue(AppSettingsCodec.decodeOrDefault("""{"units":"METRIC"}""").showCoordinates)
        val hidden = AppSettings(showCoordinates = false)
        assertEquals(hidden, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(hidden)))
    }

    @Test
    fun `the app that opens a point is kept, and nothing is chosen by default`() {
        assertNull(AppSettings().pointApp)
        val waze = AppSettings(pointApp = "com.waze")
        assertEquals(waze, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(waze)))
    }

    @Test
    fun `maps are sharp by default, also for a file written before the sharpness setting existed`() {
        assertEquals(TileSharpness.SHARP, AppSettings().maps.tileMagnification)
        val old = """{"units":"METRIC","maps":{"overscalingLevels":2}}"""
        val s = AppSettingsCodec.decodeOrDefault(old)
        assertEquals(2, s.maps.overscalingLevels)
        assertEquals(TileSharpness.SHARP, s.maps.tileMagnification)
        for (m in listOf(1.5, TileSharpness.ANDROID_SIZE)) {
            val chosen = AppSettings(maps = MapDisplaySettings(tileMagnification = m))
            assertEquals(chosen, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(chosen)))
        }
        // On a phone of density 2.8125 the default asks MapLibre for 91 px tiles, "Taille Android" for 256 px.
        assertEquals(91, AppSettings().maps.zoomMapping(2.8125).declaredTileSize(256))
        val android = MapDisplaySettings(tileMagnification = TileSharpness.ANDROID_SIZE)
        assertEquals(ZoomMapping.STANDARD, android.zoomMapping(2.8125))
    }

    @Test
    fun `imperial distances, elevations and speeds`() {
        val u = UnitSystem.IMPERIAL
        val l = Locale.US
        assertEquals("0 ft", TrackFormat.distance(0.0, l, u))
        assertEquals("328 ft", TrackFormat.distance(100.0, l, u))
        assertEquals("0.62 mi", TrackFormat.distance(1000.0, l, u))
        assertEquals("6.21 mi", TrackFormat.distance(10_000.0 - 1.0, l, u))
        assertEquals("12.4 mi", TrackFormat.distance(20_000.0, l, u))
        assertEquals("3281 ft", TrackFormat.elevation(1000.0, l, u))
        assertEquals("1000 m", TrackFormat.elevation(1000.0, l, UnitSystem.METRIC))
        assertEquals("2.2 mph", TrackFormat.speed(1.0, l, u))
        // The metric defaults are unchanged.
        assertEquals("850 m", TrackFormat.distance(849.6, l))
    }

    @Test
    fun `imperial scale bar is a round number of feet or miles`() {
        // 100 dp at zoom 15 near 45 N is about 100 * 3.3 m = 330 m across for 120 dp: 1000 ft is 305 m.
        val bar = ScaleBar.compute(45.0, 15.0, 120.0, UnitSystem.IMPERIAL)
        assertTrue(bar.label.endsWith(" ft") || bar.label.endsWith(" mi"), bar.label)
        assertTrue(bar.widthDp <= 120.0 + 1e-9, "width ${bar.widthDp}")
        // Zoomed out: miles.
        val far = ScaleBar.compute(45.0, 9.0, 120.0, UnitSystem.IMPERIAL)
        assertTrue(far.label.endsWith(" mi"), far.label)
        // The length in meters matches the label.
        assertEquals(5280.0 * 0.3048 * 10, ScaleBar.compute(45.0, 8.0, 120.0, UnitSystem.IMPERIAL).meters, 1e-6)
        // Metric is unchanged.
        assertEquals(ScaleBar.compute(45.0, 12.0, 120.0), ScaleBar.compute(45.0, 12.0, 120.0, UnitSystem.METRIC))
    }

    @Test
    fun `the night mode darkens by its intensity, kept in its range, and not at all when off`() {
        assertEquals(0f, MapDisplaySettings().darkening)
        assertEquals(0.6f, MapDisplaySettings(nightMode = true).darkening, 1e-6f)
        assertEquals(0f, MapDisplaySettings(nightMode = false, nightPercent = 80).darkening)
        assertEquals(0.9f, MapDisplaySettings(nightMode = true, nightPercent = 150).darkening, 1e-6f)
        assertEquals(0.2f, MapDisplaySettings(nightMode = true, nightPercent = 0).darkening, 1e-6f)
        val s = AppSettings(maps = MapDisplaySettings(nightMode = true, nightPercent = 40))
        assertEquals(s, AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)))
    }
}
