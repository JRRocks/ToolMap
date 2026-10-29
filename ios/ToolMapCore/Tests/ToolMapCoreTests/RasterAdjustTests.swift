// Port of core/src/test/kotlin/fr/toolmap/core/layers/RasterAdjustTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct RasterAdjustTests {
    private func layer(contrast: Float = 0, brightness: Float = 0, saturation: Float = 0, hue: Float = 0) -> LayerConfig {
        LayerConfig(id: "l", sourceId: "s", contrast: contrast, brightness: brightness, saturation: saturation, hue: hue)
    }

    /// Applies a 4 x 5 matrix to a colour in 0..1 (the offset column is in 0..255).
    private func apply(_ m: [Float], _ rgb: [Float]) -> [Float] {
        (0 ..< 3).map { (row: Int) -> Float in
            let r = m[row * 5] * rgb[0]
            let g = m[row * 5 + 1] * rgb[1]
            let b = m[row * 5 + 2] * rgb[2]
            return r + g + b + m[row * 5 + 4] / 255
        }
    }

    /// The raster shader of the map, written straight from its description.
    private func shader(_ rgb: [Float], _ contrast: Float, _ brightness: Float, _ saturation: Float, _ hue: Float = 0) -> [Float] {
        let sat: Float = saturation >= 0 ? 1 - 1 / (1.001 - saturation) : -saturation
        let con: Float = contrast > 0 ? 1 / (1 - contrast) : 1 + contrast
        let range = RasterAdjust.brightnessRange(brightness)
        // Hue first: MapLibre's spin weights, dot(rgb, w.xyz), dot(rgb, w.zxy), dot(rgb, w.yzx).
        let a = Angle.radians(Double(hue))
        let root3 = 3.0.squareRoot()
        let w: [Float] = [
            Float((2 * cos(a) + 1) / 3),
            Float((-root3 * sin(a) - cos(a) + 1) / 3),
            Float((root3 * sin(a) - cos(a) + 1) / 3),
        ]
        let spun: [Float] = [
            rgb[0] * w[0] + rgb[1] * w[1] + rgb[2] * w[2],
            rgb[0] * w[2] + rgb[1] * w[0] + rgb[2] * w[1],
            rgb[0] * w[1] + rgb[1] * w[2] + rgb[2] * w[0],
        ]
        let average: Float = (rgb[0] + rgb[1] + rgb[2]) / 3
        return (0 ..< 3).map { (i: Int) -> Float in
            let s = spun[i] + (average - spun[i]) * sat
            let c = (s - 0.5) * con + 0.5
            return range.min + (range.max - range.min) * c
        }
    }

    private let samples: [[Float]] = [
        [0.2, 0.5, 0.8],
        [1, 0, 0],
        [0.5, 0.5, 0.5],
        [0.9, 0.85, 0.1],
    ]

    @Test("a layer without adjustments needs no filter")
    func noFilter() {
        #expect(RasterAdjust.colorMatrix(layer()) == nil)
    }

    @Test("the matrix does what the shader does for any setting")
    func matrixIsShader() throws {
        for contrast: Float in [-0.6, 0, 0.3] {
            for brightness: Float in [-0.4, 0, 0.5] {
                for saturation: Float in [-1, -0.3, 0, 0.4] {
                    if contrast == 0, brightness == 0, saturation == 0 { continue }
                    let m = try #require(RasterAdjust.colorMatrix(layer(contrast: contrast, brightness: brightness, saturation: saturation)))
                    for c in samples {
                        let expected = shader(c, contrast, brightness, saturation)
                        let actual = apply(m, c)
                        for i in 0 ..< 3 {
                            #expect(abs(expected[i] - actual[i]) <= 1e-4, "c=\(contrast) b=\(brightness) s=\(saturation)")
                        }
                    }
                }
            }
        }
    }

    @Test("full desaturation gives grey and the alpha row is left alone")
    func desaturation() throws {
        let m = try #require(RasterAdjust.colorMatrix(layer(saturation: -1)))
        let grey = apply(m, [0.9, 0.3, 0.0])
        #expect(abs(grey[0] - 0.4) <= 1e-5)
        #expect(abs(grey[0] - grey[1]) <= 1e-6)
        #expect(abs(grey[1] - grey[2]) <= 1e-6)
        #expect(Array(m[15 ..< 20]) == [0, 0, 0, 1, 0])
    }

    @Test("contrast and brightness extremes stay finite")
    func extremes() throws {
        let m = try #require(RasterAdjust.colorMatrix(layer(contrast: 1, brightness: 1, saturation: 1)))
        #expect(m.allSatisfy { $0.isFinite })
    }

    @Test("an inverted layer turns black into white and white into black")
    func inverted() throws {
        let inverted = LayerConfig(id: "l", sourceId: "s", inverted: true)
        #expect(inverted.hasAdjustments)
        let m = try #require(RasterAdjust.colorMatrix(inverted))
        let black = apply(m, [0, 0, 0])
        let white = apply(m, [1, 1, 1])
        let colour = apply(m, [0.2, 0.5, 0.9])
        // The shader's neutral saturation is 0.001, not 0 exactly, hence the small tolerance.
        for i in 0 ..< 3 {
            #expect(abs(black[i] - 1) <= 1e-3)
            #expect(abs(white[i] - 0) <= 1e-3)
        }
        #expect(abs(colour[0] - 0.8) <= 1e-3)
        #expect(abs(colour[1] - 0.5) <= 1e-3)
        #expect(abs(colour[2] - 0.1) <= 1e-3)
    }

    @Test("the inverted range is the normal one read backwards")
    func invertedRange() {
        let normal = RasterAdjust.brightnessRange(0.3)
        let flipped = RasterAdjust.brightnessRange(0.3, inverted: true)
        #expect(normal.min == flipped.max)
        #expect(normal.max == flipped.min)
        #expect(RasterAdjust.brightnessRange(0, inverted: true) == BrightnessRange(1, 0))
    }

    @Test("the style gets the swapped brightness range for an inverted layer")
    func styleInverted() throws {
        let stack = LayerStack().add("osm").setInverted("l1", true)
        let desired = StackToStyle.desired(stack) { _ in true }
        let props = try #require(desired.first?.props)
        #expect(props.brightnessMin == 1)
        #expect(props.brightnessMax == 0)
    }

    @Test("the night mode darkens every map, inverted or not")
    func nightMode() throws {
        let stack = LayerStack().add("osm")
        let nightLayers = StackToStyle.desired(stack, darkening: 0.6) { _ in true }
        let night = try #require(nightLayers.first?.props)
        #expect(night.brightnessMin == 0)
        #expect(abs(night.brightnessMax - 0.4) <= 1e-6)
        let invertedLayers = StackToStyle.desired(stack.setInverted("l1", true), darkening: 0.6) { _ in true }
        let inverted = try #require(invertedLayers.first?.props)
        #expect(abs(inverted.brightnessMin - 0.4) <= 1e-6)
        #expect(inverted.brightnessMax == 0)
        let plain = StackToStyle.desired(stack) { _ in true }
        let darkened = StackToStyle.desired(stack, darkening: 0) { _ in true }
        #expect(plain == darkened)
    }

    @Test("the matrix does what the shader does with a hue, alone or with the other settings")
    func matrixWithHue() throws {
        let settings: [(Float, Float, Float)] = [(0, 0, 0), (0.3, -0.4, -0.3), (-0.6, 0.5, 0.4), (0, 0, -1)]
        for hue: Float in [-150, -45, 30, 120, 180] {
            for (contrast, brightness, saturation) in settings {
                let adjusted = layer(contrast: contrast, brightness: brightness, saturation: saturation, hue: hue)
                let m = try #require(RasterAdjust.colorMatrix(adjusted))
                for c in samples {
                    let expected = shader(c, contrast, brightness, saturation, hue)
                    let actual = apply(m, c)
                    for i in 0 ..< 3 {
                        #expect(abs(expected[i] - actual[i]) <= 1e-4, "h=\(hue) c=\(contrast) s=\(saturation)")
                    }
                }
            }
        }
    }

    @Test("a third of a turn sends red to green, green to blue and blue to red, and keeps grey")
    func thirdOfATurn() throws {
        let m = try #require(RasterAdjust.colorMatrix(layer(hue: 120)))
        let red = apply(m, [1, 0, 0])
        let green = apply(m, [0, 1, 0])
        let grey = apply(m, [0.4, 0.4, 0.4])
        // The shader's neutral saturation is 0.001, not 0 exactly, hence the small tolerance.
        for (i, v) in [Float(0), 1, 0].enumerated() {
            #expect(abs(red[i] - v) <= 1e-3)
        }
        for (i, v) in [Float(0), 0, 1].enumerated() {
            #expect(abs(green[i] - v) <= 1e-3)
        }
        for v in grey {
            #expect(abs(v - 0.4) <= 1e-5)
        }
        // A full turn either way is the same.
        let back = try #require(RasterAdjust.colorMatrix(layer(hue: -180)))
        let forth = try #require(RasterAdjust.colorMatrix(layer(hue: 180)))
        for i in back.indices {
            #expect(abs(back[i] - forth[i]) <= 1e-5)
        }
    }

    @Test("a hue alone is an adjustment, and the style gets it clamped to half a turn")
    func hueAlone() throws {
        #expect(layer(hue: 15).hasAdjustments)
        let stack = LayerStack().add("osm").setHue("l1", 400)
        #expect(stack["l1"]?.hue == RasterAdjust.maxHue)
        let clamped = StackToStyle.desired(stack) { _ in true }
        #expect(clamped.first?.props.hueRotate == 180)
        let turned = StackToStyle.desired(LayerStack().add("osm").setHue("l1", -75)) { _ in true }
        #expect(turned.first?.props.hueRotate == -75)
        let plain = StackToStyle.desired(LayerStack().add("osm")) { _ in true }
        #expect(plain.first?.props.hueRotate == 0)
    }
}
