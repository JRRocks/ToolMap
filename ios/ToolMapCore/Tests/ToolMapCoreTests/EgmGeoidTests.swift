// Port of core/src/test/kotlin/fr/toolmap/core/geo/EgmGeoidTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct EgmGeoidTests {
    /// A grid whose values are the column index in centimetres along the longitude (big-endian 16-bit), built once.
    private static let grid: EgmGeoid? = {
        var bytes = [UInt8]()
        bytes.reserveCapacity(EgmGeoid.fileBytes)
        for _ in 0 ..< EgmGeoid.rows {
            for col in 0 ..< EgmGeoid.cols {
                let value = UInt16(col % Int(Int16.max))
                bytes.append(UInt8(value >> 8))
                bytes.append(UInt8(value & 0xFF))
            }
        }
        return try? EgmGeoid.parse(Data(bytes))
    }()

    private func near(_ a: Double, _ b: Double) -> Bool {
        abs(a - b) <= 1e-9
    }

    @Test("the nodes give the values of the file, in metres")
    func nodes() throws {
        let grid = try #require(EgmGeoidTests.grid)
        // Column 4 is 1° east (values of 4 cm), row 0 is the North Pole.
        #expect(near(grid.undulation(90.0, 1.0), 0.04))
        #expect(near(grid.undulation(90.0, 0.0), 0.0))
    }

    @Test("between the nodes the value is interpolated bilinearly")
    func bilinear() throws {
        let grid = try #require(EgmGeoidTests.grid)
        // Halfway between columns 4 and 5 (4 cm and 5 cm): 4.5 cm.
        #expect(near(grid.undulation(90.0, 1.0 + 0.125), 0.045))
    }

    @Test("longitudes wrap around the globe")
    func wrap() throws {
        let grid = try #require(EgmGeoidTests.grid)
        // 359.875° east is between column 1439 and column 0 of the file: the value runs from 14.39 m to 0 m.
        let between = grid.undulation(90.0, 359.875)
        #expect(near(between, Double(1439 + 0) / 2.0 / 100.0))
        #expect(near(grid.undulation(90.0, 0.0), grid.undulation(90.0, 360.0)))
        #expect(near(grid.undulation(90.0, 10.0), grid.undulation(90.0, -350.0)))
    }

    @Test("latitudes are read from the north row to the south row")
    func latitudes() throws {
        let grid = try #require(EgmGeoidTests.grid)
        // Row 1 is at 89.75° N: the values are the row index in the file only along the same column, so compare rows.
        #expect(near(grid.undulation(89.75, 0.0), 0.0))
        #expect(near(grid.undulation(0.0, 0.0), 0.0))
        #expect(near(grid.undulation(-90.0, 0.0), 0.0))
    }

    @Test("the orthometric height removes the undulation from the ellipsoidal one")
    func orthometric() throws {
        let grid = try #require(EgmGeoidTests.grid)
        #expect(near(grid.orthometric(100.0, 90.0, 1.0), 100.0 - 0.04))
    }

    @Test("a file of the wrong size is refused")
    func wrongSize() {
        #expect(throws: ToolMapError.self) { try EgmGeoid.parse(Data(count: 10)) }
    }
}
