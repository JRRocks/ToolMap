// Port of core/src/test/kotlin/fr/toolmap/core/layers/LayerZoomTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct LayerZoomTests {
    /// MapLibre's own tile size, and AlpineQuest's sharpness on screens of density 1.5, 2.8125 and 4.
    private let mappings: [ZoomMapping] = [
        ZoomMapping.standard,
        ZoomMapping.forScreen(1.5, 1.0),
        ZoomMapping.forScreen(2.8125, 1.0),
        ZoomMapping.forScreen(4.0, 1.0),
    ]

    /// MapLibre's rule for `minzoom` / `maxzoom` of a layer at a given camera zoom.
    private func visibleInMapLibre(_ props: LayerProps, _ camera: Double) -> Bool {
        let aboveMin = props.minZoom.map { camera >= Double($0) } ?? true
        let belowMax = props.maxZoom.map { camera < Double($0) } ?? true
        return aboveMin && belowMax
    }

    /// First quarter zoom the camera reaches (camera zoom ≥ 0).
    private func firstQuarter(_ mapping: ZoomMapping) -> Double {
        ceil(mapping.toTileZoom(0.0) * 4) / 4
    }

    /// Camera zoom of a displayed zoom, nudged up by far less than a visible step: MapLibre's bounds are floats, so
    /// exactly on an irrational bound either side can win; just above it, an inclusive minimum and an exclusive maximum
    /// both have a single answer.
    private func cameraAt(_ mapping: ZoomMapping, _ displayed: Double) -> Double {
        mapping.toCameraZoom(displayed) + 1e-4
    }

    private func props(_ applied: [AppliedLayer], _ sourceId: String) throws -> LayerProps {
        var bySource: [String: LayerProps] = [:]
        for layer in applied {
            bySource[layer.sourceId] = layer.props
        }
        return try #require(bySource[sourceId], "\(sourceId)")
    }

    @Test("an inclusive display range becomes a camera range with an exclusive upper bound")
    func cameraRange() {
        // Displayed 8..16 = camera 7 up to (not including) 16.
        #expect(LayerZoom.cameraRange(8, 16) == LayerZoom.CameraRange(7, 16))
        #expect(LayerZoom.cameraRange(1, 7) == LayerZoom.CameraRange(nil, 7))
        #expect(LayerZoom.cameraRange(nil, nil) == LayerZoom.CameraRange(nil, nil))
        #expect(LayerZoom.cameraRange(0, nil) == LayerZoom.CameraRange(nil, nil))
        #expect(LayerZoom.cameraRange(12, nil) == LayerZoom.CameraRange(11, nil))
    }

    @Test("the camera range agrees with the displayed zoom rule at every quarter zoom")
    func quarterZooms() {
        let ranges: [(Int?, Int?)] = [(8, 16), (1, 7), (nil, 5), (10, nil), (nil, nil), (3, 3), (0, 1)]
        var failures: [String] = []
        for mapping in mappings {
            for (min, max) in ranges {
                let camera = LayerZoom.cameraRange(min, max, mapping)
                let minPositive = camera.minZoom.map { $0 > 0 } ?? true
                let maxNotNegative = camera.maxZoom.map { $0 >= 0 } ?? true
                #expect(minPositive)
                #expect(maxNotNegative, "MapLibre refuses a negative maxzoom")
                let props = LayerProps(minZoom: camera.minZoom, maxZoom: camera.maxZoom)
                // Only the displayed zooms the camera can reach (camera zoom ≥ 0).
                var displayed = firstQuarter(mapping)
                while displayed <= 22.0 {
                    let expected = LayerZoom.isActiveAt(min, max, displayed)
                    if expected != visibleInMapLibre(props, cameraAt(mapping, displayed)) {
                        failures.append("range \(String(describing: min))..\(String(describing: max)) at displayed zoom \(displayed) with \(mapping)")
                    }
                    displayed += 0.25
                }
            }
        }
        #expect(failures.isEmpty, "\(failures.prefix(5))")
    }

    @Test("the author's workspace shows Topo and slopes from 8 and Plan and slopes below, with no gap at 7_5")
    func authorWorkspace() throws {
        let stack = TestWorkspaces.topoPlanSlopes.toStack { _ in true }.stack
        for mapping in mappings {
            let applied = StackToStyle.desired(stack, mapping: mapping) { _ in true }
            let planProps = try props(applied, "ign-plan")
            let topoProps = try props(applied, "ign-scan25")
            let slopesProps = try props(applied, "ign-pentes")
            var displayed = Swift.max(1.0, firstQuarter(mapping))
            while displayed < 17.0 {
                let camera = cameraAt(mapping, displayed)
                let plan = visibleInMapLibre(planProps, camera)
                let topo = visibleInMapLibre(topoProps, camera)
                let slopes = visibleInMapLibre(slopesProps, camera)
                #expect(plan || topo, "a base map is shown at \(displayed)")
                #expect(plan != topo, "exactly one base map at \(displayed)")
                #expect(slopes, "slopes shown at \(displayed)")
                #expect(topo == (displayed >= 8.0), "topo at \(displayed)")
                #expect(plan == (displayed < 8.0), "plan at \(displayed)")
                displayed += 0.25
            }
            // Past 16.999 neither base map nor slopes remain (the ranges end at 16).
            let beyond = cameraAt(mapping, 17.0)
            #expect(!visibleInMapLibre(topoProps, beyond))
            #expect(!visibleInMapLibre(slopesProps, beyond))
        }
    }

    @Test("on a sharp phone screen the same displayed range starts at a lower camera zoom")
    func sharpPhone() throws {
        // Density 2.8125 (samsung SM-S931U), AlpineQuest's sharpness: displayed zoom = camera + 2.49.
        let phone = ZoomMapping.forScreen(2.8125, 1.0)
        let range = LayerZoom.cameraRange(8, 16, phone)
        let minZoom = try #require(range.minZoom)
        let maxZoom = try #require(range.maxZoom)
        #expect(abs(Double(minZoom) - (8 - phone.offset)) <= 1e-5)
        #expect(abs(Double(maxZoom) - (17 - phone.offset)) <= 1e-5)
    }

    @Test("setZoomRange clamps and keeps the minimum below the maximum")
    func setZoomRange() throws {
        let stack = LayerStack().add("osm")
        let id = try #require(stack.layers.first?.id)
        let set = try #require(stack.setZoomRange(id, 8, 16)[id])
        #expect(set.displayMinZoom == 8 && set.displayMaxZoom == 16)
        let reversed = try #require(stack.setZoomRange(id, 12, 5)[id])
        #expect(reversed.displayMinZoom == 12)
        #expect(reversed.displayMaxZoom == 12)
        let clamped = try #require(stack.setZoomRange(id, -4, 99)[id])
        #expect(clamped.displayMinZoom == 0)
        #expect(clamped.displayMaxZoom == ZoomBar.max)
        let cleared = try #require(stack.setZoomRange(id, 8, 16).setZoomRange(id, nil, nil)[id])
        #expect(cleared.displayMinZoom == nil)
        #expect(cleared.displayMaxZoom == nil)
        #expect(stack.setZoomRange("unknown", 1, 2) == stack)
    }

    @Test("zoom ranges are written to the style and survive the saved work state")
    func savedWorkState() throws {
        let base = LayerStack().add("ign-scan25")
        let id = try #require(base.layers.first?.id)
        let stack = base.setZoomRange(id, 8, 16)
        let desired = StackToStyle.desired(stack) { _ in true }
        let applied = try #require(desired.first)
        #expect(applied.props.minZoom == 7)
        #expect(applied.props.maxZoom == 16)

        let text = try WorkStateCodec.encode(WorkState(stack: stack))
        let restored = WorkStateCodec.decodeOrDefault(text)
        #expect(restored.stack[id]?.displayMinZoom == 8)
        #expect(restored.stack[id]?.displayMaxZoom == 16)
    }

    @Test("a change of range is a plain layer update for the style diff")
    func rangeChangeIsUpdate() throws {
        let stack = LayerStack().add("osm")
        let id = try #require(stack.layers.first?.id)
        let before = StackToStyle.desired(stack) { _ in true }
        let after = StackToStyle.desired(stack.setZoomRange(id, 5, 12)) { _ in true }
        let ops = StyleDiff.compute(before, after)
        #expect(ops.count == 1)
        var isUpdate = false
        if let op = ops.first, case .updateLayer = op {
            isUpdate = true
        }
        #expect(isUpdate, "expected an update, got \(ops)")
    }

    @Test("the preset only uses sources of the built-in catalogue")
    func presetSources() throws {
        let sources = try BuiltinSources.load()
        let ids = Set(sources.map(\.id))
        let used = Workspaces.builtin.flatMap { $0.layers.map(\.sourceId) }
        let unknown = used.filter { !ids.contains($0) }
        #expect(unknown.isEmpty, "\(unknown)")
    }
}
