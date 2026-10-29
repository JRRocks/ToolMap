// Port of core/src/main/kotlin/fr/toolmap/core/print/ScaleBarPlan.kt.

import Foundation

/// One block of the graphic scale: from `startMm` to `endMm` on the paper, black or white.
public struct ScaleBarBlock: Equatable, Hashable, Sendable {
    public let startMm: Double
    public let endMm: Double
    public let black: Bool

    public init(startMm: Double, endMm: Double, black: Bool) {
        self.startMm = startMm
        self.endMm = endMm
        self.black = black
    }
}

/// A tick label of the graphic scale, `xMm` from the bar's left end.
public struct ScaleBarLabel: Equatable, Hashable, Sendable {
    public let xMm: Double
    public let text: String

    public init(xMm: Double, text: String) {
        self.xMm = xMm
        self.text = text
    }
}

/// The graphic scale of a printed map: alternating black and white blocks and their labels.
public struct ScaleBarPlan: Equatable, Hashable, Sendable {
    public let totalMeters: Double
    public let widthMm: Double
    public let blocks: [ScaleBarBlock]
    public let labels: [ScaleBarLabel]

    public init(totalMeters: Double, widthMm: Double, blocks: [ScaleBarBlock], labels: [ScaleBarLabel]) {
        self.totalMeters = totalMeters
        self.widthMm = widthMm
        self.blocks = blocks
        self.labels = labels
    }

    public static let empty = ScaleBarPlan(totalMeters: 0.0, widthMm: 0.0, blocks: [], labels: [])
}

/// Builds the graphic scale: a round length that fits in `maxWidthMm` at 1:`scale`, cut in even blocks.
public enum ScaleBarPlanner {
    private static let km = 1000.0

    public static func plan(_ scale: Double, maxWidthMm: Double, locale: Locale = .current) -> ScaleBarPlan {
        if !(scale > 0) || !(maxWidthMm > 0) { return .empty }
        let total = ScaleBar.niceDistance(PrintMath.groundMeters(maxWidthMm, scale))
        if total <= 0.0 { return .empty }
        let widthMm = total * 1000.0 / scale
        let blocksCount = blocksFor(total)
        let blockMeters = total / Double(blocksCount)
        var blocks: [ScaleBarBlock] = []
        for i in 0 ..< blocksCount {
            blocks.append(
                ScaleBarBlock(
                    startMm: Double(i) * blockMeters * 1000.0 / scale,
                    endMm: Double(i + 1) * blockMeters * 1000.0 / scale,
                    black: i % 2 == 0
                )
            )
        }
        let inKm = total >= km
        var labels: [ScaleBarLabel] = []
        for i in 0 ... blocksCount {
            let meters = Double(i) * blockMeters
            let value = inKm ? meters / km : meters
            let unit = i == blocksCount ? (inKm ? " km" : " m") : ""
            labels.append(ScaleBarLabel(xMm: meters * 1000.0 / scale, text: formatNumber(value, locale) + unit))
        }
        return ScaleBarPlan(totalMeters: total, widthMm: widthMm, blocks: blocks, labels: labels)
    }

    /// 1 × 10^n → 5 blocks (0.2), 2 × 10^n → 4 blocks (0.5), 5 × 10^n → 5 blocks (1).
    private static func blocksFor(_ total: Double) -> Int {
        let mantissa = total / Foundation.pow(10.0, floor(log10(total)))
        if mantissa < 1.5 { return 5 }
        if mantissa < 3.5 { return 4 }
        return 5
    }

    private static func formatNumber(_ value: Double, _ locale: Locale) -> String {
        let rounded = Double(kotlinRound(value * 100.0)) / 100.0
        if rounded == floor(rounded) {
            return String(kotlinToLong(rounded))
        }
        return trim(rounded, locale)
    }

    private static func trim(_ value: Double, _ locale: Locale) -> String {
        var text = PrintLocaleFormat.fixed(value, 2, locale)
        while text.hasSuffix("0") {
            text.removeLast()
        }
        while text.hasSuffix(",") || text.hasSuffix(".") {
            text.removeLast()
        }
        return text
    }
}
