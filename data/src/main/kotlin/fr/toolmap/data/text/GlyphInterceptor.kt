package fr.toolmap.data.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import fr.toolmap.core.text.GlyphRange
import fr.toolmap.core.text.GlyphRasterizer
import fr.toolmap.core.text.RasterGlyph
import fr.toolmap.core.text.SignedDistance
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Answers MapLibre's glyph requests (`https://glyphs.toolmap.local/{fontstack}/{range}.pbf`, ADR-062) with files made
 * on the device by [GlyphRange] from [rasterizer]: names show on the map without a hosted font, offline. Each file is
 * kept in memory once made (a few kilobytes per range used). Other requests go through untouched.
 */
class GlyphInterceptor(
    private val rasterizer: GlyphRasterizer = SystemFontRasterizer(),
) : Interceptor {
    private val made = ConcurrentHashMap<String, ByteArray>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val segments = request.url.pathSegments
        val start = segments.getOrNull(1)?.removeSuffix(PBF)?.let(GlyphRange::startOf)
        if (segments.size != 2 || start == null) {
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HTTP_NOT_FOUND)
                .message("not a glyph range")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }
        val fontStack = segments[0]
        val body = made.getOrPut("$fontStack/$start") { GlyphRange.encode(fontStack, start, rasterizer) }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(HTTP_OK)
            .message("OK")
            .body(body.toResponseBody(PROTOBUF))
            .build()
    }

    companion object {
        const val HOST = "glyphs.toolmap.local"

        /** The `glyphs` URL of the map style. */
        const val STYLE_URL = "https://$HOST/{fontstack}/{range}.pbf"

        /** The font stack of the names on the map: the system sans serif, medium weight. */
        const val FONT = "ToolMap Sans Medium"

        /** The same sans serif at its regular weight (the letters of the MGRS squares, P24.3). */
        const val REGULAR_FONT = "ToolMap Sans"

        private const val PBF = ".pbf"
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private val PROTOBUF = "application/x-protobuf".toMediaType()
    }
}

/** Draws glyphs with the phone's own sans serif (Roboto on most phones), at MapLibre's 24 px. */
class SystemFontRasterizer : GlyphRasterizer {
    private val regular = paint(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL))
    private val bold = paint(Typeface.create("sans-serif-medium", Typeface.NORMAL))

    private fun paint(face: Typeface) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = GlyphRange.FONT_SIZE.toFloat()
            color = OPAQUE_BLACK
        }

    @Synchronized
    override fun draw(
        codePoint: Int,
        bold: Boolean,
    ): RasterGlyph? {
        // Surrogates and control characters have no glyph of their own.
        if (codePoint in SURROGATES || Character.isISOControl(codePoint)) return null
        val paint = if (bold) this.bold else regular
        val text = String(Character.toChars(codePoint))
        if (!Character.isWhitespace(codePoint) && !paint.hasGlyph(text)) return null
        val advance = paint.measureText(text).roundToInt()
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.isEmpty) return RasterGlyph(0, 0, 0, 0, advance, ByteArray(0))
        val buffer = SignedDistance.BUFFER
        val width = bounds.width()
        val height = bounds.height()
        val bitmap = Bitmap.createBitmap(width + 2 * buffer, height + 2 * buffer, Bitmap.Config.ALPHA_8)
        Canvas(bitmap).drawText(text, (buffer - bounds.left).toFloat(), (buffer - bounds.top).toFloat(), paint)
        // Rows of an ALPHA_8 bitmap may be padded: copy them all, then keep the pixels of each row.
        val rows = ByteArray(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rows))
        val alpha = ByteArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) {
            System.arraycopy(
                rows,
                y * bitmap.rowBytes,
                alpha,
                y * bitmap.width,
                bitmap.width,
            )
        }
        bitmap.recycle()
        return RasterGlyph(width, height, bounds.left, -bounds.top, advance, alpha)
    }

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        val SURROGATES = 0xD800..0xDFFF
    }
}
