package fr.toolmap.core.print

/**
 * The spacing of the grid lines from the scale (P17, Q12): squares of 2 to 5 cm on the paper, the spacings printed
 * maps use. Projected grids are in meters, the graticules in degrees.
 *
 * | Scale            | UTM / MGRS / Lambert | DMS  | Decimal |
 * |------------------|----------------------|------|---------|
 * | 1:10 000         | 500 m                | 15″  | 0.005°  |
 * | 1:20 000–25 000  | 1 km                 | 30″  | 0.01°   |
 * | 1:50 000         | 1 km                 | 1′   | 0.02°   |
 * | 1:100 000        | 2 km                 | 2′   | 0.05°   |
 * | 1:250 000        | 10 km                | 5′   | 0.1°    |
 */
object GridSpacing {
    fun auto(
        kind: GridKind,
        scale: Double,
    ): Double =
        when (kind) {
            GridKind.NONE, GridKind.UTM, GridKind.MGRS, GridKind.LAMBERT93 -> meters(scale)
            GridKind.WGS84 -> dms(scale)
            GridKind.WGS84_DECIMAL -> decimal(scale)
        }

    /** The spacings offered when the user picks one by hand, in the grid's own unit. */
    fun choices(kind: GridKind): List<Double> =
        when (kind) {
            GridKind.NONE -> emptyList()
            GridKind.UTM, GridKind.MGRS, GridKind.LAMBERT93 -> METERS
            GridKind.WGS84 -> DMS
            GridKind.WGS84_DECIMAL -> DECIMAL
        }

    private fun meters(scale: Double): Double =
        when {
            scale <= 7_500 -> 200.0
            scale <= 15_000 -> 500.0
            scale <= 75_000 -> 1_000.0
            scale <= 175_000 -> 2_000.0
            scale <= 600_000 -> 10_000.0
            else -> 100_000.0
        }

    private fun dms(scale: Double): Double =
        when {
            scale <= 7_500 -> 10 / SECONDS
            scale <= 15_000 -> 15 / SECONDS
            scale <= 37_500 -> 30 / SECONDS
            scale <= 75_000 -> 60 / SECONDS
            scale <= 175_000 -> 120 / SECONDS
            scale <= 600_000 -> 300 / SECONDS
            else -> 1_800 / SECONDS
        }

    private fun decimal(scale: Double): Double =
        when {
            scale <= 7_500 -> 0.002
            scale <= 15_000 -> 0.005
            scale <= 37_500 -> 0.01
            scale <= 75_000 -> 0.02
            scale <= 175_000 -> 0.05
            scale <= 600_000 -> 0.1
            else -> 0.5
        }

    private const val SECONDS = 3_600.0

    private val METERS = listOf(100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)
    private val DMS = listOf(10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0).map { it / SECONDS }
    private val DECIMAL = listOf(0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1)
}
