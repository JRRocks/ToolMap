// Port of core/src/test/kotlin/fr/toolmap/core/p2p/ShareModelsTest.kt: saved state and map features of the group.

import Foundation
import Testing
@testable import ToolMapCore

struct ShareModelsTests {
    @Test("settings and group survive the JSON form")
    func roundTrip() throws {
        let group = GroupRecord.create()
        let state = ShareState(
            settings: ShareSettings(name: "Jean", colorIndex: 3, intervalSec: 60, minMoveMeters: 25, stun: false),
            group: group
        )
        let back = ShareState.fromJson(state.toJson())
        #expect(back.settings == state.settings)
        let restored = try #require(back.group)
        #expect(restored.groupId == group.groupId)
        #expect(restored.key == group.key)
        #expect(restored.selfId == group.selfId)
    }

    @Test("icon, wheel colour and trail choice survive, and a document of 0_4_0 keeps its palette colour")
    func iconAndColour() throws {
        let colour = Int32(bitPattern: 0xFF12_3456)
        let chosen = ShareSettings(name: "Jo", icon: 4, color: colour, showTrails: false)
        let back = ShareState.fromJson(ShareState(settings: chosen).toJson()).settings
        #expect(back == chosen)
        #expect(back.look() == MemberLook.pack(4, colour))

        let old = ShareState.fromJson("{\"settings\":{\"name\":\"Jo\",\"colorIndex\":2}}").settings
        #expect(old.icon == MemberLook.initials)
        #expect(old.showTrails)
        #expect(old.colorArgb() == MemberPalette.argb(2))

        let withPicture = ShareSettings(icon: 3).withPicture([9, 8, 7])
        let kept = ShareState.fromJson(ShareState(settings: withPicture).toJson()).settings
        #expect(kept.icon == MemberLook.picture)
        #expect(kept.pictureBytes() == [9, 8, 7])
        #expect(kept.withPicture(nil).icon == MemberLook.initials)
        #expect(kept.withPicture(nil).picture == nil)
        // A picture chosen but missing or too large falls back to the initials.
        let big = Data(repeating: 0, count: MemberLook.maxPictureBytes + 1).base64EncodedString()
        let tooBig = ShareState.fromJson("{\"settings\":{\"icon\":127,\"picture\":\"\(big)\"}}").settings
        #expect(tooBig.icon == MemberLook.initials)
        #expect(tooBig.picture == nil)

        let hostile = ShareState.fromJson("{\"settings\":{\"icon\":99,\"color\":0}}").settings
        #expect(hostile.icon == MemberLook.initials)
        #expect(hostile.colorArgb() == Int32(bitPattern: 0xFF00_0000))
    }

    @Test("a negative member id and the largest ids keep their value")
    func largeIds() throws {
        for id: Int64 in [-1, Int64.min, Int64.max, 0x7FFF_FFFF_FFFF_FFFF] {
            let group = GroupRecord([UInt8](repeating: 0, count: 16), [UInt8](repeating: 0, count: 32), id)
            let back = try #require(ShareState.fromJson(ShareState(group: group).toJson()).group)
            #expect(back.selfId == id)
        }
    }

    @Test("without a group, and with damaged or hostile documents, the defaults come back")
    func defaults() {
        #expect(ShareState.fromJson(ShareState().toJson()).group == nil)
        for bad in ["", "{", "[]", "null", "{\"group\":{\"id\":\"zz\",\"key\":\"00\",\"self\":\"1\"}}"] {
            let s = ShareState.fromJson(bad)
            #expect(s.group == nil, "\(bad)")
            #expect(s.settings == ShareSettings(), "\(bad)")
        }
        // A key of the wrong size is not accepted.
        let id = String(repeating: "00", count: 16)
        let key = String(repeating: "00", count: 5)
        let shortKey = "{\"group\":{\"id\":\"\(id)\",\"key\":\"\(key)\",\"self\":\"1\"}}"
        #expect(ShareState.fromJson(shortKey).group == nil)
    }

    @Test("values out of range are brought back into range")
    func outOfRange() {
        let name = String(repeating: "x", count: 60)
        let s = ShareState.fromJson(
            "{\"settings\":{\"name\":\"\(name)\",\"colorIndex\":-1,\"intervalSec\":1,\"minMoveMeters\":-5}}"
        ).settings
        #expect(s.name.utf16.count <= 24)
        #expect(s.colorIndex == MemberPalette.colors.count - 1)
        #expect(s.intervalSec == ShareCadence.minIntervalS)
        #expect(s.minMoveMeters == 0)
        #expect(ShareState.fromJson("{\"settings\":{\"intervalSec\":99999}}").settings.intervalSec == 1800)
        // Unknown fields from a later version are ignored.
        #expect(ShareState.fromJson("{\"future\":1,\"settings\":{\"name\":\"a\"}}").settings.name == "a")
    }

    @Test("each group gets its own id, key and member id")
    func distinctGroups() {
        let a = GroupRecord.create()
        let b = GroupRecord.create()
        #expect(a.selfId != b.selfId)
        #expect(a.groupId != b.groupId)
        #expect(a.key != b.key)
        #expect(a.selfId != 0)
        #expect(ShareSettings().cadence().intervalSec == 30)
    }
}

struct ShareGeoJsonTests {
    private let now: Int64 = 1_790_000_000_000

    private func fix(_ lat: Double, _ lon: Double, ageSec: Int64 = 0) -> PeerFix {
        PeerFix(lat, lon, timeSec: now / 1000 - ageSec)
    }

    private func member(
        _ id: Int64,
        _ name: String,
        status: MemberStatus = .direct,
        isSelf: Bool = false,
        fix: PeerFix? = nil,
        trail: [PeerFix] = [],
        color: Int32 = Int32(bitPattern: 0xFFD3_2F2F),
        sharing: Bool? = nil
    ) -> MemberView {
        MemberView(id, name, color, status, isSelf, now, fix, trail, sharing: sharing)
    }

    private func features(_ json: String) throws -> [[String: Any]] {
        let parsed = try JSONSerialization.jsonObject(with: Data(json.utf8))
        let root = try #require(parsed as? [String: Any])
        return try #require(root["features"] as? [[String: Any]])
    }

    private func properties(_ feature: [String: Any]) throws -> [String: Any] {
        try #require(feature["properties"] as? [String: Any])
    }

    private func coordinates(_ feature: [String: Any]) throws -> [Any] {
        let geometry = try #require(feature["geometry"] as? [String: Any])
        return try #require(geometry["coordinates"] as? [Any])
    }

    @Test("members with a position become points, this device and the unlocated are left out")
    func points() throws {
        let json = ShareGeoJson.members(
            [
                member(1, "Moi", isSelf: true, fix: fix(45.0, 6.0)),
                member(2, "Alice", fix: fix(45.1234567, 6.7654321)),
                member(3, "Sans position"),
            ],
            [],
            now
        )
        let list = try features(json)
        #expect(list.count == 1)
        let f = try #require(list.first)
        let props = try properties(f)
        #expect(props["mid"] as? String == "2")
        #expect(props["name"] as? String == "Alice")
        #expect(props["color"] as? String == "#D32F2F")
        #expect(props["old"] as? Bool == false)
        #expect(props["sos"] as? Bool == false)
        let c = try coordinates(f)
        let lon = try #require(c[0] as? Double)
        let lat = try #require(c[1] as? Double)
        #expect(abs(lon - 6.7654321) <= 1e-9)
        #expect(abs(lat - 45.1234567) <= 1e-9)
    }

    @Test("a member that only listens has no point nor trail, unless its alert brought a position")
    func listening() throws {
        let listening = member(2, "Alice", fix: fix(45.1, 6.1), trail: [fix(45.0, 6.0), fix(45.1, 6.1)], sharing: false)
        let sharing = member(3, "Bruno", fix: fix(45.2, 6.2), trail: [fix(45.0, 6.0), fix(45.2, 6.2)], sharing: true)
        func names(_ json: String) throws -> [String] {
            try features(json).map { feature -> String in
                let props = feature["properties"] as? [String: Any]
                return props?["name"] as? String ?? "trail"
            }
        }
        #expect(try names(ShareGeoJson.members([listening, sharing], [], now)) == ["Bruno"])
        #expect(try names(ShareGeoJson.members([listening, sharing], [2], now)) == ["Alice", "Bruno"])
        #expect(try features(ShareGeoJson.trails([listening, sharing])).count == 1)
    }

    @Test("a 64-bit id survives as text, a name with quotes stays valid JSON")
    func longIdAndQuotes() throws {
        let id = Int64.max - 5
        let json = ShareGeoJson.members([member(id, "Jo \"le Fou\"", fix: fix(1.0, 2.0))], [id], now)
        let f = try #require(try features(json).first)
        let props = try properties(f)
        #expect(props["mid"] as? String == String(id))
        #expect(props["name"] as? String == "Jo \"le Fou\"")
        #expect(props["sos"] as? Bool == true)
    }

    @Test("each member names its marker image")
    func markerImage() throws {
        let fox = try #require(MemberLook.icons.firstIndex(of: "fox_head"))
        let look = MemberLook.pack(fox, Int32(bitPattern: 0xFFAA_0000))
        let json = ShareGeoJson.members([member(1, "Jo", fix: fix(1.0, 2.0), color: look)], [], now)
        let f = try #require(try features(json).first)
        let props = try properties(f)
        #expect(props["img"] as? String == "tm-m-aa0000-fox_head")
        #expect(props["color"] as? String == "#AA0000")
    }

    @Test("lost members and old positions are faded")
    func faded() throws {
        let json = ShareGeoJson.members(
            [
                member(1, "Perdu", status: .lost, fix: fix(1.0, 1.0)),
                member(2, "Vieux", fix: fix(2.0, 2.0, ageSec: 11 * 60)),
                member(3, "Frais", fix: fix(3.0, 3.0, ageSec: 9 * 60)),
            ],
            [],
            now
        )
        let old = try features(json).map { feature -> Bool in
            let props = try properties(feature)
            return props["old"] as? Bool ?? false
        }
        #expect(old == [true, true, false])
    }

    @Test("a trail needs two points and keeps their order")
    func trailOrder() throws {
        let json = ShareGeoJson.trails([
            member(1, "Une seule", trail: [fix(1.0, 1.0)]),
            member(2, "Alice", trail: [fix(45.0, 6.0), fix(45.1, 6.1), fix(45.2, 6.2)], color: Int32(bitPattern: 0xFF19_76D2)),
            member(3, "Moi", isSelf: true, trail: [fix(1.0, 1.0), fix(2.0, 2.0)]),
        ])
        let list = try features(json)
        #expect(list.count == 1)
        let f = try #require(list.first)
        #expect(try properties(f)["color"] as? String == "#1976D2")
        let coords = try coordinates(f)
        #expect(coords.count == 3)
        let last = try #require(coords[2] as? [Double])
        #expect(abs(last[1] - 45.2) <= 1e-9)
    }

    @Test("no member gives an empty collection")
    func empty() {
        #expect(ShareGeoJson.members([], [], now) == "{\"type\":\"FeatureCollection\",\"features\":[]}")
        #expect(ShareGeoJson.trails([]) == "{\"type\":\"FeatureCollection\",\"features\":[]}")
    }
}
