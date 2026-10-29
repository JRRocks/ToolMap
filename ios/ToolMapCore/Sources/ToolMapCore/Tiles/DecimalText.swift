// The `{$bbox}` numbers of core/src/main/kotlin/fr/toolmap/core/tiles/TileUrlBuilder.kt, written as on the JVM:
// `BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()`.

/// Exact decimal text of a `Double` rounded half up (away from zero on a tie) to a number of decimals, without
/// trailing zeros and never in scientific notation: `-180.0` gives `-180`, `85.05112877980659` with 10 decimals
/// gives `85.0511287798`. The rounding works on the exact binary value, like `BigDecimal(double)`, so a value whose
/// binary expansion ends exactly on a 5 rounds the same way on both platforms.
enum DecimalText {
    static func halfUp(_ value: Double, decimals: Int) -> String {
        guard value.isFinite else { return "\(value)" }
        let bits = value.bitPattern
        let exponentBits = Int((bits >> 52) & 0x7FF)
        let fraction = bits & 0x000F_FFFF_FFFF_FFFF
        // |value| = mantissa * 2^exponent, exactly.
        let mantissa = exponentBits == 0 ? fraction : fraction | (1 << 52)
        let exponent = exponentBits == 0 ? -1074 : exponentBits - 1075
        if mantissa == 0 { return "0" }

        // |value| = digits / 10^scale, exactly (2^-k = 5^k / 10^k).
        var number = LargeDecimal(mantissa)
        var scale = 0
        if exponent >= 0 {
            number.multiplyByPowerOfTwo(exponent)
        } else {
            number.multiplyByPowerOfFive(-exponent)
            scale = -exponent
        }
        var digits = number.digits()

        if scale > decimals {
            let drop = scale - decimals
            if digits.count < drop + 1 {
                digits.insert(contentsOf: [UInt8](repeating: 0, count: drop + 1 - digits.count), at: 0)
            }
            let roundUp = digits[digits.count - drop] >= 5
            digits.removeLast(drop)
            scale = decimals
            if roundUp { increment(&digits) }
        }
        while scale > 0, digits.last == 0 {
            digits.removeLast()
            scale -= 1
        }
        if digits.allSatisfy({ $0 == 0 }) { return "0" }

        if digits.count <= scale {
            digits.insert(contentsOf: [UInt8](repeating: 0, count: scale - digits.count + 1), at: 0)
        }
        var integerPart = Array(digits[0 ..< (digits.count - scale)])
        while integerPart.count > 1, integerPart[0] == 0 {
            integerPart.removeFirst()
        }
        let fractionPart = digits[(digits.count - scale)...]
        var text = value < 0 ? "-" : ""
        text += String(decoding: integerPart.map { $0 + 0x30 }, as: UTF8.self)
        if scale > 0 {
            text += "." + String(decoding: fractionPart.map { $0 + 0x30 }, as: UTF8.self)
        }
        return text
    }

    /// Adds one to a number written as decimal digits (most significant first).
    private static func increment(_ digits: inout [UInt8]) {
        var i = digits.count - 1
        while i >= 0 {
            if digits[i] < 9 {
                digits[i] += 1
                return
            }
            digits[i] = 0
            i -= 1
        }
        digits.insert(1, at: 0)
    }
}

/// A non-negative integer in base 10^9 limbs (least significant first): just what `DecimalText` needs.
private struct LargeDecimal {
    private static let base: UInt64 = 1_000_000_000
    private var limbs: [UInt64]

    init(_ value: UInt64) {
        limbs = []
        var rest = value
        repeat {
            limbs.append(rest % LargeDecimal.base)
            rest /= LargeDecimal.base
        } while rest > 0
    }

    /// Multiplies by `factor` (below 2^32, so that no limb product overflows).
    private mutating func multiply(by factor: UInt64) {
        var carry: UInt64 = 0
        for i in limbs.indices {
            let product = limbs[i] * factor + carry
            limbs[i] = product % LargeDecimal.base
            carry = product / LargeDecimal.base
        }
        while carry > 0 {
            limbs.append(carry % LargeDecimal.base)
            carry /= LargeDecimal.base
        }
    }

    mutating func multiplyByPowerOfTwo(_ power: Int) {
        var left = power
        while left >= 31 {
            multiply(by: 1 << 31)
            left -= 31
        }
        if left > 0 { multiply(by: 1 << UInt64(left)) }
    }

    mutating func multiplyByPowerOfFive(_ power: Int) {
        var left = power
        while left >= 13 {
            multiply(by: 1_220_703_125) // 5^13
            left -= 13
        }
        var factor: UInt64 = 1
        for _ in 0 ..< left {
            factor *= 5
        }
        if factor > 1 { multiply(by: factor) }
    }

    /// Decimal digits, most significant first, without leading zeros.
    func digits() -> [UInt8] {
        var result: [UInt8] = []
        for (index, limb) in limbs.enumerated().reversed() {
            var chunk = [UInt8](repeating: 0, count: 9)
            var rest = limb
            for position in stride(from: 8, through: 0, by: -1) {
                chunk[position] = UInt8(rest % 10)
                rest /= 10
            }
            if index == limbs.count - 1 {
                let firstNonZero = chunk.firstIndex(where: { $0 != 0 }) ?? 8
                result.append(contentsOf: chunk[firstNonZero...])
            } else {
                result.append(contentsOf: chunk)
            }
        }
        return result
    }
}
