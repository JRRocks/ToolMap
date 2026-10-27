package fr.toolmap.core.wizard

/**
 * Finds API keys written in the query string of a tile URL and moves them to `<param>` values, so that the key is
 * stored (and can be hidden, redacted when sharing a file) apart from the URL: `apikey=abc` becomes
 * `apikey={$param:apikey}` with `apikey = abc` among the source parameters.
 */
object ApiKeyExtractor {
    /** The rewritten template and the parameters that were pulled out of it (name -> secret value). */
    data class Extraction(
        val template: String,
        val params: Map<String, String>,
    ) {
        val found: Boolean get() = params.isNotEmpty()
    }

    private val KEY_NAMES =
        setOf("apikey", "api_key", "api-key", "key", "token", "access_token", "accesstoken", "appid", "app_id")
    private val QUERY_PAIR = Regex("""([?&])([A-Za-z0-9_\-]+)=([^&#]*)""")

    /**
     * Rewrites [template]; keys that already point at a `{$...}` placeholder are left alone. When a parameter of
     * the same name already exists with another value, the new one is called `name-2`, `name-3`...
     */
    fun extract(
        template: String,
        existingParams: Map<String, String> = emptyMap(),
    ): Extraction {
        val taken = existingParams.toMutableMap()
        val extracted = LinkedHashMap<String, String>()
        val rewritten =
            QUERY_PAIR.replace(template) { match ->
                val (separator, name, value) = match.destructured
                if (name.lowercase() !in KEY_NAMES || value.isEmpty() || value.contains("{$")) {
                    match.value
                } else {
                    val param = freeName(name.lowercase(), value, taken)
                    taken[param] = value
                    extracted[param] = value
                    "$separator$name={\$param:$param}"
                }
            }
        return Extraction(rewritten, extracted)
    }

    /** `••••••n_ws`: the last [visible] characters of [secret], for display next to a masked field. */
    fun masked(
        secret: String,
        visible: Int = 4,
    ): String =
        if (secret.length <=
            visible
        ) {
            "•".repeat(secret.length)
        } else {
            "•".repeat(MASK_LENGTH) + secret.takeLast(visible)
        }

    private fun freeName(
        base: String,
        value: String,
        taken: Map<String, String>,
    ): String {
        var candidate = base
        var n = 2
        while (taken.containsKey(candidate) && taken[candidate] != value) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    private const val MASK_LENGTH = 6
}
