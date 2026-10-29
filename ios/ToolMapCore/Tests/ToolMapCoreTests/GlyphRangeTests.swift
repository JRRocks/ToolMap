// Port of core/src/test/kotlin/fr/toolmap/core/text/GlyphRangeTest.kt, and the exact field of a small image as the
// Kotlin code computes it (the same maths on the JVM, values copied here).

import Foundation
import Testing
@testable import ToolMapCore

/// A square of ink of `side` pixels, with the margin MapLibre expects around it.
private func square(_ side: Int) -> RasterGlyph {
    let size = side + 2 * SignedDistance.buffer
    var alpha = [UInt8](repeating: 0, count: size * size)
    for y in 0 ..< side {
        for x in 0 ..< side {
            alpha[(y + SignedDistance.buffer) * size + x + SignedDistance.buffer] = 0xFF
        }
    }
    return RasterGlyph(width: side, height: side, left: 1, top: side, advance: side + 2, alpha: alpha)
}

/// A field of a protocol buffer message: a varint or a length-delimited value.
private enum ProtoValue: Equatable {
    case varint(UInt64)
    case bytes([UInt8])

    var number: UInt64? {
        if case let .varint(value) = self { return value }
        return nil
    }

    var data: [UInt8]? {
        if case let .bytes(value) = self { return value }
        return nil
    }
}

/// Just enough protocol buffer reading to check the file: varints and length-delimited fields.
private struct Proto {
    let bytes: [UInt8]
    var pos = 0

    init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    private mutating func varint() -> UInt64 {
        var shift: UInt64 = 0
        var result: UInt64 = 0
        while true {
            let b = bytes[pos]
            pos += 1
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
        }
    }

    /// The fields in their order; nil when a wire type other than varint or length-delimited is found.
    mutating func fields() -> [(Int, ProtoValue)]? {
        var out: [(Int, ProtoValue)] = []
        while pos < bytes.count {
            let key = varint()
            let field = Int(key >> 3)
            switch key & 7 {
            case 0:
                out.append((field, .varint(varint())))
            case 2:
                let length = Int(varint())
                out.append((field, .bytes(Array(bytes[pos ..< pos + length]))))
                pos += length
            default:
                return nil
            }
        }
        return out
    }
}

/// Kotlin `(v ushr 1) xor -(v and 1)`.
private func unzig(_ v: UInt64) -> Int64 {
    Int64(bitPattern: (v >> 1) ^ (0 &- (v & 1)))
}

struct GlyphRangeTests {
    @Test("the field is high inside, 191 near the edge and low far outside")
    func fieldValues() throws {
        let side = 10
        let size = side + 2 * SignedDistance.buffer
        let field = try SignedDistance.of(square(side).alpha, size, size)
        func at(_ x: Int, _ y: Int) -> Int { Int(field[y * size + x]) }
        let centre = at(size / 2, size / 2)
        let inkEdge = at(SignedDistance.buffer, size / 2)
        let justOutside = at(SignedDistance.buffer - 1, size / 2)
        let corner = at(0, 0)
        #expect(centre > inkEdge, "centre \(centre), edge \(inkEdge)")
        // The edge (191) lies between the first inked pixel and the empty one before it.
        #expect((192 ... 230).contains(inkEdge), "edge pixel \(inkEdge)")
        #expect((150 ... 190).contains(justOutside), "outside pixel \(justOutside)")
        #expect(corner < justOutside, "corner \(corner)")
    }

    @Test("an empty image is far from any edge everywhere")
    func emptyImage() throws {
        let field = try SignedDistance.of([UInt8](repeating: 0, count: 36), 6, 6)
        #expect(field.allSatisfy { $0 == 0 })
    }

    @Test("the field of a small image is the one the Kotlin code computes")
    func exactField() throws {
        let alpha: [UInt8] = [
            0, 0, 0, 0, 0, 0, 0,
            0, 64, 255, 255, 128, 0, 0,
            0, 255, 255, 255, 255, 32, 0,
            0, 200, 255, 255, 255, 0, 0,
            0, 0, 128, 255, 10, 0, 0,
            0, 0, 0, 0, 0, 0, 0,
        ]
        let expected: [UInt8] = [
            145, 158, 159, 159, 159, 146, 120,
            158, 183, 223, 223, 191, 159, 145,
            159, 223, 236, 236, 223, 179, 157,
            159, 200, 223, 236, 223, 159, 145,
            146, 159, 191, 223, 177, 156, 126,
            120, 146, 159, 159, 156, 144, 118,
        ]
        #expect(try SignedDistance.of(alpha, 7, 6) == expected)
        // The square of the test above, at the points it checks.
        let field = try SignedDistance.of(square(10).alpha, 16, 16)
        #expect([field[8 * 16 + 8], field[8 * 16 + 3], field[8 * 16 + 2], field[0]] == [255, 223, 159, 56])
        #expect(throws: ToolMapError.self) { try SignedDistance.of([0, 0, 0], 2, 2) }
    }

    @Test("ranges are read only when MapLibre could ask for them")
    func ranges() {
        #expect(GlyphRange.startOf("0-255") == 0)
        #expect(GlyphRange.startOf("256-511") == 256)
        #expect(GlyphRange.startOf("1-256") == nil)
        #expect(GlyphRange.startOf("0-511") == nil)
        #expect(GlyphRange.startOf("abc") == nil)
        #expect(GlyphRange.startOf("65536-65791") == nil)
    }

    @Test("bold faces are recognised from the font stack")
    func boldFaces() {
        #expect(GlyphRange.isBold("ToolMap Sans Bold"))
        #expect(GlyphRange.isBold("Roboto Medium"))
        #expect(!GlyphRange.isBold("ToolMap Sans Regular"))
    }

    @Test("the file holds one stack with every glyph the font draws")
    func oneStack() throws {
        let file = try GlyphRange.encode("ToolMap Sans Bold", 0) { code, bold in
            #expect(bold)
            switch code {
            case 0x41: // 'A'
                return square(4)
            case 0x20: // ' '
                return RasterGlyph(width: 0, height: 0, left: 0, top: 0, advance: 6, alpha: [])
            default:
                return nil
            }
        }
        var fileReader = Proto([UInt8](file))
        let stacksRead = fileReader.fields()
        let stacks = try #require(stacksRead)
        let stackFields = stacks.map { $0.0 }
        #expect(stackFields == [1])
        let stackBytes = try #require(stacks.first?.1.data)
        var stackReader = Proto(stackBytes)
        let stackRead = stackReader.fields()
        let stack = try #require(stackRead)
        let nameField = stack.first(where: { $0.0 == 1 })
        let rangeField = stack.first(where: { $0.0 == 2 })
        let name = try #require(nameField?.1.data)
        let range = try #require(rangeField?.1.data)
        #expect(String(decoding: name, as: UTF8.self) == "ToolMap Sans Bold")
        #expect(String(decoding: range, as: UTF8.self) == "0-255")
        var glyphs: [[Int: ProtoValue]] = []
        for (field, value) in stack where field == 3 {
            let glyphBytes = try #require(value.data)
            var glyphReader = Proto(glyphBytes)
            let glyphRead = glyphReader.fields()
            let glyphFields = try #require(glyphRead)
            glyphs.append(Dictionary(glyphFields, uniquingKeysWith: { _, last in last }))
        }
        let codes = glyphs.map { $0[1]?.number ?? 0 }
        #expect(codes == [0x20, 0x41])
        let space = try #require(glyphs.first)
        #expect(space[2] == nil, "a blank glyph has no bitmap")
        #expect(space[7]?.number == 6)
        let a = try #require(glyphs.last)
        #expect(a[2]?.data?.count == 10 * 10, "4 px of ink and a 3 px margin on each side")
        #expect(a[3]?.number == 4)
        #expect(a[4]?.number == 4)
        let left = try #require(a[5]?.number)
        let top = try #require(a[6]?.number)
        #expect(unzig(left) == 1)
        #expect(unzig(top) == Int64(4 - GlyphRange.ascent))
        #expect(a[7]?.number == 6)
    }
}
