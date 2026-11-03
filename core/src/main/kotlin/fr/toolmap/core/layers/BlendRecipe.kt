package fr.toolmap.core.layers

import java.util.Locale

/**
 * What a blended layer's tile is made of (P26): the layers drawn from the bottom of the stack up to it ([parts], the
 * last one is the layer itself), each with its own map, opacity, colours, zoom range, elevation band, blend mode and
 * tint. The tile interceptor draws them in that order, so a mode mixes the layer with everything under it.
 *
 * The recipe travels in the query of the layer's tile URL (`blend=…`, [query], [parse]): any change under the layer
 * is a new URL, so MapLibre asks for the tiles again.
 */
data class BlendRecipe(
    val parts: List<LayerConfig>,
) {
    /** `blend=<part>;<part>…`, each part `source,mode,opacity,…` (see [encode]). Only URL-safe characters. */
    fun query(): String = PARAM + "=" + parts.joinToString(PART_SEPARATOR.toString(), transform = ::encode)

    /** The parts drawn on a tile of zoom [z]: those whose zoom range holds it (a band joined by « OU » always is). */
    fun partsAt(z: Int): List<LayerConfig> =
        parts.filter { part ->
            !LayerElevation.limitsZoom(part) ||
                (z >= (part.displayMinZoom ?: Int.MIN_VALUE) && z <= (part.displayMaxZoom ?: Int.MAX_VALUE))
        }

    companion object {
        const val PARAM = "blend"
        private const val PART_SEPARATOR = ';'
        private const val FIELD_SEPARATOR = ','
        private const val FIELDS = 15
        private const val ZOOM_MIN_FIELD = 8
        private const val ELEVATION_MAX_FIELD = 11

        /**
         * The recipe of [layer] in [stack], or `null` when it is drawn by MapLibre alone (no blend mode, no tint).
         * Hidden and fully transparent layers under it are left out.
         */
        fun of(
            stack: LayerStack,
            layer: LayerConfig,
        ): BlendRecipe? {
            if (!layer.isBlended) return null
            val index = stack.indexOf(layer.id)
            if (index < 0) return null
            val under = stack.layers.subList(0, index).filter { it.visible && it.opacity > 0f }
            return BlendRecipe((under + layer).map { it.copy(id = "", visible = true) })
        }

        /** The recipe in an encoded URL query (as sent, `%XX` kept), or `null` when there is none or it is broken. */
        fun parse(encodedQuery: String?): BlendRecipe? {
            if (encodedQuery.isNullOrEmpty()) return null
            val value =
                encodedQuery
                    .split('&')
                    .firstOrNull { it.startsWith("$PARAM=") }
                    ?.substring(PARAM.length + 1) ?: return null
            val parts = value.split(PART_SEPARATOR).map { decode(it) ?: return null }
            return if (parts.isEmpty()) null else BlendRecipe(parts)
        }

        /**
         * One part: map (with `%XX` for anything but letters, digits, `-`, `_` and `.`), mode, opacity, contrast,
         * brightness, saturation, hue, inverted (`1` / empty), zoom range, elevation band, `or` / empty, tint
         * (`rrggbb`), tint strength. An empty field is the default value.
         */
        private fun encode(part: LayerConfig): String =
            listOf(
                escape(part.sourceId),
                part.blend.key.takeIf { part.blend != BlendMode.NORMAL } ?: "",
                number(part.opacity, 1f),
                number(part.contrast, 0f),
                number(part.brightness, 0f),
                number(part.saturation, 0f),
                number(part.hue, 0f),
                if (part.inverted) "1" else "",
                part.displayMinZoom?.toString() ?: "",
                part.displayMaxZoom?.toString() ?: "",
                part.displayMinElevation?.toString() ?: "",
                part.displayMaxElevation?.toString() ?: "",
                if (part.rangeLogic == RangeLogic.OR) "or" else "",
                part.tint?.let { "%06x".format(Locale.ROOT, it and RGB) } ?: "",
                number(part.tintStrength, LayerConfig.DEFAULT_TINT_STRENGTH),
            ).joinToString(FIELD_SEPARATOR.toString()).trimEnd(FIELD_SEPARATOR)

        private fun decode(text: String): LayerConfig? {
            val f = text.split(FIELD_SEPARATOR).let { it + List((FIELDS - it.size).coerceAtLeast(0)) { "" } }
            if (f.size != FIELDS) return null
            // Zoom range and elevation band: an empty field is no limit, anything else must be a whole number.
            if ((ZOOM_MIN_FIELD..ELEVATION_MAX_FIELD).any { f[it].isNotEmpty() && f[it].toIntOrNull() == null }) {
                return null
            }
            val source = unescape(f[0])?.takeIf { it.isNotEmpty() } ?: return null
            return LayerConfig(
                id = "",
                sourceId = source,
                blend = if (f[1].isEmpty()) BlendMode.NORMAL else BlendMode.fromKey(f[1]) ?: return null,
                opacity = float(f[2], 1f) ?: return null,
                contrast = float(f[3], 0f) ?: return null,
                brightness = float(f[4], 0f) ?: return null,
                saturation = float(f[5], 0f) ?: return null,
                hue = float(f[6], 0f) ?: return null,
                inverted = f[7] == "1",
                displayMinZoom = f[8].toIntOrNull(),
                displayMaxZoom = f[9].toIntOrNull(),
                displayMinElevation = f[10].toIntOrNull(),
                displayMaxElevation = f[11].toIntOrNull(),
                rangeLogic = if (f[12] == "or") RangeLogic.OR else RangeLogic.AND,
                tint = if (f[13].isEmpty()) null else f[13].toIntOrNull(HEX) ?: return null,
                tintStrength = float(f[14], LayerConfig.DEFAULT_TINT_STRENGTH) ?: return null,
            )
        }

        private fun float(
            field: String,
            default: Float,
        ): Float? = if (field.isEmpty()) default else field.toFloatOrNull()

        private fun number(
            value: Float,
            default: Float,
        ): String {
            if (value == default) return ""
            val text = "%.3f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
            return if (text == "-0") "0" else text
        }

        private fun escape(text: String): String =
            buildString {
                for (byte in text.toByteArray(Charsets.UTF_8)) {
                    val c = (byte.toInt() and BYTE).toChar()
                    if ((c.isLetterOrDigit() && c.code < ASCII) || c == '-' || c == '_' || c == '.') {
                        append(c)
                    } else {
                        append('%').append("%02X".format(Locale.ROOT, byte.toInt() and BYTE))
                    }
                }
            }

        private fun unescape(text: String): String? {
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val hex = text.substring(i + 1, i + 3)
                    bytes.write(hex.toIntOrNull(HEX) ?: return null)
                    i += 3
                } else {
                    bytes.write(c.code)
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        private const val BYTE = 0xFF
        private const val ASCII = 128
        private const val HEX = 16
        private const val RGB = 0xFFFFFF
    }
}
