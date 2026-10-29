// Port of core/src/main/kotlin/fr/toolmap/core/print/GridSpacing.kt.

/// The spacing of the grid lines from the scale (P17, Q12): squares of 2 to 5 cm on the paper, the spacings printed
/// maps use. Projected grids are in meters, the graticules in degrees.
///
/// | Scale            | UTM / MGRS / Lambert | DMS  | Decimal |
/// |------------------|----------------------|------|---------|
/// | 1:10 000         | 500 m                | 15″  | 0.005°  |
/// | 1:20 000–25 000  | 1 km                 | 30″  | 0.01°   |
/// | 1:50 000         | 1 km                 | 1′   | 0.02°   |
/// | 1:100 000        | 2 km                 | 2′   | 0.05°   |
/// | 1:250 000        | 10 km                | 5′   | 0.1°    |
public enum GridSpacing {
    private static let seconds = 3_600.0

    private static let metersChoices: [Double] = [100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0]
    private static let dmsChoices: [Double] = [10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0].map { $0 / 3_600.0 }
    private static let decimalChoices: [Double] = [0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1]

    public static func auto(_ kind: GridKind, _ scale: Double) -> Double {
        switch kind {
        case .none, .utm, .mgrs, .lambert93: meters(scale)
        case .wgs84: dms(scale)
        case .wgs84Decimal: decimal(scale)
        }
    }

    /// The spacings offered when the user picks one by hand, in the grid's own unit.
    public static func choices(_ kind: GridKind) -> [Double] {
        switch kind {
        case .none: []
        case .utm, .mgrs, .lambert93: metersChoices
        case .wgs84: dmsChoices
        case .wgs84Decimal: decimalChoices
        }
    }

    private static func meters(_ scale: Double) -> Double {
        if scale <= 7_500 { return 200.0 }
        if scale <= 15_000 { return 500.0 }
        if scale <= 75_000 { return 1_000.0 }
        if scale <= 175_000 { return 2_000.0 }
        if scale <= 600_000 { return 10_000.0 }
        return 100_000.0
    }

    private static func dms(_ scale: Double) -> Double {
        if scale <= 7_500 { return 10 / seconds }
        if scale <= 15_000 { return 15 / seconds }
        if scale <= 37_500 { return 30 / seconds }
        if scale <= 75_000 { return 60 / seconds }
        if scale <= 175_000 { return 120 / seconds }
        if scale <= 600_000 { return 300 / seconds }
        return 1_800 / seconds
    }

    private static func decimal(_ scale: Double) -> Double {
        if scale <= 7_500 { return 0.002 }
        if scale <= 15_000 { return 0.005 }
        if scale <= 37_500 { return 0.01 }
        if scale <= 75_000 { return 0.02 }
        if scale <= 175_000 { return 0.05 }
        if scale <= 600_000 { return 0.1 }
        return 0.5
    }
}
