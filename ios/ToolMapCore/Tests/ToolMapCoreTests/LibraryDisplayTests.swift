// Port of data/src/test/kotlin/fr/toolmap/data/tracks/TrackDisplayTest.kt (Robolectric and Room in memory on
// Android; here a SQLite file in a new temporary folder).

import Foundation
import Testing
@testable import ToolMapCore

struct LibraryDisplayTests {
    /// A winding path, so that the simplification really drops points but keeps the bends.
    private func pt(_ i: Int) -> TrackPoint {
        let k = Double(i)
        let lat = 46.0 + k * 0.00002 + sin(k / 30.0) * 0.0003
        let lon = 7.0 + k * 0.00003 + cos(k / 45.0) * 0.0003
        return TrackPoint(lat: lat, lon: lon, timeMillis: Int64(i) * 1000)
    }

    private func points(_ range: Range<Int>) -> [TrackPoint] {
        range.map { pt($0) }
    }

    private func importTrack(_ repo: TrackRepository, _ count: Int) throws -> Int64 {
        let track = Track(name: "live", segments: [points(0 ..< count)])
        try repo.import(TrackDocument(tracks: [track]), fileName: "l.gpx")
        return try LibraryTesting.single(repo.library().tracks).id
    }

    /// The simplified geometry through the same entry point as the print.
    private func lines(_ display: TrackDisplay) throws -> [[TrackPoint]] {
        _ = try display.data()
        let track = try LibraryTesting.single(display.printItems().tracks)
        return track.segments.map { segment in segment.map { TrackPoint(lat: $0.lat, lon: $0.lon) } }
    }

    @Test("a batch appended to a shown track does not simplify the whole track again")
    func appendWithoutReload() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let display = TrackDisplay(repository: repo)
        let id = try importTrack(repo, 2_000)
        _ = try display.data()
        #expect(display.fullLoads == 1)
        var next = 2_000
        for _ in 0 ..< 40 {
            try repo.appendPoints(id, points(next ..< next + 10))
            next += 10
            _ = try display.data()
        }
        #expect(display.fullLoads == 1)
    }

    @Test("the extended line follows the raw points within the tolerance")
    func extendedLine() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let display = TrackDisplay(repository: repo)
        let id = try importTrack(repo, 1_500)
        _ = try display.data()
        var next = 1_500
        for _ in 0 ..< 60 {
            try repo.appendPoints(id, points(next ..< next + 10))
            next += 10
            _ = try display.data()
        }
        let segments = try lines(display)
        #expect(segments.count == 1)
        let line = try LibraryTesting.single(segments)
        let raw = points(0 ..< next)
        let firstLine = try #require(line.first)
        let lastLine = try #require(line.last)
        let firstRaw = try #require(raw.first)
        let lastRaw = try #require(raw.last)
        #expect(abs(firstRaw.lat - firstLine.lat) <= 1e-9)
        #expect(abs(lastRaw.lat - lastLine.lat) <= 1e-9)
        #expect(line.count < raw.count / 3, "the line is thinned: \(line.count) of \(raw.count)")
        var worst = 0.0
        for p in raw {
            worst = max(worst, distanceToLine(p, line))
        }
        // The tolerance is 1 m; the tail is simplified in pieces, which can add up to twice that.
        #expect(worst <= 2.5, "worst gap to the line: \(worst) m")
        #expect(display.fullLoads == 1)
    }

    @Test("a new segment is a new line")
    func newSegmentLine() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let display = TrackDisplay(repository: repo)
        let id = try importTrack(repo, 500)
        _ = try display.data()
        try repo.appendPoints(id, points(500 ..< 600), newSegment: true)
        _ = try display.data()
        let segments = try lines(display)
        #expect(segments.count == 2)
        if segments.count == 2 {
            let second = segments[1]
            let start = try #require(second.first)
            let end = try #require(second.last)
            #expect(abs(pt(500).lat - start.lat) <= 1e-9)
            #expect(abs(pt(599).lat - end.lat) <= 1e-9)
        }
        #expect(display.fullLoads == 1)
    }

    @Test("a track that is rewritten is read again")
    func rewrittenTrack() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let display = TrackDisplay(repository: repo)
        let id = try importTrack(repo, 800)
        _ = try display.data()
        try repo.appendPoints(id, points(800 ..< 810))
        _ = try display.data()
        #expect(display.fullLoads == 1)
        try repo.reverseTrack(id)
        let segments = try lines(display)
        #expect(display.fullLoads == 2)
        let line = try LibraryTesting.single(segments)
        let start = try #require(line.first)
        let end = try #require(line.last)
        #expect(abs(pt(809).lat - start.lat) <= 1e-9)
        #expect(abs(pt(0).lat - end.lat) <= 1e-9)
    }

    @Test("appended batches are found again only while they are contiguous")
    func appendLog() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let id = try importTrack(repo, 100)
        let before = repo.pointsRevision(id)
        try repo.appendPoints(id, points(100 ..< 105))
        try repo.appendPoints(id, points(105 ..< 110))
        let found = repo.appendedSince(id, before)
        let since = try #require(found)
        #expect(since.batches.count == 2)
        #expect(since.batches.last?.endCount == 110)
        #expect(since.revision == repo.pointsRevision(id))
        let current = repo.appendedSince(id, repo.pointsRevision(id))
        #expect(current?.batches.isEmpty == true)
        try repo.reverseTrack(id)
        #expect(repo.appendedSince(id, before) == nil)
    }

    /// Distance in meters from `p` to the polyline `line`, on a flat projection (enough at this scale).
    private func distanceToLine(_ p: TrackPoint, _ line: [TrackPoint]) -> Double {
        let kx = 111_320.0 * cos(Angle.radians(p.lat))
        let ky = 110_540.0
        var best = Double.greatestFiniteMagnitude
        for i in 0 ..< max(0, line.count - 1) {
            let ax = (line[i].lon - p.lon) * kx
            let ay = (line[i].lat - p.lat) * ky
            let bx = (line[i + 1].lon - p.lon) * kx
            let by = (line[i + 1].lat - p.lat) * ky
            let dx = bx - ax
            let dy = by - ay
            let len2 = dx * dx + dy * dy
            let t = len2 == 0.0 ? 0.0 : min(max((-ax * dx - ay * dy) / len2, 0.0), 1.0)
            best = min(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }
}
