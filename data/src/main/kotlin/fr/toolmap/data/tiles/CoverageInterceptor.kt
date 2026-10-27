package fr.toolmap.data.tiles

import fr.toolmap.core.tiles.SolidPng
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Fake host of the "stored coverage" overlay: `https://coverage.toolmap.local/<sourceId>/<z>/<x>/<y>`. */
object CoverageHost {
    const val HOST = "coverage.toolmap.local"

    fun templateFor(sourceId: String): String {
        val base =
            HttpUrl
                .Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegment(sourceId)
                .build()
                .toString()
                .trimEnd('/')
        return "$base/{z}/{x}/{y}"
    }
}

/**
 * Draws the stored coverage as map tiles: green with a frame where the tile of that source is stored, pale green
 * where only deeper tiles are stored, 404 (no tile) elsewhere. Never touches the network.
 */
class CoverageInterceptor(
    private val store: MbTilesStore,
) : Interceptor {
    private val full = SolidPng.create(TILE_SIZE, FULL_FILL, FULL_FRAME, frameWidth = 2)
    private val partial = SolidPng.create(TILE_SIZE, PARTIAL_FILL)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != CoverageHost.HOST) return chain.proceed(request)
        val segments = request.url.pathSegments
        val z = segments.getOrNull(1)?.toIntOrNull()
        val x = segments.getOrNull(2)?.toIntOrNull()
        val y = segments.getOrNull(3)?.toIntOrNull()
        if (segments.size != SEGMENTS || z == null || x == null || y == null || z !in 0..MAX_ZOOM) {
            return reply(request, 400, ByteArray(0))
        }
        val tileCount = 1L shl z
        if (x < 0 || y < 0 || x >= tileCount || y >= tileCount) return reply(request, 400, ByteArray(0))
        val coverage =
            try {
                // The coverage of the map is that of the storage its tiles live in at this zoom (ADR-031).
                store.storageIdOf(TileKey(segments[0], z, x, y))?.let(store::peek)?.coverage(z, x, y) ?: Coverage.NONE
            } catch (e: android.database.sqlite.SQLiteException) {
                Coverage.NONE
            }
        return when (coverage) {
            Coverage.FULL -> reply(request, 200, full)
            Coverage.PARTIAL -> reply(request, 200, partial)
            Coverage.NONE -> reply(request, 404, ByteArray(0))
        }
    }

    private fun reply(
        request: Request,
        code: Int,
        body: ByteArray,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "No coverage")
            .header("Content-Type", "image/png")
            .body(body.toResponseBody(PNG))
            .build()

    private companion object {
        const val TILE_SIZE = 256
        const val SEGMENTS = 4
        const val MAX_ZOOM = 22
        const val FULL_FILL = 0x6633CC33
        const val FULL_FRAME = 0xCC1E7A1E.toInt()
        const val PARTIAL_FILL = 0x4466DD66
        val PNG = "image/png".toMediaType()
    }
}
