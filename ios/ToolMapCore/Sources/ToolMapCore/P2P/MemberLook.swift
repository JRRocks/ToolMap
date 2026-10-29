// Port of core/src/main/kotlin/fr/toolmap/core/p2p/MemberLook.kt.

import Foundation

/// How a member is drawn for the others, like a game token on a minimap: a silhouette (or its initials) in a free
/// colour on a contrasting disc ringed with that colour, or its own picture. Icon and colour travel as one 32-bit
/// number (the `color` of pairing codes, HELLO, PEERS and of the server's presence beacon): the icon id in the top
/// byte, the RGB colour below. A top byte that is not a known icon (`0xFF` from the ARGB colours of version 0.4.0)
/// means the initials. The picture itself travels in an AVATAR message (`Msg.Avatar`); until it arrives, the initials
/// stand in.
public enum MemberLook {
    /// The two letters of the name, as before icons existed.
    public static let initials = 0

    /// The member's own picture (`Msg.Avatar`).
    public static let picture = 0x7F

    /// The largest picture accepted, encoded (a 64 px square JPEG is about 2 KB).
    public static let maxPictureBytes = 6 * 1024

    /// The side of the square a picture is reduced to before it is shared.
    public static let picturePx = 64

    /// The tokens of the picker, at most 15 with the initials; the id is the index and travels on the wire, so a token
    /// is only ever appended. The keys name the drawables (`ic_token_<key>`, silhouettes of game-icons.net).
    public static let icons: [String] = [
        "initials",
        "chess_knight",
        "wolf_head",
        "fox_head",
        "eagle_head",
        "octopus",
        "dinosaur_rex",
        "unicorn",
        "dragon_head",
        "ghost",
        "alien_skull",
        "robot_golem",
        "wizard_face",
        "ninja_head",
        "viking_head",
    ]

    private static let rgbMask: Int32 = 0xFFFFFF
    private static let opaque = Int32(bitPattern: 0xFF00_0000)
    private static let iconShift: Int32 = 24
    private static let white = Int32(bitPattern: 0xFFFF_FFFF)
    private static let nearBlack = Int32(bitPattern: 0xFF20_2124)
    private static let lumaR = 0.299
    private static let lumaG = 0.587
    private static let lumaB = 0.114
    private static let lumaThreshold = 140.0

    public static func pack(_ icon: Int, _ argb: Int32) -> Int32 {
        (Int32(iconOf(icon)) << iconShift) | (argb & rgbMask)
    }

    /// The icon id of `look`; an unknown one gives `initials`.
    public static func icon(_ look: Int32) -> Int {
        iconOf(Int(UInt32(bitPattern: look) >> 24))
    }

    /// The opaque colour of `look`.
    public static func argb(_ look: Int32) -> Int32 { look | opaque }

    /// `true` when white reads better than black on `argb`.
    public static func isDark(_ argb: Int32) -> Bool {
        let r = Double((argb >> 16) & 0xFF)
        let g = Double((argb >> 8) & 0xFF)
        let b = Double(argb & 0xFF)
        return r * lumaR + g * lumaG + b * lumaB < lumaThreshold
    }

    /// The disc under a silhouette of colour `argb`: white under a dark colour, near-black under a light one, so that
    /// every colour of the wheel stays readable on any map.
    public static func backdrop(_ argb: Int32) -> Int32 { isDark(argb) ? white : nearBlack }

    private static func iconOf(_ id: Int) -> Int {
        id >= 0 && id < icons.count || id == picture ? id : initials
    }

    /// The name of the map image of a member: one per icon and colour, per name for `initials` (and for a picture not
    /// received yet), per picture content for `picture`.
    public static func imageKey(_ look: Int32, _ name: String, _ picture: [UInt8]? = nil) -> String {
        let iconId = icon(look)
        let rgb = hex(UInt32(bitPattern: look & rgbMask), 6)
        if iconId == MemberLook.picture, let bytes = picture {
            return "tm-p-\(hex(UInt32(bitPattern: javaHashCode(bytes)), 8))-\(bytes.count)"
        }
        if iconId == MemberLook.initials || iconId == MemberLook.picture {
            return "tm-m-\(rgb)-\(MemberPalette.initials(name))"
        }
        return "tm-m-\(rgb)-\(icons[iconId])"
    }

    /// `java.util.Arrays.hashCode(byte[])`.
    static func javaHashCode(_ bytes: [UInt8]) -> Int32 {
        var h: Int32 = 1
        for b in bytes {
            h = h &* 31 &+ Int32(Int8(bitPattern: b))
        }
        return h
    }

    /// `%0<width>x`.
    private static func hex(_ value: UInt32, _ width: Int) -> String {
        let digits = String(value, radix: 16)
        return digits.count >= width ? digits : String(repeating: "0", count: width - digits.count) + digits
    }
}

/// The colour wheel of the picker: hue around the circle, saturation from the centre (white) to the rim.
public enum ColorWheel {
    private static let degrees = 360.0
    private static let sector = 60.0
    private static let channel = 255.0
    private static let opaque = Int32(bitPattern: 0xFF00_0000)

    /// Hue (degrees), saturation and value (0..1) of an ARGB colour.
    public static func toHsv(_ argb: Int32) -> [Float] {
        let r = Double((argb >> 16) & 0xFF) / channel
        let g = Double((argb >> 8) & 0xFF) / channel
        let b = Double(argb & 0xFF) / channel
        let maxValue = Swift.max(r, g, b)
        let delta = maxValue - Swift.min(r, g, b)
        let hue: Double
        if delta == 0.0 {
            hue = 0.0
        } else if maxValue == r {
            hue = sector * p2pFloorMod((g - b) / delta, 6.0)
        } else if maxValue == g {
            hue = sector * ((b - r) / delta + 2)
        } else {
            hue = sector * ((r - g) / delta + 4)
        }
        let saturation = maxValue == 0.0 ? 0.0 : delta / maxValue
        return [Float(hue), Float(saturation), Float(maxValue)]
    }

    /// The opaque ARGB colour of `hue` (degrees), `saturation` and `value` (0..1).
    public static func fromHsv(_ hue: Float, _ saturation: Float, _ value: Float) -> Int32 {
        let h = p2pFloorMod(Double(hue), degrees) / sector
        let s = Swift.min(Swift.max(Double(saturation), 0.0), 1.0)
        let v = Swift.min(Swift.max(Double(value), 0.0), 1.0)
        let c = v * s
        let x = c * (1 - abs(p2pFloorMod(h, 2.0) - 1))
        let rgb: (Double, Double, Double) = switch kotlinToInt(h) {
        case 0: (c, x, 0.0)
        case 1: (x, c, 0.0)
        case 2: (0.0, c, x)
        case 3: (0.0, x, c)
        case 4: (x, 0.0, c)
        default: (c, 0.0, x)
        }
        let m = v - c
        func channelOf(_ f: Double) -> Int32 {
            let rounded = Int32(truncatingIfNeeded: kotlinRound((f + m) * ColorWheel.channel))
            return Swift.min(Swift.max(rounded, 0), 0xFF)
        }
        return ColorWheel.opaque | (channelOf(rgb.0) << 16) | (channelOf(rgb.1) << 8) | channelOf(rgb.2)
    }

    /// Hue and saturation under a touch at (`dx`, `dy`) from the centre of a wheel of `radius` (screen axes, y down,
    /// red to the right and hues turning clockwise); a touch outside the wheel gives the rim.
    public static func hueSatAt(_ dx: Float, _ dy: Float, _ radius: Float) -> (Float, Float) {
        let hue = Float(p2pFloorMod(Angle.degrees(atan2(Double(dy), Double(dx))), degrees))
        let saturation = Float(Swift.min(Swift.max(hypot(Double(dx), Double(dy)) / Double(radius), 0.0), 1.0))
        return (hue, saturation)
    }

    /// Where `hue` and `saturation` lie on a wheel of `radius`, from its centre: the inverse of `hueSatAt`.
    public static func pointOf(_ hue: Float, _ saturation: Float, _ radius: Float) -> (Float, Float) {
        let a = Angle.radians(Double(hue))
        let d = Swift.min(Swift.max(saturation, 0), 1) * radius
        return (Float(cos(a) * Double(d)), Float(sin(a) * Double(d)))
    }
}

/// Kotlin `Double.mod(Double)`: the remainder with the sign of the divisor.
private func p2pFloorMod(_ a: Double, _ b: Double) -> Double {
    let r = a.truncatingRemainder(dividingBy: b)
    if r != 0, (r < 0) != (b < 0) { return r + b }
    return r
}
