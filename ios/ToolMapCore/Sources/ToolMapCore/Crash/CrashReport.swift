import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/crash/CrashReport.kt. The fields keep their Android names
// (`androidRelease`, `apiLevel`, OpenGL ES and Vulkan versions) so that the reports of both apps read the same; the
// iPhone app fills them with what it has (system version, nothing for the graphics versions it cannot read).

/// The phone and the build a crash happened on: what is needed to reproduce it without the phone.
public struct CrashDevice: Equatable, Hashable, Sendable {
    public let appVersion: String
    public let commit: String
    public let device: String
    public let androidRelease: String
    public let apiLevel: Int
    public let abis: [String]
    public let glEsVersion: String
    public let vulkanVersion: String?

    public init(
        appVersion: String,
        commit: String,
        device: String,
        androidRelease: String,
        apiLevel: Int,
        abis: [String],
        glEsVersion: String,
        vulkanVersion: String?
    ) {
        self.appVersion = appVersion
        self.commit = commit
        self.device = device
        self.androidRelease = androidRelease
        self.apiLevel = apiLevel
        self.abis = abis
        self.glEsVersion = glEsVersion
        self.vulkanVersion = vulkanVersion
    }
}

public enum CrashKind: String, CaseIterable, Sendable {
    /// An exception thrown by Java or Kotlin code: its stack trace is known.
    case java = "JAVA"
    /// The process was killed by a signal in native code (MapLibre, WebRTC...): only the tombstone tells where.
    case native = "NATIVE"

    public var label: String {
        switch self {
        case .java: "java exception"
        case .native: "native crash"
        }
    }
}

/// A crash as the user sees it on the report screen and copies or shares it. The text is plain and stable so that it
/// can be pasted into an issue as is.
public struct CrashReport: Equatable, Hashable, Sendable {
    public let kind: CrashKind
    public let atMillis: Int64
    public let process: String
    public let thread: String?
    public let headline: String
    public let details: String
    public let device: CrashDevice
    public let log: String?

    public init(
        kind: CrashKind,
        atMillis: Int64,
        process: String,
        thread: String?,
        headline: String,
        details: String,
        device: CrashDevice,
        log: String? = nil
    ) {
        self.kind = kind
        self.atMillis = atMillis
        self.process = process
        self.thread = thread
        self.headline = headline
        self.details = details
        self.device = device
        self.log = log
    }

    public func toText(zone: TimeZone = .current) -> String {
        var out = ""
        func appendLine(_ line: String = "") {
            out += line
            out += "\n"
        }
        appendLine("ToolMap \(device.appVersion) (\(device.commit))")
        appendLine("when: \(CrashText.date(atMillis, zone))")
        appendLine("kind: \(kind.label)")
        appendLine("device: \(device.device)")
        appendLine("android: \(device.androidRelease) (API \(device.apiLevel))")
        appendLine("abis: \(device.abis.joined(separator: ", "))")
        appendLine("opengl es: \(device.glEsVersion)")
        appendLine("vulkan: \(device.vulkanVersion ?? "none")")
        appendLine("process: \(process)" + (thread.map { ", thread: \($0)" } ?? ""))
        appendLine()
        appendLine(headline)
        appendLine()
        appendLine(CrashText.trimEnd(details))
        if let text = log, !CrashText.isBlank(text) {
            appendLine()
            appendLine("--- log ---")
            appendLine(CrashText.trimEnd(text))
        }
        return out
    }
}

/// Decoding helpers for what Android hands over about a native crash.
public enum NativeCrash {
    private static let signals: [Int: String] = [
        4: "SIGILL",
        5: "SIGTRAP",
        6: "SIGABRT",
        7: "SIGBUS",
        8: "SIGFPE",
        9: "SIGKILL",
        11: "SIGSEGV",
        31: "SIGSYS",
    ]

    /// "signal 11 (SIGSEGV)": the status of a native crash exit is the signal number.
    public static func signal(_ status: Int) -> String {
        "signal \(status)" + (signals[status].map { " (\($0))" } ?? "")
    }

    /// The readable parts of a tombstone. Android gives it as a protocol buffer: rather than embedding its schema,
    /// every run of printable ASCII of at least `minLength` characters is kept (abort message, signal, library paths,
    /// function names of the backtrace), one per line, cut at `maxChars`.
    public static func printableStrings(_ bytes: [UInt8], minLength: Int = 4, maxChars: Int = 64_000) -> String {
        var out: [UInt8] = []
        var run: [UInt8] = []
        var last: [UInt8]?

        func flush() {
            if run.count >= minLength {
                if run != last, out.count < maxChars {
                    out += run
                    out.append(0x0A)
                    last = run
                }
            }
            run.removeAll(keepingCapacity: true)
        }
        for c in bytes {
            if (0x20 ... 0x7E).contains(c) || c == 0x09 {
                run.append(c)
            } else {
                flush()
            }
        }
        flush()
        if out.count > maxChars { out.removeLast(out.count - maxChars) }
        return String(decoding: out, as: UTF8.self)
    }

    /// The Vulkan version advertised by `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`: major.minor.patch packed.
    public static func vulkanVersion(_ packed: Int) -> String {
        let bits = UInt32(truncatingIfNeeded: packed)
        return "\(bits >> 22).\((bits >> 12) & 0x3FF).\(bits & 0xFFF)"
    }

    /// The OpenGL ES version of `ConfigurationInfo.reqGlEsVersion`: major in the high 16 bits.
    public static func glEsVersion(_ packed: Int) -> String {
        let bits = UInt32(truncatingIfNeeded: packed)
        return "\(bits >> 16).\(bits & 0xFFFF)"
    }
}

/// One past exit of a process of the app, as `ActivityManager.getHistoricalProcessExitReasons` lists them.
public struct ProcessExit<T> {
    public let timestampMillis: Int64
    public let nativeCrash: Bool
    public let processName: String
    /// The platform record it was read from, handed back by the selection.
    public let source: T

    public init(timestampMillis: Int64, nativeCrash: Bool, processName: String, source: T) {
        self.timestampMillis = timestampMillis
        self.nativeCrash = nativeCrash
        self.processName = processName
        self.source = source
    }
}

extension ProcessExit: Equatable where T: Equatable {}

extension ProcessExit: Hashable where T: Hashable {}

extension ProcessExit: Sendable where T: Sendable {}

public enum ExitSelection {
    /// The native crash to report at start-up, if any: the latest one of `mainProcess` that happened with the installed
    /// app (after `installedAtMillis`) and after the previous check (`checkedUpToMillis`), so that a crash is reported
    /// once and a crash of an older version never is.
    public static func nativeCrashToReport<T>(
        _ exits: [ProcessExit<T>],
        _ mainProcess: String,
        _ installedAtMillis: Int64,
        _ checkedUpToMillis: Int64
    ) -> ProcessExit<T>? {
        let after = max(installedAtMillis, checkedUpToMillis)
        return exits
            .filter { $0.nativeCrash && $0.processName == mainProcess && $0.timestampMillis > after }
            .max { $0.timestampMillis < $1.timestampMillis }
    }

    /// The new bound of the checks: nothing up to it is reported again.
    public static func checkedUpTo<T>(_ exits: [ProcessExit<T>], _ checkedUpToMillis: Int64) -> Int64 {
        max(checkedUpToMillis, exits.map(\.timestampMillis).max() ?? 0)
    }
}

/// What the crash and error reports share: the date line and Kotlin's `trimEnd` and `isBlank`.
enum CrashText {
    /// Java `DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")` in `zone`: `2026-09-21 14:13:20 +00:00`.
    static func date(_ millis: Int64, _ zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss xxx"
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// Kotlin `trimEnd()`: without the whitespace at the end.
    static func trimEnd(_ text: String) -> String {
        var scalars = text.unicodeScalars
        while let last = scalars.last, KotlinText.isWhitespace(last) {
            scalars.removeLast()
        }
        return String(scalars)
    }

    /// Kotlin `isBlank()`: empty, or only whitespace.
    static func isBlank(_ text: String) -> Bool {
        text.unicodeScalars.allSatisfy(KotlinText.isWhitespace)
    }
}
