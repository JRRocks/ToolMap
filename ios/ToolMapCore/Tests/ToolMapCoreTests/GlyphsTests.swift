// The glyphs of the waypoints, the activities and the share tokens: VectorPath (SVG path data read into a CGPath) and
// Glyphs (the resource glyphs.json, written by ios/tools/glyphs.py from Android's vector drawables, drawn as images).
// No Kotlin counterpart: Android draws its drawables with the platform.

import CoreGraphics
import Foundation
import Testing
@testable import ToolMapCore

/// One element of a CoreGraphics path, to compare paths exactly.
private struct Step: Equatable {
    let kind: String
    let points: [CGPoint]

    init(_ kind: String, _ points: [CGPoint] = []) {
        self.kind = kind
        self.points = points
    }
}

private func point(_ x: Double, _ y: Double) -> CGPoint {
    CGPoint(x: x, y: y)
}

private func steps(_ path: CGPath) -> [Step] {
    var out: [Step] = []
    path.applyWithBlock { pointer in
        let element = pointer.pointee
        switch element.type {
        case .moveToPoint:
            out.append(Step("M", [element.points[0]]))
        case .addLineToPoint:
            out.append(Step("L", [element.points[0]]))
        case .addQuadCurveToPoint:
            out.append(Step("Q", [element.points[0], element.points[1]]))
        case .addCurveToPoint:
            out.append(Step("C", [element.points[0], element.points[1], element.points[2]]))
        case .closeSubpath:
            out.append(Step("Z"))
        @unknown default:
            out.append(Step("?"))
        }
    }
    return out
}

/// The bounding box of the shape drawn by `data` (control points left out).
private func box(_ data: String) throws -> CGRect {
    try VectorPath.parse(data).boundingBoxOfPath
}

private func near(_ rect: CGRect, _ minX: Double, _ minY: Double, _ maxX: Double, _ maxY: Double) -> Bool {
    let actual = [Double(rect.minX), Double(rect.minY), Double(rect.maxX), Double(rect.maxY)]
    let expected = [minX, minY, maxX, maxY]
    return zip(actual, expected).allSatisfy { abs($0 - $1) <= 0.01 }
}

private func srgb(_ red: Double, _ green: Double, _ blue: Double) -> CGColor {
    CGColor(srgbRed: CGFloat(red), green: CGFloat(green), blue: CGFloat(blue), alpha: 1)
}

/// The RGBA (alpha premultiplied) of the pixel at (x, y) of a square image, counted from the top-left corner.
private func rgba(_ image: CGImage, _ x: Int, _ y: Int) throws -> [UInt8] {
    let context = try #require(TileComposer.context(image.width))
    let frame = CGRect(x: 0, y: 0, width: image.width, height: image.height)
    context.clear(frame)
    context.draw(image, in: frame)
    let data = try #require(context.data)
    let bytes = data.bindMemory(to: UInt8.self, capacity: context.bytesPerRow * image.height)
    let offset = y * context.bytesPerRow + x * 4
    return [bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]]
}

struct VectorPathTests {
    @Test("relative commands start from the current point, and the numbers after m are lines")
    func relative() throws {
        let square: [Step] = [
            Step("M", [point(1, 1)]),
            Step("L", [point(3, 1)]),
            Step("L", [point(3, 3)]),
            Step("L", [point(1, 3)]),
            Step("Z"),
        ]
        #expect(try steps(VectorPath.parse("m1,1 2,0 0,2 h-2z")) == square)
        #expect(try steps(VectorPath.parse("M1,1 L3,1 L3,3 H1 Z")) == square)
        #expect(try steps(VectorPath.parse("M1,1 L3,1 V3 H1 Z")) == square)
    }

    @Test("numbers written together, signs, exponents, and any separator")
    func numbers() throws {
        let together: [Step] = [Step("M", [point(1.5, 0.5)]), Step("L", [point(-1, -2)]), Step("L", [point(9, -2.25)])]
        #expect(try steps(VectorPath.parse("M1.5.5L-1-2l1e1-2.5e-1")) == together)
        let spaced: [Step] = [Step("M", [point(1, 2)]), Step("L", [point(3, 4)])]
        #expect(try steps(VectorPath.parse("\n\tM+1 2\tL3,4\r\n")) == spaced)
        let dots: [Step] = [Step("M", [point(10, 2)])]
        #expect(try steps(VectorPath.parse("M1.e1,2.")) == dots)
        #expect(try VectorPath.parse("").isEmpty)
        #expect(try VectorPath.parse(" \n ").isEmpty)
    }

    @Test("Z goes back to the start of the subpath, where a relative m or a line starts the next one")
    func closeThenMove() throws {
        let moved: [Step] = [
            Step("M", [point(2, 2)]),
            Step("L", [point(4, 2)]),
            Step("L", [point(4, 4)]),
            Step("Z"),
            Step("M", [point(3, 3)]),
            Step("L", [point(4, 3)]),
        ]
        #expect(try steps(VectorPath.parse("M2,2 L4,2 L4,4 Z m1,1 l1,0")) == moved)
        let reopened: [Step] = [
            Step("M", [point(2, 2)]),
            Step("L", [point(4, 2)]),
            Step("Z"),
            Step("M", [point(2, 2)]),
            Step("L", [point(2, 4)]),
        ]
        #expect(try steps(VectorPath.parse("M2,2 L4,2 Z L2,4")) == reopened)
    }

    @Test("S and T reflect the previous control point, or start from the current point")
    func smooth() throws {
        let cubic: [Step] = [
            Step("M", [point(0, 0)]),
            Step("C", [point(1, 2), point(3, 2), point(4, 0)]),
            Step("C", [point(5, -2), point(7, -2), point(8, 0)]),
        ]
        #expect(try steps(VectorPath.parse("M0,0 C1,2 3,2 4,0 S7,-2 8,0")) == cubic)
        #expect(try steps(VectorPath.parse("M0,0 c1,2 3,2 4,0 s3,-2 4,0")) == cubic)
        let quad: [Step] = [
            Step("M", [point(0, 0)]),
            Step("Q", [point(1, 1), point(2, 0)]),
            Step("Q", [point(3, -1), point(4, 0)]),
        ]
        #expect(try steps(VectorPath.parse("M0,0 Q1,1 2,0 T4,0")) == quad)
        #expect(try steps(VectorPath.parse("M0,0 q1,1 2,0 t2,0")) == quad)
        let alone: [Step] = [
            Step("M", [point(0, 0)]),
            Step("L", [point(1, 1)]),
            Step("C", [point(1, 1), point(2, 2), point(3, 3)]),
        ]
        #expect(try steps(VectorPath.parse("M0,0 L1,1 S2,2 3,3")) == alone)
    }

    @Test("arcs become cubic curves of a quarter turn at most, through the right side")
    func arcs() throws {
        let half = try VectorPath.parse("M0,10 A10,10 0 0,1 20,10")
        #expect(near(half.boundingBoxOfPath, 0, 0, 20, 10))
        #expect(half.currentPoint == point(20, 10))
        #expect(steps(half).map(\.kind) == ["M", "C", "C"])
        // The flags of an arc are single digits, here touching the next number.
        let together = try VectorPath.parse("M0,10a10 10 0 0120 0")
        #expect(near(together.boundingBoxOfPath, 0, 0, 20, 10))
        // Three quarters of a turn (large arc), centred on (5, 0).
        let large = try VectorPath.parse("M0,0 A5,5 0 1,1 5,5")
        #expect(near(large.boundingBoxOfPath, 0, -5, 10, 5))
        #expect(steps(large).map(\.kind) == ["M", "C", "C", "C"])
        // A circle drawn as two half arcs, as tools/icons/generate.mjs writes them.
        #expect(try near(box("M0,0 a5,5 0 1,0 10,0 a5,5 0 1,0 -10,0 z"), 0, -5, 10, 5))
        // Radii too small grow until they join the two points.
        #expect(try near(box("M0,0 A1,1 0 0,0 10,0"), 0, 0, 10, 5))
        // A rotated ellipse.
        #expect(try near(box("M0,0 A10,5 90 0,1 0,20"), 0, 0, 5, 20))
        // A zero radius is a line; the same point at both ends, nothing.
        let line: [Step] = [Step("M", [point(0, 0)]), Step("L", [point(10, 0)])]
        #expect(try steps(VectorPath.parse("M0,0 A0,5 0 0,1 10,0")) == line)
        let nothing: [Step] = [Step("M", [point(1, 1)]), Step("L", [point(2, 2)])]
        #expect(try steps(VectorPath.parse("M1,1 A5,5 0 0,1 1,1 L2,2")) == nothing)
    }

    @Test("data that is not valid path data throws")
    func invalid() {
        let wrong = [
            "L1,1", "1,1", "M1", "M1,1 L", "M1,1 L2,2,3", "M1,1 X2", "M1,1 A1,1 0 2,0 3,3", "M1,1 Z 2,2", "M1e", "M1-.5.5",
        ]
        for data in wrong {
            #expect(throws: VectorPathError.self, "\(data)") { try VectorPath.parse(data) }
        }
        #expect(throws: VectorPathError("unexpected character", offset: 5)) { try VectorPath.parse("M1,1 X2") }
    }
}

struct GlyphsTests {
    @Test("the resource glyphs.json is read once; every key is of a known family")
    func resource() throws {
        let glyphs = try Glyphs.load()
        #expect(glyphs.count > 300)
        #expect(Glyphs.keys == glyphs.keys.sorted())
        let prefixes = [Glyphs.waypointPrefix, Glyphs.activityPrefix, Glyphs.tokenPrefix]
        let strays = Glyphs.keys.filter { key in !prefixes.contains { key.hasPrefix($0) } }
        #expect(strays.isEmpty, "\(strays)")
    }

    @Test("a glyph is read from its JSON: viewport, path data, fill rule (non-zero when absent)")
    func decodeJson() throws {
        let json = #"{"wp_test": {"w": 15, "h": 24.5, "paths": [{"d": "M0,0 L1,1", "evenOdd": true}, {"d": "M2,2 h1"}]}}"#
        let glyphs = try Glyphs.decode(Data(json.utf8))
        let paths = [
            Glyphs.Glyph.PathData(data: "M0,0 L1,1", evenOdd: true),
            Glyphs.Glyph.PathData(data: "M2,2 h1", evenOdd: false),
        ]
        let expected = Glyphs.Glyph(width: 15, height: 24.5, paths: paths)
        #expect(glyphs == ["wp_test": expected])
    }

    @Test("every icon of the waypoint catalog has a glyph, and every waypoint glyph is an icon of the catalog")
    func waypoints() {
        let icons = WaypointCatalog.categories.flatMap(\.icons)
        #expect(!icons.isEmpty)
        let missing = icons.filter { Glyphs.glyph(Glyphs.waypointKey($0)) == nil }.map(\.id)
        #expect(missing.isEmpty, "\(missing)")
        let catalogKeys = Set(icons.map { Glyphs.waypointKey($0) })
        let glyphKeys = Set(Glyphs.keys.filter { $0.hasPrefix(Glyphs.waypointPrefix) })
        #expect(glyphKeys == catalogKeys)
        #expect(Glyphs.waypointKey(id: "peak") == "wp_peak")
        #expect(Glyphs.path("wp_peak") != nil)
    }

    @Test("every activity and every token of the share has a glyph")
    func activitiesAndTokens() {
        let activities = TrackActivity.allCases.filter { Glyphs.glyph(Glyphs.activityKey($0)) == nil }
        #expect(activities.isEmpty, "\(activities)")
        #expect(Glyphs.activityKey(.trailRun) == "act_trail_run")
        let activityKeys = Set(TrackActivity.allCases.map { Glyphs.activityKey($0) })
        #expect(Set(Glyphs.keys.filter { $0.hasPrefix(Glyphs.activityPrefix) }) == activityKeys)

        let tokens = (0 ..< MemberLook.icons.count).compactMap { Glyphs.tokenKey($0) }
        #expect(tokens.count == MemberLook.icons.count - 1)
        let missing = tokens.filter { Glyphs.glyph($0) == nil }
        #expect(missing.isEmpty, "\(missing)")
        #expect(Set(Glyphs.keys.filter { $0.hasPrefix(Glyphs.tokenPrefix) }) == Set(tokens))
        #expect(Glyphs.tokenKey(1) == "token_chess_knight")
        #expect(Glyphs.tokenKey(MemberLook.initials) == nil)
        #expect(Glyphs.tokenKey(MemberLook.picture) == nil)
        #expect(Glyphs.tokenKey(-1) == nil)
    }

    @Test("every path of every glyph is valid path data, and its shape fits in the viewport")
    func pathsFit() throws {
        let margin = 0.5
        var problems: [String] = []
        for key in Glyphs.keys {
            let glyph = try #require(Glyphs.glyph(key))
            if glyph.paths.isEmpty { problems.append("\(key): no path") }
            for part in glyph.paths {
                do {
                    let bounds = try VectorPath.parse(part.data).boundingBoxOfPath
                    if bounds.isNull {
                        problems.append("\(key): empty path")
                        continue
                    }
                    let minX = Double(bounds.minX)
                    let minY = Double(bounds.minY)
                    let maxX = Double(bounds.maxX)
                    let maxY = Double(bounds.maxY)
                    let insideX = minX >= -margin && maxX <= glyph.width + margin
                    let insideY = minY >= -margin && maxY <= glyph.height + margin
                    if !insideX || !insideY {
                        problems.append("\(key): \(bounds) outside \(glyph.width) x \(glyph.height)")
                    }
                } catch {
                    problems.append("\(key): \(error)")
                }
            }
            if Glyphs.path(key) == nil { problems.append("\(key): no path") }
        }
        #expect(problems.isEmpty, "\(problems)")
    }

    @Test("image draws the glyph in its colour, the right way up, on size × scale pixels")
    func imageUpright() throws {
        let white = srgb(1, 1, 1)
        let image = try #require(Glyphs.image("wp_waypoint", size: 24, scale: 2, color: white))
        #expect(image.width == 48)
        #expect(image.height == 48)
        // mdi:flag-variant in a 24 × 24 viewport: the flag flies right of the pole, from y ≈ 5 to y ≈ 14.
        let flag = try rgba(image, 30, 18)
        #expect(flag[3] == 255)
        #expect(flag[0] >= 250 && flag[1] >= 250 && flag[2] >= 250)
        // Below the flag, nothing: an image upside down would have the flag here.
        #expect(try rgba(image, 30, 30)[3] == 0)
        #expect(try rgba(image, 0, 0)[3] == 0)
    }

    @Test("image puts the glyph, inside the padding, on a disc of the background colour")
    func imageOnDisc() throws {
        let white = srgb(1, 1, 1)
        let blue = srgb(0.2, 0.4, 0.8)
        let drawn = Glyphs.image("wp_peak", size: 20, scale: 3, color: white, background: blue, padding: 4)
        let image = try #require(drawn)
        #expect(image.width == 60)
        #expect(image.height == 60)
        // The centre: the glyph or the disc, opaque either way.
        #expect(try rgba(image, 30, 30)[3] == 255)
        // On the disc, in the padding (12 pixels), so outside the glyph: the background colour.
        let disc = try rgba(image, 3, 30)
        #expect(disc[3] == 255)
        #expect(disc[2] > disc[1] && disc[1] > disc[0])
        // The corners are outside the disc.
        #expect(try rgba(image, 0, 0)[3] == 0)
        #expect(try rgba(image, 59, 59)[3] == 0)
    }

    @Test("no image for an unknown key or an empty size; the other families draw too")
    func otherImages() {
        let white = srgb(1, 1, 1)
        #expect(Glyphs.image("wp_no_such_icon", size: 20, scale: 2, color: white) == nil)
        #expect(Glyphs.image("wp_peak", size: 0, scale: 2, color: white) == nil)
        #expect(Glyphs.image("wp_peak", size: 20, scale: 0, color: white) == nil)
        #expect(Glyphs.glyph("nothing") == nil)
        #expect(Glyphs.path("nothing") == nil)
        #expect(Glyphs.image(Glyphs.activityKey(.hike), size: 24, scale: 2, color: white) != nil)
        let token = Glyphs.tokenKey(2) ?? ""
        #expect(Glyphs.image(token, size: 24, scale: 2, color: white, background: srgb(0, 0, 0), padding: 3) != nil)
    }
}
