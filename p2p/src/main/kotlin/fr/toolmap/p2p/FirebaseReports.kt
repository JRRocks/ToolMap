package fr.toolmap.p2p

import fr.toolmap.core.p2p.RelayConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Drops problem reports (ADR-116, `docs/specs/problems.md`) into the `reports` node of the coordination server, where
 * the GitHub workflow `problems.yml` picks them up and turns them into issues. The device signs in anonymously with its
 * own identity ([authStore], not the one of the sharing), and the rules let it create a report, never read one.
 *
 * Calls block: run them off the main thread. A refusal ([FirebaseRefused]: rules not published yet) and a network
 * failure (an `IOException`) are thrown for the caller to keep the report and try again later.
 */
class FirebaseReports(
    private val config: RelayConfig,
    authStore: AuthStore,
    private val http: OkHttpClient = DEFAULT_HTTP,
    endpoints: FirebaseEndpoints = FirebaseEndpoints(),
) {
    private val auth = FirebaseAuth(config, { http }, authStore, endpoints, System::currentTimeMillis)
    private val db = FirebaseDb(endpoints.dbBase ?: config.base, auth, { http })

    /** Writes the report [json] under `reports/<id>`; [id] is `<fingerprint>-<random>` (checked by the rules). */
    fun send(
        id: String,
        json: String,
    ) {
        require(ID.matches(id)) { "bad report id" }
        val body =
            buildJsonObject {
                put("v", FORMAT)
                put("j", json)
                putJsonObject("t") { put(".sv", "timestamp") }
            }
        db.put("reports/$id", body.toString())
    }

    companion object {
        const val FORMAT = 1
        val ID = Regex("""^[0-9a-f]{16}-[0-9a-z]{10}$""")

        private val DEFAULT_HTTP: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
