// Ports of core/src/test/kotlin/fr/toolmap/core/settings/AppSettingsTest.kt and BarometerTickTest.kt. The two imperial
// cases of AppSettingsTest test TrackFormat and ScaleBar (track/ and geo/): they belong with the ports of those files.

import Foundation
import Testing
@testable import ToolMapCore

struct AppSettingsTests {
    @Test("settings survive a round trip")
    func roundTrip() throws {
        let s = AppSettings(
            units: .imperial,
            coordinates: .mgrs,
            theme: .dark,
            wallpaperColors: true,
            gloves: true,
            keepScreenOn: true,
            screenGrid: .utm,
            altitude: .barometer,
            alerts: AlertSettings(proximityRadiusMeters: 250, offTrackMeters: 80, sound: false)
        )
        #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)) == s)
    }

    @Test("the map canvas, placemark, positioning and recorder choices survive a round trip")
    func panelsRoundTrip() throws {
        let s = AppSettings(
            bearing: .mils,
            slope: .degrees,
            pressure: .inhg,
            north: .grid,
            hideStatusBar: false,
            showOverLockScreen: true,
            confirmExit: true,
            heading: .both,
            canvas: MapCanvasSettings(
                menusOpaque: true,
                menusLeftHanded: true,
                infoBoxSize: .large,
                infoBoxBackground: .opaque,
                zoomButtons: false,
                zoomStep: .half,
                gridDensity: .fine,
                gridStyle: .dashed,
                longPress: .target,
                pinchZoom: false,
                manualRotation: false,
                perspective: false,
                explorerAuto: false
            ),
            placemarks: PlacemarkSettings(
                size: .large,
                defaultColor: Int32(bitPattern: 0xFF00_C853),
                trackArrows: false,
                trackContour: false,
                borders: false,
                shadows: false
            ),
            positioning: PositionSettings(
                course: .none,
                autoRelock: true,
                showTrail: false,
                disableInBackground: false,
                egm96: false
            ),
            recorder: RecorderSettings(
                lostSignalWarning: true,
                askActivity: false,
                resetStatsOnSegment: false,
                dashboardMode: true
            )
        )
        #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)) == s)
    }

    @Test("a missing, empty or damaged file gives the defaults and never throws")
    func damagedFile() {
        #expect(AppSettingsCodec.decodeOrDefault(nil) == AppSettings())
        #expect(AppSettingsCodec.decodeOrDefault("") == AppSettings())
        #expect(AppSettingsCodec.decodeOrDefault("{not json") == AppSettings())
        #expect(AppSettingsCodec.decodeOrDefault("[1,2]") == AppSettings())
    }

    @Test("an older or newer file is still read")
    func olderOrNewerFile() {
        // A field this version does not know, and a value of an enum it does not know: the rest is kept.
        let text = #"{"units":"IMPERIAL","theme":"SOLARIZED","future":{"a":1},"screenGrid":"MGRS"}"#
        let s = AppSettingsCodec.decodeOrDefault(text)
        #expect(s.units == .imperial)
        #expect(s.theme == .system)
        #expect(s.screenGrid == .mgrs)
        #expect(s.coordinates == .dd)
    }

    @Test("the coordinates box is shown by default and its choice survives a round trip")
    func coordinatesBox() throws {
        #expect(AppSettings().showCoordinates)
        #expect(AppSettingsCodec.decodeOrDefault(#"{"units":"METRIC"}"#).showCoordinates)
        let hidden = AppSettings(showCoordinates: false)
        #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(hidden)) == hidden)
    }

    @Test("the app that opens a point is kept, and nothing is chosen by default")
    func pointApp() throws {
        #expect(AppSettings().pointApp == nil)
        let waze = AppSettings(pointApp: "com.waze")
        #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(waze)) == waze)
    }

    @Test("maps are sharp by default, also for a file written before the sharpness setting existed")
    func sharpByDefault() throws {
        #expect(AppSettings().maps.tileMagnification == TileSharpness.sharp)
        let old = #"{"units":"METRIC","maps":{"overscalingLevels":2}}"#
        let s = AppSettingsCodec.decodeOrDefault(old)
        #expect(s.maps.overscalingLevels == 2)
        #expect(s.maps.tileMagnification == TileSharpness.sharp)
        for m in [1.5, TileSharpness.androidSize] {
            let chosen = AppSettings(maps: MapDisplaySettings(tileMagnification: m))
            #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(chosen)) == chosen)
        }
        // On a phone of density 2.8125 the default asks MapLibre for 91 px tiles, "Taille Android" for 256 px.
        #expect(AppSettings().maps.zoomMapping(2.8125).declaredTileSize(256) == 91)
        let android = MapDisplaySettings(tileMagnification: TileSharpness.androidSize)
        #expect(android.zoomMapping(2.8125) == ZoomMapping.standard)
    }

    @Test("the night mode darkens by its intensity, kept in its range, and not at all when off")
    func nightMode() throws {
        #expect(MapDisplaySettings().darkening == 0)
        #expect(abs(MapDisplaySettings(nightMode: true).darkening - 0.6) < 1e-6)
        #expect(MapDisplaySettings(nightMode: false, nightPercent: 80).darkening == 0)
        #expect(abs(MapDisplaySettings(nightMode: true, nightPercent: 150).darkening - 0.9) < 1e-6)
        #expect(abs(MapDisplaySettings(nightMode: true, nightPercent: 0).darkening - 0.2) < 1e-6)
        let s = AppSettings(maps: MapDisplaySettings(nightMode: true, nightPercent: 40))
        #expect(try AppSettingsCodec.decodeOrDefault(AppSettingsCodec.encode(s)) == s)
    }

    // Not in AppSettingsTest.kt: the file must stay readable by the Android app.

    @Test("every field is written under its Kotlin name, nulls included")
    func kotlinKeys() throws {
        let text = try AppSettingsCodec.encode(AppSettings())
        let json = try JSONSerialization.jsonObject(with: Data(text.utf8))
        let object = try #require(json as? [String: Any])
        let keys: Set<String> = [
            "units", "areaUnits", "coordinates", "secondaryCoordinates", "bearing", "slope", "pressure", "north",
            "hideStatusBar", "hideNavigationBar", "showOverLockScreen", "confirmExit", "theme", "wallpaperColors",
            "gloves", "keepScreenOn", "showCoordinates", "pointApp", "heading", "screenGrid", "altitude", "alerts",
            "maps", "canvas", "placemarks", "positioning", "recorder", "library",
        ]
        #expect(Set(object.keys) == keys)
        let secondaryIsNull = object["secondaryCoordinates"] is NSNull
        let pointAppIsNull = object["pointApp"] is NSNull
        #expect(secondaryIsNull)
        #expect(pointAppIsNull)
        #expect((object["north"] as? String) == "TRUE")
        #expect((object["screenGrid"] as? String) == "NONE")
        let placemarks = try #require(object["placemarks"] as? [String: Any])
        #expect((placemarks["routingNoData"] as? String) == "LEG_BY_HAND")
        let library = try #require(object["library"] as? [String: Any])
        #expect((library["sort"] as? String) == "DATE")
        #expect((library["filter"] as? String) == "ALL")
    }

    @Test("a file written by the Android app is read, a null keeping the default of its field")
    func androidFile() {
        let text = """
            {
                "units": "IMPERIAL",
                "secondaryCoordinates": "UTM",
                "north": "MAGNETIC",
                "pointApp": null,
                "heading": null,
                "maps": {
                    "overscalingLevels": 2,
                    "tileMagnification": 1.5,
                    "hiddenMaps": ["osm", "ign-maps"],
                    "nightMode": true,
                    "nightPercent": 70,
                    "corridorRadius": 750
                },
                "placemarks": {
                    "defaultColor": -16725933,
                    "routingNoData": "ALL_BY_HAND",
                    "routingProfiles": ["my-profile"]
                },
                "library": { "sort": "NAME", "filter": "WAYPOINTS" }
            }
            """
        let s = AppSettingsCodec.decodeOrDefault(text)
        #expect(s.units == .imperial)
        #expect(s.secondaryCoordinates == CoordinateSystem.utm)
        #expect(s.north == .magnetic)
        #expect(s.pointApp == nil)
        #expect(s.heading == .arrow)
        #expect(s.maps == MapDisplaySettings(
            overscalingLevels: 2,
            tileMagnification: 1.5,
            hiddenMaps: ["osm", "ign-maps"],
            nightMode: true,
            nightPercent: 70,
            corridorRadius: 750
        ))
        #expect(s.placemarks.defaultColor == Int32(bitPattern: 0xFF00_C853))
        #expect(s.placemarks.routingNoData == .allByHand)
        #expect(s.placemarks.routingProfiles == ["my-profile"])
        #expect(s.library.sort == .name)
        #expect(s.library.filter == .waypoints)
        // A value of the wrong type is not coerced: the whole file gives the defaults, as on Android.
        #expect(AppSettingsCodec.decodeOrDefault(#"{"units":"IMPERIAL","gloves":"yes please"}"#) == AppSettings())
    }
}

struct BarometerTickTests {
    @Test("the tick is on for every mode but GPS")
    func onButGps() {
        #expect(!AltitudeMode.gps.barometerOn)
        #expect(AltitudeMode.auto.barometerOn)
        #expect(AltitudeMode.barometer.barometerOn)
    }

    @Test("ticking from GPS gives the automatic mode")
    func tickFromGps() {
        #expect(AltitudeMode.gps.withBarometer(true) == .auto)
    }

    @Test("ticking keeps a mode that already uses the barometer")
    func tickKeeps() {
        #expect(AltitudeMode.auto.withBarometer(true) == .auto)
        #expect(AltitudeMode.barometer.withBarometer(true) == .barometer)
    }

    @Test("unticking gives the GPS whatever the mode was")
    func untick() {
        #expect(AltitudeMode.gps.withBarometer(false) == .gps)
        #expect(AltitudeMode.auto.withBarometer(false) == .gps)
        #expect(AltitudeMode.barometer.withBarometer(false) == .gps)
    }

    @Test("the tick reads back what it set")
    func readsBack() {
        for mode in AltitudeMode.allCases {
            #expect(mode.withBarometer(true).barometerOn)
            #expect(!mode.withBarometer(false).barometerOn)
        }
    }
}
