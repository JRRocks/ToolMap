import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/settings/AppSettings.kt, without its enums (in SettingsEnums.swift).
// The settings file is the JSON kotlinx.serialization writes on Android (ADR-022): the same keys, the same default for
// a missing key, and the same tolerance (`coerceInputValues`): a null, or the name of an enum value this version does
// not know, gives the default of that field; any other damage gives the default settings as a whole.
// Fields are `var` where Kotlin has `val`: changing a copy is the Swift form of the data class `copy(...)`.

/// Where BRouter has no data or finds no way: that leg by hand, or the whole route drawn by hand from there on.
public enum RoutingNoData: String, Codable, CaseIterable, Sendable {
    case legByHand = "LEG_BY_HAND"
    case allByHand = "ALL_BY_HAND"
}

/// The alerts raised while the position is known (feature P9.4).
public struct AlertSettings: Equatable, Hashable, Sendable, Codable {
    public var proximityEnabled: Bool
    /// Default radius for a waypoint watched without its own radius, in meters.
    public var proximityRadiusMeters: Int
    public var offTrackEnabled: Bool
    /// Distance from the followed track beyond which the alert fires, in meters.
    public var offTrackMeters: Int
    public var vibrate: Bool
    public var sound: Bool

    public init(
        proximityEnabled: Bool = true,
        proximityRadiusMeters: Int = 100,
        offTrackEnabled: Bool = true,
        offTrackMeters: Int = 50,
        vibrate: Bool = true,
        sound: Bool = true
    ) {
        self.proximityEnabled = proximityEnabled
        self.proximityRadiusMeters = proximityRadiusMeters
        self.offTrackEnabled = offTrackEnabled
        self.offTrackMeters = offTrackMeters
        self.vibrate = vibrate
        self.sound = sound
    }

    private enum CodingKeys: String, CodingKey {
        case proximityEnabled, proximityRadiusMeters, offTrackEnabled, offTrackMeters, vibrate, sound
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AlertSettings()
        proximityEnabled = try c.settingsValue(.proximityEnabled, d.proximityEnabled)
        proximityRadiusMeters = try c.settingsValue(.proximityRadiusMeters, d.proximityRadiusMeters)
        offTrackEnabled = try c.settingsValue(.offTrackEnabled, d.offTrackEnabled)
        offTrackMeters = try c.settingsValue(.offTrackMeters, d.offTrackMeters)
        vibrate = try c.settingsValue(.vibrate, d.vibrate)
        sound = try c.settingsValue(.sound, d.sound)
    }
}

/// How maps are drawn: sharpness of the tiles (ADR-032) and outside the zooms they serve (ADR-031).
public struct MapDisplaySettings: Equatable, Hashable, Sendable, Codable {
    public static let defaultNightPercent = 60
    public static let minNightPercent = 20
    public static let maxNightPercent = 90
    public static let nightStepPercent = 10
    private static let percent: Float = 100

    /// `map.scales.default_overscaling_levels`: how many zoom levels a map may borrow from a neighbouring zoom so that
    /// it never shows an empty area (a map that starts at zoom 6 is still drawn at zoom 5, 4 and 3 with 3). `0` shows
    /// nothing outside the zooms of the map. See `ScalingPolicy`.
    public var overscalingLevels: Int
    /// Screen pixels covered by one pixel of a map tile (`TileSharpness`): 1 = as sharp as AlpineQuest (default),
    /// more = larger map texts and fewer tiles to load, `TileSharpness.androidSize` = MapLibre's default size.
    public var tileMagnification: Double
    /// Provided maps the user hid from « Cartes disponibles » (F3): no longer listed, offered by a favorite or shown.
    public var hiddenMaps: Set<String>
    /// « Mode nuit » of the main menu (F14): the maps darkened by `nightPercent`; the tracks and the menus stay.
    public var nightMode: Bool
    /// « Intensité » of « Mode nuit de la carte » (Réglages ▸ Affichage): how much darker, in percent.
    public var nightPercent: Int
    /// Width in meters on each side of the last band saved around a track or a route (P30.2), offered again.
    public var corridorRadius: Int

    public init(
        overscalingLevels: Int = ScalingPolicy.defaultLevels,
        tileMagnification: Double = TileSharpness.sharp,
        hiddenMaps: Set<String> = [],
        nightMode: Bool = false,
        nightPercent: Int = MapDisplaySettings.defaultNightPercent,
        corridorRadius: Int = CorridorWidth.defaultWidth
    ) {
        self.overscalingLevels = overscalingLevels
        self.tileMagnification = tileMagnification
        self.hiddenMaps = hiddenMaps
        self.nightMode = nightMode
        self.nightPercent = nightPercent
        self.corridorRadius = corridorRadius
    }

    public var scaling: ScalingPolicy {
        ScalingPolicy(levels: min(max(overscalingLevels, 0), ScalingPolicy.maxLevels))
    }

    /// How much the maps are darkened, from 0 (not at all, night mode off) to 1 (black).
    public var darkening: Float {
        guard nightMode else { return 0 }
        let kept = min(max(nightPercent, MapDisplaySettings.minNightPercent), MapDisplaySettings.maxNightPercent)
        return Float(kept) / MapDisplaySettings.percent
    }

    /// Camera / tile zoom relation on a screen of `density` physical pixels per point.
    public func zoomMapping(_ density: Double) -> ZoomMapping {
        ZoomMapping.forScreen(density, tileMagnification)
    }

    private enum CodingKeys: String, CodingKey {
        case overscalingLevels, tileMagnification, hiddenMaps, nightMode, nightPercent, corridorRadius
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = MapDisplaySettings()
        overscalingLevels = try c.settingsValue(.overscalingLevels, d.overscalingLevels)
        tileMagnification = try c.settingsValue(.tileMagnification, d.tileMagnification)
        hiddenMaps = try c.settingsValue(.hiddenMaps, d.hiddenMaps)
        nightMode = try c.settingsValue(.nightMode, d.nightMode)
        nightPercent = try c.settingsValue(.nightPercent, d.nightPercent)
        corridorRadius = try c.settingsValue(.corridorRadius, d.corridorRadius)
    }
}

/// The map screen as AlpineQuest's « Map canvas settings » (P11.8): menus, information boxes, zoom buttons, grid,
/// long press and gestures.
public struct MapCanvasSettings: Equatable, Hashable, Sendable, Codable {
    /// Menus drawn on a plain surface instead of a translucent one.
    public var menusOpaque: Bool
    /// The + and − buttons on the left of the screen, for a left-handed user.
    public var menusLeftHanded: Bool
    public var infoBoxSize: InfoBoxSize
    public var infoBoxBackground: InfoBoxBackground
    public var zoomButtons: Bool
    public var zoomStep: ZoomStep
    public var gridDensity: GridDensity
    public var gridStyle: GridStyle
    /// What a long press on the map does (creating a waypoint was the only action before P11.8).
    public var longPress: LongPressAction
    public var pinchZoom: Bool
    public var manualRotation: Bool
    public var perspective: Bool
    /// Pitching the map down with two fingers also starts the explorer mode: perspective, heading up, following.
    public var explorerAuto: Bool

    public init(
        menusOpaque: Bool = false,
        menusLeftHanded: Bool = false,
        infoBoxSize: InfoBoxSize = .normal,
        infoBoxBackground: InfoBoxBackground = .translucent,
        zoomButtons: Bool = true,
        zoomStep: ZoomStep = .one,
        gridDensity: GridDensity = .normal,
        gridStyle: GridStyle = .solid,
        longPress: LongPressAction = .waypoint,
        pinchZoom: Bool = true,
        manualRotation: Bool = true,
        perspective: Bool = true,
        explorerAuto: Bool = true
    ) {
        self.menusOpaque = menusOpaque
        self.menusLeftHanded = menusLeftHanded
        self.infoBoxSize = infoBoxSize
        self.infoBoxBackground = infoBoxBackground
        self.zoomButtons = zoomButtons
        self.zoomStep = zoomStep
        self.gridDensity = gridDensity
        self.gridStyle = gridStyle
        self.longPress = longPress
        self.pinchZoom = pinchZoom
        self.manualRotation = manualRotation
        self.perspective = perspective
        self.explorerAuto = explorerAuto
    }

    private enum CodingKeys: String, CodingKey {
        case menusOpaque, menusLeftHanded, infoBoxSize, infoBoxBackground, zoomButtons, zoomStep, gridDensity
        case gridStyle, longPress, pinchZoom, manualRotation, perspective, explorerAuto
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = MapCanvasSettings()
        menusOpaque = try c.settingsValue(.menusOpaque, d.menusOpaque)
        menusLeftHanded = try c.settingsValue(.menusLeftHanded, d.menusLeftHanded)
        infoBoxSize = try c.settingsEnum(.infoBoxSize, d.infoBoxSize)
        infoBoxBackground = try c.settingsEnum(.infoBoxBackground, d.infoBoxBackground)
        zoomButtons = try c.settingsValue(.zoomButtons, d.zoomButtons)
        zoomStep = try c.settingsEnum(.zoomStep, d.zoomStep)
        gridDensity = try c.settingsEnum(.gridDensity, d.gridDensity)
        gridStyle = try c.settingsEnum(.gridStyle, d.gridStyle)
        longPress = try c.settingsEnum(.longPress, d.longPress)
        pinchZoom = try c.settingsValue(.pinchZoom, d.pinchZoom)
        manualRotation = try c.settingsValue(.manualRotation, d.manualRotation)
        perspective = try c.settingsValue(.perspective, d.perspective)
        explorerAuto = try c.settingsValue(.explorerAuto, d.explorerAuto)
    }
}

/// The placemarks: paths, waypoints and icons drawn on the map (AlpineQuest's « Placemarks »).
public struct PlacemarkSettings: Equatable, Hashable, Sendable, Codable {
    /// Kotlin `PlacemarkSettings.TARGET_COLOR` (ARGB in a signed 32-bit integer, like `PathStyle`).
    public static let defaultTargetColor = Int32(bitPattern: 0xFFD8_1B60)

    public var size: PlacemarkSize
    /// Colour given to a new waypoint or track.
    public var defaultColor: Int32
    /// Colour of the placemark the user targets (see `LongPressAction.target`).
    public var targetColor: Int32
    /// Direction arrows on the tracks (a track can still hide its own).
    public var trackArrows: Bool
    /// A light contour around the tracks, so that they read on dark maps.
    public var trackContour: Bool
    /// A white border around the placemark icons and discs.
    public var borders: Bool
    /// A soft shadow under the placemarks.
    public var shadows: Bool
    /// The names of the waypoints and of the group's members next to their marker (ADR-062).
    public var labels: Bool
    /// What a computed route does where BRouter has no data or finds no way (P19, `docs/specs/routing.md` §3).
    public var routingNoData: RoutingNoData
    /// BRouter profile of the last computed route, taken again by the next one.
    public var routingProfile: String
    /// Personal BRouter profiles the user typed (« Autre… »), offered again.
    public var routingProfiles: [String]
    /// Radius in meters of the last zone to avoid (P19.8), offered first for the next one.
    public var routingAvoidRadius: Int
    /// « Aller vers un point » by the ways BRouter computes (P30.3); otherwise in a straight line, as before.
    public var goToRouted: Bool
    /// BRouter profile of « Aller vers un point ».
    public var goToProfile: String
    /// Minutes between two computations of the way to the target (one of `GoToRules.intervalMinutes`).
    public var goToIntervalMinutes: Int
    /// Meters off the way that have it computed again at once (one of `GoToRules.offRouteMeters`).
    public var goToOffRouteMeters: Int
    /// A vibration when the walker leaves the way to the target.
    public var goToVibrate: Bool

    public init(
        size: PlacemarkSize = .normal,
        defaultColor: Int32 = PathStyle.defaultColor,
        targetColor: Int32 = PlacemarkSettings.defaultTargetColor,
        trackArrows: Bool = true,
        trackContour: Bool = true,
        borders: Bool = true,
        shadows: Bool = true,
        labels: Bool = true,
        routingNoData: RoutingNoData = .legByHand,
        routingProfile: String = RoutingProfiles.hiking,
        routingProfiles: [String] = [],
        routingAvoidRadius: Int = 250,
        goToRouted: Bool = true,
        goToProfile: String = RoutingProfiles.hiking,
        goToIntervalMinutes: Int = 2,
        goToOffRouteMeters: Int = 50,
        goToVibrate: Bool = true
    ) {
        self.size = size
        self.defaultColor = defaultColor
        self.targetColor = targetColor
        self.trackArrows = trackArrows
        self.trackContour = trackContour
        self.borders = borders
        self.shadows = shadows
        self.labels = labels
        self.routingNoData = routingNoData
        self.routingProfile = routingProfile
        self.routingProfiles = routingProfiles
        self.routingAvoidRadius = routingAvoidRadius
        self.goToRouted = goToRouted
        self.goToProfile = goToProfile
        self.goToIntervalMinutes = goToIntervalMinutes
        self.goToOffRouteMeters = goToOffRouteMeters
        self.goToVibrate = goToVibrate
    }

    /// The rules of « Aller vers un point » these settings give.
    public var goToRules: GoToRules {
        GoToRules.of(goToIntervalMinutes, goToOffRouteMeters)
    }

    private enum CodingKeys: String, CodingKey {
        case size, defaultColor, targetColor, trackArrows, trackContour, borders, shadows, labels, routingNoData
        case routingProfile, routingProfiles, routingAvoidRadius, goToRouted, goToProfile, goToIntervalMinutes
        case goToOffRouteMeters, goToVibrate
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = PlacemarkSettings()
        size = try c.settingsEnum(.size, d.size)
        defaultColor = try c.settingsValue(.defaultColor, d.defaultColor)
        targetColor = try c.settingsValue(.targetColor, d.targetColor)
        trackArrows = try c.settingsValue(.trackArrows, d.trackArrows)
        trackContour = try c.settingsValue(.trackContour, d.trackContour)
        borders = try c.settingsValue(.borders, d.borders)
        shadows = try c.settingsValue(.shadows, d.shadows)
        labels = try c.settingsValue(.labels, d.labels)
        routingNoData = try c.settingsEnum(.routingNoData, d.routingNoData)
        routingProfile = try c.settingsValue(.routingProfile, d.routingProfile)
        routingProfiles = try c.settingsValue(.routingProfiles, d.routingProfiles)
        routingAvoidRadius = try c.settingsValue(.routingAvoidRadius, d.routingAvoidRadius)
        goToRouted = try c.settingsValue(.goToRouted, d.goToRouted)
        goToProfile = try c.settingsValue(.goToProfile, d.goToProfile)
        goToIntervalMinutes = try c.settingsValue(.goToIntervalMinutes, d.goToIntervalMinutes)
        goToOffRouteMeters = try c.settingsValue(.goToOffRouteMeters, d.goToOffRouteMeters)
        goToVibrate = try c.settingsValue(.goToVibrate, d.goToVibrate)
    }
}

/// Positioning: GNSS course, camera after a pause, trail and battery (AlpineQuest's « Positioning »).
public struct PositionSettings: Equatable, Hashable, Sendable, Codable {
    public var course: CourseDisplay
    /// After 10 s without a gesture on the map, the camera goes back to the position (when it is known).
    public var autoRelock: Bool
    /// The last positions of the device, as a trail.
    public var showTrail: Bool
    /// The location stops while the app is hidden (the track recorder is not affected).
    public var disableInBackground: Bool
    /// Altitudes of the GNSS are given above the geoid (EGM96), as the maps and the barometer are.
    public var egm96: Bool

    public init(
        course: CourseDisplay = .arrow,
        autoRelock: Bool = false,
        showTrail: Bool = true,
        disableInBackground: Bool = true,
        egm96: Bool = true
    ) {
        self.course = course
        self.autoRelock = autoRelock
        self.showTrail = showTrail
        self.disableInBackground = disableInBackground
        self.egm96 = egm96
    }

    private enum CodingKeys: String, CodingKey {
        case course, autoRelock, showTrail, disableInBackground, egm96
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = PositionSettings()
        course = try c.settingsEnum(.course, d.course)
        autoRelock = try c.settingsValue(.autoRelock, d.autoRelock)
        showTrail = try c.settingsValue(.showTrail, d.showTrail)
        disableInBackground = try c.settingsValue(.disableInBackground, d.disableInBackground)
        egm96 = try c.settingsValue(.egm96, d.egm96)
    }
}

/// How the explorer of the tracks and waypoints is sorted and filtered (F7): kept from one opening to the next.
public struct LibraryViewSettings: Equatable, Hashable, Sendable, Codable {
    public var sort: LibrarySort
    public var filter: LibraryFilter

    public init(sort: LibrarySort = .date, filter: LibraryFilter = .all) {
        self.sort = sort
        self.filter = filter
    }

    private enum CodingKeys: String, CodingKey {
        case sort, filter
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = LibraryViewSettings()
        sort = try c.settingsEnum(.sort, d.sort)
        filter = try c.settingsEnum(.filter, d.filter)
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(sort.rawValue, forKey: .sort)
        try c.encode(filter.rawValue, forKey: .filter)
    }
}

/// The track recorder (AlpineQuest's « Track recorder »).
public struct RecorderSettings: Equatable, Hashable, Sendable, Codable {
    /// A warning when no fix has come for a minute while recording.
    public var lostSignalWarning: Bool
    /// The activity is asked when a recording or a segment starts; it names the track.
    public var askActivity: Bool
    /// A new segment starts the « done so far » statistics again.
    public var resetStatsOnSegment: Bool
    /// The recording pauses when the app is hidden and resumes when it is back.
    public var dashboardMode: Bool

    public init(
        lostSignalWarning: Bool = false,
        askActivity: Bool = true,
        resetStatsOnSegment: Bool = true,
        dashboardMode: Bool = false
    ) {
        self.lostSignalWarning = lostSignalWarning
        self.askActivity = askActivity
        self.resetStatsOnSegment = resetStatsOnSegment
        self.dashboardMode = dashboardMode
    }

    private enum CodingKeys: String, CodingKey {
        case lostSignalWarning, askActivity, resetStatsOnSegment, dashboardMode
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = RecorderSettings()
        lostSignalWarning = try c.settingsValue(.lostSignalWarning, d.lostSignalWarning)
        askActivity = try c.settingsValue(.askActivity, d.askActivity)
        resetStatsOnSegment = try c.settingsValue(.resetStatsOnSegment, d.resetStatsOnSegment)
        dashboardMode = try c.settingsValue(.dashboardMode, d.dashboardMode)
    }
}

/// What the user chose in the settings screen. Every field has a default, so that a file written by an older version
/// (or a damaged one) still gives a usable value.
public struct AppSettings: Equatable, Hashable, Sendable, Codable {
    /// Distance unit: distances, heights and speeds (AlpineQuest's « Distance unit »).
    public var units: UnitSystem
    /// Area unit, apart from the distances (« Area unit »).
    public var areaUnits: UnitSystem
    public var coordinates: CoordinateSystem
    /// A second format shown under the first in the position box, or none (« Secondary loc. format »).
    public var secondaryCoordinates: CoordinateSystem?
    public var bearing: BearingUnit
    public var slope: SlopeUnit
    public var pressure: PressureUnit
    public var north: NorthReference
    /// The status bar is hidden on the map; a swipe from the top edge shows it for a moment.
    public var hideStatusBar: Bool
    public var hideNavigationBar: Bool
    /// The map can be used on a locked phone, without unlocking it first.
    public var showOverLockScreen: Bool
    /// Leaving the app from the map asks first.
    public var confirmExit: Bool
    public var theme: ThemeMode
    /// Material You colours from the wallpaper (Android 12+); the ToolMap palette otherwise (ADR-074).
    public var wallpaperColors: Bool
    /// Larger touch targets and text, for use with gloves (P10.2).
    public var gloves: Bool
    public var keepScreenOn: Bool
    /// The box at the top left with the centre coordinates and the zoom (« Information box » of the main menu).
    public var showCoordinates: Bool
    /// The app that opens the centre point with « Ouvrir avec » (package name on Android), or nil to be asked.
    public var pointApp: String?
    /// How the compass heading is shown on the map (« Current heading display »).
    public var heading: HeadingDisplay
    /// Coordinate grid drawn over the map (UTM, Lambert 93, WGS84, MGRS), or none.
    public var screenGrid: GridKind
    public var altitude: AltitudeMode
    public var alerts: AlertSettings
    public var maps: MapDisplaySettings
    public var canvas: MapCanvasSettings
    public var placemarks: PlacemarkSettings
    public var positioning: PositionSettings
    public var recorder: RecorderSettings
    public var library: LibraryViewSettings

    public init(
        units: UnitSystem = .metric,
        areaUnits: UnitSystem = .metric,
        coordinates: CoordinateSystem = .dd,
        secondaryCoordinates: CoordinateSystem? = nil,
        bearing: BearingUnit = .degrees,
        slope: SlopeUnit = .percent,
        pressure: PressureUnit = .hpa,
        north: NorthReference = .trueNorth,
        hideStatusBar: Bool = true,
        hideNavigationBar: Bool = true,
        showOverLockScreen: Bool = false,
        confirmExit: Bool = false,
        theme: ThemeMode = .system,
        wallpaperColors: Bool = false,
        gloves: Bool = false,
        keepScreenOn: Bool = false,
        showCoordinates: Bool = true,
        pointApp: String? = nil,
        heading: HeadingDisplay = .arrow,
        screenGrid: GridKind = .none,
        altitude: AltitudeMode = .auto,
        alerts: AlertSettings = AlertSettings(),
        maps: MapDisplaySettings = MapDisplaySettings(),
        canvas: MapCanvasSettings = MapCanvasSettings(),
        placemarks: PlacemarkSettings = PlacemarkSettings(),
        positioning: PositionSettings = PositionSettings(),
        recorder: RecorderSettings = RecorderSettings(),
        library: LibraryViewSettings = LibraryViewSettings()
    ) {
        self.units = units
        self.areaUnits = areaUnits
        self.coordinates = coordinates
        self.secondaryCoordinates = secondaryCoordinates
        self.bearing = bearing
        self.slope = slope
        self.pressure = pressure
        self.north = north
        self.hideStatusBar = hideStatusBar
        self.hideNavigationBar = hideNavigationBar
        self.showOverLockScreen = showOverLockScreen
        self.confirmExit = confirmExit
        self.theme = theme
        self.wallpaperColors = wallpaperColors
        self.gloves = gloves
        self.keepScreenOn = keepScreenOn
        self.showCoordinates = showCoordinates
        self.pointApp = pointApp
        self.heading = heading
        self.screenGrid = screenGrid
        self.altitude = altitude
        self.alerts = alerts
        self.maps = maps
        self.canvas = canvas
        self.placemarks = placemarks
        self.positioning = positioning
        self.recorder = recorder
        self.library = library
    }

    private enum CodingKeys: String, CodingKey {
        case units, areaUnits, coordinates, secondaryCoordinates, bearing, slope, pressure, north, hideStatusBar
        case hideNavigationBar, showOverLockScreen, confirmExit, theme, wallpaperColors, gloves, keepScreenOn
        case showCoordinates, pointApp, heading, screenGrid, altitude, alerts, maps, canvas, placemarks, positioning
        case recorder, library
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        units = try c.settingsEnum(.units, d.units)
        areaUnits = try c.settingsEnum(.areaUnits, d.areaUnits)
        coordinates = try c.settingsEnum(.coordinates, d.coordinates)
        secondaryCoordinates = try c.settingsOptionalEnum(.secondaryCoordinates, CoordinateSystem.self)
        bearing = try c.settingsEnum(.bearing, d.bearing)
        slope = try c.settingsEnum(.slope, d.slope)
        pressure = try c.settingsEnum(.pressure, d.pressure)
        north = try c.settingsEnum(.north, d.north)
        hideStatusBar = try c.settingsValue(.hideStatusBar, d.hideStatusBar)
        hideNavigationBar = try c.settingsValue(.hideNavigationBar, d.hideNavigationBar)
        showOverLockScreen = try c.settingsValue(.showOverLockScreen, d.showOverLockScreen)
        confirmExit = try c.settingsValue(.confirmExit, d.confirmExit)
        theme = try c.settingsEnum(.theme, d.theme)
        wallpaperColors = try c.settingsValue(.wallpaperColors, d.wallpaperColors)
        gloves = try c.settingsValue(.gloves, d.gloves)
        keepScreenOn = try c.settingsValue(.keepScreenOn, d.keepScreenOn)
        showCoordinates = try c.settingsValue(.showCoordinates, d.showCoordinates)
        pointApp = try c.decodeIfPresent(String.self, forKey: .pointApp)
        heading = try c.settingsEnum(.heading, d.heading)
        screenGrid = try c.settingsEnum(.screenGrid, d.screenGrid)
        altitude = try c.settingsEnum(.altitude, d.altitude)
        alerts = try c.settingsValue(.alerts, d.alerts)
        maps = try c.settingsValue(.maps, d.maps)
        canvas = try c.settingsValue(.canvas, d.canvas)
        placemarks = try c.settingsValue(.placemarks, d.placemarks)
        positioning = try c.settingsValue(.positioning, d.positioning)
        recorder = try c.settingsValue(.recorder, d.recorder)
        library = try c.settingsValue(.library, d.library)
    }

    /// Every field is written, nulls included (`encodeDefaults` and `explicitNulls` of kotlinx.serialization).
    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(units, forKey: .units)
        try c.encode(areaUnits, forKey: .areaUnits)
        try c.encode(coordinates, forKey: .coordinates)
        if let secondary = secondaryCoordinates {
            try c.encode(secondary, forKey: .secondaryCoordinates)
        } else {
            try c.encodeNil(forKey: .secondaryCoordinates)
        }
        try c.encode(bearing, forKey: .bearing)
        try c.encode(slope, forKey: .slope)
        try c.encode(pressure, forKey: .pressure)
        try c.encode(north, forKey: .north)
        try c.encode(hideStatusBar, forKey: .hideStatusBar)
        try c.encode(hideNavigationBar, forKey: .hideNavigationBar)
        try c.encode(showOverLockScreen, forKey: .showOverLockScreen)
        try c.encode(confirmExit, forKey: .confirmExit)
        try c.encode(theme, forKey: .theme)
        try c.encode(wallpaperColors, forKey: .wallpaperColors)
        try c.encode(gloves, forKey: .gloves)
        try c.encode(keepScreenOn, forKey: .keepScreenOn)
        try c.encode(showCoordinates, forKey: .showCoordinates)
        if let app = pointApp {
            try c.encode(app, forKey: .pointApp)
        } else {
            try c.encodeNil(forKey: .pointApp)
        }
        try c.encode(heading, forKey: .heading)
        try c.encode(screenGrid.rawValue, forKey: .screenGrid)
        try c.encode(altitude, forKey: .altitude)
        try c.encode(alerts, forKey: .alerts)
        try c.encode(maps, forKey: .maps)
        try c.encode(canvas, forKey: .canvas)
        try c.encode(placemarks, forKey: .placemarks)
        try c.encode(positioning, forKey: .positioning)
        try c.encode(recorder, forKey: .recorder)
        try c.encode(library, forKey: .library)
    }
}

/// Reads and writes `settings.json`.
public enum AppSettingsCodec {
    /// Pretty-printed JSON with every value written, defaults and nulls included, as the Android app writes it.
    public static func encode(_ settings: AppSettings) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .withoutEscapingSlashes]
        let data = try encoder.encode(settings)
        return String(decoding: data, as: UTF8.self)
    }

    /// Never throws: a missing or damaged file gives the defaults.
    public static func decodeOrDefault(_ text: String?) -> AppSettings {
        guard let raw = text, !isBlank(raw) else { return AppSettings() }
        do {
            return try JSONDecoder().decode(AppSettings.self, from: Data(raw.utf8))
        } catch {
            return AppSettings()
        }
    }

    /// Kotlin `isBlank()`: empty, or only whitespace.
    private static func isBlank(_ text: String) -> Bool {
        text.unicodeScalars.allSatisfy(KotlinText.isWhitespace)
    }
}

/// kotlinx.serialization with `coerceInputValues`: what a field becomes when its key is missing, null or unknown.
fileprivate extension KeyedDecodingContainer {
    /// A missing key or a null keeps the default; a value of the wrong type fails (the whole file then gives the
    /// default settings).
    func settingsValue<T: Decodable>(_ key: Key, _ fallback: T) throws -> T {
        try decodeIfPresent(T.self, forKey: key) ?? fallback
    }

    /// An enum, written by its Kotlin name: a missing key, a null or a name this version does not know keep the
    /// default.
    func settingsEnum<T: RawRepresentable>(_ key: Key, _ fallback: T) throws -> T where T.RawValue == String {
        guard let name = try decodeIfPresent(String.self, forKey: key) else { return fallback }
        return T(rawValue: name) ?? fallback
    }

    /// A nullable enum, nil by default: a name this version does not know gives nil too.
    func settingsOptionalEnum<T: RawRepresentable>(_ key: Key, _ type: T.Type) throws -> T? where T.RawValue == String {
        guard let name = try decodeIfPresent(String.self, forKey: key) else { return nil }
        return T(rawValue: name)
    }
}
