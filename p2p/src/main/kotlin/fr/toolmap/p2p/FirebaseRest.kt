package fr.toolmap.p2p

import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.p2p.RelayConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** The keys made by a Firebase push: their first 8 characters are the server time in milliseconds, in base 64. */
internal object PushIds {
    private const val CHARS = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
    private const val TIME_CHARS = 8
    private const val BITS = 6

    /** The time a push key was made, or `null` when [key] is not one. */
    fun timeMs(key: String): Long? {
        if (key.length < TIME_CHARS) return null
        var t = 0L
        for (i in 0 until TIME_CHARS) {
            val v = CHARS.indexOf(key[i])
            if (v < 0) return null
            t = (t shl BITS) or v.toLong()
        }
        return t
    }

    /** The time part of a key made at [timeMs] (tests). */
    fun timePrefix(timeMs: Long): String {
        val out = CharArray(TIME_CHARS)
        var t = timeMs
        for (i in TIME_CHARS - 1 downTo 0) {
            out[i] = CHARS[(t and ((1L shl BITS) - 1)).toInt()]
            t = t shr BITS
        }
        return String(out)
    }
}

/** Where the Firebase services are; the defaults are Google's, tests point them at the local emulator. */
class FirebaseEndpoints(
    val signUp: String = "https://identitytoolkit.googleapis.com/v1/accounts:signUp",
    val refresh: String = "https://securetoken.googleapis.com/v1/token",
    /** Overrides the database address of the [RelayConfig] (tests only). */
    val dbBase: String? = null,
)

/** The server said no (as opposed to not answering): the key is wrong, anonymous sign-in is off, the rules refuse. */
class FirebaseRefused(
    message: String,
) : IOException(message)

/** What survives a restart of the app so that a device keeps its anonymous Firebase identity. */
interface AuthStore {
    fun read(): String?

    fun write(refreshToken: String?)
}

/**
 * Anonymous sign-in and token renewal over REST (`accounts:signUp`, `securetoken`). Calls block. [http] is read at each
 * call: the relay changes it with the network (P21.5 f).
 */
internal class FirebaseAuth(
    private val config: RelayConfig,
    private val http: () -> OkHttpClient,
    private val store: AuthStore,
    private val endpoints: FirebaseEndpoints,
    private val clockMs: () -> Long,
) {
    @Volatile
    private var idToken: String? = null
    private var expiresAtMs = 0L
    private var refreshToken: String? = store.read()

    var uid: String = ""
        private set

    /** The ID token in use, as it is (no renewal, never blocks): the stream compares it with its own (P21.5 g). */
    val current: String? get() = idToken

    /** A valid ID token, signing in or renewing first when needed. */
    @Synchronized
    fun token(): String {
        val t = idToken
        if (t != null && clockMs() < expiresAtMs - RENEW_MARGIN_MS) return t
        return renew()
    }

    /** Forgets [token] (any token when null); a newer one, already renewed, is kept for the next call. */
    @Synchronized
    fun invalidate(token: String? = null) {
        if (token == null || token == idToken) idToken = null
    }

    private fun renew(): String {
        val rt = refreshToken
        val t0 = clockMs()
        if (rt != null) {
            try {
                val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).build()
                val json = call(Request.Builder().url(withKey(endpoints.refresh)).post(body).build())
                return accept(
                    json.str("id_token"),
                    json.str("refresh_token"),
                    json.str("expires_in"),
                    json.str("user_id"),
                ).also { EventLog.event("fb.auth", "via" to "refresh", "ms" to clockMs() - t0) }
            } catch (e: FirebaseRefused) {
                // The saved identity is gone (account deleted, token revoked): start again as a new anonymous user.
                EventLog.event("fb.auth.lost", "why" to e.message)
                refreshToken = null
                store.write(null)
            } catch (e: IOException) {
                EventLog.event(
                    "fb.auth.fail",
                    "via" to "refresh",
                    "error" to e.javaClass.simpleName,
                    "why" to e.message,
                )
                throw e
            }
        }
        val body = """{"returnSecureToken":true}""".toRequestBody(JSON_TYPE)
        val json =
            try {
                call(Request.Builder().url(withKey(endpoints.signUp)).post(body).build())
            } catch (e: IOException) {
                EventLog.event("fb.auth.fail", "via" to "signup", "error" to e.javaClass.simpleName, "why" to e.message)
                throw e
            }
        return accept(json.str("idToken"), json.str("refreshToken"), json.str("expiresIn"), json.str("localId"))
            .also { EventLog.event("fb.auth", "via" to "signup", "ms" to clockMs() - t0) }
    }

    private fun accept(
        id: String,
        refresh: String,
        expiresInS: String,
        user: String,
    ): String {
        idToken = id
        refreshToken = refresh
        uid = user
        expiresAtMs = clockMs() + (expiresInS.toLongOrNull() ?: DEFAULT_LIFETIME_S) * MS_PER_S
        store.write(refresh)
        return id
    }

    private fun withKey(url: String) = url.toHttpUrl().newBuilder().addQueryParameter("key", config.apiKey).build()

    private fun call(request: Request): JsonObject =
        http().newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) {
                runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw IOException("bad answer") }
            } else if (r.code in REFUSED_CODES) {
                throw FirebaseRefused(errorMessage(text) ?: "HTTP ${r.code}")
            } else {
                throw IOException("HTTP ${r.code}")
            }
        }

    private fun errorMessage(text: String): String? =
        runCatching {
            Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw IOException("missing $key")

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
        const val RENEW_MARGIN_MS = 120_000L
        const val DEFAULT_LIFETIME_S = 3600L
        const val MS_PER_S = 1000L
        val REFUSED_CODES = setOf(400, 401, 403)
    }
}

/**
 * A few calls on the Realtime Database over REST; every call blocks and carries the current token. [http] is read at
 * each call (see [FirebaseAuth]).
 */
internal class FirebaseDb(
    private val base: String,
    private val auth: FirebaseAuth,
    private val http: () -> OkHttpClient,
) {
    private fun url(
        path: String,
        token: String,
    ) = "$base/$path.json".toHttpUrl().newBuilder().addQueryParameter("auth", token).build()

    /** Runs [build] with a fresh token; one retry with a renewed token when the server answers 401. */
    private fun <T> exchange(
        build: (String) -> Request,
        read: (Response) -> T,
    ): T {
        repeat(2) { attempt ->
            val token = auth.token()
            val response = http().newCall(build(token)).execute()
            response.use { r ->
                // The address is never logged: it carries the token.
                if (!r.isSuccessful) {
                    EventLog.event(
                        "fb.http",
                        "method" to r.request.method,
                        "code" to r.code,
                        "try" to attempt,
                    )
                }
                if (r.code == UNAUTHORIZED && attempt == 0) {
                    auth.invalidate(token)
                } else if (r.code == UNAUTHORIZED || r.code == FORBIDDEN) {
                    throw FirebaseRefused("permission denied (${r.code})")
                } else if (!r.isSuccessful) {
                    throw IOException("HTTP ${r.code}")
                } else {
                    return read(r)
                }
            }
        }
        throw FirebaseRefused("permission denied")
    }

    fun put(
        path: String,
        json: String,
    ) = exchange({ Request.Builder().url(url(path, it)).put(json.toRequestBody(JSON)).build() }, { })

    fun patch(
        path: String,
        json: String,
    ) = exchange({ Request.Builder().url(url(path, it)).patch(json.toRequestBody(JSON)).build() }, { })

    fun delete(path: String) = exchange({ Request.Builder().url(url(path, it)).delete().build() }, { })

    /** Appends a child with a time-ordered key and returns the key. */
    fun push(
        path: String,
        json: String,
    ): String =
        exchange({ Request.Builder().url(url(path, it)).post(json.toRequestBody(JSON)).build() }) { r ->
            Json.parseToJsonElement(
                r.body?.string().orEmpty(),
            ).jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

    fun get(path: String): String =
        exchange({
            Request.Builder().url(url(path, it)).get().build()
        }) { it.body?.string().orEmpty() }

    /** The keys of the children of [path], without their content (`shallow`); none when [path] is empty. */
    fun keys(path: String): List<String> {
        val body =
            exchange({
                val shallow = url(path, it).newBuilder().addQueryParameter("shallow", "true").build()
                Request.Builder().url(shallow).get().build()
            }) { it.body?.string().orEmpty() }
        val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        return (json as? JsonObject)?.keys?.toList().orEmpty()
    }

    /**
     * Opens a streamed read with [token]; the caller reads the body until it ends and closes the response ([onCall]
     * can cancel it).
     */
    fun stream(
        path: String,
        client: OkHttpClient,
        token: String = auth.token(),
        onCall: (Call) -> Unit = {},
    ): Response {
        val call =
            client.newCall(
                Request
                    .Builder()
                    .url(url(path, token))
                    .header("Accept", "text/event-stream")
                    .get()
                    .build(),
            )
        onCall(call)
        val response = call.execute()
        if (!response.isSuccessful) EventLog.event("fb.http", "method" to "STREAM", "code" to response.code)
        if (response.code == UNAUTHORIZED || response.code == FORBIDDEN) {
            response.close()
            auth.invalidate(token)
            throw FirebaseRefused("permission denied (${response.code})")
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}
