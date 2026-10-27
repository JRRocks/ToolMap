package fr.toolmap.core.source

/**
 * An immutable, sorted set of integer zoom levels.
 *
 * Parsed from the AQX syntaxes `"1-17"`, `"1,3,5"`, `"1-3,5,7-9"` (also the `zoom-values` form
 * `"1,2,3,4"`). Zoom levels are tile zooms (Web Mercator, 256 px tiles), the same numbers as in
 * the tile URLs.
 */
class ZoomSet private constructor(
    private val values: List<Int>,
) {
    val min: Int get() = values.first()
    val max: Int get() = values.last()
    val size: Int get() = values.size
    val isContiguous: Boolean get() = max - min + 1 == size

    fun toList(): List<Int> = values

    operator fun contains(zoom: Int): Boolean = values.binarySearch(zoom) >= 0

    /** Canonical text form, e.g. `"1-3,5,7-9"`. Round-trips through [parse]. */
    fun toSpec(): String {
        val parts = mutableListOf<String>()
        var start = values.first()
        var previous = start
        for (zoom in values.drop(1) + (Int.MAX_VALUE)) {
            if (zoom == previous + 1) {
                previous = zoom
                continue
            }
            parts += if (start == previous) "$start" else "$start-$previous"
            start = zoom
            previous = zoom
        }
        return parts.joinToString(",")
    }

    override fun equals(other: Any?): Boolean = other is ZoomSet && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ZoomSet(${toSpec()})"

    companion object {
        const val MAX_ZOOM = 30

        fun of(vararg zooms: Int): ZoomSet = create(zooms.toList())

        fun range(
            min: Int,
            max: Int,
        ): ZoomSet {
            require(min <= max) { "Empty zoom range $min-$max" }
            return create((min..max).toList())
        }

        /** @throws IllegalArgumentException when [spec] is empty or malformed. */
        fun parse(spec: String): ZoomSet {
            val zooms = mutableListOf<Int>()
            for (rawPart in spec.split(',', ';', ' ')) {
                val part = rawPart.trim()
                if (part.isEmpty()) continue
                val bounds = part.split('-').map { it.trim() }
                val from = bounds[0].toIntOrNull()
                val to = if (bounds.size == 2) bounds[1].toIntOrNull() else from
                require(bounds.size <= 2 && from != null && to != null && from <= to) {
                    "Invalid zoom specification '$part' in '$spec'"
                }
                zooms += from..to
            }
            return create(zooms)
        }

        private fun create(zooms: List<Int>): ZoomSet {
            val sorted = zooms.distinct().sorted()
            require(sorted.isNotEmpty()) { "A zoom set cannot be empty" }
            require(sorted.first() >= 0 && sorted.last() <= MAX_ZOOM) {
                "Zoom levels must be within 0..$MAX_ZOOM: ${sorted.first()}..${sorted.last()}"
            }
            return ZoomSet(sorted)
        }
    }
}
