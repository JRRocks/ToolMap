package fr.toolmap.core.source

import fr.toolmap.core.tiles.ScalingPolicy
import java.text.Normalizer

/**
 * One zoom range of a multi-zoom map: from [from] to [to] (displayed tile zooms, inclusive) the map shows [mapId],
 * at the displayed zoom or, with [forcedZoom], always with the tiles of that zoom (shrunk below it, enlarged above).
 */
data class ZoomBand(
    val from: Int,
    val to: Int,
    val mapId: String,
    val forcedZoom: Int? = null,
) {
    val zooms: IntRange get() = from..to
}

/** What is wrong (or worth knowing) in a [MultiZoomDraft]; [band] is the index of the band concerned, if any. */
data class DraftIssue(
    val kind: Kind,
    val band: Int? = null,
    /** Second band of an overlap, or the zoom range of a gap. */
    val other: Int? = null,
) {
    enum class Kind(
        val blocking: Boolean,
    ) {
        NO_NAME(true),
        NO_BAND(true),
        BAD_RANGE(true),
        OVERLAP(true),
        UNKNOWN_MAP(true),

        /** The band shows a map that shows this one (directly or not): it would loop. */
        LOOP(true),

        /** The forced zoom is more than [ScalingPolicy.MAX_LEVELS] above the band: too many tiles for one. */
        FORCED_TOO_FAR(true),

        /** The map has no tile at the forced zoom (its nearest zoom is used). */
        FORCED_NOT_SERVED(false),

        /** The map serves none of the zooms of the band (its nearest zoom is used). */
        NOT_SERVED(false),

        /** No band covers the zooms between two bands (they are filled from the nearest band). */
        GAP(false),
    }
}

/**
 * A multi-zoom map being edited (ADR-031): the AlpineQuest `.aqx` whose levels point to different servers
 * (`USR.PERSONNAL_MULTIZOOM.AQX`), built in the app instead of by hand. Each band references a map of the
 * catalogue, so the tiles are those of that map: already downloaded tiles are used and nothing is stored twice.
 */
data class MultiZoomDraft(
    val name: String,
    val bands: List<ZoomBand>,
) {
    /** Problems sorted by band; the draft can be saved when none is [DraftIssue.Kind.blocking]. */
    fun issues(
        selfId: String?,
        lookup: (String) -> TileSource?,
    ): List<DraftIssue> {
        val out = mutableListOf<DraftIssue>()
        if (name.isBlank()) out += DraftIssue(DraftIssue.Kind.NO_NAME)
        if (bands.isEmpty()) out += DraftIssue(DraftIssue.Kind.NO_BAND)
        bands.forEachIndexed { i, band ->
            if (band.from > band.to || band.from < 0 || band.to > ZoomSet.MAX_ZOOM) {
                out += DraftIssue(DraftIssue.Kind.BAD_RANGE, i)
                return@forEachIndexed
            }
            val map = lookup(band.mapId)
            when {
                map == null -> {
                    out += DraftIssue(DraftIssue.Kind.UNKNOWN_MAP, i)
                }

                selfId != null && reaches(map, selfId, lookup, 0) -> {
                    out += DraftIssue(DraftIssue.Kind.LOOP, i)
                }

                band.forcedZoom != null && band.forcedZoom - band.from > ScalingPolicy.MAX_LEVELS -> {
                    out += DraftIssue(DraftIssue.Kind.FORCED_TOO_FAR, i)
                }

                band.forcedZoom != null && !map.isMultiZoom && map.levelFor(band.forcedZoom) == null -> {
                    out += DraftIssue(DraftIssue.Kind.FORCED_NOT_SERVED, i)
                }

                band.forcedZoom == null && !map.isMultiZoom && band.zooms.none { map.levelFor(it) != null } -> {
                    out += DraftIssue(DraftIssue.Kind.NOT_SERVED, i)
                }
            }
        }
        val valid = bands.withIndex().filter { (_, b) -> b.from <= b.to }.sortedBy { it.value.from }
        for ((a, b) in valid.zipWithNext()) {
            when {
                b.value.from <= a.value.to -> out += DraftIssue(DraftIssue.Kind.OVERLAP, a.index, b.index)
                b.value.from > a.value.to + 1 -> out += DraftIssue(DraftIssue.Kind.GAP, a.index, b.index)
            }
        }
        return out.sortedWith(compareBy({ it.band ?: -1 }, { it.kind.ordinal }))
    }

    fun canSave(
        selfId: String?,
        lookup: (String) -> TileSource?,
    ): Boolean = issues(selfId, lookup).none { it.kind.blocking }

    /** The map to save under [id]. Its credit, type and preview come from the maps it shows. */
    fun toSource(
        id: String,
        lookup: (String) -> TileSource?,
    ): TileSource {
        val sorted = bands.sortedBy { it.from }
        val members = sorted.mapNotNull { lookup(it.mapId) }.distinctBy { it.id }
        val widest = sorted.maxByOrNull { it.to - it.from }?.let { lookup(it.mapId) }
        return TileSource(
            id = id,
            name = name.trim(),
            type = widest?.type ?: members.firstOrNull()?.type,
            copyright = members.mapNotNull { it.copyright }.distinct().joinToString(" | ").ifEmpty { null },
            dataSource = members.firstNotNullOfOrNull { it.dataSource },
            region = members.firstNotNullOfOrNull { it.region },
            previewLocation = widest?.previewLocation ?: members.firstNotNullOfOrNull { it.previewLocation },
            levels =
                sorted.map {
                    LevelDef(ZoomSet.range(it.from, it.to), listOf(MapRef(it.mapId, it.forcedZoom).toServer()))
                },
        )
    }

    companion object {
        private const val ID_PREFIX = "mz-"
        private val NOT_ID = Regex("[^a-z0-9]+")
        private val MARKS = Regex("\\p{Mn}+")

        /** The draft of a map made of references only (one band per contiguous run of zooms), or `null`. */
        fun of(source: TileSource): MultiZoomDraft? {
            if (source.levels.isEmpty() || source.levels.any { it.mapRef == null }) return null
            val bands =
                source.levels.flatMap { level ->
                    val ref = requireNotNull(level.mapRef)
                    runsOf(level.zooms.toList()).map { ZoomBand(it.first, it.last, ref.sourceId, ref.tileZoom) }
                }
            return MultiZoomDraft(source.name, bands.sortedBy { it.from })
        }

        /**
         * A new multi-zoom map built from any map: each level becomes bands showing the catalogue map that serves the
         * same tiles (the built-in one first, else [source] itself), and a zoom missing inside a `<zoom-values>`
         * list becomes a band forced to its nearest listed zoom (the coarser one on a tie), which is what AlpineQuest
         * displays there. A multi-zoom map is simply copied.
         */
        fun derivedFrom(
            source: TileSource,
            catalogue: List<TileSource>,
            name: String,
        ): MultiZoomDraft {
            of(source)?.let { return it.copy(name = name) }
            val owners = LinkedHashMap<String, String>()
            for (map in catalogue.sortedBy { if (it.id == source.id) 1 else 0 }) {
                if (map.isMultiZoom) continue
                for (identity in TileSetIdentity.allOf(map)) owners.putIfAbsent(identity, map.id)
            }
            val bands = mutableListOf<ZoomBand>()
            for (level in source.levels) {
                val ref = level.mapRef
                val mapId =
                    when {
                        ref != null -> ref.sourceId
                        level.isLocalOnly -> source.id
                        else -> TileSetIdentity.of(source, level)?.let { owners[it] } ?: source.id
                    }
                if (ref?.tileZoom != null) {
                    runsOf(level.zooms.toList()).forEach { bands += ZoomBand(it.first, it.last, mapId, ref.tileZoom) }
                    continue
                }
                val zooms = level.zooms.toList()
                runsOf(zooms).forEach { bands += ZoomBand(it.first, it.last, mapId) }
                // Holes inside the list: forced to the nearest listed zoom.
                for (z in zooms.first()..zooms.last()) {
                    if (z in level.zooms || source.levelFor(z) != null) continue
                    val nearest = zooms.minWith(compareBy({ kotlin.math.abs(it - z) }, { it }))
                    bands += ZoomBand(z, z, mapId, nearest)
                }
            }
            return MultiZoomDraft(name, mergeAdjacent(bands.sortedBy { it.from }))
        }

        /** A free id for a new multi-zoom map named [name]. */
        fun newId(
            name: String,
            taken: (String) -> Boolean,
        ): String {
            val plain = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
            val base = ID_PREFIX + plain.replace(NOT_ID, "-").trim('-').take(MAX_SLUG).trim('-').ifEmpty { "carte" }
            if (!taken(base)) return base
            return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { !taken(it) }
        }

        private const val MAX_SLUG = 32

        private fun runsOf(zooms: List<Int>): List<IntRange> {
            val runs = mutableListOf<IntRange>()
            var start = zooms.first()
            var previous = start
            for (z in zooms.drop(1)) {
                if (z != previous + 1) {
                    runs += start..previous
                    start = z
                }
                previous = z
            }
            runs += start..previous
            return runs
        }

        private fun mergeAdjacent(bands: List<ZoomBand>): List<ZoomBand> {
            val out = mutableListOf<ZoomBand>()
            for (band in bands) {
                val last = out.lastOrNull()
                if (last != null && last.to + 1 == band.from && last.mapId == band.mapId &&
                    last.forcedZoom == band.forcedZoom
                ) {
                    out[out.lastIndex] = last.copy(to = band.to)
                } else {
                    out += band
                }
            }
            return out
        }

        /** True when [map] shows the map [targetId], directly or through other multi-zoom maps. */
        private fun reaches(
            map: TileSource,
            targetId: String,
            lookup: (String) -> TileSource?,
            depth: Int,
        ): Boolean {
            if (map.id == targetId) return true
            if (depth > StorageKeys.MAX_DEPTH) return true
            return map.levels.any { level ->
                val ref = level.mapRef ?: return@any false
                val next = lookup(ref.sourceId) ?: return@any false
                next.id != map.id && reaches(next, targetId, lookup, depth + 1)
            }
        }
    }
}
