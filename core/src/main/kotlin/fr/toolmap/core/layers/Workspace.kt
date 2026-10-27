package fr.toolmap.core.layers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One layer of a [Workspace]: a [LayerConfig] without the id that only has a meaning inside a live stack. */
@Serializable
data class WorkspaceLayer(
    val sourceId: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val contrast: Float = 0f,
    val brightness: Float = 0f,
    val saturation: Float = 0f,
    val displayMinZoom: Int? = null,
    val displayMaxZoom: Int? = null,
    val inverted: Boolean = false,
    val displayMinElevation: Int? = null,
    val displayMaxElevation: Int? = null,
    val rangeLogic: RangeLogic = RangeLogic.AND,
) {
    /** True when both a zoom range and an elevation band are set: only then does [rangeLogic] change the drawing. */
    val combinesRanges: Boolean
        get() =
            (displayMinZoom != null || displayMaxZoom != null) &&
                (displayMinElevation != null || displayMaxElevation != null)
}

/** The result of putting a workspace on the map. */
data class AppliedWorkspace(
    val stack: LayerStack,
    /** Source ids of the workspace that this device does not know (not imported, removed...). */
    val skipped: List<String>,
)

/**
 * A named layer stack with its zoom ranges and elevation bands (P20), to switch between setups in one tap. Layers are
 * listed from the bottom of the stack to the top.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val layers: List<WorkspaceLayer>,
) {
    val isBuiltin: Boolean get() = id.startsWith(BUILTIN_PREFIX)

    /** Builds the live stack (fresh layer ids), leaving out the layers whose source is not [isKnownSource]. */
    fun toStack(isKnownSource: (String) -> Boolean): AppliedWorkspace {
        val known = layers.filter { isKnownSource(it.sourceId) }
        val configs =
            known.mapIndexed { index, layer ->
                LayerConfig(
                    id = "${LayerStack.ID_PREFIX}${index + 1}",
                    sourceId = layer.sourceId,
                    visible = layer.visible,
                    opacity = layer.opacity,
                    contrast = layer.contrast,
                    brightness = layer.brightness,
                    saturation = layer.saturation,
                    displayMinZoom = layer.displayMinZoom,
                    displayMaxZoom = layer.displayMaxZoom,
                    inverted = layer.inverted,
                    displayMinElevation = layer.displayMinElevation,
                    displayMaxElevation = layer.displayMaxElevation,
                    rangeLogic = layer.rangeLogic,
                )
            }
        return AppliedWorkspace(
            LayerStack(configs),
            layers.filterNot {
                isKnownSource(it.sourceId)
            }.map { it.sourceId },
        )
    }

    /**
     * True when [stack] shows exactly this workspace: same maps in the same order with the same opacity, colours,
     * zoom ranges and elevation bands (layer ids do not matter). Used to highlight the favorite that is on screen.
     */
    fun matches(stack: LayerStack): Boolean {
        if (stack.layers.size != layers.size) return false
        return layers.zip(stack.layers).all { (want, have) ->
            want.sourceId == have.sourceId &&
                want.visible == have.visible &&
                want.inverted == have.inverted &&
                want.displayMinZoom == have.displayMinZoom &&
                want.displayMaxZoom == have.displayMaxZoom &&
                want.displayMinElevation == have.displayMinElevation &&
                want.displayMaxElevation == have.displayMaxElevation &&
                (!want.combinesRanges || want.rangeLogic == have.rangeLogic) &&
                near(want.opacity, have.opacity) &&
                near(want.contrast, have.contrast) &&
                near(want.brightness, have.brightness) &&
                near(want.saturation, have.saturation)
        }
    }

    companion object {
        const val BUILTIN_PREFIX = "builtin-"

        private const val TOLERANCE = 0.005f

        private fun near(
            a: Float,
            b: Float,
        ) = kotlin.math.abs(a - b) <= TOLERANCE

        fun of(
            id: String,
            name: String,
            stack: LayerStack,
        ): Workspace =
            Workspace(
                id,
                name,
                stack.layers.map {
                    WorkspaceLayer(
                        it.sourceId,
                        it.visible,
                        it.opacity,
                        it.contrast,
                        it.brightness,
                        it.saturation,
                        it.displayMinZoom,
                        it.displayMaxZoom,
                        it.inverted,
                        it.displayMinElevation,
                        it.displayMaxElevation,
                        it.rangeLogic,
                    )
                },
            )
    }
}

/** Workspaces shipped with the app. */
object Workspaces {
    /**
     * The author's own setup (from USR.PERSONNAL_MULTIZOOM.AQX), the first favorite: one map per zoom range,
     * stacked as ordinary layers so each range shows in "Displayed maps" and can be changed (ADR-063).
     */
    val randoMultiZoom =
        Workspace(
            id = "${Workspace.BUILTIN_PREFIX}rando-multizoom",
            name = "Rando Multi-Zoom",
            layers =
                listOf(
                    WorkspaceLayer("osm", displayMinZoom = 1, displayMaxZoom = 8),
                    WorkspaceLayer("opentopomap", displayMinZoom = 9, displayMaxZoom = 11),
                    WorkspaceLayer("ign-scan25", displayMinZoom = 12, displayMaxZoom = 17),
                    WorkspaceLayer("osm", displayMinZoom = 18, displayMaxZoom = 18),
                    WorkspaceLayer("ign-ortho", displayMinZoom = 19, displayMaxZoom = 19),
                ),
        )

    /**
     * The author's ski setup (ADR-069): IGN SCAN 25 everywhere, OpenSnowMap's snow base map above 1,550 m, and from
     * zoom 12 the ski pistes, the Yeti winter tracks and the IGN slopes at 30 %.
     */
    val sortieSki =
        Workspace(
            id = "${Workspace.BUILTIN_PREFIX}sortie-ski",
            name = "Sortie Ski",
            layers =
                listOf(
                    WorkspaceLayer("ign-scan25"),
                    WorkspaceLayer("OSMSNOWM", displayMinElevation = 1550),
                    WorkspaceLayer("OSMSKIM", displayMinZoom = 12),
                    WorkspaceLayer("TRACESHIVERNALES", displayMinZoom = 12),
                    WorkspaceLayer("ign-pentes", opacity = 0.3f, displayMinZoom = 12),
                ),
        )

    /** Id of the built-in multi-zoom map that [randoMultiZoom] showed until it became a stack of maps (ADR-063). */
    const val RETIRED_RANDO_SOURCE = "RANDO_ZOOM_GROUP"

    /**
     * Puts the maps of [randoMultiZoom] in place of a layer of the retired built-in map [RETIRED_RANDO_SOURCE],
     * so a stack saved before ADR-063 does not open on an empty map. Left unchanged when the device knows a map
     * with that id (a copy of the author's file imported by hand).
     */
    fun replaceRetired(
        stack: LayerStack,
        isKnownSource: (String) -> Boolean,
    ): LayerStack {
        if (stack.layers.none { it.sourceId == RETIRED_RANDO_SOURCE } || isKnownSource(RETIRED_RANDO_SOURCE)) {
            return stack
        }
        val replacement = randoMultiZoom.toStack { true }.stack.layers
        val layers =
            stack.layers
                .flatMap { if (it.sourceId == RETIRED_RANDO_SOURCE) replacement else listOf(it) }
                .mapIndexed { index, layer -> layer.copy(id = "${LayerStack.ID_PREFIX}${index + 1}") }
        return LayerStack(layers)
    }

    /** Favorites shown first in the "Maps and layers" panel (ADR-069: only these two). */
    val builtin: List<Workspace> = listOf(randoMultiZoom, sortieSki)
}

object WorkspaceCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(workspace: Workspace): String = json.encodeToString(Workspace.serializer(), workspace)

    fun decodeOrNull(text: String): Workspace? =
        runCatching {
            json.decodeFromString(Workspace.serializer(), text)
        }.getOrNull()
}
