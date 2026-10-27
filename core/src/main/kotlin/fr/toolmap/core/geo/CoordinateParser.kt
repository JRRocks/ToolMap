package fr.toolmap.core.geo

/** Result of reading a typed position. */
sealed interface ParsedCoordinate {
    data class Success(
        val lat: Double,
        val lon: Double,
    ) : ParsedCoordinate

    data class Failure(
        val reason: Reason,
    ) : ParsedCoordinate

    enum class Reason {
        /** Nothing recognisable, or not exactly two coordinates. */
        SYNTAX,

        /** Latitude beyond 90 degrees or longitude beyond 180. */
        RANGE,

        /** Minutes or seconds of 60 or more, or a fractional degree next to minutes. */
        SEXAGESIMAL,

        /** Two latitudes, two longitudes, or a sign together with a hemisphere letter. */
        AXES,

        /** A UTM, MGRS or Lambert 93 reference that cannot be a position (letters that do not go together, range). */
        GRID,
    }
}

/**
 * Reads positions the way people type them: decimal degrees (`44.8, 5.9`), degrees and decimal minutes
 * (`N 44° 48.000' E 5° 54.000'`) and degrees, minutes and seconds (`44°48'00"N 5°54'00"E`), with a hemisphere letter
 * before or after (N, S, E, W, and the French O for ouest) or a minus sign. Latitude comes first unless the letters
 * say otherwise. A decimal comma is accepted when the two coordinates are told apart by something else. UTM, MGRS and
 * Lambert 93 references are read too ([GridReference]).
 */
object CoordinateParser {
    private val TOKEN = Regex("""[NSEWO]|\d+(?:\.\d+)?|[;,]|[°'"]""")
    private val WORDS =
        listOf(
            "NORD" to "N",
            "SUD" to "S",
            "EST" to "E",
            "OUEST" to "W",
            "NORTH" to "N",
            "SOUTH" to "S",
            "WEST" to "W",
            "EAST" to "E",
        )

    private class Group {
        var hemisphere: Char? = null
        var negative = false
        val numbers = ArrayList<Double>()
        var fractionalBeforeLast = false
    }

    fun parse(text: String): ParsedCoordinate {
        GridReference.parse(text)?.let { return it }
        val input = normalise(text)
        if (input.isBlank()) return failure(ParsedCoordinate.Reason.SYNTAX)
        val groups = split(input) ?: return failure(ParsedCoordinate.Reason.SYNTAX)
        if (groups.size != 2) return failure(ParsedCoordinate.Reason.SYNTAX)
        val axes = axesOf(groups) ?: return failure(ParsedCoordinate.Reason.AXES)
        val lat = value(groups[axes.first], 90.0)
        val lon = value(groups[axes.second], 180.0)
        return when {
            lat is Reading.Bad -> failure(lat.reason)
            lon is Reading.Bad -> failure(lon.reason)
            else -> ParsedCoordinate.Success((lat as Reading.Ok).degrees, (lon as Reading.Ok).degrees)
        }
    }

    private fun failure(reason: ParsedCoordinate.Reason) = ParsedCoordinate.Failure(reason)

    private fun normalise(text: String): String {
        var s = text.trim().uppercase()
        for ((word, letter) in WORDS) s = s.replace(Regex("\\b$word\\b"), letter)
        s = s.replace('′', '\'').replace('’', '\'').replace('‘', '\'').replace('´', '\'')
        s = s.replace("″", "\"").replace('”', '"').replace('“', '"').replace("''", "\"")
        s = s.replace('º', '°').replace('˚', '°')
        s = s.replace('−', '-').replace('–', '-')
        // A comma between digits is a decimal comma when a semicolon separates the pair, or when no dot is used and
        // something else (a space, a hemisphere letter) tells the two coordinates apart.
        val pairs = Regex("""\d,\d""")
        val commaIsDecimal =
            pairs.containsMatchIn(s) &&
                (';' in s || ('.' !in s && Regex("""\s|[NSEWO]""").containsMatchIn(s.replace(pairs, ""))))
        if (commaIsDecimal) s = s.replace(Regex("""(\d),(\d)"""), "$1.$2")
        return s
    }

    /** Cuts the text into groups of numbers, closed by a trailing letter, a separator, or the start of a new letter. */
    private fun split(input: String): List<Group>? {
        val groups = ArrayList<Group>()
        var current = Group()
        var hasContent = false

        fun close() {
            if (hasContent) groups += current
            current = Group()
            hasContent = false
        }
        var sign = false
        var index = 0
        val stripped = input.replace(Regex("""\s+"""), " ")
        // Only numbers (and unit marks): the pair is told apart by degree marks, or else by splitting the numbers in two.
        val simple = stripped.none { it in "NSEWO;," }
        val hasDegreeMark = '°' in stripped
        while (index < stripped.length) {
            val c = stripped[index]
            if (c == '-' || c == '+') {
                sign = c == '-'
                if (current.numbers.isNotEmpty()) close()
                current.negative = sign
                hasContent = true
                index++
                continue
            }
            val token = TOKEN.matchAt(stripped, index)
            if (token == null) {
                if (c == ' ') {
                    index++
                    continue
                }
                return null
            }
            val t = token.value
            index = token.range.last + 1
            when {
                t == ";" || t == "," -> {
                    close()
                }

                t[0] in "NSEWO" -> {
                    val letter = if (t[0] == 'O') 'W' else t[0]
                    when {
                        current.hemisphere != null -> {
                            close()
                            current.hemisphere = letter
                            hasContent = true
                        }

                        current.numbers.isNotEmpty() -> {
                            current.hemisphere = letter
                            close()
                        }

                        else -> {
                            current.hemisphere = letter
                            hasContent = true
                        }
                    }
                }

                t[0].isDigit() -> {
                    if (current.numbers.size == 3) close()
                    if (simple && hasDegreeMark && current.numbers.isNotEmpty() &&
                        degreeMarkFollows(stripped, index)
                    ) {
                        close()
                    }
                    val number = t.toDouble()
                    if (current.numbers.isNotEmpty() && current.numbers.last() % 1.0 != 0.0) {
                        current.fractionalBeforeLast = true
                    }
                    current.numbers += number
                    hasContent = true
                }

                else -> {
                    Unit
                }
            }
        }
        close()
        if (simple && !hasDegreeMark && groups.size == 1) return halve(groups.single())
        return groups.filter { it.numbers.isNotEmpty() }.takeIf { all -> all.size == groups.size }
    }

    /** Whether the number that ends at [end] is followed by a degree sign (spaces allowed). */
    private fun degreeMarkFollows(
        text: String,
        end: Int,
    ): Boolean = text.drop(end).trimStart().startsWith('°')

    /** `44 48 36 5 54 36`: an even count of plain numbers is two coordinates of the same kind. */
    private fun halve(all: Group): List<Group>? {
        val n = all.numbers.size
        if (n !in setOf(2, 4, 6)) return null
        return listOf(all.numbers.subList(0, n / 2), all.numbers.subList(n / 2, n)).map { part ->
            Group().also {
                it.numbers += part
                it.fractionalBeforeLast = part.dropLast(1).any { v -> v % 1.0 != 0.0 }
            }
        }
    }

    /** Indices of the latitude and longitude groups, or `null` for an impossible pairing. */
    private fun axesOf(groups: List<Group>): Pair<Int, Int>? {
        val kinds =
            groups.map {
                when (it.hemisphere) {
                    'N', 'S' -> LAT
                    'E', 'W' -> LON
                    else -> NONE
                }
            }
        return when {
            kinds[0] == LAT && kinds[1] != LAT -> 0 to 1
            kinds[0] == LON && kinds[1] != LON -> 1 to 0
            kinds[0] == NONE && kinds[1] == LON -> 0 to 1
            kinds[0] == NONE && kinds[1] == LAT -> 1 to 0
            kinds[0] == NONE && kinds[1] == NONE -> 0 to 1
            else -> null
        }
    }

    private sealed interface Reading {
        data class Ok(
            val degrees: Double,
        ) : Reading

        data class Bad(
            val reason: ParsedCoordinate.Reason,
        ) : Reading
    }

    private fun value(
        group: Group,
        limit: Double,
    ): Reading {
        val n = group.numbers
        val negativeLetter = group.hemisphere == 'S' || group.hemisphere == 'W'
        if (group.negative && group.hemisphere != null) return Reading.Bad(ParsedCoordinate.Reason.AXES)
        if (group.fractionalBeforeLast) return Reading.Bad(ParsedCoordinate.Reason.SEXAGESIMAL)
        if (n.drop(1).any { it >= 60.0 }) return Reading.Bad(ParsedCoordinate.Reason.SEXAGESIMAL)
        var degrees = n[0]
        if (n.size > 1) degrees += n[1] / 60.0
        if (n.size > 2) degrees += n[2] / 3600.0
        if (degrees > limit) return Reading.Bad(ParsedCoordinate.Reason.RANGE)
        return Reading.Ok(if (group.negative || negativeLetter) -degrees else degrees)
    }

    private const val LAT = 1
    private const val LON = 2
    private const val NONE = 0
}
