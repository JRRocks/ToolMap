import Foundation

// Port of core/src/main/kotlin/fr/toolmap/core/crash/ErrorReport.kt.

/// The report the user sends by hand from « Plus… › Signaler une erreur » (P16.3, AlpineQuest's « Report errors »):
/// the build and the phone, the last crash report if there was one, and the end of the app's own log. Nothing leaves
/// the phone unless the user shares it; the text is plain so that it can be read before it is sent.
public enum ErrorReport {
    public static func text(
        _ device: CrashDevice,
        _ atMillis: Int64,
        _ lastCrash: String?,
        _ log: String?,
        zone: TimeZone = .current
    ) -> String {
        var out = ""
        func appendLine(_ line: String = "") {
            out += line
            out += "\n"
        }
        appendLine("ToolMap \(device.appVersion) (\(device.commit))")
        appendLine("when: \(CrashText.date(atMillis, zone))")
        appendLine("device: \(device.device)")
        appendLine("android: \(device.androidRelease) (API \(device.apiLevel))")
        appendLine("abis: \(device.abis.joined(separator: ", "))")
        appendLine("opengl es: \(device.glEsVersion)")
        appendLine("vulkan: \(device.vulkanVersion ?? "none")")
        if let crash = lastCrash, !CrashText.isBlank(crash) {
            appendLine()
            appendLine("--- last crash report ---")
            appendLine(CrashText.trimEnd(crash))
        }
        if let logText = log, !CrashText.isBlank(logText) {
            appendLine()
            appendLine("--- log ---")
            appendLine(CrashText.trimEnd(logText))
        }
        return out
    }
}
