// Port of core/src/main/kotlin/fr/toolmap/core/text/SignedDistance.kt: the same maths in the same order, so that both
// apps make the same bytes.

/// The signed distance field MapLibre draws its text with (ADR-062): one byte per pixel, 191 on the edge of the glyph,
/// more inside, less outside, down to 0 at `radius` pixels away. Same maths as Mapbox's TinySDF (an exact Euclidean
/// distance transform, Felzenszwalb and Huttenlocher), so that the glyphs look like the ones of a hosted font.
public enum SignedDistance {
    /// Pixels of empty margin around each glyph bitmap, the value MapLibre expects (`border` of its glyph atlas).
    public static let buffer = 3

    /// Distance, in pixels, over which the field goes from the edge to nothing.
    public static let radius = 8.0

    /// Where the edge sits on the 0..1 scale (0.25 → byte 191, the `gamma` centre of MapLibre's text shader).
    public static let cutoff = 0.25

    private static let inf = 1e20
    private static let opaque = 255.0

    /// The field of a coverage image: `alpha` holds `width` × `height` bytes (0 = empty, 255 = ink), row by row. The
    /// result has the same size.
    public static func of(_ alpha: [UInt8], _ width: Int, _ height: Int) throws -> [UInt8] {
        try require(width >= 0 && height >= 0, "size \(width) × \(height)")
        try require(alpha.count == width * height, "alpha has \(alpha.count) bytes, expected \(width * height)")
        let size = width * height
        var outer = [Double](repeating: 0, count: size)
        var inner = [Double](repeating: 0, count: size)
        for i in 0 ..< size {
            let a = Double(alpha[i]) / opaque
            if a >= 1.0 {
                outer[i] = 0.0
                inner[i] = inf
            } else if a <= 0.0 {
                outer[i] = inf
                inner[i] = 0.0
            } else {
                // A partly covered pixel: the edge crosses it, at a distance given by its coverage.
                let d = 0.5 - a
                outer[i] = d > 0 ? d * d : 0.0
                inner[i] = d < 0 ? d * d : 0.0
            }
        }
        transform(&outer, width, height)
        transform(&inner, width, height)
        var out = [UInt8](repeating: 0, count: size)
        for i in 0 ..< size {
            let d = outer[i].squareRoot() - inner[i].squareRoot()
            let v = kotlinRound(opaque - opaque * (d / radius + cutoff))
            out[i] = UInt8(min(max(v, 0), 255))
        }
        return out
    }

    /// Squared Euclidean distance transform of `grid` in place: columns, then rows.
    private static func transform(_ grid: inout [Double], _ width: Int, _ height: Int) {
        let n = max(width, height)
        var f = [Double](repeating: 0, count: n)
        var v = [Int](repeating: 0, count: n)
        var z = [Double](repeating: 0, count: n + 1)
        for x in 0 ..< width {
            line(&grid, x, width, height, &f, &v, &z)
        }
        for y in 0 ..< height {
            line(&grid, y * width, 1, width, &f, &v, &z)
        }
    }

    /// One-dimensional transform of the `length` values of `grid` from `offset`, `step` apart (lower envelope).
    private static func line(
        _ grid: inout [Double],
        _ offset: Int,
        _ step: Int,
        _ length: Int,
        _ f: inout [Double],
        _ v: inout [Int],
        _ z: inout [Double]
    ) {
        for q in 0 ..< length {
            f[q] = grid[offset + q * step]
        }
        var k = 0
        v[0] = 0
        z[0] = -inf
        z[1] = inf
        var q = 1
        while q < length {
            var s = 0.0
            while true {
                let r = v[k]
                s = (f[q] - f[r] + Double(q) * Double(q) - Double(r) * Double(r)) / Double(q - r) / 2.0
                // The parabola of q hides the one of r: drop r and compare with the one before.
                if s > z[k] { break }
                k -= 1
                if k < 0 { break }
            }
            k += 1
            v[k] = q
            z[k] = s
            z[k + 1] = inf
            q += 1
        }
        k = 0
        for p in 0 ..< length {
            while z[k + 1] < Double(p) {
                k += 1
            }
            let r = v[k]
            let dq = Double(p - r)
            grid[offset + p * step] = dq * dq + f[r]
        }
    }
}
