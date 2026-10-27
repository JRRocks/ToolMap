package fr.toolmap.core.crash

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The report the user sends by hand from « Plus… › Signaler une erreur » (P16.3, AlpineQuest's « Report errors »):
 * the build and the phone, the last crash report if there was one, and the end of the app's own log. Nothing leaves
 * the phone unless the user shares it; the text is plain so that it can be read before it is sent.
 */
object ErrorReport {
    fun text(
        device: CrashDevice,
        atMillis: Long,
        lastCrash: String?,
        log: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        buildString {
            appendLine("ToolMap ${device.appVersion} (${device.commit})")
            appendLine("when: ${DATE.withZone(zone).format(Instant.ofEpochMilli(atMillis))}")
            appendLine("device: ${device.device}")
            appendLine("android: ${device.androidRelease} (API ${device.apiLevel})")
            appendLine("abis: ${device.abis.joinToString(", ")}")
            appendLine("opengl es: ${device.glEsVersion}")
            appendLine("vulkan: ${device.vulkanVersion ?: "none"}")
            if (!lastCrash.isNullOrBlank()) {
                appendLine()
                appendLine("--- last crash report ---")
                appendLine(lastCrash.trimEnd())
            }
            if (!log.isNullOrBlank()) {
                appendLine()
                appendLine("--- log ---")
                appendLine(log.trimEnd())
            }
        }

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
}
