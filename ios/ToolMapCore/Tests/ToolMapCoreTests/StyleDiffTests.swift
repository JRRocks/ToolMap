// Port of core/src/test/kotlin/fr/toolmap/core/layers/StyleDiffTest.kt.

import Testing
@testable import ToolMapCore

/// A small seeded generator (SplitMix64), for the random transitions: Kotlin's `Random(20261002)` sequence itself does
/// not matter, only that the run is the same every time.
private struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

struct StyleDiffTests {
    private func layer(_ id: String, _ source: String? = nil, opacity: Float = 1) -> AppliedLayer {
        AppliedLayer(id: id, sourceId: source ?? "s-\(id)", props: LayerProps(opacity: opacity))
    }

    @Test("identical states need no operation")
    func identical() {
        let state = [layer("a"), layer("b")]
        #expect(StyleDiff.compute(state, state).isEmpty)
    }

    @Test("an empty style gets sources then layers bottom to top")
    func emptyStyle() {
        let desired = [layer("a"), layer("b")]
        let ops = StyleDiff.compute([], desired)
        var addedSources: [String] = []
        var addedLayers: [String] = []
        for op in ops {
            switch op {
            case let .addSource(sourceId): addedSources.append(sourceId)
            case let .addLayer(added, _): addedLayers.append(added.id)
            default: break
            }
        }
        #expect(addedSources.sorted() == ["s-a", "s-b"])
        #expect(addedLayers == ["a", "b"])
        #expect(StyleDiff.apply([], ops) == desired)
    }

    @Test("removing the last layer of a source removes the source")
    func removeSource() {
        let current = [layer("a"), layer("b")]
        let ops = StyleDiff.compute(current, [layer("a")])
        #expect(ops.contains(.removeLayer(id: "b")))
        #expect(ops.contains(.removeSource(sourceId: "s-b")))
        #expect(!ops.contains(.removeSource(sourceId: "s-a")))
    }

    @Test("a source shared by two layers survives the removal of one of them")
    func sharedSource() {
        let current = [layer("a", "shared"), layer("b", "shared")]
        let ops = StyleDiff.compute(current, [layer("a", "shared")])
        #expect(ops == [.removeLayer(id: "b")])
    }

    @Test("a property change is an update, not a rebuild")
    func propertyChange() {
        let current = [layer("a"), layer("b", opacity: 1)]
        let desired = [layer("a"), layer("b", opacity: 0.5)]
        #expect(StyleDiff.compute(current, desired) == [.updateLayer(id: "b", props: desired[1].props)])
    }

    @Test("a layer whose source changes is rebuilt in place")
    func sourceChange() {
        let current = [layer("a", "osm"), layer("b", "pentes")]
        let desired = [layer("a", "scan25"), layer("b", "pentes")]
        let ops = StyleDiff.compute(current, desired)
        #expect(StyleDiff.apply(current, ops) == desired)
        #expect(ops.contains(.removeSource(sourceId: "osm")))
        #expect(ops.contains(.addSource(sourceId: "scan25")))
    }

    @Test("swapping two layers ends in the desired order")
    func swapping() {
        let current = [layer("a"), layer("b"), layer("c")]
        let desired = [layer("c"), layer("b"), layer("a")]
        #expect(StyleDiff.apply(current, StyleDiff.compute(current, desired)) == desired)
    }

    @Test("any transition between random stacks ends exactly in the desired state")
    func randomTransitions() {
        var random = SeededGenerator(seed: 20_261_002)
        let pool = ["a", "b", "c", "d", "e", "f", "g", "h"]
        func randomState() -> [AppliedLayer] {
            let count = Int.random(in: 0 ... pool.count, using: &random)
            return pool.shuffled(using: &random).prefix(count).map { (id: String) -> AppliedLayer in
                let source = "s-\(Int.random(in: 0 ..< 4, using: &random))"
                let opacity = Float(Int.random(in: 0 ..< 3, using: &random)) / 2
                return layer(id, source, opacity: opacity)
            }
        }
        var failures: [String] = []
        for _ in 0 ..< 500 {
            let current = randomState()
            let desired = randomState()
            let ops = StyleDiff.compute(current, desired)
            if StyleDiff.apply(current, ops) != desired {
                failures.append("from \(current) to \(desired) via \(ops)")
            }
            // Sources: every wanted source is present after the ops, no unused one is left.
            var present = Set(current.map(\.sourceId))
            for op in ops {
                switch op {
                case let .addSource(sourceId): present.insert(sourceId)
                case let .removeSource(sourceId): present.remove(sourceId)
                default: break
                }
            }
            if Set(desired.map(\.sourceId)) != present {
                failures.append("sources from \(current) to \(desired) via \(ops)")
            }
        }
        #expect(failures.isEmpty, "\(failures.prefix(3))")
    }

    @Test("stack to style drops unknown sources and converts brightness")
    func stackToStyle() throws {
        let stack = LayerStack()
            .add("osm")
            .add("ghost")
            .add("pentes", opacity: 0.5)
            .setAdjustments("l3", 0.2, -0.5, -1)
        let known: Set<String> = ["osm", "pentes"]
        let desired = StackToStyle.desired(stack) { known.contains($0) }
        #expect(desired.map(\.id) == ["l1", "l3"])
        let pentes = try #require(desired.last?.props)
        #expect(pentes.opacity == 0.5)
        #expect(pentes.contrast == 0.2)
        #expect(pentes.brightnessMin == 0)
        #expect(pentes.brightnessMax == 0.5)
        #expect(pentes.saturation == -1)
    }
}
