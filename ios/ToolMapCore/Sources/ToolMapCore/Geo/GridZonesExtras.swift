// Port of the rest of core/src/main/kotlin/fr/toolmap/core/geo/GridZones.kt (ZonePiece, pieces, edges, meeting),
// beside Geo/GridZones.swift (P24.2).

/// Where the grid of `zone` is drawn: a rectangle in the longitudes of the region, `shift` degrees more than the
/// zone's own (a world copy past the antimeridian).
public struct ZonePiece: Equatable, Hashable, Sendable {
    public let zone: Int
    public let area: Bounds
    public let shift: Double

    public init(zone: Int, area: Bounds, shift: Double) {
        self.zone = zone
        self.area = area
        self.shift = shift
    }
}

extension GridZones {
    /// The world copies a region can meet: its longitudes run past 180° on a screen across the antimeridian.
    private static let worldShifts: [Double] = [-360.0, 0.0, 360.0]

    /// The parts of `area` with one zone's grid, the bands of a zone that share its edges joined as one (P24.2): the
    /// screen and a printed sheet draw each one with the grid of its zone, cut at its edges. The longitudes of `area`
    /// may run past 180° (a region across the antimeridian).
    public static func pieces(_ area: Bounds) -> [ZonePiece] {
        var pieces: [ZonePiece] = []
        for shift in worldShifts {
            // Kotlin `groupBy { Triple(zone, west, east) }`: the columns in the order they first appear.
            var columns: [(zone: Int, west: Double, east: Double)] = []
            var members: [[GridZone]] = []
            for z in coveringShifted(area, shift) {
                if let index = columns.firstIndex(where: { $0.zone == z.zone && $0.west == z.west && $0.east == z.east }) {
                    members[index].append(z)
                } else {
                    columns.append((zone: z.zone, west: z.west, east: z.east))
                    members.append([z])
                }
            }
            for (index, column) in columns.enumerated() {
                // South to north, the bands that touch are joined: their lines run on.
                var runs: [(south: Double, north: Double)] = []
                for z in members[index].sorted(by: { $0.south < $1.south }) {
                    if let last = runs.last, last.north == z.south {
                        runs[runs.count - 1] = (south: last.south, north: z.north)
                    } else {
                        runs.append((south: z.south, north: z.north))
                    }
                }
                let west = Swift.max(column.west + shift, area.minX)
                let east = Swift.min(column.east + shift, area.maxX)
                for run in runs {
                    let s = Swift.max(run.south, area.minY)
                    let n = Swift.min(run.north, area.maxY)
                    if east > west, n > s {
                        let bounds = Bounds(minX: west, minY: s, maxX: east, maxY: n)
                        pieces.append(ZonePiece(zone: column.zone, area: bounds, shift: shift))
                    }
                }
            }
        }
        return pieces
    }

    /// The edges of the grid zones inside `area`, each once: meridians and parallels, straight on the map.
    public static func edges(_ area: Bounds) -> [[LonLat]] {
        var edges: [[LonLat]] = []
        var seen = Set<[LonLat]>()
        func add(_ edge: [LonLat]) {
            if seen.insert(edge).inserted { edges.append(edge) }
        }
        for shift in worldShifts {
            for z in coveringShifted(area, shift) {
                let south = Swift.max(z.south, area.minY)
                let north = Swift.min(z.north, area.maxY)
                for lon in [z.west + shift, z.east + shift] {
                    if lon > area.minX, lon < area.maxX {
                        add([LonLat(lon: lon, lat: south), LonLat(lon: lon, lat: north)])
                    }
                }
                let west = Swift.max(z.west + shift, area.minX)
                let east = Swift.min(z.east + shift, area.maxX)
                for lat in [z.south, z.north] {
                    if lat > area.minY, lat < area.maxY {
                        add([LonLat(lon: west, lat: lat), LonLat(lon: east, lat: lat)])
                    }
                }
            }
        }
        return edges
    }

    /// The grid zones that meet `area`, whose longitudes may run past 180°; each once.
    public static func meeting(_ area: Bounds) -> [GridZone] {
        var zones: [GridZone] = []
        var seen = Set<GridZone>()
        for shift in worldShifts {
            for z in coveringShifted(area, shift) {
                if seen.insert(z).inserted { zones.append(z) }
            }
        }
        return zones
    }

    /// The grid zones the region meets in the world copy `shift`.
    private static func coveringShifted(_ area: Bounds, _ shift: Double) -> [GridZone] {
        let west = Swift.max(area.minX - shift, -180.0)
        let east = Swift.min(area.maxX - shift, 180.0)
        if east > west {
            return covering(Bounds(minX: west, minY: area.minY, maxX: east, maxY: area.maxY))
        }
        return []
    }
}
