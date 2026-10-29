// Port of core/src/test/kotlin/fr/toolmap/core/track/EditingTest.kt.

import Testing
@testable import ToolMapCore

struct EditingTests {
    private func line(_ count: Int) -> [TrackPoint] {
        (0 ..< count).map { i in
            TrackPoint(lat: 45.0 + Double(i) * 0.001, lon: 6.0, ele: 1000.0 + Double(i), timeMillis: 1000 * Int64(i))
        }
    }

    @Test("reverse flips segments and points and drops the times")
    func reverse() {
        let a = [TrackPoint(lat: 1.0, lon: 1.0, timeMillis: 5), TrackPoint(lat: 2.0, lon: 2.0, timeMillis: 6)]
        let b = [TrackPoint(lat: 3.0, lon: 3.0, timeMillis: 7), TrackPoint(lat: 4.0, lon: 4.0, timeMillis: 8)]
        let r = TrackOps.reverse([a, b])
        #expect(r[0].map(\.lat) == [4.0, 3.0])
        #expect(r[1].map(\.lat) == [2.0, 1.0])
        #expect(r.joined().allSatisfy { $0.timeMillis == nil })
        // Reversing twice gives the points back in the original order.
        #expect(TrackOps.reverse(r).joined().map(\.lat) == [1.0, 2.0, 3.0, 4.0])
    }

    @Test("split shares the cut point and refuses the ends")
    func split() throws {
        let (first, second) = try TrackOps.split(line(5), 2)
        #expect(first.count == 3)
        #expect(second.count == 3)
        #expect(first.last == second.first)
        #expect(throws: ToolMapError.self) { try TrackOps.split(line(5), 0) }
        #expect(throws: ToolMapError.self) { try TrackOps.split(line(5), 4) }
    }

    @Test("merge keeps segments apart unless the ends meet")
    func merge() {
        let a = [line(3)]
        let far = [(0 ..< 3).map { TrackPoint(lat: 50.0 + Double($0) * 0.001, lon: 8.0) }]
        #expect(TrackOps.merge(a, far).count == 2)
        #expect(TrackOps.merge(a, far, joinWithinMeters: 50.0).count == 2)

        let next = [(0 ..< 3).map { TrackPoint(lat: 45.002 + Double($0) * 0.001, lon: 6.0) }]
        // The end of `a` is exactly the start of `next`: one segment, the shared point once.
        let joined = TrackOps.merge(a, next, joinWithinMeters: 5.0)
        #expect(joined.count == 1)
        #expect(joined.first?.count == 5)
        // 20 m apart: joined without dropping a point.
        let close = [(0 ..< 2).map { TrackPoint(lat: 45.0022 + Double($0) * 0.001, lon: 6.0) }]
        let j2 = TrackOps.merge(a, close, joinWithinMeters: 50.0)
        #expect(j2.count == 1)
        #expect(j2.first?.count == 5)
        #expect(TrackOps.merge(a, []) == a)
        #expect(TrackOps.merge([], far) == far)
    }

    @Test("trim keeps the range, ends included")
    func trim() throws {
        let t = try TrackOps.trim(line(10), 2, 5)
        #expect(t.map(\.ele) == [1002.0, 1003.0, 1004.0, 1005.0])
        #expect(throws: ToolMapError.self) { try TrackOps.trim(line(3), 2, 1) }
    }

    @Test("midpoint averages what both sides have")
    func midpoint() {
        let m = TrackOps.midpoint(
            TrackPoint(lat: 0.0, lon: 0.0, ele: 100.0, timeMillis: 0),
            TrackPoint(lat: 2.0, lon: 4.0, ele: 200.0, timeMillis: 10)
        )
        #expect(m.lat == 1.0)
        #expect(m.lon == 2.0)
        #expect(m.ele == 150.0)
        #expect(m.timeMillis == 5)
        #expect(TrackOps.midpoint(TrackPoint(lat: 0.0, lon: 0.0), TrackPoint(lat: 1.0, lon: 1.0, ele: 5.0)).ele == nil)
    }

    @Test("adding appends, then inserts after the selected vertex")
    func adding() {
        let e = PolylineEditor()
        e.add(1.0, 1.0)
        e.add(2.0, 2.0)
        e.add(3.0, 3.0)
        #expect(e.points.map(\.lat) == [1.0, 2.0, 3.0])
        #expect(e.selected == 2)
        e.select(0)
        e.add(1.5, 1.5)
        #expect(e.points.map(\.lat) == [1.0, 1.5, 2.0, 3.0])
        #expect(e.selected == 1)
    }

    @Test("move and delete act on the selected vertex")
    func moveAndDelete() {
        let e = PolylineEditor(line(4))
        #expect(!e.moveSelected(0.0, 0.0))
        #expect(!e.deleteSelected())
        e.select(2)
        e.moveSelected(10.0, 20.0)
        #expect(e.points[2] == TrackPoint(lat: 10.0, lon: 20.0))
        e.deleteSelected()
        #expect(e.points.count == 3)
        #expect(e.selected == 2)
        e.deleteSelected()
        e.deleteSelected()
        e.deleteSelected()
        #expect(e.points.isEmpty)
        #expect(e.selected == nil)
    }

    @Test("undo and redo walk the history and a new edit forgets the redo branch")
    func undoRedo() {
        let e = PolylineEditor()
        e.add(1.0, 1.0)
        e.add(2.0, 2.0)
        e.add(3.0, 3.0)
        #expect(e.undo())
        #expect(e.points.count == 2)
        #expect(e.undo())
        #expect(e.redo())
        #expect(e.points.count == 2)
        e.add(9.0, 9.0)
        #expect(!e.canRedo)
        #expect(e.points.map(\.lat) == [1.0, 2.0, 9.0])
        while e.undo() {}
        #expect(e.points.isEmpty)
        #expect(!e.undo())
    }

    @Test("selection is not an undoable edit")
    func selectionNotUndoable() {
        let e = PolylineEditor(line(3))
        e.select(1)
        #expect(!e.canUndo)
        e.add(1.0, 1.0)
        e.undo()
        #expect(e.points.count == 3)
    }

    @Test("midpoint insertion selects the new vertex")
    func midpointInsertion() {
        let e = PolylineEditor([TrackPoint(lat: 0.0, lon: 0.0), TrackPoint(lat: 2.0, lon: 2.0)])
        #expect(!e.insertMidpoint(1))
        #expect(e.insertMidpoint(0))
        #expect(e.points.count == 3)
        #expect(e.points[1].lat == 1.0)
        #expect(e.selected == 1)
    }

    @Test("nearest finds a vertex under the finger and nothing far away")
    func nearest() {
        let e = PolylineEditor(line(5))
        // 0.001 degree of latitude is about 111 m.
        #expect(e.nearest(45.002, 6.0001, 30.0) == 2)
        #expect(e.nearest(46.0, 6.0, 30.0) == nil)
        // Two candidates: the nearer one wins.
        #expect(e.nearest(45.0029, 6.0, 500.0) == 3)
    }

    @Test("freehand strokes are thinned and appended in one undoable step")
    func freehandStroke() {
        // A straight 1 km line sampled every metre (1000 points) becomes a handful.
        let stroke = (0 ..< 1000).map { TrackPoint(lat: 45.0 + Double($0) * 0.000009, lon: 6.0) }
        let e = PolylineEditor([TrackPoint(lat: 44.0, lon: 6.0)])
        #expect(e.addStroke(stroke, toleranceMeters: 3.0))
        #expect(e.points.count < 10, "kept \(e.points.count)")
        #expect(e.points.last == stroke.last)
        e.undo()
        #expect(e.points.count == 1)
        #expect(!PolylineEditor().addStroke([], toleranceMeters: 3.0))
    }

    @Test("freehand keeps real corners")
    func freehandCorners() {
        let north: [TrackPoint] = (0 ..< 50).map { TrackPoint(lat: 45.0 + Double($0) * 0.00002, lon: 6.0) }
        let east: [TrackPoint] = (0 ..< 50).map { TrackPoint(lat: 45.00098, lon: 6.0 + Double($0 + 1) * 0.00002) }
        let stroke = north + east
        let thin = Freehand.thin(stroke, 2.0)
        #expect(thin.count >= 3 && thin.count <= 6, "kept \(thin.count)")
        #expect(thin.contains { abs($0.lat - 45.00098) < 1e-6 && abs($0.lon - 6.0) < 3e-5 }, "corner kept")
    }

    @Test("reverse, simplify, trim and split through the editor")
    func editorOperations() throws {
        let e = PolylineEditor(line(6))
        e.select(1)
        e.reverse()
        #expect(e.points.first?.ele == 1005.0)
        #expect(e.selected == 4)

        e.load(line(6))
        e.select(2)
        e.trimBeforeSelected()
        #expect(e.points.count == 4)
        #expect(e.selected == 0)
        e.select(2)
        e.trimAfterSelected()
        #expect(e.points.count == 3)

        e.load(line(6))
        e.select(3)
        let second = try #require(e.splitAtSelected())
        #expect(e.points.count == 4)
        #expect(second.count == 3)
        #expect(e.points.last == second.first)
        e.select(0)
        #expect(e.splitAtSelected() == nil)

        e.load(line(3))
        #expect(e.simplify(5.0), "a straight 3-point line loses its middle point")
        #expect(e.points.count == 2)
        #expect(!e.simplify(5.0), "nothing left to drop")
    }

    @Test("history is bounded by points, not steps")
    func boundedHistory() {
        let e = PolylineEditor(line(100), maxHistoryPoints: 1000)
        for i in 0 ..< 50 {
            e.add(1.0 + Double(i), 1.0)
        }
        var undone = 0
        while e.undo() {
            undone += 1
        }
        #expect(undone >= 5 && undone <= 15, "undone \(undone)")
        // The most recent edits are always undoable even if one snapshot is bigger than the budget.
        let big = PolylineEditor(line(5000), maxHistoryPoints: 100)
        big.add(1.0, 1.0)
        #expect(big.undo())
        #expect(big.points == line(5000))
    }
}
