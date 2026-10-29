// Port of data/src/test/kotlin/fr/toolmap/data/tracks/TrackRepositoryTest.kt (Robolectric and an in-memory Room
// database on Android; here a SQLite file in a new temporary folder), plus the checks of the schema shared with Room.

import Foundation
import Testing
@testable import ToolMapCore

/// What the library tests share: a repository on a new file, Kotlin's `single()` and `assertSamePoints`.
enum LibraryTesting {
    static func folder() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("toolmap-library-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// A library in a new file, whose clock always says `now`.
    static func repository(now: Int64) throws -> TrackRepository {
        let path = try folder().appendingPathComponent(TrackDatabase.fileName).path
        return try TrackRepository(path: path, now: { now })
    }

    /// Kotlin `single()`: the one element of `items`.
    static func single<T>(_ items: [T]) throws -> T {
        #expect(items.count == 1)
        return try #require(items.first)
    }

    /// Kotlin `assertSamePoints`: what the database keeps of a point is within its rounding of the original.
    static func expectSamePoints(_ expected: [TrackPoint], _ actual: [TrackPoint]) {
        #expect(expected.count == actual.count)
        for (a, b) in zip(expected, actual) {
            #expect(abs(a.lat - b.lat) < 1e-6 && abs(a.lon - b.lon) < 1e-6, "position \(a) vs \(b)")
            #expect((a.ele == nil) == (b.ele == nil))
            if let ea = a.ele, let eb = b.ele {
                #expect(abs(ea - eb) < 1e-2, "elevation \(a) vs \(b)")
            }
            #expect(a.timeMillis == b.timeMillis)
            #expect(a.extras == b.extras)
        }
    }
}

struct LibraryRepositoryTests {
    private func synthetic(_ points: Int, name: String = "synthetic", withTime: Bool = true) -> Track {
        let line = (0 ..< points).map { i in
            TrackPoint(
                lat: 45.0 + Double(i) * 0.0001,
                lon: 6.0 + Double(i) * 0.0001,
                ele: 1000.0 + Double(i % 50),
                timeMillis: withTime ? 1_700_000_000_000 + Int64(i) * 1000 : nil
            )
        }
        return Track(name: name, segments: [line])
    }

    private func kml() throws -> TrackDocument {
        try KmlReader.read(Fixtures.data("kml/folders-styles-gxtrack.kml"))
    }

    @Test("real GPX files survive import, storage and export")
    func realFilesRoundTrip() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        for file in ["Mojstrovka", "cerknicko-jezero", "korita-zbevnica", "garmin-connect-run"] {
            let source = try GpxReader.read(Fixtures.data("gpx/\(file).gpx"))
            let summary = try repo.import(source, fileName: "\(file).gpx")
            #expect(summary.tracks == source.tracks.count, "\(file)")
            #expect(summary.waypoints == source.waypoints.count, "\(file)")
            #expect(summary.routes == source.routes.count, "\(file)")

            let exported = try repo.document(TrackSelection(folderIds: [summary.folderId]))
            // The export is a real GPX file: read it back to prove it.
            let again = try GpxReader.read(GpxWriter.toBytes(exported, creator: "ToolMap"))
            #expect(again.tracks.count == source.tracks.count, "\(file)")
            let sourceNames = source.waypoints.map { $0.name }
            let againNames = again.waypoints.map { $0.name }
            #expect(sourceNames == againNames, "\(file)")
            for (a, b) in zip(source.tracks, again.tracks) {
                #expect(a.name == b.name, "\(file)")
                #expect(a.segments.count == b.segments.count, "\(file)")
                for (x, y) in zip(a.segments, b.segments) {
                    LibraryTesting.expectSamePoints(x, y)
                }
                #expect(a.extras == b.extras, "\(file)")
            }
        }
    }

    @Test("statistics are computed once and cached on the track")
    func cachedStatistics() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let source = try GpxReader.read(Fixtures.data("gpx/Mojstrovka.gpx"))
        try repo.import(source, fileName: "Mojstrovka.gpx")
        let entity = try LibraryTesting.single(repo.library().tracks)
        let sourceTrack = try LibraryTesting.single(source.tracks)
        let expected = TrackStatsCalculator.compute(sourceTrack.segments)
        #expect(entity.pointCount == expected.pointCount)
        #expect(abs(entity.distanceMeters - expected.distance2dMeters) <= 1.0)
        let ascent = try #require(entity.ascentMeters)
        #expect(abs(ascent - expected.ascentMeters) <= 1.0)
        #expect(entity.minLat < entity.maxLat && entity.minLon < entity.maxLon)
        #expect(entity.startTimeMillis == expected.startTimeMillis)
    }

    @Test("long tracks are split in chunks and read back whole")
    func chunks() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let track = synthetic(2 * TrackRepository.chunkPoints + 1)
        let summary = try repo.import(TrackDocument(tracks: [track]), fileName: "long.gpx")
        let id = try LibraryTesting.single(repo.library().tracks).id
        #expect(try repo.chunks(id).count == 3)
        #expect(summary.points == track.pointCount)
        let loaded = try repo.loadTrack(id)
        let back = try #require(loaded)
        let expected = try LibraryTesting.single(track.segments)
        let actual = try LibraryTesting.single(back.segments)
        LibraryTesting.expectSamePoints(expected, actual)
    }

    @Test("a track without elevation has no climb figures")
    func noElevation() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let t = synthetic(10)
        let flat = Track(
            name: t.name,
            segments: t.segments.map { s in s.map { TrackPoint(lat: $0.lat, lon: $0.lon, timeMillis: $0.timeMillis) } }
        )
        try repo.import(TrackDocument(tracks: [flat]), fileName: "flat.gpx")
        let entity = try LibraryTesting.single(repo.library().tracks)
        #expect(entity.ascentMeters == nil)
        #expect(entity.descentMeters == nil)
    }

    @Test("an import makes one folder named after the document or the file")
    func importFolderName() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        try repo.import(TrackDocument(name: "Tour du Mont Blanc", tracks: [synthetic(5)]), fileName: "x.gpx")
        try repo.import(TrackDocument(tracks: [synthetic(5)]), fileName: "week-end.gpx")
        try repo.import(TrackDocument(tracks: [synthetic(5)]), fileName: nil)
        let folders = try repo.library().folders
        let names = Set(folders.map { $0.name })
        let expected: Set<String> = ["Tour du Mont Blanc", "week-end", TrackRepository.defaultFolderName]
        #expect(names == expected)
    }

    @Test("tracks of one import get different colours")
    func importColours() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let doc = TrackDocument(tracks: [synthetic(5, name: "a"), synthetic(5, name: "b"), synthetic(5, name: "c")])
        try repo.import(doc, fileName: "colours.gpx")
        let colours = try repo.library().tracks.map { $0.colorArgb }
        #expect(Set(colours).count == 3)
        let inPalette = colours.allSatisfy { PathStyle.palette.contains($0) }
        #expect(inPalette)
    }

    @Test("KML folders become nested folders and export to one flat file")
    func kmlFolders() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let source = try kml()
        let summary = try repo.import(source, fileName: "sortie.kml")
        let lib = try repo.library()
        let root = try LibraryTesting.single(lib.folders.filter { $0.id == summary.folderId })
        #expect(root.name == "Sortie test")
        let day = try LibraryTesting.single(lib.folders.filter { $0.name == "Jour 1" })
        let points = try LibraryTesting.single(lib.folders.filter { $0.name == "Points" })
        #expect(day.parentId == root.id)
        #expect(points.parentId == day.id)
        #expect(lib.pathOf(points.id) == "Sortie test / Jour 1 / Points")
        let waypoint = try LibraryTesting.single(lib.waypoints)
        #expect(waypoint.folderId == points.id)
        let climb = try LibraryTesting.single(lib.tracks.filter { $0.name == "Montée" })
        #expect(climb.folderId == day.id)
        let recording = try LibraryTesting.single(lib.tracks.filter { $0.name == "Enregistrement GPS" })
        #expect(recording.folderId == root.id)
        // The style the file gave is kept.
        #expect(climb.colorArgb == Int32(bitPattern: 0xFFFF_0000))

        let exported = try repo.document(TrackSelection(folderIds: [summary.folderId]))
        #expect(exported.tracks.count == source.tracks.count)
        #expect(exported.waypoints.count == source.waypoints.count)
        #expect(exported.name == "Sortie test")
    }

    @Test("hiding a folder hides what is in it at any depth")
    func hiddenFolder() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        try repo.import(kml(), fileName: "sortie.kml")
        var lib = try repo.library()
        let day = try LibraryTesting.single(lib.folders.filter { $0.name == "Jour 1" })
        let refuge = try LibraryTesting.single(lib.waypoints)
        #expect(lib.isShown(refuge.folderId, refuge.visible))
        try repo.setFolderVisible(day.id, false)
        lib = try repo.library()
        #expect(!lib.isShown(refuge.folderId, refuge.visible))
        let recording = try LibraryTesting.single(lib.tracks.filter { $0.name == "Enregistrement GPS" })
        #expect(lib.isShown(recording.folderId, recording.visible))
        // An item hidden on its own stays hidden when the folder comes back.
        try repo.setVisible(ItemRef(kind: .waypoint, id: refuge.id), false)
        try repo.setFolderVisible(day.id, true)
        lib = try repo.library()
        let hidden = try LibraryTesting.single(lib.waypoints)
        #expect(!lib.isShown(refuge.folderId, hidden.visible))
    }

    @Test("deleting a folder deletes its contents and their points")
    func deleteFolder() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        try repo.import(kml(), fileName: "sortie.kml")
        let other = try repo.import(TrackDocument(tracks: [synthetic(5, name: "kept")]), fileName: "other.gpx")
        let lib = try repo.library()
        let day = try LibraryTesting.single(lib.folders.filter { $0.name == "Jour 1" })
        try repo.deleteFolder(day.id)
        let after = try repo.library()
        let names = Set(after.tracks.map { $0.name })
        let expected: Set<String?> = ["Enregistrement GPS", "kept"]
        #expect(names == expected)
        #expect(after.waypoints.isEmpty)
        let kept = after.tracks.filter { $0.folderId == other.folderId }
        #expect(kept.count == 1)
        // Chunks of the deleted tracks went with them.
        let deleted = try LibraryTesting.single(lib.tracks.filter { $0.name == "Montée" })
        #expect(try repo.chunks(deleted.id).isEmpty)
    }

    @Test("rename, restyle and delete an item")
    func renameRestyleDelete() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        try repo.import(TrackDocument(tracks: [synthetic(5, name: "old")]), fileName: "t.gpx")
        let created = try LibraryTesting.single(repo.library().tracks)
        let ref = ItemRef(kind: .track, id: created.id)
        try repo.rename(ref, "  new name ")
        try repo.setStyle(ref, PathStyle(colorArgb: Int32(bitPattern: 0xFF12_3456), widthDp: 7, dashed: true, arrows: true))
        let e = try LibraryTesting.single(repo.library().tracks)
        #expect(e.name == "new name")
        #expect(e.colorArgb == Int32(bitPattern: 0xFF12_3456))
        #expect(e.widthDp == 7)
        #expect(e.dashed && e.arrows)
        try repo.delete(ref)
        #expect(try repo.library().tracks.isEmpty)
    }

    @Test("routes and waypoints are stored and exported")
    func routesAndWaypoints() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let source = try GpxReader.read(Fixtures.data("gpx/cerknicko-jezero.gpx"))
        let summary = try repo.import(source, fileName: "c.gpx")
        let lib = try repo.library()
        #expect(lib.waypoints.count == source.waypoints.count)
        let w = try #require(lib.waypoints.first)
        let found = try repo.loadWaypoint(w.id)
        let loaded = try #require(found)
        #expect(loaded.name == w.name)
        #expect(summary.waypoints == source.waypoints.count)
    }

    @Test("exporting imports that use one prefix for two namespaces keeps both valid")
    func prefixClash() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        func doc(_ uri: String) -> TrackDocument {
            let ext = RawXml("<x:hr>90</x:hr>")
            let p = TrackPoint(lat: 45.0, lon: 6.0, extras: [ext])
            let q = TrackPoint(lat: 45.001, lon: 6.001, extras: [ext])
            return TrackDocument(tracks: [Track(name: uri, segments: [[p, q]])], namespaces: ["x": uri])
        }
        let a = try repo.import(doc("urn:a"), fileName: "a.gpx")
        let b = try repo.import(doc("urn:b"), fileName: "b.gpx")
        let merged = try repo.document(TrackSelection(folderIds: [a.folderId, b.folderId]))
        let text = GpxWriter.toString(merged, creator: "ToolMap")
        let again = try GpxReader.read(Data(text.utf8))
        #expect(again.tracks.count == 2)
        #expect(text.contains("urn:a"))
        #expect(text.contains("urn:b"))
    }

    @Test("the map layer shows visible items only and keeps its work between changes")
    func mapLayer() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let source = try kml()
        try repo.import(source, fileName: "sortie.kml")
        try repo.import(TrackDocument(tracks: [synthetic(300, name: "long")]), fileName: "long.gpx")
        let display = TrackDisplay(repository: repo)
        let all = try display.data()
        #expect(all.lineCount == source.tracks.count + 1)
        #expect(all.pointCount == 1)
        #expect(all.linesGeoJson.contains("\"type\":\"FeatureCollection\""))

        let lib = try repo.library()
        let long = try LibraryTesting.single(lib.tracks.filter { $0.name == "long" })
        let points = try LibraryTesting.single(lib.folders.filter { $0.name == "Points" })
        try repo.setVisible(ItemRef(kind: .track, id: long.id), false)
        try repo.setFolderVisible(points.id, false)
        let some = try display.data()
        #expect(some.lineCount == source.tracks.count)
        #expect(some.pointCount == 0)
        #expect(some.linesGeoJson.count < all.linesGeoJson.count)
    }

    @Test("the displayed line follows a track that grows or changes")
    func displayFollows() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        try repo.import(TrackDocument(tracks: [synthetic(50, name: "live")]), fileName: "live.gpx")
        let id = try LibraryTesting.single(repo.library().tracks).id
        let display = TrackDisplay(repository: repo)
        let before = try display.data().linesGeoJson

        // A recording adds points at the end.
        let added = (0 ..< 50).map { i in TrackPoint(lat: 46.0 + Double(i) * 0.0001, lon: 7.0 + Double(i) * 0.0001) }
        try repo.appendPoints(id, added)
        let grown = try display.data().linesGeoJson
        #expect(grown != before, "the line did not grow")

        // An edit rewrites the points.
        try repo.reverseTrack(id)
        let reversed = try display.data().linesGeoJson
        #expect(reversed != grown, "the reversed line is still the old one")
    }

    @Test("the points of interest of a route go out as waypoints too, and come back in the route only")
    func routePois() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let a = RouteStop(lat: 45.0, lon: 6.0)
        let b = RouteStop(lat: 45.01, lon: 6.0)
        let plan = RoutePlan(
            stops: [a, b],
            legs: [RouteLeg.straight(a, b, RoutingProfiles.hiking, mode: .routed, state: .ok)],
            pois: [RoutePoi(lat: 45.005, lon: 6.001, name: "Source", sym: "Spring")]
        )
        let route = Route(
            name: "Tour",
            points: RoutePlanGpx.routePoints(plan),
            extras: RoutePlanGpx.attach([], plan)
        )
        let first = try repo.import(TrackDocument(routes: [route]), fileName: "tour.gpx")
        let exported = try repo.document(TrackSelection(folderIds: [first.folderId]))
        let names = exported.waypoints.map { $0.name }
        let expected: [String?] = ["Source"]
        #expect(names == expected)
        let bytes = GpxWriter.toBytes(exported, creator: "ToolMap")
        // Imported again: the route keeps its point of interest, which is not a waypoint of the library as well.
        let again = try repo.import(GpxReader.read(bytes), fileName: "tour.gpx")
        #expect(again.waypoints == 0)
        #expect(try repo.library().waypoints.isEmpty)
    }

    // MARK: - The file shared with Room (not in the Kotlin test: Room checks its own schema on Android)

    @Test("the file is a Room database of version 1 and opens again")
    func roomFile() throws {
        let path = try LibraryTesting.folder().appendingPathComponent(TrackDatabase.fileName).path
        let repo = try TrackRepository(path: path, now: { 1_000 })
        try repo.createWaypoint(Waypoint(lat: 45.0, lon: 6.0, name: "Col"))
        repo.close()

        let db = try SQLiteDatabase(path: path)
        let version = try db.query("PRAGMA user_version").first?.first?.int64
        #expect(version == 1)
        let hash = try db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").first?.first?.string
        #expect(hash == "edd986eec0fa9bb18756aad5db9e14a6")
        let tables = try db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").compactMap { $0.first?.string }
        #expect(tables.contains("folders") && tables.contains("tracks") && tables.contains("track_chunks"))
        #expect(tables.contains("routes") && tables.contains("waypoints"))
        db.close()

        let again = try TrackRepository(path: path, now: { 2_000 })
        let names = try again.library().waypoints.map { $0.name }
        let expected: [String?] = ["Col"]
        #expect(names == expected)
    }

    @Test("every column of every table reads back what was written")
    func columnsRoundTrip() throws {
        let path = try LibraryTesting.folder().appendingPathComponent(TrackDatabase.fileName).path
        let db = try SQLiteDatabase(path: path)
        let repo = try TrackRepository(database: db, now: { 1_000 })

        var folder = FolderEntity(name: "Dossier", visible: false, createdAt: 5, namespacesJson: "{\"x\":\"urn:x\"}")
        try db.run(FolderEntity.insertSql, Array(folder.columnValues.dropFirst()))
        folder.id = db.lastInsertRowId
        var track = TrackEntity(
            folderId: folder.id, name: "n", cmt: "c", desc: "d", src: "s", number: 4, type: "t", linksJson: "[]",
            extrasJson: "[\"<a/>\"]", colorArgb: Int32(bitPattern: 0xFF12_3456), widthDp: 3.5, dashed: true,
            arrows: true, visible: false, createdAt: 99, format: "GPX", pointCount: 10, segmentCount: 2, minLat: 1.5,
            minLon: 2.5, maxLat: 3.5, maxLon: 4.5, distanceMeters: 123.25, ascentMeters: 10.5, descentMeters: 11.5,
            startTimeMillis: 1000, endTimeMillis: 2000
        )
        try db.run(TrackEntity.insertSql, Array(track.columnValues.dropFirst()))
        track.id = db.lastInsertRowId
        var route = RouteEntity(
            folderId: folder.id, name: "r", cmt: "c", desc: "d", src: "s", number: 1, type: "t", linksJson: "[]",
            extrasJson: "[]", colorArgb: -5, widthDp: 2.25, dashed: true, arrows: false, visible: true, createdAt: 7,
            format: "KML", pointCount: 2, minLat: 1, minLon: 2, maxLat: 3, maxLon: 4, distanceMeters: 5.5,
            pointsJson: "[{\"lat\":1.0,\"lon\":2.0}]"
        )
        try db.run(RouteEntity.insertSql, Array(route.columnValues.dropFirst()))
        route.id = db.lastInsertRowId
        var waypoint = WaypointEntity(
            folderId: folder.id, name: "w", cmt: "c", desc: "d", src: "s", lat: 45.5, lon: 6.25, ele: 1234.5,
            timeMillis: 1_700_000_000_000, sym: "Summit", type: "t", linksJson: "[]", extrasJson: "[]", visible: false,
            createdAt: 8, format: "GPX"
        )
        try db.run(WaypointEntity.insertSql, Array(waypoint.columnValues.dropFirst()))
        waypoint.id = db.lastInsertRowId
        let bare = WaypointEntity(lat: 1, lon: 2, createdAt: 3)
        try db.run(WaypointEntity.insertSql, Array(bare.columnValues.dropFirst()))
        let bareId = db.lastInsertRowId

        let lib = try repo.library()
        #expect(lib.folders == [folder])
        #expect(lib.tracks == [track])
        #expect(lib.routes == [route])
        let full = lib.waypoints.first(where: { $0.id == waypoint.id })
        let stored = try #require(full)
        #expect(stored == waypoint)
        let found = lib.waypoints.first(where: { $0.id == bareId })
        let storedBare = try #require(found)
        var expectedBare = bare
        expectedBare.id = bareId
        #expect(storedBare == expectedBare)

        let chunk = TrackChunkEntity(trackId: track.id, segment: 1, chunk: 2, pointCount: 3, data: Data([1, 2, 3]))
        try db.run(TrackChunkEntity.insertSql, chunk.columnValues)
        #expect(try repo.chunks(track.id) == [chunk])
        #expect(TrackEntity.columns.count == track.columnValues.count)
        #expect(RouteEntity.columns.count == route.columnValues.count)
        #expect(WaypointEntity.columns.count == waypoint.columnValues.count)
        #expect(FolderEntity.columns.count == folder.columnValues.count)
    }
}
