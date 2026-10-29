// Port of core/src/test/kotlin/fr/toolmap/core/p2p/MemberLookTest.kt.

import Testing
@testable import ToolMapCore

struct MemberLookTests {
    private let blue = Int32(bitPattern: 0xFF33_66CC)
    private let green = Int32(bitPattern: 0xFF00_FF00)

    @Test("icon and colour come back from the packed number")
    func packed() {
        for icon in MemberLook.icons.indices {
            let look = MemberLook.pack(icon, blue)
            #expect(MemberLook.icon(look) == icon)
            #expect(MemberLook.argb(look) == blue)
        }
    }

    @Test("an ARGB colour of version 0_4_0 or an unknown icon means the initials")
    func legacy() {
        let red = Int32(bitPattern: 0xFFD3_2F2F)
        #expect(MemberLook.icon(red) == MemberLook.initials)
        #expect(MemberLook.argb(red) == red)
        #expect(MemberLook.icon(MemberLook.pack(200, 0)) == MemberLook.initials)
        #expect(MemberLook.icon(MemberLook.pack(-1, 0)) == MemberLook.initials)
    }

    @Test("the map image is shared by icon and colour, and per name for initials")
    func imageKeys() throws {
        let star = try #require(MemberLook.icons.firstIndex(of: "ghost"))
        let a = MemberLook.imageKey(MemberLook.pack(star, green), "Anne")
        #expect(a == MemberLook.imageKey(MemberLook.pack(star, green), "Bruno"))
        #expect(a != MemberLook.imageKey(MemberLook.pack(star, Int32(bitPattern: 0xFF00_00FF)), "Anne"))
        let initials = MemberLook.pack(MemberLook.initials, green)
        #expect(MemberLook.imageKey(initials, "Anne") != MemberLook.imageKey(initials, "Bruno"))
        #expect(a == "tm-m-00ff00-ghost")
    }

    @Test("at most 15 tokens, all different, and the picture has its own id")
    func tokens() {
        #expect(MemberLook.icons.count <= 15)
        #expect(MemberLook.icons[MemberLook.initials] == "initials")
        #expect(MemberLook.icons.count == Set(MemberLook.icons).count)
        #expect(!MemberLook.icons.indices.contains(MemberLook.picture))
        #expect(MemberLook.icon(MemberLook.pack(MemberLook.picture, 0)) == MemberLook.picture)
    }

    @Test("a picture names its image by content, and stands in with initials until it arrives")
    func pictureKey() throws {
        let look = MemberLook.pack(MemberLook.picture, green)
        let one = MemberLook.imageKey(look, "Anne", [1, 2, 3])
        #expect(one.hasPrefix("tm-p-"))
        #expect(one != MemberLook.imageKey(look, "Anne", [1, 2, 4]))
        #expect(MemberLook.imageKey(look, "Anne") == "tm-m-00ff00-AN")
        // A picture sent with another icon chosen is ignored.
        let ghost = try #require(MemberLook.icons.firstIndex(of: "ghost"))
        #expect(MemberLook.imageKey(MemberLook.pack(ghost, green), "A", [1]) == "tm-m-00ff00-ghost")
    }

    @Test("a silhouette sits on white when dark and on near-black when light")
    func backdrop() {
        #expect(MemberLook.backdrop(Int32(bitPattern: 0xFF19_76D2)) == Int32(bitPattern: 0xFFFF_FFFF))
        #expect(MemberLook.backdrop(Int32(bitPattern: 0xFFFF_EB3B)) == Int32(bitPattern: 0xFF20_2124))
    }

    @Test("ink is white on dark colours and black on light ones")
    func ink() {
        #expect(MemberLook.isDark(Int32(bitPattern: 0xFF19_76D2)))
        #expect(!MemberLook.isDark(Int32(bitPattern: 0xFFFF_EB3B)))
    }

    @Test("HSV conversions round trip the palette")
    func hsvRoundTrip() {
        let extra = [Int32(bitPattern: 0xFFFF_FFFF), Int32(bitPattern: 0xFF00_0000), Int32(bitPattern: 0xFF80_8080)]
        for c in MemberPalette.colors + extra {
            let hsv = ColorWheel.toHsv(c)
            #expect(ColorWheel.fromHsv(hsv[0], hsv[1], hsv[2]) == c, "\(String(UInt32(bitPattern: c), radix: 16))")
        }
        #expect(ColorWheel.fromHsv(0, 1, 1) == Int32(bitPattern: 0xFFFF_0000))
        #expect(ColorWheel.fromHsv(120, 1, 1) == Int32(bitPattern: 0xFF00_FF00))
        #expect(ColorWheel.fromHsv(240, 1, 1) == Int32(bitPattern: 0xFF00_00FF))
        #expect(ColorWheel.fromHsv(360, 1, 1) == Int32(bitPattern: 0xFFFF_0000))
    }

    @Test("a touch on the wheel gives hue and saturation, and back")
    func wheelTouch() {
        let r: Float = 100
        #expect(ColorWheel.hueSatAt(0, 0, r).1 == 0)
        let (h, s) = ColorWheel.hueSatAt(0, 50, r)
        #expect(abs(h - 90) <= 0.01)
        #expect(abs(s - 0.5) <= 0.001)
        #expect(ColorWheel.hueSatAt(-300, 0, r).1 == 1)
        for hue: Float in [0, 45, 200, 359] {
            let (x, y) = ColorWheel.pointOf(hue, 0.7, r)
            let (h2, s2) = ColorWheel.hueSatAt(x, y, r)
            #expect(abs(h2 - hue) < 0.01, "\(hue) → \(h2)")
            #expect(abs(s2 - 0.7) <= 0.001)
        }
    }
}
