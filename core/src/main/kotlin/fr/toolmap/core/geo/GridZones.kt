package fr.toolmap.core.geo

import kotlin.math.max
import kotlin.math.min

/**
 * A grid zone of UTM and MGRS (« 31T »): the part of the earth one zone and one latitude band cover. It is a
 * rectangle in longitude and latitude, so on the Web Mercator map too: two meridians and two parallels.
 */
data class GridZone(
    val zone: Int,
    val band: Char,
    val west: Double,
    val east: Double,
    val south: Double,
    val north: Double,
) {
    val name: String get() = "$zone$band"

    /** Inside, the west and south edges included; the north edge too for band X, which ends the grid at 84 N. */
    fun contains(
        lat: Double,
        lon: Double,
    ): Boolean {
        val l = Utm.normalizeLon(lon)
        val northOk = if (band == 'X') lat <= north else lat < north
        return l >= west && l < east && lat >= south && northOk
    }
}

/**
 * Where the grid of [zone] is drawn: a rectangle in the longitudes of the region, [shift] degrees more than the
 * zone's own (a world copy past the antimeridian).
 */
class ZonePiece(
    val zone: Int,
    val area: Bounds,
    val shift: Double,
)

/** The 1 197 grid zones between 80 S and 84 N, with the Norway and Svalbard exceptions (P24.1). */
object GridZones {
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private const val SOUTH = -80.0
    private const val NORTH = 84.0
    private const val BAND_HEIGHT = 8.0
    private const val ZONE_WIDTH = 6.0

    /** West and east edges of the zones of band V and band X that differ from the regular 6° ones. */
    private val BAND_V = mapOf(31 to (0.0 to 3.0), 32 to (3.0 to 12.0))
    private val BAND_X = mapOf(31 to (0.0 to 9.0), 33 to (9.0 to 21.0), 35 to (21.0 to 33.0), 37 to (33.0 to 42.0))
    private val MISSING_IN_X = setOf(32, 34, 36)

    val all: List<GridZone> by lazy {
        BANDS.indices.flatMap { b -> (1..60).mapNotNull { zone -> make(zone, BANDS[b]) } }
    }

    /** The grid zone [zone] [band], or `null` when it does not exist (32X, 34X, 36X, a zone or band out of range). */
    fun get(
        zone: Int,
        band: Char,
    ): GridZone? = if (zone in 1..60 && band in BANDS) make(zone, band) else null

    /** The grid zone of a position; `null` outside 80 S .. 84 N. */
    fun of(
        lat: Double,
        lon: Double,
    ): GridZone? {
        val band = Utm.bandLetter(lat) ?: return null
        return get(Utm.zoneOf(lat, lon), band)
    }

    /** The grid zones that meet [area] (west, south, east, north in degrees), south to north then west to east. */
    fun covering(area: Bounds): List<GridZone> {
        if (area.maxY < SOUTH || area.minY > NORTH) return emptyList()
        return all.filter { it.east > area.minX && it.west < area.maxX && it.north > area.minY && it.south < area.maxY }
    }

    /**
     * The parts of [area] with one zone's grid, the bands of a zone that share its edges joined as one (P24.2): the
     * screen and a printed sheet draw each one with the grid of its zone, cut at its edges. The longitudes of [area]
     * may run past 180° (a region across the antimeridian).
     */
    fun pieces(area: Bounds): List<ZonePiece> {
        val pieces = ArrayList<ZonePiece>()
        for (shift in SHIFTS) {
            for ((column, zones) in covering(area, shift).groupBy { Triple(it.zone, it.west, it.east) }) {
                // South to north, the bands that touch are joined: their lines run on.
                val runs = ArrayList<Pair<Double, Double>>()
                for (z in zones.sortedBy { it.south }) {
                    val last = runs.lastOrNull()
                    if (last != null && last.second == z.south) {
                        runs[runs.lastIndex] = last.first to z.north
                    } else {
                        runs += z.south to z.north
                    }
                }
                val west = max(column.second + shift, area.minX)
                val east = min(column.third + shift, area.maxX)
                for ((south, north) in runs) {
                    val s = max(south, area.minY)
                    val n = min(north, area.maxY)
                    if (east > west && n > s) pieces += ZonePiece(column.first, Bounds(west, s, east, n), shift)
                }
            }
        }
        return pieces
    }

    /** The edges of the grid zones inside [area], each once: meridians and parallels, straight on the map. */
    fun edges(area: Bounds): List<List<LonLat>> {
        val edges = LinkedHashSet<List<LonLat>>()
        for (shift in SHIFTS) {
            for (z in covering(area, shift)) {
                val south = max(z.south, area.minY)
                val north = min(z.north, area.maxY)
                for (lon in listOf(z.west + shift, z.east + shift)) {
                    if (lon > area.minX && lon < area.maxX) edges += listOf(LonLat(lon, south), LonLat(lon, north))
                }
                val west = max(z.west + shift, area.minX)
                val east = min(z.east + shift, area.maxX)
                for (lat in listOf(z.south, z.north)) {
                    if (lat > area.minY && lat < area.maxY) edges += listOf(LonLat(west, lat), LonLat(east, lat))
                }
            }
        }
        return edges.toList()
    }

    /** The grid zones that meet [area], whose longitudes may run past 180°; each once. */
    fun meeting(area: Bounds): List<GridZone> = SHIFTS.flatMap { covering(area, it) }.distinct()

    /** The grid zones the region meets in the world copy [shift]. */
    private fun covering(
        area: Bounds,
        shift: Double,
    ): List<GridZone> {
        val west = max(area.minX - shift, -180.0)
        val east = min(area.maxX - shift, 180.0)
        return if (east > west) covering(Bounds(west, area.minY, east, area.maxY)) else emptyList()
    }

    /** The world copies a region can meet: its longitudes run past 180° on a screen across the antimeridian. */
    private val SHIFTS = listOf(-360.0, 0.0, 360.0)

    private fun make(
        zone: Int,
        band: Char,
    ): GridZone? {
        val south = SOUTH + BAND_HEIGHT * BANDS.indexOf(band)
        val north = if (band == 'X') NORTH else south + BAND_HEIGHT
        val regular = -180.0 + ZONE_WIDTH * (zone - 1) to -180.0 + ZONE_WIDTH * zone
        val (west, east) =
            when (band) {
                'V' -> BAND_V[zone] ?: regular
                'X' -> if (zone in MISSING_IN_X) return null else BAND_X[zone] ?: regular
                else -> regular
            }
        return GridZone(zone, band, west, east, south, north)
    }
}
