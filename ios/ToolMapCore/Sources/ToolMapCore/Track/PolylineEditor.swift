// Port of core/src/main/kotlin/fr/toolmap/core/track/PolylineEditor.kt.

/// What the editor shows: the line and the vertex the user picked, if any.
public struct EditorState: Equatable, Hashable, Sendable {
    public let points: [TrackPoint]
    public let selected: Int?

    public init(_ points: [TrackPoint] = [], _ selected: Int? = nil) {
        self.points = points
        self.selected = selected
    }
}

/// The model behind drawing and editing a line on the map: add, move, insert, delete, freehand, with unlimited-feeling
/// undo and redo. Every operation returns whether it changed anything. Not thread-safe: used from the UI thread.
///
/// History keeps whole snapshots; it is limited by the total number of points kept, not by the number of steps, so a
/// 50 000-point track still allows a few dozen undos without eating the memory.
public final class PolylineEditor {
    public static let defaultHistoryPoints = 400_000

    private let maxHistoryPoints: Int
    private var undoStack: [EditorState] = []
    private var redoStack: [EditorState] = []
    private var historyPoints = 0

    public private(set) var state: EditorState

    public init(_ initial: [TrackPoint] = [], maxHistoryPoints: Int = PolylineEditor.defaultHistoryPoints) {
        state = EditorState(initial)
        self.maxHistoryPoints = maxHistoryPoints
    }

    public var points: [TrackPoint] { state.points }
    public var selected: Int? { state.selected }
    public var canUndo: Bool { !undoStack.isEmpty }
    public var canRedo: Bool { !redoStack.isEmpty }

    /// Adds a vertex: after the selected one when there is one, else at the end. The new vertex becomes selected.
    @discardableResult
    public func add(_ lat: Double, _ lon: Double) -> Bool {
        let at = selected.map { $0 + 1 } ?? points.count
        var next = points
        next.insert(TrackPoint(lat: lat, lon: lon), at: at)
        return change(EditorState(next, at))
    }

    /// Puts the selected vertex somewhere else (its elevation and time are dropped: they belonged to the old place).
    @discardableResult
    public func moveSelected(_ lat: Double, _ lon: Double) -> Bool {
        guard let i = selected else { return false }
        var next = points
        next[i] = TrackPoint(lat: lat, lon: lon)
        return change(EditorState(next, state.selected))
    }

    @discardableResult
    public func deleteSelected() -> Bool {
        guard let i = selected else { return false }
        var next = points
        next.remove(at: i)
        return change(EditorState(next, next.isEmpty ? nil : Swift.min(i, next.count - 1)))
    }

    /// Selection is not an edit: it is not undoable on its own.
    public func select(_ index: Int?) {
        let valid = index.flatMap { $0 >= 0 && $0 < points.count ? $0 : nil }
        state = EditorState(state.points, valid)
    }

    /// Inserts a vertex halfway along segment `index` (between vertex `index` and the next) and selects it.
    @discardableResult
    public func insertMidpoint(_ index: Int) -> Bool {
        if index < 0 || index >= points.count - 1 { return false }
        let mid = TrackOps.midpoint(points[index], points[index + 1])
        var next = points
        next.insert(mid, at: index + 1)
        return change(EditorState(next, index + 1))
    }

    /// Index of the vertex nearest to a position if it is within `maxMeters`, else nil.
    public func nearest(_ lat: Double, _ lon: Double, _ maxMeters: Double) -> Int? {
        var best: Int?
        var bestDistance = maxMeters
        for i in points.indices {
            let d = Geodesy.distance(lat, lon, points[i].lat, points[i].lon)
            if d <= bestDistance {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /// Appends a hand-drawn stroke, thinned to `toleranceMeters` (see `Freehand`).
    @discardableResult
    public func addStroke(_ stroke: [TrackPoint], toleranceMeters: Double) -> Bool {
        let thinned = Freehand.thin(stroke, toleranceMeters)
        if thinned.isEmpty { return false }
        return change(EditorState(points + thinned, points.count + thinned.count - 1))
    }

    @discardableResult
    public func reverse() -> Bool {
        if points.count < 2 { return false }
        let reversed = TrackOps.reverse([points]).first ?? []
        return change(EditorState(reversed, selected.map { points.count - 1 - $0 }))
    }

    @discardableResult
    public func simplify(_ toleranceMeters: Double) -> Bool {
        let next = TrackOps.simplify(points, toleranceMeters)
        if next.count == points.count { return false }
        return change(EditorState(next, nil))
    }

    /// Drops everything before the selected vertex.
    @discardableResult
    public func trimBeforeSelected() -> Bool {
        guard let i = selected, i != 0 else { return false }
        guard let next = try? TrackOps.trim(points, i, points.count - 1) else { return false }
        return change(EditorState(next, 0))
    }

    /// Drops everything after the selected vertex.
    @discardableResult
    public func trimAfterSelected() -> Bool {
        guard let i = selected, i != points.count - 1 else { return false }
        guard let next = try? TrackOps.trim(points, 0, i) else { return false }
        return change(EditorState(next, i))
    }

    /// Keeps the first half and returns the second (they share the selected vertex), for the caller to store as a new
    /// track. nil when nothing valid is selected.
    public func splitAtSelected() -> [TrackPoint]? {
        guard let i = selected, i >= 1, i < points.count - 1 else { return nil }
        guard let parts = try? TrackOps.split(points, i) else { return nil }
        change(EditorState(parts.first, i))
        return parts.second
    }

    @discardableResult
    public func clear() -> Bool {
        points.isEmpty ? false : change(EditorState())
    }

    @discardableResult
    public func undo() -> Bool {
        guard let previous = undoStack.popLast() else { return false }
        historyPoints -= previous.points.count
        redoStack.append(state)
        state = previous
        return true
    }

    @discardableResult
    public func redo() -> Bool {
        guard let next = redoStack.popLast() else { return false }
        undoStack.append(state)
        historyPoints += state.points.count
        state = next
        return true
    }

    /// Replaces the whole line (loading a track into the editor); clears the history.
    public func load(_ points: [TrackPoint]) {
        undoStack.removeAll()
        redoStack.removeAll()
        historyPoints = 0
        state = EditorState(points)
    }

    @discardableResult
    private func change(_ next: EditorState) -> Bool {
        undoStack.append(state)
        historyPoints += state.points.count
        redoStack.removeAll()
        while undoStack.count > 1, historyPoints > maxHistoryPoints {
            historyPoints -= undoStack.removeFirst().points.count
        }
        state = next
        return true
    }
}

/// Turns the raw positions of a finger dragged over the map into a usable line.
public enum Freehand {
    /// Drops positions closer than half the tolerance to the last kept one (a finger resting on the glass), then thins
    /// the rest with Douglas-Peucker. The first and last positions are always kept.
    public static func thin(_ stroke: [TrackPoint], _ toleranceMeters: Double) -> [TrackPoint] {
        guard stroke.count >= 2, let first = stroke.first, let last = stroke.last else { return stroke }
        var kept: [TrackPoint] = [first]
        if stroke.count > 2 {
            for i in 1 ..< stroke.count - 1 where Geodesy.distance(kept[kept.count - 1], stroke[i]) >= toleranceMeters / 2 {
                kept.append(stroke[i])
            }
        }
        kept.append(last)
        return PathSimplifier.simplify(kept, toleranceMeters)
    }
}
