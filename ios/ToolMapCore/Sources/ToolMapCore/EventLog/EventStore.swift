// Port of core/src/main/kotlin/fr/toolmap/core/eventlog/EventStore.kt.

import Foundation

/// The end of a job of the writing queue, waited for by the caller.
private final class EventStoreSignal: @unchecked Sendable {
    let done = DispatchSemaphore(value: 0)
}

/// Where the event log is kept on the phone (P21): `current.log` and the file before it, `previous.log`, of at most
/// `maxFileBytes` each, so the log never takes more than twice that. Every line has a sequence number that goes on
/// across launches; `epoch` names this log and changes only when its folder is emptied (app data cleared), so that a
/// reader never mixes two logs with the same numbers.
///
/// Lines are written to disk by a serial queue (logging never waits for the disk); the last `memoryLines` lines also
/// stay in memory for `linesAfter` (the debug build sends them to the test collector).
public final class EventStore: EventSink, @unchecked Sendable {
    public static let current = "current.log"
    public static let previous = "previous.log"
    public static let epochFile = "epoch"
    public static let defaultMaxFileBytes: Int64 = 1 << 20
    public static let defaultMemoryLines = 256
    private static let epochBytes = 4
    private static let tailBytes = 16 * 1024
    private static let flushWaitMs: Int64 = 5000

    private let dir: URL
    private let maxFileBytes: Int64
    private let memoryLines: Int
    private let zone: TimeZone
    /// Every line is also given here as it is made (the console in the debug build).
    private let mirror: (@Sendable (String) -> Void)?
    private let currentFile: URL
    private let previousFile: URL
    private let lock = NSLock()
    /// The last lines, oldest first, from `ringStart` on (the slots before it are spent).
    private var ring: [(Int64, String)] = []
    private var ringStart = 0
    private let queue = DispatchQueue(label: "toolmap-eventlog")
    /// Used on `queue` only.
    private var out: FileHandle?
    private var written: Int64 = 0
    private var closed = false

    public let epoch: String
    private var lastSeqValue: Int64 = 0

    /// The number of the last line made (written or about to be).
    public var lastSeq: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return lastSeqValue
    }

    public init(
        _ dir: URL,
        maxFileBytes: Int64 = EventStore.defaultMaxFileBytes,
        memoryLines: Int = EventStore.defaultMemoryLines,
        zone: TimeZone = TimeZone.current,
        mirror: (@Sendable (String) -> Void)? = nil
    ) {
        self.dir = dir
        self.maxFileBytes = maxFileBytes
        self.memoryLines = memoryLines
        self.zone = zone
        self.mirror = mirror
        currentFile = dir.appendingPathComponent(EventStore.current)
        previousFile = dir.appendingPathComponent(EventStore.previous)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        epoch = EventStore.readOrMakeEpoch(dir)
        lastSeqValue = EventStore.lastSeqOnDisk([currentFile, previousFile])
    }

    public func write(_ event: Event) {
        lock.lock()
        lastSeqValue += 1
        let seq = lastSeqValue
        let line = EventLine.format(seq, event, zone)
        ring.append((seq, line))
        while ring.count - ringStart > memoryLines {
            ringStart += 1
        }
        if ringStart > 1024, ringStart * 2 > ring.count {
            ring.removeFirst(ringStart)
            ringStart = 0
        }
        lock.unlock()
        queue.async { [self] in append(line) }
        mirror?(line)
    }

    /// The lines after number `seq`, oldest first, at most `maxBytes` of text but one line at least: from memory when
    /// it still has them, else from the files (older lines; ask again for the next ones).
    public func linesAfter(_ seq: Int64, _ maxBytes: Int) -> [String] {
        lock.lock()
        let memory = Array(ring[ringStart...])
        lock.unlock()
        if let first = memory.first?.0, first <= seq + 1 {
            return take(memory.filter { $0.0 > seq }, maxBytes)
        }
        let firstInMemory = memory.first?.0 ?? Int64.max
        var fromFiles: [String] = []
        var bytes = 0
        for file in [previousFile, currentFile] {
            if bytes >= maxBytes { continue }
            guard let data = try? Data(contentsOf: file) else { continue }
            let text = String(decoding: data, as: UTF8.self)
            for line in EventStore.lines(text) {
                guard let n = EventLine.seqOf(line) else { continue }
                // Lines still in memory come from memory: the file may hold the last one half written.
                if n <= seq || n >= firstInMemory { continue }
                let size = line.utf16.count + 1
                if bytes > 0, bytes + size > maxBytes { break }
                fromFiles.append(line)
                bytes += size
            }
        }
        if !fromFiles.isEmpty { return fromFiles }
        return take(memory.filter { $0.0 > seq }, maxBytes)
    }

    /// Waits until every line made so far is on disk (tests, and before reading the files).
    public func flush(_ timeoutMs: Int64 = EventStore.flushWaitMs) {
        let signal = EventStoreSignal()
        queue.async { [self] in
            try? out?.synchronize()
            signal.done.signal()
        }
        _ = signal.done.wait(timeout: .now() + .milliseconds(Int(timeoutMs)))
    }

    public func close() {
        flush()
        let signal = EventStoreSignal()
        queue.async { [self] in
            try? out?.close()
            out = nil
            closed = true
            signal.done.signal()
        }
        _ = signal.done.wait(timeout: .now() + .milliseconds(Int(EventStore.flushWaitMs)))
    }

    private func take(_ lines: [(Int64, String)], _ maxBytes: Int) -> [String] {
        var result: [String] = []
        var bytes = 0
        for (_, line) in lines {
            let size = line.utf16.count + 1
            if !result.isEmpty, bytes + size > maxBytes { break }
            result.append(line)
            bytes += size
        }
        return result
    }

    /// On `queue`.
    private func append(_ line: String) {
        if closed { return }
        do {
            let size = Int64(line.utf16.count + 1)
            if out == nil { try open() }
            if written > 0, written + size > maxFileBytes { try rotate() }
            try out?.write(contentsOf: Data((line + "\n").utf8))
            written += size
        } catch {
            // Disk full or folder gone: the line stays in memory only.
            try? out?.close()
            out = nil
        }
    }

    private func open() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        if !fm.fileExists(atPath: currentFile.path) {
            fm.createFile(atPath: currentFile.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: currentFile)
        let end = try handle.seekToEnd()
        written = Int64(end)
        out = handle
    }

    private func rotate() throws {
        try? out?.close()
        out = nil
        let fm = FileManager.default
        try? fm.removeItem(at: previousFile)
        do {
            try fm.moveItem(at: currentFile, to: previousFile)
        } catch {
            try? fm.removeItem(at: currentFile)
        }
        try open()
    }

    private static func readOrMakeEpoch(_ dir: URL) -> String {
        let file = dir.appendingPathComponent(epochFile)
        if let data = try? Data(contentsOf: file) {
            let known = kotlinTrim(String(decoding: data, as: UTF8.self))
            if !known.isEmpty { return known }
        }
        let made = P2PHex.encode(SystemSecureRandom().nextBytes(epochBytes))
        try? Data(made.utf8).write(to: file)
        return made
    }

    /// The number of the last complete line of the newest file that has one.
    private static func lastSeqOnDisk(_ files: [URL]) -> Int64 {
        for file in files {
            guard let handle = try? FileHandle(forReadingFrom: file) else { continue }
            defer { try? handle.close() }
            guard let size = try? handle.seekToEnd(), size > 0 else { continue }
            let from = size > UInt64(tailBytes) ? size - UInt64(tailBytes) : 0
            guard (try? handle.seek(toOffset: from)) != nil, let data = try? handle.readToEnd() else { continue }
            let tail = String(decoding: data, as: UTF8.self)
            // Only lines followed by a line break are complete.
            guard let lastBreak = tail.unicodeScalars.lastIndex(of: "\n") else { continue }
            let complete = tail.unicodeScalars[..<lastBreak]
            for line in complete.split(separator: "\n", omittingEmptySubsequences: false).reversed() {
                if let n = EventLine.seqOf(String(String.UnicodeScalarView(line))) { return n }
            }
        }
        return 0
    }

    /// Kotlin `lineSequence()`: lines cut at `\r\n`, `\n` or `\r` (a Swift `Character` holds `\r\n` as one).
    private static func lines(_ text: String) -> [String] {
        var out: [String] = []
        var current = String.UnicodeScalarView()
        var previousCR = false
        for scalar in text.unicodeScalars {
            if scalar == "\n", previousCR {
                previousCR = false
                continue
            }
            previousCR = false
            if scalar == "\n" || scalar == "\r" {
                out.append(String(current))
                current = String.UnicodeScalarView()
                previousCR = scalar == "\r"
            } else {
                current.append(scalar)
            }
        }
        // A last empty line after the final break is not a line.
        if !current.isEmpty { out.append(String(current)) }
        return out
    }
}
