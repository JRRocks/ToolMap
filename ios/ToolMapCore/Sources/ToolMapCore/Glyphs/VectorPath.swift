// SVG path data (`d`, the same syntax as `android:pathData` in Android's vector drawables) read into a CoreGraphics
// path: the outlines of the glyphs of the waypoints, the activities and the share tokens (Glyphs.swift), copied from
// Android's drawables by ios/tools/glyphs.py.

#if canImport(CoreGraphics)
    import CoreGraphics
    import Foundation

    /// Path data that cannot be read: what is wrong, and where (offset in UTF-8 bytes from the start of the data).
    public struct VectorPathError: Error, Equatable, Sendable, CustomStringConvertible {
        public let message: String
        public let offset: Int

        public init(_ message: String, offset: Int) {
            self.message = message
            self.offset = offset
        }

        public var description: String { "\(message) at \(offset)" }
    }

    /// Reads SVG path data: the commands M L H V C S Q T A Z, absolute in upper case, relative to the current point in
    /// lower case. The numbers of a command may repeat it (after a moveto they are linetos); numbers may be written
    /// together (`1.5.5` is 1.5 then .5, `1-2` is 1 then -2) and carry an exponent (`1e-3`); spaces, tabs, line breaks
    /// and commas separate them; the flags of an arc are single digits (`0120 0` is 0, 1, 20, 0). Arcs become cubic
    /// Bézier curves of at most a quarter turn each (SVG 1.1, appendix F.6). The coordinates stay those of the data
    /// (in a viewport, y counts downwards); `transform` maps them on the way.
    public enum VectorPath {
        /// The path of `data`; empty data gives an empty path. Throws `VectorPathError` on data that is not valid.
        public static func parse(_ data: String, transform: CGAffineTransform = .identity) throws -> CGPath {
            let path = CGMutablePath()
            try append(data, to: path, transform: transform)
            return path
        }

        /// Adds the subpaths of `data` to `path`. Throws `VectorPathError` on data that is not valid (`path` then holds
        /// what was read before the error).
        public static func append(
            _ data: String,
            to path: CGMutablePath,
            transform: CGAffineTransform = .identity
        ) throws {
            var reader = PathDataReader(bytes: Array(data.utf8), path: path, transform: transform)
            try reader.read()
        }
    }

    /// The ASCII bytes the reader looks for.
    private enum PathByte {
        static let plus = UInt8(ascii: "+")
        static let minus = UInt8(ascii: "-")
        static let dot = UInt8(ascii: ".")
        static let zero = UInt8(ascii: "0")
        static let one = UInt8(ascii: "1")
        static let lowerE = UInt8(ascii: "e")
        static let upperE = UInt8(ascii: "E")
        static let moveTo = UInt8(ascii: "M")
        static let moveToRelative = UInt8(ascii: "m")
        static let lineTo = UInt8(ascii: "L")
        static let lineToRelative = UInt8(ascii: "l")
        static let closeRelative = UInt8(ascii: "z")
        static let firstLowerCase = UInt8(ascii: "a")
        /// Set in a lower-case ASCII letter, clear in its upper case.
        static let caseBit: UInt8 = 0x20
        static let commands: Set<UInt8> = Set("MmLlHhVvCcSsQqTtAaZz".utf8)

        static func isDigit(_ byte: UInt8) -> Bool {
            switch byte {
            case 0x30 ... 0x39: true
            default: false
            }
        }

        static func startsNumber(_ byte: UInt8) -> Bool {
            switch byte {
            case 0x30 ... 0x39, 0x2B, 0x2D, 0x2E: true
            default: false
            }
        }

        /// Space, tab, line feed, form feed, carriage return and comma.
        static func isSeparator(_ byte: UInt8) -> Bool {
            switch byte {
            case 0x20, 0x09, 0x0A, 0x0C, 0x0D, 0x2C: true
            default: false
            }
        }
    }

    /// A point of the path data, in `Double` (converted to `CGPoint` only for CoreGraphics).
    private struct DataPoint {
        var x: Double
        var y: Double

        var cgPoint: CGPoint { CGPoint(x: x, y: y) }
    }

    /// An ellipse of an arc: centre, radii, and the cosine and sine of its rotation.
    private struct ArcEllipse {
        let centerX: Double
        let centerY: Double
        let radiusX: Double
        let radiusY: Double
        let cosine: Double
        let sine: Double

        /// The point at parameter `angle` (SVG 1.1, F.6.3).
        func point(_ angle: Double) -> DataPoint {
            let along = radiusX * cos(angle)
            let across = radiusY * sin(angle)
            return DataPoint(x: centerX + along * cosine - across * sine, y: centerY + along * sine + across * cosine)
        }

        /// The derivative of `point` at `angle`.
        func tangent(_ angle: Double) -> DataPoint {
            let along = -radiusX * sin(angle)
            let across = radiusY * cos(angle)
            return DataPoint(x: along * cosine - across * sine, y: along * sine + across * cosine)
        }
    }

    private struct PathDataReader {
        let bytes: [UInt8]
        let path: CGMutablePath
        let transform: CGAffineTransform
        var index = 0
        /// The command whose numbers are being read; nil before the first one.
        var command: UInt8?
        var current = DataPoint(x: 0, y: 0)
        /// The first point of the current subpath, where Z goes back to.
        var start = DataPoint(x: 0, y: 0)
        /// True when no subpath is open: before the first moveto and after a Z.
        var needsMove = true
        /// The second control point of the previous C or S, reflected by an S that follows at once.
        var cubicControl: DataPoint?
        /// The control point of the previous Q or T, reflected by a T that follows at once.
        var quadControl: DataPoint?

        init(bytes: [UInt8], path: CGMutablePath, transform: CGAffineTransform) {
            self.bytes = bytes
            self.path = path
            self.transform = transform
        }

        mutating func read() throws {
            while true {
                skipSeparators()
                guard index < bytes.count else { return }
                let byte = bytes[index]
                if PathByte.commands.contains(byte) {
                    if command == nil, byte != PathByte.moveTo, byte != PathByte.moveToRelative {
                        throw VectorPathError("path data must start with a moveto", offset: index)
                    }
                    index += 1
                    command = byte
                    if (byte | PathByte.caseBit) == PathByte.closeRelative {
                        close()
                    } else {
                        try segment(byte)
                    }
                } else if PathByte.startsNumber(byte) {
                    // More numbers repeat the command; after a moveto, they are linetos.
                    guard let previous = command, (previous | PathByte.caseBit) != PathByte.closeRelative else {
                        throw VectorPathError("number without a command", offset: index)
                    }
                    var repeated = previous
                    if previous == PathByte.moveTo {
                        repeated = PathByte.lineTo
                    } else if previous == PathByte.moveToRelative {
                        repeated = PathByte.lineToRelative
                    }
                    command = repeated
                    try segment(repeated)
                } else {
                    throw VectorPathError("unexpected character", offset: index)
                }
            }
        }

        /// One group of numbers of `letter`, read and added to the path.
        mutating func segment(_ letter: UInt8) throws {
            let relative = letter >= PathByte.firstLowerCase
            let origin = relative ? current : DataPoint(x: 0, y: 0)
            var nextCubic: DataPoint?
            var nextQuad: DataPoint?
            switch Unicode.Scalar(letter & ~PathByte.caseBit) {
            case "M":
                let point = try pair(origin)
                path.move(to: point.cgPoint, transform: transform)
                start = point
                current = point
                needsMove = false
            case "L":
                let point = try pair(origin)
                lineTo(point)
            case "H":
                let x = try number() + origin.x
                lineTo(DataPoint(x: x, y: current.y))
            case "V":
                let y = try number() + origin.y
                lineTo(DataPoint(x: current.x, y: y))
            case "C":
                let control1 = try pair(origin)
                let control2 = try pair(origin)
                let end = try pair(origin)
                curveTo(control1, control2, end)
                nextCubic = control2
            case "S":
                let control2 = try pair(origin)
                let end = try pair(origin)
                curveTo(reflection(cubicControl), control2, end)
                nextCubic = control2
            case "Q":
                let control = try pair(origin)
                let end = try pair(origin)
                quadTo(control, end)
                nextQuad = control
            case "T":
                let end = try pair(origin)
                let control = reflection(quadControl)
                quadTo(control, end)
                nextQuad = control
            case "A":
                let radiusX = try number()
                let radiusY = try number()
                let rotation = try number()
                let large = try flag()
                let sweep = try flag()
                let end = try pair(origin)
                openSubpath()
                arc(to: end, radiusX: radiusX, radiusY: radiusY, rotation: rotation, large: large, sweep: sweep)
                current = end
            default:
                throw VectorPathError("unknown command", offset: index)
            }
            cubicControl = nextCubic
            quadControl = nextQuad
        }

        /// The previous control point mirrored through the current point, or the current point when there is none.
        func reflection(_ control: DataPoint?) -> DataPoint {
            guard let previous = control else { return current }
            return DataPoint(x: 2 * current.x - previous.x, y: 2 * current.y - previous.y)
        }

        /// A drawing command right after a Z starts a new subpath at the point Z went back to.
        mutating func openSubpath() {
            guard needsMove else { return }
            path.move(to: current.cgPoint, transform: transform)
            start = current
            needsMove = false
        }

        mutating func close() {
            if !needsMove { path.closeSubpath() }
            current = start
            needsMove = true
            cubicControl = nil
            quadControl = nil
        }

        mutating func lineTo(_ point: DataPoint) {
            openSubpath()
            path.addLine(to: point.cgPoint, transform: transform)
            current = point
        }

        mutating func curveTo(_ control1: DataPoint, _ control2: DataPoint, _ end: DataPoint) {
            openSubpath()
            path.addCurve(to: end.cgPoint, control1: control1.cgPoint, control2: control2.cgPoint, transform: transform)
            current = end
        }

        mutating func quadTo(_ control: DataPoint, _ end: DataPoint) {
            openSubpath()
            path.addQuadCurve(to: end.cgPoint, control: control.cgPoint, transform: transform)
            current = end
        }

        /// An elliptical arc from the current point to `end`, as cubic curves (SVG 1.1, F.6.5 and F.6.6).
        func arc(to end: DataPoint, radiusX: Double, radiusY: Double, rotation: Double, large: Bool, sweep: Bool) {
            let begin = current
            // The same point at both ends: no arc. A zero radius: a straight line.
            if begin.x == end.x, begin.y == end.y { return }
            var rx = abs(radiusX)
            var ry = abs(radiusY)
            if rx == 0 || ry == 0 {
                path.addLine(to: end.cgPoint, transform: transform)
                return
            }
            let angle = rotation * Double.pi / 180
            let cosine = cos(angle)
            let sine = sin(angle)
            // F.6.5.1: the start point in the frame of the ellipse, from the middle of the chord.
            let halfX = (begin.x - end.x) / 2
            let halfY = (begin.y - end.y) / 2
            let x1 = cosine * halfX + sine * halfY
            let y1 = cosine * halfY - sine * halfX
            // F.6.6.2: radii too small to join the two points grow until they do.
            let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
            if lambda > 1 {
                let grow = lambda.squareRoot()
                rx *= grow
                ry *= grow
            }
            // F.6.5.2: the centre in the frame of the ellipse, on the side the two flags choose.
            let rx2 = rx * rx
            let ry2 = ry * ry
            let denominator = rx2 * y1 * y1 + ry2 * x1 * x1
            let numerator = rx2 * ry2 - denominator
            var coefficient = denominator > 0 ? Swift.max(0, numerator / denominator).squareRoot() : 0
            if large == sweep { coefficient = -coefficient }
            let centerX1 = coefficient * rx * y1 / ry
            let centerY1 = -(coefficient * ry * x1 / rx)
            // F.6.5.3: the centre in the frame of the data.
            let ellipse = ArcEllipse(
                centerX: cosine * centerX1 - sine * centerY1 + (begin.x + end.x) / 2,
                centerY: sine * centerX1 + cosine * centerY1 + (begin.y + end.y) / 2,
                radiusX: rx,
                radiusY: ry,
                cosine: cosine,
                sine: sine
            )
            // F.6.5.5 and F.6.5.6: the start angle, and the angle swept in the direction of the sweep flag.
            let theta1 = atan2((y1 - centerY1) / ry, (x1 - centerX1) / rx)
            let theta2 = atan2((-y1 - centerY1) / ry, (-x1 - centerX1) / rx)
            var delta = theta2 - theta1
            if sweep, delta < 0 {
                delta += 2 * Double.pi
            } else if !sweep, delta > 0 {
                delta -= 2 * Double.pi
            }
            // Numbers so large that the squares overflow: the chord stands in for the arc.
            guard delta.isFinite, ellipse.centerX.isFinite, ellipse.centerY.isFinite, rx.isFinite, ry.isFinite else {
                path.addLine(to: end.cgPoint, transform: transform)
                return
            }
            // At most a quarter turn per cubic curve; the last one ends exactly on `end`.
            let turns = abs(delta) / (Double.pi / 2) - 1e-7
            let count = Swift.max(1, Int(turns.rounded(.up)))
            let step = delta / Double(count)
            let handle = 4.0 / 3.0 * tan(step / 4)
            var pieceStart = begin
            var angleStart = theta1
            for piece in 1 ... count {
                let angleEnd = piece == count ? theta1 + delta : theta1 + step * Double(piece)
                let pieceEnd = piece == count ? end : ellipse.point(angleEnd)
                let tangentStart = ellipse.tangent(angleStart)
                let tangentEnd = ellipse.tangent(angleEnd)
                let control1 = DataPoint(
                    x: pieceStart.x + handle * tangentStart.x,
                    y: pieceStart.y + handle * tangentStart.y
                )
                let control2 = DataPoint(x: pieceEnd.x - handle * tangentEnd.x, y: pieceEnd.y - handle * tangentEnd.y)
                path.addCurve(
                    to: pieceEnd.cgPoint,
                    control1: control1.cgPoint,
                    control2: control2.cgPoint,
                    transform: transform
                )
                pieceStart = pieceEnd
                angleStart = angleEnd
            }
        }

        mutating func pair(_ origin: DataPoint) throws -> DataPoint {
            let x = try number()
            let y = try number()
            return DataPoint(x: x + origin.x, y: y + origin.y)
        }

        /// An arc flag: one digit, 0 or 1, that may touch the next number.
        mutating func flag() throws -> Bool {
            skipSeparators()
            guard index < bytes.count, bytes[index] == PathByte.zero || bytes[index] == PathByte.one else {
                throw VectorPathError("arc flag (0 or 1) expected", offset: index)
            }
            let value = bytes[index] == PathByte.one
            index += 1
            return value
        }

        /// A number: sign, digits, a dot and digits (either part may be missing, not both), an exponent. It ends where
        /// its syntax ends, so that `1.5.5` and `1-2` are two numbers each.
        mutating func number() throws -> Double {
            skipSeparators()
            let begin = index
            var cursor = index
            var text = ""
            if cursor < bytes.count, bytes[cursor] == PathByte.plus || bytes[cursor] == PathByte.minus {
                if bytes[cursor] == PathByte.minus { text = "-" }
                cursor += 1
            }
            let integerStart = cursor
            cursor = digits(from: cursor)
            let integerEnd = cursor
            var fraction: Range<Int>?
            if cursor < bytes.count, bytes[cursor] == PathByte.dot {
                let fractionStart = cursor + 1
                cursor = digits(from: fractionStart)
                fraction = fractionStart ..< cursor
            }
            let fractionCount = fraction?.count ?? 0
            guard integerEnd > integerStart || fractionCount > 0 else {
                throw VectorPathError("number expected", offset: begin)
            }
            // Written again in the form `Double(_:)` reads: digits on both sides of the dot.
            text += integerEnd > integerStart ? ascii(integerStart ..< integerEnd) : "0"
            if let fractionDigits = fraction {
                text += "."
                text += fractionDigits.isEmpty ? "0" : ascii(fractionDigits)
            }
            if cursor < bytes.count, bytes[cursor] == PathByte.lowerE || bytes[cursor] == PathByte.upperE {
                var exponentStart = cursor + 1
                var exponentSign = ""
                if exponentStart < bytes.count {
                    let sign = bytes[exponentStart]
                    if sign == PathByte.plus || sign == PathByte.minus {
                        if sign == PathByte.minus { exponentSign = "-" }
                        exponentStart += 1
                    }
                }
                let exponentEnd = digits(from: exponentStart)
                // An "e" without digits is not part of the number (and fails as the next token).
                if exponentEnd > exponentStart {
                    text += "e" + exponentSign + ascii(exponentStart ..< exponentEnd)
                    cursor = exponentEnd
                }
            }
            guard let value = Double(text), value.isFinite else {
                throw VectorPathError("number out of range", offset: begin)
            }
            index = cursor
            return value
        }

        func digits(from position: Int) -> Int {
            var cursor = position
            while cursor < bytes.count, PathByte.isDigit(bytes[cursor]) {
                cursor += 1
            }
            return cursor
        }

        func ascii(_ range: Range<Int>) -> String {
            String(decoding: bytes[range], as: UTF8.self)
        }

        mutating func skipSeparators() {
            while index < bytes.count, PathByte.isSeparator(bytes[index]) {
                index += 1
            }
        }
    }
#endif
