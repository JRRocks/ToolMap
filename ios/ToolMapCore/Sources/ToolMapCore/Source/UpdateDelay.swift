// Port of core/src/main/kotlin/fr/toolmap/core/source/UpdateDelay.kt.

/// AQX `<update-delay>`: how long a stored tile stays valid, written `<number><unit>` with the units D (days),
/// W (weeks), M (months, 30 days) and Y (years, 365 days), e.g. `0D`, `2W`, `3M`, `1Y`.
public enum UpdateDelay {
    private static let dayMillis = 86_400_000

    /// Validity in milliseconds, or nil when tiles never expire: no delay declared, `0` (read as "never refresh",
    /// the safe reading for offline use) or text that cannot be understood.
    ///
    /// Same grammar as the Kotlin pattern `(\d{1,5})\s*([DWMY])`, case-insensitive, on the trimmed text.
    public static func toMillis(_ text: String?) -> Int? {
        let scalars = Array(kotlinTrim(text ?? "").unicodeScalars)
        var i = 0
        var amount = 0
        while i < scalars.count, i < 5, let digit = asciiDigit(scalars[i]) {
            amount = amount * 10 + digit
            i += 1
        }
        guard i > 0 else { return nil }
        while i < scalars.count, KotlinText.isRegexSpace(scalars[i]) {
            i += 1
        }
        guard i == scalars.count - 1 else { return nil }
        let days: Int
        switch scalars[i] {
        case "D", "d": days = 1
        case "W", "w": days = 7
        case "M", "m": days = 30
        case "Y", "y": days = 365
        default: return nil
        }
        if amount == 0 { return nil }
        return amount * days * dayMillis
    }

    private static func asciiDigit(_ scalar: Unicode.Scalar) -> Int? {
        (0x30 ... 0x39).contains(scalar.value) ? Int(scalar.value - 0x30) : nil
    }
}
