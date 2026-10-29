// Port of core/src/main/kotlin/fr/toolmap/core/print/PrintContent.kt.

import Foundation

/// A track as it is drawn on the page: its segments, colour and width.
public struct PrintTrack: Equatable, Sendable {
    public let name: String
    /// `0xAARRGGBB`.
    public let colorArgb: Int
    /// Line width on the paper, in millimeters.
    public let widthMm: Double
    public let segments: [[LonLat]]

    public init(name: String, colorArgb: Int, widthMm: Double, segments: [[LonLat]]) {
        self.name = name
        self.colorArgb = colorArgb
        self.widthMm = widthMm
        self.segments = segments
    }
}

public struct PrintWaypoint: Equatable, Sendable {
    public let name: String?
    public let lat: Double
    public let lon: Double
    /// `0xAARRGGBB`.
    public let colorArgb: Int
    /// The icon of the waypoint (catalogue id), whose white glyph is drawn on the disc when `PrintContent.glyphs` has
    /// it.
    public let iconId: String?

    public init(name: String?, lat: Double, lon: Double, colorArgb: Int, iconId: String? = nil) {
        self.name = name
        self.lat = lat
        self.lon = lon
        self.colorArgb = colorArgb
        self.iconId = iconId
    }
}

/// The words written on the page. The defaults are English so the layout can be tested; the app passes the user's
/// language. `%s` / `%1$s` / `%d` are filled the way Java's `String.format` does, with the page locale.
public struct PrintTexts: Equatable, Hashable, Sendable {
    public var legendTitle: String
    public var layers: String
    public var tracks: String
    public var waypoints: String
    public var printedOn: String
    public var gridDescription: String
    public var declination: String
    public var gridConvergence: String
    public var page: String
    public var overviewTitle: String
    public var producedWith: String
    public var missingTiles: String
    public var oversampled: String
    public var east: String
    public var west: String
    public var north: String
    public var scaleLabel: String
    public var coordinateSystem: String
    // ---- sheet of P17
    public var south: String
    public var mapSource: String
    public var cmOnGround: String
    public var scaleNote: String
    public var declinationDetail: String
    public var gridSquares: String
    public var gridLines: String
    public var trueNorth: String
    public var magneticNorth: String
    public var gridNorth: String
    public var trueNorthName: String
    public var annualChange: String
    public var gridMagneticAngle: String
    public var notToScale: String
    public var noGrid: String
    public var principalDigits: String
    /// MGRS: what the two figures of a line are (ADR-087).
    public var squareKilometers: String
    public var zoneEdgeMeridian: String
    public var zoneEdgeParallel: String
    public var neighbourGrid: String
    public var and: String
    public var squares: String
    public var squaresOfZones: String
    public var westSide: String
    public var eastSide: String
    public var southSide: String
    public var northSide: String
    public var scaleHeading: String
    public var northHeading: String
    public var gridHeading: String

    public init(
        legendTitle: String = "Legend",
        layers: String = "Layers",
        tracks: String = "Tracks",
        waypoints: String = "Waypoints (%d)",
        printedOn: String = "Printed on %s",
        gridDescription: String = "%1$s grid, %2$s",
        declination: String = "Declination",
        gridConvergence: String = "Grid convergence",
        page: String = "Page %1$d of %2$d",
        overviewTitle: String = "Overview of the %d pages",
        producedWith: String = "Made with ToolMap",
        missingTiles: String = "Hatched areas: tiles not available",
        oversampled: String = "%1$s: enlarged x%2$s",
        east: String = "E",
        west: String = "W",
        north: String = "N",
        scaleLabel: String = "1:%s",
        coordinateSystem: String = "WGS84 / Web Mercator",
        south: String = "S",
        mapSource: String = "Map: %s",
        cmOnGround: String = "1 cm on the map = %s on the ground",
        scaleNote: String = "exact at the centre, ±%s %% at the edges",
        declinationDetail: String = "in %1$d, changing %2$s a year",
        gridSquares: String = "Squares of %s",
        gridLines: String = "Lines every %s",
        trueNorth: String = "TN",
        magneticNorth: String = "MN",
        gridNorth: String = "GN",
        trueNorthName: String = "true north",
        annualChange: String = "annual change %s",
        gridMagneticAngle: String = "Grid\u{2013}magnetic angle",
        notToScale: String = "Diagram not to scale",
        noGrid: String = "No grid",
        principalDigits: String = "Large figures: kilometers",
        squareKilometers: String = "= %s km inside the 100 km square",
        zoneEdgeMeridian: String = "Zones %1$s / %2$s boundary: meridian %3$s (thick line)",
        zoneEdgeParallel: String = "Bands %1$s / %2$s boundary: parallel %3$s (thick line)",
        neighbourGrid: String = "In blue in the margin: grid of the neighbouring zone",
        and: String = "and",
        squares: String = "100 km square: %s",
        squaresOfZones: String = "100 km squares: %s",
        westSide: String = "west",
        eastSide: String = "east",
        southSide: String = "south",
        northSide: String = "north",
        scaleHeading: String = "SCALE",
        northHeading: String = "NORTHS",
        gridHeading: String = "GRID"
    ) {
        self.legendTitle = legendTitle
        self.layers = layers
        self.tracks = tracks
        self.waypoints = waypoints
        self.printedOn = printedOn
        self.gridDescription = gridDescription
        self.declination = declination
        self.gridConvergence = gridConvergence
        self.page = page
        self.overviewTitle = overviewTitle
        self.producedWith = producedWith
        self.missingTiles = missingTiles
        self.oversampled = oversampled
        self.east = east
        self.west = west
        self.north = north
        self.scaleLabel = scaleLabel
        self.coordinateSystem = coordinateSystem
        self.south = south
        self.mapSource = mapSource
        self.cmOnGround = cmOnGround
        self.scaleNote = scaleNote
        self.declinationDetail = declinationDetail
        self.gridSquares = gridSquares
        self.gridLines = gridLines
        self.trueNorth = trueNorth
        self.magneticNorth = magneticNorth
        self.gridNorth = gridNorth
        self.trueNorthName = trueNorthName
        self.annualChange = annualChange
        self.gridMagneticAngle = gridMagneticAngle
        self.notToScale = notToScale
        self.noGrid = noGrid
        self.principalDigits = principalDigits
        self.squareKilometers = squareKilometers
        self.zoneEdgeMeridian = zoneEdgeMeridian
        self.zoneEdgeParallel = zoneEdgeParallel
        self.neighbourGrid = neighbourGrid
        self.and = and
        self.squares = squares
        self.squaresOfZones = squaresOfZones
        self.westSide = westSide
        self.eastSide = eastSide
        self.southSide = southSide
        self.northSide = northSide
        self.scaleHeading = scaleHeading
        self.northHeading = northHeading
        self.gridHeading = gridHeading
    }
}

/// What goes on the pages besides the map tiles; everything is optional.
public struct PrintContent: Sendable {
    public let tracks: [PrintTrack]
    public let waypoints: [PrintWaypoint]
    /// `© …` credits of the sources printed, required by their licences.
    public let attributions: [String]
    /// The layers actually drawn, bottom to top, for the legend.
    public let layerNames: [String]
    /// Magnetic declination at the centre in degrees, east positive; nil when unknown.
    public let declinationDegrees: Double?
    /// Year the declination model was evaluated for.
    public let declinationYear: Int?
    /// How much the declination changes in a year, in degrees (east positive).
    public let declinationAnnualDegrees: Double?
    /// White glyphs of the waypoint icons, by icon id, with their transparency.
    public let glyphs: [String: PdfImage]
    public let dateText: String
    public let texts: PrintTexts
    public let locale: Locale

    public init(
        tracks: [PrintTrack] = [],
        waypoints: [PrintWaypoint] = [],
        attributions: [String] = [],
        layerNames: [String] = [],
        declinationDegrees: Double? = nil,
        declinationYear: Int? = nil,
        declinationAnnualDegrees: Double? = nil,
        glyphs: [String: PdfImage] = [:],
        dateText: String = "",
        texts: PrintTexts = PrintTexts(),
        locale: Locale = Locale(identifier: "en_US_POSIX")
    ) {
        self.tracks = tracks
        self.waypoints = waypoints
        self.attributions = attributions
        self.layerNames = layerNames
        self.declinationDegrees = declinationDegrees
        self.declinationYear = declinationYear
        self.declinationAnnualDegrees = declinationAnnualDegrees
        self.glyphs = glyphs
        self.dateText = dateText
        self.texts = texts
        self.locale = locale
    }
}

/// Number and angle formats shared by the page elements.
public enum PrintFormat {
    private static let group = 3
    private static let metersPerKm = 1_000.0
    private static let wholeKmFromM = 100_000.0
    private static let maxTitleInName = 48
    private static let minutesPerDegree = 60.0

    /// Characters a file system or a share sheet dislikes, as the JVM reads `[\\/:*?"<>|·\p{Cntrl}\s]+`.
    private static let unsafeInName: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"[\\/:*?"<>|\x{00B7}\x{0000}-\x{001F}\x{007F} \t\n\x{000B}\f\r]+"#
    )

    /// `1:25 000`: the scale with a no-break space as thousands separator, the way a French map writes it.
    public static func scale(_ denominator: Double, _ texts: PrintTexts) -> String {
        let digits = Array(String(kotlinRound(denominator)))
        var grouped = ""
        for (i, c) in digits.enumerated() {
            if i > 0, (digits.count - i) % group == 0 { grouped += "\u{00A0}" }
            grouped.append(c)
        }
        return PrintLocaleFormat.format(PrintLocaleFormat.root, texts.scaleLabel, grouped)
    }

    /// `2°15′ E`: degrees and minutes of an angle, with the side given by `east` / `west`.
    public static func declination(_ degrees: Double, _ east: String, _ west: String) -> String {
        let totalMinutes = kotlinRound(abs(degrees) * minutesPerDegree)
        let d = totalMinutes / 60
        let m = totalMinutes % 60
        let side = degrees >= 0 ? east : west
        return "\(d)°\(PrintLocaleFormat.padded(m, 2))\u{2032} \(side)"
    }

    /// The ground size of a frame, both sides written alike in `locale`: "6,6 km" and "3,7 km" (one decimal under
    /// 100 km, none above), or "850 m" and "480 m" under a kilometer.
    public static func groundSize(_ widthMeters: Double, _ heightMeters: Double, _ locale: Locale) -> (String, String) {
        let largest = Swift.max(widthMeters, heightMeters)
        func side(_ meters: Double) -> String {
            if largest < metersPerKm { return PrintLocaleFormat.fixed(meters, 0, locale) + " m" }
            if largest < wholeKmFromM { return PrintLocaleFormat.fixed(meters / metersPerKm, 1, locale) + " km" }
            return PrintLocaleFormat.fixed(meters / metersPerKm, 0, locale) + " km"
        }
        return (side(widthMeters), side(heightMeters))
    }

    /// A file name for the PDF: `ToolMap_Belledonne_1-25000_2026-10-02.pdf`. Characters a file system or a share
    /// sheet dislikes become `_`; a blank title gives "carte".
    public static func fileName(_ title: String, _ scale: Double, _ isoDate: String) -> String {
        var cleaned = kotlinTrim(title)
        if let regex = unsafeInName {
            let range = NSRange(cleaned.startIndex ..< cleaned.endIndex, in: cleaned)
            cleaned = regex.stringByReplacingMatches(in: cleaned, options: [], range: range, withTemplate: "_")
        }
        var scalars = Array(cleaned.unicodeScalars)
        while let first = scalars.first, first == "_" || first == "." {
            scalars.removeFirst()
        }
        while let last = scalars.last, last == "_" || last == "." {
            scalars.removeLast()
        }
        var name = String(String.UnicodeScalarView(scalars.prefix(maxTitleInName)))
        if name.isEmpty { name = "carte" }
        return "ToolMap_\(name)_1-\(kotlinRound(scale))_\(isoDate).pdf"
    }
}
