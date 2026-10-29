// Port of core/src/test/kotlin/fr/toolmap/core/state/WorkStateTest.kt.

import Testing
@testable import ToolMapCore

struct WorkStateTests {
    @Test("the default state shows OpenStreetMap over France")
    func defaultState() {
        let state = WorkState()
        #expect(state.stack.layers.map(\.sourceId) == ["osm"])
        #expect(state.camera.zoom == 5.0)
    }

    @Test("a state survives a JSON round trip")
    func roundTrip() throws {
        let state = WorkState(
            stack: LayerStack().add("ign-scan25").add("ign-pentes", opacity: 0.5).setAdjustments("l2", 0.1, 0.2, 0.3),
            camera: CameraState(lat: 45.19, lon: 5.72, zoom: 11.5, bearing: 30.0),
            recentBases: ["ign-scan25", "osm"],
            offline: true
        )
        let text = try WorkStateCodec.encode(state)
        #expect(WorkStateCodec.decodeOrDefault(text) == state)
    }

    @Test("damaged or missing content gives the default state")
    func damaged() {
        #expect(WorkStateCodec.decodeOrDefault(nil) == WorkState())
        #expect(WorkStateCodec.decodeOrDefault("") == WorkState())
        #expect(WorkStateCodec.decodeOrDefault("{ not json") == WorkState())
        #expect(WorkStateCodec.decodeOrDefault(#"{"camera": "oops"}"#) == WorkState())
    }

    @Test("unknown fields from a newer version are ignored")
    func unknownFields() {
        let text = #"{"camera":{"lat":1.0,"lon":2.0,"zoom":3.0,"bearing":0.0,"pitch":45.0},"future":true}"#
        #expect(WorkStateCodec.decodeOrDefault(text).camera.lat == 1.0)
    }

    @Test("recent bases keep the two latest distinct maps")
    func recentBases() {
        var state = WorkState(recentBases: ["osm"])
        #expect(state.swapTarget() == nil)
        state = state.withBaseUsed("scan25")
        #expect(state.recentBases == ["scan25", "osm"])
        #expect(state.swapTarget() == "osm")
        state = state.withBaseUsed("scan25")
        #expect(state.recentBases == ["scan25", "osm"])
        state = state.withBaseUsed("ortho")
        #expect(state.recentBases == ["ortho", "scan25"])
        state = state.withBaseUsed("scan25")
        #expect(state.recentBases == ["scan25", "ortho"])
    }

    @Test("a state written by Android reads the same, nulls and all")
    func androidJson() {
        let text = #"""
        {
            "stack": {
                "layers": [
                    {
                        "id": "l1",
                        "sourceId": "ign-scan25",
                        "visible": true,
                        "opacity": 1.0,
                        "contrast": 0.0,
                        "brightness": 0.0,
                        "saturation": 0.0,
                        "displayMinZoom": 8,
                        "displayMaxZoom": null,
                        "inverted": false,
                        "displayMinElevation": null,
                        "displayMaxElevation": null,
                        "rangeLogic": "OR",
                        "hue": -40.0
                    }
                ]
            },
            "camera": {"lat": 45.0, "lon": 6.0, "zoom": 12.0, "bearing": 0.0},
            "recentBases": ["ign-scan25"],
            "offline": false
        }
        """#
        let state = WorkStateCodec.decodeOrDefault(text)
        let expected = LayerConfig(id: "l1", sourceId: "ign-scan25", displayMinZoom: 8, rangeLogic: .or, hue: -40)
        #expect(state.stack.layers == [expected])
        #expect(state.camera == CameraState(lat: 45.0, lon: 6.0, zoom: 12.0, bearing: 0.0))
        #expect(state.recentBases == ["ign-scan25"])
    }
}
