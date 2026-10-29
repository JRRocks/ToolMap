// Port of data/src/test/kotlin/fr/toolmap/data/tracks/TrackCreationTest.kt (Robolectric and Room in memory on
// Android; here a SQLite file in a new temporary folder).

import Foundation
import Testing
@testable import ToolMapCore

struct LibraryCreationTests {
    private func walk(_ from: Int, _ count: Int) -> [TrackPoint] {
        (0 ..< count).map { i in
            let k = from + i
            return TrackPoint(
                lat: 45.0 + Double(k) * 0.0001,
                lon: 6.0 + Double(k) * 0.00005,
                ele: 1000.0 + Double(k) * 0.5,
                timeMillis: 1_000_000 + Int64(k) * 10_000
            )
        }
    }

    private func stored(_ repo: TrackRepository, _ id: Int64) throws -> [[TrackPoint]] {
        let track = try repo.loadTrack(id)
        return try #require(track).segments
    }

    @Test("an empty track is created at the root of the library and fills up point by point")
    func emptyTrackFillsUp() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("  Sortie du matin  ", style: PathStyle(colorArgb: Int32(bitPattern: 0xFF00_FF00)))
        let entity = try LibraryTesting.single(repo.library().tracks)
        #expect(entity.name == "Sortie du matin")
        #expect(entity.folderId == nil)
        #expect(entity.format == nil)
        #expect(entity.pointCount == 0)

        try repo.appendPoints(id, walk(0, 3))
        try repo.appendPoints(id, walk(3, 2))
        let segments = try stored(repo, id)
        #expect(segments.count == 1)
        let line = try LibraryTesting.single(segments)
        #expect(line.count == 5)
        for (want, got) in zip(walk(0, 5), line) {
            #expect(abs(want.lat - got.lat) <= 1e-6)
            #expect(abs(want.lon - got.lon) <= 1e-6)
            #expect(want.timeMillis == got.timeMillis)
        }
        let e = try LibraryTesting.single(repo.library().tracks)
        #expect(e.pointCount == 5)
        #expect(e.segmentCount == 1)
        #expect(e.startTimeMillis == 1_000_000)
        #expect(e.endTimeMillis == 1_040_000)
        #expect(abs(e.minLat - 45.0) <= 1e-6)
        #expect(abs(e.maxLat - 45.0004) <= 1e-6)
        let expected = TrackStatsCalculator.compute([walk(0, 5)]).distance2dMeters
        #expect(abs(e.distanceMeters - expected) <= 0.01)
    }

    @Test("appending in small steps gives the same track as storing it whole")
    func smallSteps() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let whole = try repo.createTrack("whole", segments: [walk(0, 4500)])
        let steps = try repo.createTrack("steps")
        var k = 0
        while k < 4500 {
            let n = min(37, 4500 - k)
            try repo.appendPoints(steps, walk(k, n))
            k += n
        }
        let a = try LibraryTesting.single(stored(repo, whole))
        let b = try LibraryTesting.single(stored(repo, steps))
        #expect(a.count == b.count)
        for i in stride(from: 0, to: min(a.count, b.count), by: 250) {
            #expect(abs(a[i].lat - b[i].lat) <= 1e-6)
            #expect(a[i].timeMillis == b[i].timeMillis)
        }
        let lib = try repo.library()
        let foundA = lib.tracks.first(where: { $0.id == whole })
        let foundB = lib.tracks.first(where: { $0.id == steps })
        let ea = try #require(foundA)
        let eb = try #require(foundB)
        #expect(ea.pointCount == eb.pointCount)
        #expect(abs(ea.distanceMeters - eb.distanceMeters) <= 0.5)
        // 4500 points in chunks of 2000: three chunks, the last one holds 500.
        #expect(try repo.chunks(steps).count == 3)
    }

    @Test("a new segment starts a new chunk series and is not joined to the previous point")
    func newSegment() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("pause")
        try repo.appendPoints(id, walk(0, 3))
        try repo.appendPoints(id, walk(100, 3), newSegment: true)
        try repo.appendPoints(id, walk(103, 2))
        let sizes = try stored(repo, id).map { $0.count }
        #expect(sizes == [3, 5])
        let e = try LibraryTesting.single(repo.library().tracks)
        #expect(e.segmentCount == 2)
        #expect(e.pointCount == 8)
        let expected = TrackStatsCalculator.compute([walk(0, 3), walk(100, 5)]).distance2dMeters
        #expect(abs(e.distanceMeters - expected) <= 0.01)
    }

    @Test("the first points of a track may start with a new segment flag")
    func flaggedFirstPoints() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("flagged")
        try repo.appendPoints(id, walk(0, 2), newSegment: true)
        let sizes = try stored(repo, id).map { $0.count }
        #expect(sizes == [2])
        #expect(try LibraryTesting.single(repo.library().tracks).segmentCount == 1)
    }

    @Test("finishing recomputes the climb")
    func finishing() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("climb")
        try repo.appendPoints(id, walk(0, 60))
        #expect(try LibraryTesting.single(repo.library().tracks).ascentMeters == nil)
        try repo.finishTrack(id)
        let e = try LibraryTesting.single(repo.library().tracks)
        let ascent = try #require(e.ascentMeters)
        #expect(ascent > 20.0, "ascent \(ascent)")
    }

    @Test("appending to a missing track does nothing")
    func missingTrack() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        try repo.appendPoints(999, walk(0, 2))
        try repo.finishTrack(999)
        #expect(try repo.library().tracks.isEmpty)
    }

    @Test("a recorded track exports to GPX and reads back")
    func recordedExport() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("Enregistrée")
        try repo.appendPoints(id, walk(0, 10))
        try repo.appendPoints(id, walk(50, 10), newSegment: true)
        let doc = try repo.document(TrackSelection(trackIds: [id]))
        let back = try GpxReader.read(GpxWriter.toBytes(doc, creator: "ToolMap"))
        let track = try LibraryTesting.single(back.tracks)
        let sizes = track.segments.map { $0.count }
        #expect(sizes == [10, 10])
        #expect(track.name == "Enregistrée")
    }

    @Test("routes are created and their points replaced")
    func routes() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createRoute(
            "Itinéraire",
            [Waypoint(lat: 45.0, lon: 6.0, name: "Départ"), Waypoint(lat: 45.01, lon: 6.0)]
        )
        let before = try LibraryTesting.single(repo.library().routes)
        #expect(before.folderId == nil)
        #expect(before.pointCount == 2)
        #expect(abs(before.distanceMeters - 1112.0) <= 5.0)
        try repo.replaceRoutePoints(
            id,
            [Waypoint(lat: 45.0, lon: 6.0), Waypoint(lat: 45.01, lon: 6.0), Waypoint(lat: 45.02, lon: 6.0)]
        )
        let after = try LibraryTesting.single(repo.library().routes)
        #expect(after.pointCount == 3)
        #expect(abs(after.distanceMeters - 2224.0) <= 10.0)
        #expect(after.name == "Itinéraire")
        #expect(after.createdAt == 5_000)
        let loaded = try repo.loadRoute(id)
        let route = try #require(loaded)
        #expect(route.points.count == 3)
        #expect(abs(after.maxLat - 45.02) <= 1e-9)
    }

    @Test("waypoints are created, updated and described")
    func waypoints() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createWaypoint(Waypoint(lat: 45.5, lon: 6.5, ele: 1500.0, name: "Col", sym: "Summit"))
        #expect(try repo.loadWaypoint(id)?.name == "Col")
        try repo.setVisible(ItemRef(kind: .waypoint, id: id), false)
        try repo.updateWaypoint(
            id,
            Waypoint(lat: 45.6, lon: 6.6, ele: 1600.0, name: " Refuge ", desc: "Eau", sym: "Lodge")
        )
        let loaded = try repo.loadWaypoint(id)
        let w = try #require(loaded)
        #expect(w.name == "Refuge")
        #expect(w.lat == 45.6)
        #expect(w.sym == "Lodge")
        #expect(w.desc == "Eau")
        // Visibility is not part of what updateWaypoint rewrites.
        let entity = try LibraryTesting.single(repo.library().waypoints)
        #expect(entity.visible == false)
        try repo.describe(ItemRef(kind: .waypoint, id: id), "  Source à 50 m  ")
        #expect(try repo.loadWaypoint(id)?.desc == "Source à 50 m")
        let track = try repo.createTrack("t")
        try repo.describe(ItemRef(kind: .track, id: track), "Belle sortie")
        #expect(try repo.loadTrack(track)?.desc == "Belle sortie")
        try repo.describe(ItemRef(kind: .track, id: track), "   ")
        let described = try repo.loadTrack(track)
        let cleared = try #require(described)
        #expect(cleared.desc == nil)
    }

    @Test("the recording session file survives restarts and ignores damage")
    func sessionFile() throws {
        let file = try LibraryTesting.folder().appendingPathComponent("recording.json")
        let store = RecordingSessionStore(file)
        #expect(store.read() == nil)
        let session = RecordingSession(trackId: 7, startedAtMillis: 10, activeMillis: 2_000, paused: true, savedAtMillis: 99)
        try store.write(session)
        #expect(RecordingSessionStore(file).read() == session)
        let later = RecordingSession(trackId: 7, startedAtMillis: 10, activeMillis: 5_000, paused: true, savedAtMillis: 99)
        try store.write(later)
        #expect(store.read()?.activeMillis == 5_000)
        try Data("{ not json".utf8).write(to: file)
        #expect(store.read() == nil)
        try store.write(session)
        store.clear()
        #expect(store.read() == nil)
    }

    @Test("reversing walks the track the other way")
    func reversing() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let id = try repo.createTrack("aller", segments: [walk(0, 6)])
        try repo.reverseTrack(id)
        let back = try LibraryTesting.single(stored(repo, id))
        let first = try #require(back.first)
        let last = try #require(back.last)
        #expect(abs(first.lat - 45.0005) <= 1e-6)
        #expect(abs(last.lat - 45.0) <= 1e-6)
        let timeless = back.allSatisfy { $0.timeMillis == nil }
        #expect(timeless)
        #expect(try LibraryTesting.single(repo.library().tracks).pointCount == 6)
    }

    @Test("simplifying drops points on a straight line and leaves a bare corner alone")
    func simplifying() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let line = (0 ..< 100).map { TrackPoint(lat: 45.0 + Double($0) * 0.0001, lon: 6.0) }
        let straight = try repo.createTrack("droit", segments: [line])
        let first = try repo.simplifyTrack(straight, 2.0)
        #expect(first?.before == 100)
        #expect(first?.after == 2)
        #expect(try LibraryTesting.single(repo.library().tracks).pointCount == 2)
        let second = try repo.simplifyTrack(straight, 2.0)
        #expect(second?.after == 2)
        let missing = try repo.simplifyTrack(999, 2.0)
        #expect(missing?.before == nil)
    }

    @Test("merging builds a new track and keeps the sources")
    func merging() throws {
        let repo = try LibraryTesting.repository(now: 5_000)
        let a = try repo.createTrack("A", segments: [walk(0, 5)], style: PathStyle(colorArgb: Int32(bitPattern: 0xFF11_2233)))
        let b = try repo.createTrack("B", segments: [walk(100, 4)])
        let mergedId = try repo.mergeTracks([a, b], "A + B")
        let merged = try #require(mergedId)
        let sizes = try stored(repo, merged).map { $0.count }
        #expect(sizes == [5, 4])
        #expect(try repo.library().tracks.count == 3)
        let loaded = try repo.loadTrack(merged)
        let track = try #require(loaded)
        #expect(track.style?.colorArgb == Int32(bitPattern: 0xFF11_2233))
        let lib = try repo.library()
        let found = lib.tracks.first(where: { $0.id == merged })
        let entity = try #require(found)
        #expect(entity.pointCount == 9)
        #expect(try repo.mergeTracks([a], "seule") == nil)
        #expect(try repo.mergeTracks([a, 999], "absente") == nil)
    }
}
