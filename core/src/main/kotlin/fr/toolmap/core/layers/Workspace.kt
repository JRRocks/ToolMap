package fr.toolmap.core.layers

import fr.toolmap.core.problem.catchReported
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
    /** Hue rotation in degrees (P26.6), see [LayerConfig.hue]. */
    val hue: Float = 0f,
    /** Blend mode and tint (P26), see [LayerConfig.blend] and [LayerConfig.tint]. */
    val blend: BlendMode = BlendMode.NORMAL,
    val tint: Int? = null,
    val tintStrength: Float = LayerConfig.DEFAULT_TINT_STRENGTH,
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
                    hue = layer.hue,
                    blend = layer.blend,
                    tint = layer.tint,
                    tintStrength = layer.tintStrength,
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
            want.sourceId == have.sourceId && changedFields(want, have).isEmpty()
        }
    }

    companion object {
        const val BUILTIN_PREFIX = "builtin-"

        private const val TOLERANCE = 0.005f

        private fun near(
            a: Float,
            b: Float,
        ) = kotlin.math.abs(a - b) <= TOLERANCE

        /** What differs between [want], a layer of a workspace, and [have], a layer of the stack on screen. */
        internal fun changedFields(
            want: WorkspaceLayer,
            have: LayerConfig,
        ): Set<LayerField> =
            buildSet {
                if (want.visible != have.visible) add(LayerField.VISIBILITY)
                if (!near(want.opacity, have.opacity)) add(LayerField.OPACITY)
                if (want.displayMinZoom != have.displayMinZoom || want.displayMaxZoom != have.displayMaxZoom) {
                    add(LayerField.ZOOM)
                }
                if (want.displayMinElevation != have.displayMinElevation ||
                    want.displayMaxElevation != have.displayMaxElevation ||
                    (want.combinesRanges && want.rangeLogic != have.rangeLogic)
                ) {
                    add(LayerField.ELEVATION)
                }
                if (want.inverted != have.inverted ||
                    !near(want.contrast, have.contrast) ||
                    !near(want.brightness, have.brightness) ||
                    !near(want.saturation, have.saturation) ||
                    !near(want.hue, have.hue) ||
                    want.blend != have.blend ||
                    want.tint != have.tint ||
                    (want.tint != null && !near(want.tintStrength, have.tintStrength))
                ) {
                    add(LayerField.COLOURS)
                }
            }

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
                        it.hue,
                        it.blend,
                        it.tint,
                        it.tintStrength,
                    )
                },
            )
    }
}

/** Workspaces shipped with the app. */
object Workspaces {
    /**
     * The author's summer setup, the first favorite (ADR-107, values from the author's own export): one map per zoom
     * range, stacked as ordinary layers so each range shows in "Displayed maps" and can be changed (ADR-063), with
     * the aerial photos from zoom 19 up and the IGN slopes at 31 % on top. The id dates from its first name,
     * « Rando Multi-Zoom »: it is kept so a hidden built-in stays hidden.
     */
    val randoEte =
        Workspace(
            id = "${Workspace.BUILTIN_PREFIX}rando-multizoom",
            name = "Rando été",
            layers =
                listOf(
                    WorkspaceLayer("osm", displayMinZoom = 1, displayMaxZoom = 8),
                    WorkspaceLayer("opentopomap", displayMinZoom = 9, displayMaxZoom = 11),
                    WorkspaceLayer("ign-scan25", displayMinZoom = 12, displayMaxZoom = 17),
                    WorkspaceLayer("osm", displayMinZoom = 18, displayMaxZoom = 18),
                    WorkspaceLayer("ign-ortho", displayMinZoom = 19),
                    WorkspaceLayer(
                        "ign-pentes",
                        opacity = 0.31017873f,
                        contrast = -0.0037047565f,
                        brightness = 0.0067015886f,
                        saturation = 0.0042265654f,
                    ),
                ),
        )

    /**
     * The author's winter setup (ADR-069, values updated by ADR-107): IGN SCAN 25 everywhere, OpenSnowMap's snow
     * base map above 1,550 m, and from zoom 13 the ski pistes, the Yeti winter tracks (hue turned by -122°) and the
     * IGN slopes at 30 %. The id dates from its first name, « Sortie Ski ».
     */
    val randoHiver =
        Workspace(
            id = "${Workspace.BUILTIN_PREFIX}sortie-ski",
            name = "Rando Hiver",
            layers =
                listOf(
                    WorkspaceLayer("ign-scan25"),
                    WorkspaceLayer("OSMSNOWM", displayMinElevation = 1550),
                    WorkspaceLayer("OSMSKIM", displayMinZoom = 13),
                    WorkspaceLayer(
                        "TRACESHIVERNALES",
                        brightness = -0.039669633f,
                        saturation = -0.005179912f,
                        displayMinZoom = 13,
                        hue = -122f,
                    ),
                    WorkspaceLayer("ign-pentes", opacity = 0.3f, displayMinZoom = 13),
                ),
        )

    /** Id of the built-in multi-zoom map that [randoEte] showed until it became a stack of maps (ADR-063). */
    const val RETIRED_RANDO_SOURCE = "RANDO_ZOOM_GROUP"

    /**
     * Puts the maps of [randoEte] in place of a layer of the retired built-in map [RETIRED_RANDO_SOURCE],
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
        val replacement = randoEte.toStack { true }.stack.layers
        val layers =
            stack.layers
                .flatMap { if (it.sourceId == RETIRED_RANDO_SOURCE) replacement else listOf(it) }
                .mapIndexed { index, layer -> layer.copy(id = "${LayerStack.ID_PREFIX}${index + 1}") }
        return LayerStack(layers)
    }

    /** Favorites shown first in the "Maps and layers" panel (ADR-069: only these two). */
    val builtin: List<Workspace> = listOf(randoEte, randoHiver)
}

object WorkspaceCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(workspace: Workspace): String = json.encodeToString(Workspace.serializer(), workspace)

    /** `null` for a damaged file: the app wrote it itself, so that is a bug or a damaged disk. */
    fun decodeOrNull(text: String): Workspace? =
        catchReported("layers.workspace.decode") {
            json.decodeFromString(Workspace.serializer(), text)
        }.getOrNull()
}
