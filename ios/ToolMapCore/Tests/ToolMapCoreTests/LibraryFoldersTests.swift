// Port of data/src/test/kotlin/fr/toolmap/data/tracks/TrackFoldersTest.kt (Robolectric and Room in memory on
// Android; here a SQLite file in a new temporary folder). Folders made and filled by the user (P16.5): create, move an
// item, move a folder, and what exports keep.

import Foundation
import Testing
@testable import ToolMapCore

struct LibraryFoldersTests {
    private func track(_ name: String) -> Track {
        let line = (0 ..< 3).map { i in TrackPoint(lat: 45.0 + Double(i) * 0.001, lon: 6.0 + Double(i) * 0.001) }
        return Track(name: name, segments: [line])
    }

    /// Kotlin `assertNotNull(repo.createFolder(parent, name))`.
    private func folder(_ repo: TrackRepository, _ parent: Int64?, _ name: String) throws -> Int64 {
        let id = try repo.createFolder(parent, name)
        return try #require(id)
    }

    @Test("a folder is created at the top or inside another one, with a trimmed name")
    func createFolders() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let top = try folder(repo, nil, "  Randos  ")
        let child = try folder(repo, top, "Jour 1")
        let lib = try repo.library()
        let topFolder = try LibraryTesting.single(lib.folders.filter { $0.id == top })
        let childFolder = try LibraryTesting.single(lib.folders.filter { $0.id == child })
        #expect(topFolder.name == "Randos")
        #expect(topFolder.parentId == nil)
        #expect(childFolder.parentId == top)
        #expect(lib.pathOf(child) == "Randos / Jour 1")
    }

    @Test("a blank name or a missing parent creates nothing")
    func nothingCreated() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        #expect(try repo.createFolder(nil, "   ") == nil)
        #expect(try repo.createFolder(999, "Orphan") == nil)
        #expect(try repo.library().folders.isEmpty)
    }

    @Test("items move into a folder and back to the top of the library")
    func moveItems() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let target = try folder(repo, nil, "Randos")
        let waypoint = try repo.createWaypoint(Waypoint(lat: 45.0, lon: 6.0, name: "Col"))
        let trackId = try repo.createTrack("Boucle", segments: track("Boucle").segments)
        let ref = ItemRef(kind: .waypoint, id: waypoint)
        #expect(try repo.moveItem(ref, target))
        #expect(try repo.moveItem(ItemRef(kind: .track, id: trackId), target))
        var lib = try repo.library()
        #expect(try LibraryTesting.single(lib.waypoints).folderId == target)
        #expect(try LibraryTesting.single(lib.tracks).folderId == target)
        #expect(try repo.moveItem(ref, nil))
        lib = try repo.library()
        #expect(try LibraryTesting.single(lib.waypoints).folderId == nil)
        #expect(try LibraryTesting.single(lib.tracks).folderId == target)
    }

    @Test("moving an item to a missing folder or a missing item changes nothing")
    func moveNothing() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let waypoint = try repo.createWaypoint(Waypoint(lat: 45.0, lon: 6.0, name: "Col"))
        let toMissingFolder = try repo.moveItem(ItemRef(kind: .waypoint, id: waypoint), 999)
        #expect(!toMissingFolder)
        let missingItem = try repo.moveItem(ItemRef(kind: .waypoint, id: 999), nil)
        #expect(!missingItem)
        #expect(try LibraryTesting.single(repo.library().waypoints).folderId == nil)
    }

    @Test("a folder moves with what it holds but never inside itself")
    func moveFolder() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let a = try folder(repo, nil, "A")
        let b = try folder(repo, a, "B")
        let c = try folder(repo, b, "C")
        let other = try folder(repo, nil, "Autre")
        try repo.createWaypoint(Waypoint(lat: 45.0, lon: 6.0, name: "Col"), folderId: c)
        let intoItself = try repo.moveFolder(a, a)
        #expect(!intoItself, "inside itself")
        let intoChild = try repo.moveFolder(a, c)
        #expect(!intoChild, "inside a sub-folder")
        #expect(try repo.moveFolder(b, other))
        let lib = try repo.library()
        let movedB = try LibraryTesting.single(lib.folders.filter { $0.id == b })
        #expect(movedB.parentId == other)
        #expect(lib.pathOf(c) == "Autre / B / C")
        #expect(try LibraryTesting.single(lib.waypoints).folderId == c)
        #expect(try repo.moveFolder(b, nil))
        let moved = try LibraryTesting.single(repo.library().folders.filter { $0.id == b })
        #expect(moved.parentId == nil)
    }

    @Test("an item taken out of an import keeps the prefixes of its raw xml")
    func prefixesFollow() throws {
        let repo = try LibraryTesting.repository(now: 1_000)
        let garmin = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
        let hr = RawXml("<gpxtpx:hr>120</gpxtpx:hr>")
        let point = TrackPoint(lat: 45.0, lon: 6.0, extras: [hr])
        let next = TrackPoint(lat: 45.1, lon: 6.0, extras: [hr])
        let doc = TrackDocument(
            name: "Sortie",
            tracks: [Track(name: "Avec fc", segments: [[point, next]])],
            namespaces: ["gpxtpx": garmin]
        )
        let imported = try repo.import(doc, fileName: nil)
        let trackId = try LibraryTesting.single(repo.library().tracks).id
        let target = try folder(repo, nil, "Mes traces")
        #expect(try repo.moveItem(ItemRef(kind: .track, id: trackId), target))
        let lib = try repo.library()
        let targetFolder = try LibraryTesting.single(lib.folders.filter { $0.id == target })
        let namespaces = try TrackJson.decodeNamespaces(targetFolder.namespacesJson)
        #expect(namespaces["gpxtpx"] == garmin)
        // And at the top of the library, the export still declares the prefix on the fragment.
        #expect(try repo.moveItem(ItemRef(kind: .track, id: trackId), nil))
        let exported = try repo.document(TrackSelection(trackIds: [trackId]))
        let exportedTrack = try LibraryTesting.single(exported.tracks)
        let firstPoint = try #require(exportedTrack.segments.first?.first)
        let xml = try LibraryTesting.single(firstPoint.extras).xml
        #expect(xml.contains("xmlns:gpxtpx="), "\(xml)")
        #expect(imported.folderId > 0)
    }
}
