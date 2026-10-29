// Port of core/src/main/kotlin/fr/toolmap/core/p2p/ShareCadence.kt.

/// What the last sent position was, to decide about the next.
public struct SentPosition: Equatable, Hashable, Sendable {
    public let timeSec: Int64
    public let lat: Double
    public let lon: Double

    public init(_ timeSec: Int64, _ lat: Double, _ lon: Double) {
        self.timeSec = timeSec
        self.lat = lat
        self.lon = lon
    }
}

/// When a new GPS fix is worth sending: not before the interval has passed (the GPS is asked for that rhythm, a fix
/// can come up to a second early), only if the device moved at least `minMoveMeters` since the last one sent, and in
/// any case after `keepAliveFactor` intervals so the others know it is still there.
public struct ShareCadence: Equatable, Hashable, Sendable {
    public static let minIntervalS = 5
    public static let keepAliveFactor = 5
    private static let earlyToleranceS = 1
    public static let presetsS = [5, 10, 15, 30, 60, 120, 300, 600, 900, 1800]
    public static let movePresetsM = [0, 5, 10, 25, 50, 100]

    public let intervalSec: Int
    public let minMoveMeters: Double

    public init(_ intervalSec: Int, _ minMoveMeters: Double) throws {
        try require(intervalSec >= ShareCadence.minIntervalS, "interval below \(ShareCadence.minIntervalS) s")
        self.intervalSec = intervalSec
        self.minMoveMeters = minMoveMeters
    }

    public func shouldSend(_ last: SentPosition?, _ fix: PeerFix) -> Bool {
        guard let last else { return true }
        let elapsed = fix.timeSec - last.timeSec
        // The clock went back (set by hand or by the network): the interval counts as passed (P21.5 n).
        if elapsed < 0 { return true }
        if elapsed < Int64(intervalSec - ShareCadence.earlyToleranceS) { return false }
        if elapsed >= Int64(intervalSec) * Int64(ShareCadence.keepAliveFactor) { return true }
        return minMoveMeters <= 0.0 || Geodesy.distance(last.lat, last.lon, fix.lat, fix.lon) >= minMoveMeters
    }
}

/// The colours a member can pick (ARGB), indexed by the byte sent in pairing codes and HELLO messages.
public enum MemberPalette {
    public static let colors: [Int32] = [
        Int32(bitPattern: 0xFFD3_2F2F),
        Int32(bitPattern: 0xFF19_76D2),
        Int32(bitPattern: 0xFF38_8E3C),
        Int32(bitPattern: 0xFFF5_7C00),
        Int32(bitPattern: 0xFF7B_1FA2),
        Int32(bitPattern: 0xFF00_838F),
        Int32(bitPattern: 0xFFC2_185B),
        Int32(bitPattern: 0xFF5D_4037),
        Int32(bitPattern: 0xFF45_5A64),
        Int32(bitPattern: 0xFFAF_B42B),
        Int32(bitPattern: 0xFF51_2DA8),
        Int32(bitPattern: 0xFF00_796B),
    ]

    public static func argb(_ index: Int) -> Int32 {
        let n = colors.count
        return colors[((index % n) + n) % n]
    }

    /// Two letters for the marker: the initials of the first two words, else the first two letters.
    public static func initials(_ name: String) -> String {
        let words = splitOnSpaces(kotlinTrim(name)).filter { !$0.isEmpty }
        let letters: String
        if words.isEmpty {
            letters = "?"
        } else if words.count == 1 {
            // Kotlin's take(2) and first() count UTF-16 units.
            letters = String(decoding: Array(words[0].utf16.prefix(2)), as: UTF16.self)
        } else {
            let first = Array(words[0].utf16.prefix(1)) + Array(words[1].utf16.prefix(1))
            letters = String(decoding: first, as: UTF16.self)
        }
        return letters.uppercased()
    }
}
