// Ports of core/src/test/kotlin/fr/toolmap/core/crash/CrashReportTest.kt and ErrorReportTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

/// Kotlin `lines()` of a text that only has `\n` line breaks.
private func lines(_ text: String) -> [String] {
    text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
}

private func utc() throws -> TimeZone {
    try #require(TimeZone(secondsFromGMT: 0))
}

private let frame = "\tat fr.toolmap.ToolMapApp.onCreate(ToolMapApp.kt:31)"

struct CrashReportTests {
    private let device = CrashDevice(
        appVersion: "0.1.0 (12)",
        commit: "abc1234",
        device: "samsung SM-A515F",
        androidRelease: "13",
        apiLevel: 33,
        abis: ["arm64-v8a", "armeabi-v7a"],
        glEsVersion: "3.2",
        vulkanVersion: "1.1.0"
    )

    @Test("the report gives the build, the phone and the error")
    func buildPhoneAndError() throws {
        let report = CrashReport(
            kind: .java,
            atMillis: 1_790_000_000_000,
            process: "fr.toolmap",
            thread: "main",
            headline: "java.lang.IllegalStateException: boom",
            details: "java.lang.IllegalStateException: boom\n\(frame)\n",
            device: device,
            log: "10-03 12:00:00.000 I ToolMap: start\n"
        )
        let zone = try utc()
        let all = lines(report.toText(zone: zone))
        #expect(all[0] == "ToolMap 0.1.0 (12) (abc1234)")
        #expect(all[1] == "when: 2026-09-21 14:13:20 +00:00")
        #expect(all.contains("kind: java exception"))
        #expect(all.contains("device: samsung SM-A515F"))
        #expect(all.contains("android: 13 (API 33)"))
        #expect(all.contains("abis: arm64-v8a, armeabi-v7a"))
        #expect(all.contains("opengl es: 3.2"))
        #expect(all.contains("vulkan: 1.1.0"))
        #expect(all.contains("process: fr.toolmap, thread: main"))
        #expect(all.contains(frame))
        let logIndex = try #require(all.firstIndex(of: "--- log ---"))
        let frameIndex = try #require(all.firstIndex(of: frame))
        #expect(logIndex > frameIndex)
        #expect(all.contains("10-03 12:00:00.000 I ToolMap: start"))
    }

    @Test("a phone without vulkan and a report without log say so plainly")
    func noVulkanNoLog() throws {
        let noVulkan = CrashDevice(
            appVersion: device.appVersion,
            commit: device.commit,
            device: device.device,
            androidRelease: device.androidRelease,
            apiLevel: device.apiLevel,
            abis: device.abis,
            glEsVersion: device.glEsVersion,
            vulkanVersion: nil
        )
        let zone = try utc()
        let text = CrashReport(
            kind: .native,
            atMillis: 0,
            process: "fr.toolmap",
            thread: nil,
            headline: "signal 11 (SIGSEGV)",
            details: "",
            device: noVulkan
        ).toText(zone: zone)
        #expect(lines(text).contains("vulkan: none"))
        #expect(lines(text).contains("kind: native crash"))
        #expect(lines(text).contains("process: fr.toolmap"))
        #expect(!text.contains("--- log ---"))
    }

    @Test("signals are named")
    func signals() {
        #expect(NativeCrash.signal(11) == "signal 11 (SIGSEGV)")
        #expect(NativeCrash.signal(6) == "signal 6 (SIGABRT)")
        #expect(NativeCrash.signal(99) == "signal 99")
    }

    @Test("the readable parts of a tombstone are kept one per line")
    func tombstoneStrings() {
        var bytes: [UInt8] = [0x0A, 0x12]
        bytes += Array("Abort message: 'vk::createInstance failed'".utf8)
        bytes += [0x1A, 0x03]
        bytes += Array("abc".utf8)
        bytes += [0x00]
        bytes += Array("/data/app/fr.toolmap/lib/arm64/libmaplibre.so".utf8)
        bytes += [0x12, 0x05]
        bytes += Array("/data/app/fr.toolmap/lib/arm64/libmaplibre.so".utf8)
        let text = NativeCrash.printableStrings(bytes)
        #expect(
            lines(text) == ["Abort message: 'vk::createInstance failed'", "/data/app/fr.toolmap/lib/arm64/libmaplibre.so", ""],
            "short runs are noise, a repeated line is written once"
        )
    }

    @Test("a huge tombstone is cut")
    func hugeTombstone() {
        let parts = (0 ..< 1000).map { i -> String in
            let start = "frame \(i) "
            return start + String(repeating: "x", count: 100 - start.count)
        }
        let bytes = Array(parts.joined(separator: "\u{0001}").utf8)
        #expect(NativeCrash.printableStrings(bytes, maxChars: 500).count == 500)
    }

    @Test("packed graphics versions are decoded")
    func packedVersions() {
        #expect(NativeCrash.vulkanVersion((1 << 22) | (1 << 12)) == "1.1.0")
        #expect(NativeCrash.vulkanVersion((1 << 22) | (3 << 12) | 215) == "1.3.215")
        #expect(NativeCrash.glEsVersion(0x0003_0002) == "3.2")
    }

    @Test("only a new native crash of the main process with this apk is reported")
    func nativeCrashSelection() {
        func processExit(_ at: Int64, native: Bool = true, process: String = "fr.toolmap") -> ProcessExit<String> {
            ProcessExit(timestampMillis: at, nativeCrash: native, processName: process, source: "exit@\(at)")
        }
        let installed: Int64 = 1_000
        let exits = [
            processExit(500), // older APK
            processExit(1_500, native: false), // a normal exit, a kill, a java crash (reported by the handler)
            processExit(1_600, process: "fr.toolmap:crash"), // the report screen itself
            processExit(1_700),
            processExit(1_800),
        ]
        #expect(ExitSelection.nativeCrashToReport(exits, "fr.toolmap", installed, 0)?.source == "exit@1800")
        #expect(ExitSelection.checkedUpTo(exits, 0) == 1_800)
        #expect(ExitSelection.nativeCrashToReport(exits, "fr.toolmap", installed, 1_800) == nil, "already reported")
        #expect(ExitSelection.nativeCrashToReport(exits, "fr.toolmap", 2_000, 0) == nil, "all before the install")
        #expect(ExitSelection.nativeCrashToReport([ProcessExit<String>](), "fr.toolmap", 0, 0) == nil)
        #expect(ExitSelection.checkedUpTo([ProcessExit<String>](), 42) == 42)
    }
}

struct ErrorReportTests {
    private let device = CrashDevice(
        appVersion: "0.4.0 (1)",
        commit: "local",
        device: "Google Pixel 9",
        androidRelease: "16",
        apiLevel: 36,
        abis: ["arm64-v8a"],
        glEsVersion: "3.2",
        vulkanVersion: nil
    )

    @Test("the report gives the build and the phone first")
    func buildAndPhoneFirst() throws {
        let zone = try utc()
        let all = lines(ErrorReport.text(device, 1_790_000_000_000, nil, nil, zone: zone))
        #expect(all[0] == "ToolMap 0.4.0 (1) (local)")
        #expect(all[1] == "when: 2026-09-21 14:13:20 +00:00")
        #expect(all.contains("device: Google Pixel 9"))
        #expect(all.contains("vulkan: none"))
    }

    @Test("the last crash and the log are added only when there are some")
    func crashAndLogOnlyWhenThere() throws {
        let zone = try utc()
        let bare = ErrorReport.text(device, 0, "  ", "", zone: zone)
        #expect(!bare.contains("last crash report"))
        #expect(!bare.contains("--- log ---"))
        let full = ErrorReport.text(device, 0, "boom\n", "12:00 I ToolMap: start\n", zone: zone)
        #expect(full.contains("--- last crash report ---\nboom\n"))
        #expect(full.hasSuffix("--- log ---\n12:00 I ToolMap: start\n"))
    }
}
