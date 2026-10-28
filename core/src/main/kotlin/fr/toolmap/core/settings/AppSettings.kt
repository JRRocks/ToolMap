package fr.toolmap.core.settings

import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.geo.TileSharpness
import fr.toolmap.core.geo.ZoomMapping
import fr.toolmap.core.maps.CorridorWidth
import fr.toolmap.core.print.GridKind
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.track.LibraryFilter
import fr.toolmap.core.track.LibrarySort
import fr.toolmap.core.track.PathStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class UnitSystem { METRIC, IMPERIAL }

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Angles as a compass reads them: degrees, NATO mils (6 400 per turn) or gradians (400 per turn). */
@Serializable
enum class BearingUnit { DEGREES, MILS, GRADIANS }

/** Slopes as a percentage of the horizontal distance (`+12 %`), or as an angle. */
@Serializable
enum class SlopeUnit { PERCENT, DEGREES }

@Serializable
enum class PressureUnit { HPA, MMHG, INHG }

/** The north the bearings and the compass are measured from. */
@Serializable
enum class NorthReference { TRUE, MAGNETIC, GRID }

/**
 * Where the offline maps are kept: the phone's memory, or the app's folder on a removable card (SD card). Not a saved
 * setting: the folder in use is recorded with the tile files and moved by the app (ADR-042).
 */
@Serializable
enum class StorageLocation { INTERNAL, EXTERNAL }

@Serializable
enum class InfoBoxSize(
    val scale: Float,
) {
    SMALL(0.85f),
    NORMAL(1f),
    LARGE(1.25f),
}

@Serializable
enum class InfoBoxBackground(
    val alpha: Float,
) {
    TRANSPARENT(0f),

    // The chips of the map (P29.1, « Pastilles »): the map barely shows through, the text reads on any map.
    TRANSLUCENT(0.94f),
    OPAQUE(1f),
}

@Serializable
enum class GridDensity(
    val spacing: Double,
) {
    FINE(0.5),
    NORMAL(1.0),
    COARSE(2.0),
}

@Serializable
enum class GridStyle { SOLID, DASHED }

/** How many zoom levels the + and − buttons change at a tap. */
@Serializable
enum class ZoomStep(
    val levels: Double,
) {
    HALF(0.5),
    ONE(1.0),
    TWO(2.0),
}

/** What a long press on the map does. */
@Serializable
enum class LongPressAction { NONE, WAYPOINT, MEASURE, TARGET }

/** How the direction of travel (from the GNSS course) is drawn on the position marker. */
@Serializable
enum class CourseDisplay { NONE, ARROW }

/** How the compass heading is shown: as the cone on the position marker, as a number, or both. */
@Serializable
enum class HeadingDisplay { NONE, NUMBER, ARROW, BOTH }

/** Size of the placemarks: paths, waypoints and icons. */
@Serializable
enum class PlacemarkSize(
    val factor: Double,
) {
    SMALL(0.75),
    NORMAL(1.0),
    LARGE(1.5),
}

/** Where the altitude of a recorded or displayed position comes from. */
@Serializable
enum class AltitudeMode {
    /** The barometer once it is calibrated, the GPS otherwise. */
    AUTO,
    GPS,
    BAROMETER,
}

/** Whether the barometer gives the altitude: every mode but GPS (the « Baromètre » tick of the positioning panel). */
fun AltitudeMode.barometerOn(): Boolean = this != AltitudeMode.GPS

/**
 * The mode once the « Baromètre » tick is set or cleared. Setting it from GPS only gives [AltitudeMode.AUTO]; a mode
 * that already uses the barometer is kept as it is.
 */
fun AltitudeMode.withBarometer(on: Boolean): AltitudeMode =
    when {
        !on -> AltitudeMode.GPS
        this == AltitudeMode.GPS -> AltitudeMode.AUTO
        else -> this
    }

/** The alerts raised while the position is known (feature P9.4). */
@Serializable
data class AlertSettings(
    val proximityEnabled: Boolean = true,
    /** Default radius for a waypoint watched without its own radius, in meters. */
    val proximityRadiusMeters: Int = 100,
    val offTrackEnabled: Boolean = true,
    /** Distance from the followed track beyond which the alert fires, in meters. */
    val offTrackMeters: Int = 50,
    val vibrate: Boolean = true,
    val sound: Boolean = true,
)

/** How maps are drawn: sharpness of the tiles (ADR-032) and outside the zooms they serve (ADR-031). */
@Serializable
data class MapDisplaySettings(
    /**
     * `map.scales.default_overscaling_levels`: how many zoom levels a map may borrow from a neighbouring zoom so
     * that it never shows an empty area (a map that starts at zoom 6 is still drawn at zoom 5, 4 and 3 with 3).
     * `0` shows nothing outside the zooms of the map. See [ScalingPolicy].
     */
    val overscalingLevels: Int = ScalingPolicy.DEFAULT_LEVELS,
    /**
     * Screen pixels covered by one pixel of a map tile ([TileSharpness]): 1 = as sharp as AlpineQuest (default),
     * more = larger map texts and fewer tiles to load, [TileSharpness.ANDROID_SIZE] = MapLibre's default size.
     */
    val tileMagnification: Double = TileSharpness.SHARP,
    /**
     * Provided maps the user hid from « Cartes disponibles » (F3): no longer listed, offered by a favorite or shown;
     * « N cartes masquées · Afficher » brings them back. See [fr.toolmap.core.maps.MapCatalog.isHidden].
     */
    val hiddenMaps: Set<String> = emptySet(),
    /** « Mode nuit » of the main menu (F14): the maps darkened by [nightPercent]; the tracks and the menus stay. */
    val nightMode: Boolean = false,
    /** « Intensité » of « Mode nuit de la carte » (Réglages ▸ Affichage): how much darker, in percent. */
    val nightPercent: Int = DEFAULT_NIGHT_PERCENT,
    /** Width in meters on each side of the last band saved around a track or a route (P30.2), offered again. */
    val corridorRadius: Int = CorridorWidth.DEFAULT,
) {
    val scaling: ScalingPolicy get() = ScalingPolicy(overscalingLevels.coerceIn(0, ScalingPolicy.MAX_LEVELS))

    /** How much the maps are darkened, from 0 (not at all, night mode off) to 1 (black). */
    val darkening: Float
        get() = if (nightMode) nightPercent.coerceIn(MIN_NIGHT_PERCENT, MAX_NIGHT_PERCENT) / PERCENT else 0f

    /** Camera / tile zoom relation on a screen of [density] physical pixels per dp. */
    fun zoomMapping(density: Double): ZoomMapping = ZoomMapping.forScreen(density, tileMagnification)

    companion object {
        const val DEFAULT_NIGHT_PERCENT = 60
        const val MIN_NIGHT_PERCENT = 20
        const val MAX_NIGHT_PERCENT = 90
        const val NIGHT_STEP_PERCENT = 10
        private const val PERCENT = 100f
    }
}

/**
 * The map screen as AlpineQuest's « Map canvas settings » (P11.8): menus, information boxes, zoom buttons, grid,
 * long press and gestures.
 */
@Serializable
data class MapCanvasSettings(
    /** Menus drawn on a plain surface instead of a translucent one. */
    val menusOpaque: Boolean = false,
    /** The + and − buttons on the left of the screen, for a left-handed user. */
    val menusLeftHanded: Boolean = false,
    val infoBoxSize: InfoBoxSize = InfoBoxSize.NORMAL,
    val infoBoxBackground: InfoBoxBackground = InfoBoxBackground.TRANSLUCENT,
    val zoomButtons: Boolean = true,
    val zoomStep: ZoomStep = ZoomStep.ONE,
    val gridDensity: GridDensity = GridDensity.NORMAL,
    val gridStyle: GridStyle = GridStyle.SOLID,
    /** What a long press on the map does (creating a waypoint was the only action before P11.8). */
    val longPress: LongPressAction = LongPressAction.WAYPOINT,
    val pinchZoom: Boolean = true,
    val manualRotation: Boolean = true,
    val perspective: Boolean = true,
    /** Pitching the map down with two fingers also starts the explorer mode: perspective, heading up, following. */
    val explorerAuto: Boolean = true,
)

/** Where BRouter has no data or finds no way: that leg by hand, or the whole route drawn by hand from there on. */
@Serializable
enum class RoutingNoData { LEG_BY_HAND, ALL_BY_HAND }

/** The placemarks: paths, waypoints and icons drawn on the map (AlpineQuest's « Placemarks »). */
@Serializable
data class PlacemarkSettings(
    val size: PlacemarkSize = PlacemarkSize.NORMAL,
    /** Colour given to a new waypoint or track. */
    val defaultColor: Int = PathStyle.DEFAULT_COLOR,
    /** Colour of the placemark the user targets (see [LongPressAction.TARGET]). */
    val targetColor: Int = TARGET_COLOR,
    /** Direction arrows on the tracks (a track can still hide its own). */
    val trackArrows: Boolean = true,
    /** A light contour around the tracks, so that they read on dark maps. */
    val trackContour: Boolean = true,
    /** A white border around the placemark icons and discs. */
    val borders: Boolean = true,
    /** A soft shadow under the placemarks. */
    val shadows: Boolean = true,
    /** The names of the waypoints and of the group's members next to their marker (ADR-062). */
    val labels: Boolean = true,
    /** What a computed route does where BRouter has no data or finds no way (P19, `docs/specs/routing.md` §3). */
    val routingNoData: RoutingNoData = RoutingNoData.LEG_BY_HAND,
    /** BRouter profile of the last computed route, taken again by the next one. */
    val routingProfile: String = RoutingProfiles.HIKING,
    /** Personal BRouter profiles the user typed (« Autre… »), offered again. */
    val routingProfiles: List<String> = emptyList(),
    /** Radius in meters of the last zone to avoid (P19.8), offered first for the next one. */
    val routingAvoidRadius: Int = 250,
) {
    companion object {
        const val TARGET_COLOR: Int = 0xFFD81B60.toInt()
    }
}

/** Positioning: GNSS course, camera after a pause, trail and battery (AlpineQuest's « Positioning »). */
@Serializable
data class PositionSettings(
    val course: CourseDisplay = CourseDisplay.ARROW,
    /** After 10 s without a gesture on the map, the camera goes back to the position (when it is known). */
    val autoRelock: Boolean = false,
    /** The last positions of the device, as a trail. */
    val showTrail: Boolean = true,
    /** The location stops while the app is hidden (the track recorder is not affected). */
    val disableInBackground: Boolean = true,
    /** Altitudes of the GNSS are given above the geoid (EGM96), as the maps and the barometer are. */
    val egm96: Boolean = true,
)

/** How the explorer of the tracks and waypoints is sorted and filtered (F7): kept from one opening to the next. */
@Serializable
data class LibraryViewSettings(
    val sort: LibrarySort = LibrarySort.DATE,
    val filter: LibraryFilter = LibraryFilter.ALL,
)

/** The track recorder (AlpineQuest's « Track recorder »). */
@Serializable
data class RecorderSettings(
    /** A warning when no fix has come for a minute while recording. */
    val lostSignalWarning: Boolean = false,
    /** The activity is asked when a recording or a segment starts; it names the track. */
    val askActivity: Boolean = true,
    /** A new segment starts the « done so far » statistics again. */
    val resetStatsOnSegment: Boolean = true,
    /** The recording pauses when the app is hidden and resumes when it is back. */
    val dashboardMode: Boolean = false,
)

/**
 * What the user chose in the settings screen. Every field has a default, so that a file written by an older version
 * (or a damaged one) still gives a usable value.
 */
@Serializable
data class AppSettings(
    /** Distance unit: distances, heights and speeds (AlpineQuest's « Distance unit »). */
    val units: UnitSystem = UnitSystem.METRIC,
    /** Area unit, apart from the distances (« Area unit »). */
    val areaUnits: UnitSystem = UnitSystem.METRIC,
    val coordinates: CoordinateSystem = CoordinateSystem.DD,
    /** A second format shown under the first in the position box, or none (« Secondary loc. format »). */
    val secondaryCoordinates: CoordinateSystem? = null,
    val bearing: BearingUnit = BearingUnit.DEGREES,
    val slope: SlopeUnit = SlopeUnit.PERCENT,
    val pressure: PressureUnit = PressureUnit.HPA,
    val north: NorthReference = NorthReference.TRUE,
    /** The status bar is hidden on the map; a swipe from the top edge shows it for a moment. */
    val hideStatusBar: Boolean = true,
    val hideNavigationBar: Boolean = true,
    /** The map can be used on a locked phone, without unlocking it first. */
    val showOverLockScreen: Boolean = false,
    /** Leaving the app from the map asks first. */
    val confirmExit: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Material You colours from the wallpaper (Android 12+); the ToolMap palette otherwise (the default since P29.0:
     * renamed from `dynamicColor`, so that every phone leaves the wallpaper colours once, ADR-074).
     */
    val wallpaperColors: Boolean = false,
    /** Larger touch targets and text, for use with gloves (P10.2). */
    val gloves: Boolean = false,
    val keepScreenOn: Boolean = false,
    /** The box at the top left with the centre coordinates and the zoom (« Information box » of the main menu). */
    val showCoordinates: Boolean = true,
    /** The app that opens the centre point with « Ouvrir avec » (package name), or null to be asked (ADR-044). */
    val pointApp: String? = null,
    /** How the compass heading is shown on the map (« Current heading display »). */
    val heading: HeadingDisplay = HeadingDisplay.ARROW,
    /** Coordinate grid drawn over the map (UTM, Lambert 93, WGS84, MGRS), or none. */
    val screenGrid: GridKind = GridKind.NONE,
    val altitude: AltitudeMode = AltitudeMode.AUTO,
    val alerts: AlertSettings = AlertSettings(),
    val maps: MapDisplaySettings = MapDisplaySettings(),
    val canvas: MapCanvasSettings = MapCanvasSettings(),
    val placemarks: PlacemarkSettings = PlacemarkSettings(),
    val positioning: PositionSettings = PositionSettings(),
    val recorder: RecorderSettings = RecorderSettings(),
    val library: LibraryViewSettings = LibraryViewSettings(),
)

object AppSettingsCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            // A value written by a newer version must not make the whole file unreadable.
            coerceInputValues = true
        }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    /** Never throws: a missing or damaged file gives the defaults. */
    fun decodeOrDefault(text: String?): AppSettings =
        if (text.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString(AppSettings.serializer(), text) }.getOrDefault(AppSettings())
        }
}
