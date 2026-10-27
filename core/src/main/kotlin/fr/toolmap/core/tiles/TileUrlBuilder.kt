package fr.toolmap.core.tiles

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicInteger

/** A URL template uses a placeholder we do not know (e.g. `{$serverpart}`) or a missing `{$param:key}`. */
class TileUrlException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Turns the URL templates of a [TileSource] into real tile URLs.
 *
 * Supported placeholders: `{$x} {$y} {$!y} {$z} {$q} {$bbox} {$ibbox} {$param:key}`.
 * When a level lists several servers they are used in turn (round-robin, thread-safe).
 */
class TileUrlBuilder(
    private val source: TileSource,
) {
    private val counter = AtomicInteger()

    /**
     * URL for tile [z]/[x]/[y] using the next server in rotation, or null when no level serves [z].
     * @throws TileUrlException when the template cannot be expanded.
     */
    fun build(
        z: Int,
        x: Int,
        y: Int,
    ): String? {
        val level = source.levelFor(z) ?: return null
        return build(level, z, x, y, counter.getAndIncrement())
    }

    /** Deterministic variant: [serverIndex] picks the server modulo the number of servers. */
    fun build(
        level: LevelDef,
        z: Int,
        x: Int,
        y: Int,
        serverIndex: Int,
    ): String {
        val template = level.servers[Math.floorMod(serverIndex, level.servers.size)]
        return expand(template, level, z, x, y)
    }

    private fun expand(
        template: String,
        level: LevelDef,
        z: Int,
        x: Int,
        y: Int,
    ): String =
        PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            when {
                name == "x" -> {
                    x.toString()
                }

                name == "y" -> {
                    y.toString()
                }

                name == "!y" -> {
                    TileMath.tmsY(y, z).toString()
                }

                name == "z" -> {
                    z.toString()
                }

                name == "q" -> {
                    TileMath.quadKey(x, y, z)
                }

                name == "bbox" -> {
                    bbox(level, z, x, y, integer = false)
                }

                name == "ibbox" -> {
                    bbox(level, z, x, y, integer = true)
                }

                name.startsWith("param:") -> {
                    val key = name.removePrefix("param:")
                    source.params[key]
                        ?: throw TileUrlException("Missing <param name=\"$key\"> for source ${source.id}")
                }

                else -> {
                    throw TileUrlException("Unknown placeholder {\$$name} in source ${source.id}")
                }
            }
        }

    private fun bbox(
        level: LevelDef,
        z: Int,
        x: Int,
        y: Int,
        integer: Boolean,
    ): String {
        val (bounds, decimals) =
            when (level.bboxCrs) {
                LevelDef.CRS_WEB_MERCATOR -> TileMath.boundsMercator(x, y, z) to MERCATOR_DECIMALS
                else -> TileMath.boundsLonLat(x, y, z) to DEGREE_DECIMALS
            }
        return format(bounds, if (integer) 0 else decimals)
    }

    private fun format(
        bounds: Bounds,
        decimals: Int,
    ): String =
        listOf(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY).joinToString(",") { value ->
            BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }

    private companion object {
        // Every brace escaped: Android's regex engine (ICU) rejects a bare `}` (RegexPortabilityTest).
        val PLACEHOLDER = Regex("""\{\$([^\}]+)\}""")
        const val DEGREE_DECIMALS = 10
        const val MERCATOR_DECIMALS = 4
    }
}
