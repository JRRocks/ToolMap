// Port of core/src/main/kotlin/fr/toolmap/core/record/SignalWatch.kt.

import Foundation

/// Notices when the GNSS has stopped giving fixes while recording (the « Lost signal warning » setting). The recorder
/// calls `start` when it begins, `onFix` at each fix and `check` now and then; `check` and `onFix` answer only when the
/// state changes, so that the warning is raised once and the recovery once. Not thread-safe.
public final class SignalWatch {
    /// A minute without a fix: a tunnel is shorter, a phone in a bag is longer.
    public static let defaultLostAfterMillis: Int64 = 60_000

    public enum Change: String, Hashable, Sendable {
        case lost = "LOST"
        case recovered = "RECOVERED"
    }

    private let lostAfterMillis: Int64
    private var lastFixMillis: Int64 = 0
    private var lost = false

    public init(lostAfterMillis: Int64 = SignalWatch.defaultLostAfterMillis) {
        self.lostAfterMillis = lostAfterMillis
    }

    public var isLost: Bool { lost }

    public func start(_ nowMillis: Int64) {
        lastFixMillis = nowMillis
        lost = false
    }

    @discardableResult
    public func onFix(_ nowMillis: Int64) -> Change? {
        lastFixMillis = nowMillis
        if !lost { return nil }
        lost = false
        return .recovered
    }

    @discardableResult
    public func check(_ nowMillis: Int64) -> Change? {
        if lost || nowMillis - lastFixMillis < lostAfterMillis { return nil }
        lost = true
        return .lost
    }
}
