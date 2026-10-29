// Port of core/src/main/kotlin/fr/toolmap/core/wizard/ApiKeyExtractor.kt.

import Foundation

/// Finds API keys written in the query string of a tile URL and moves them to `<param>` values, so that the key is
/// stored (and can be hidden, redacted when sharing a file) apart from the URL: `apikey=abc` becomes
/// `apikey={$param:apikey}` with `apikey = abc` among the source parameters.
public enum ApiKeyExtractor {
    /// The rewritten template and the parameters that were pulled out of it (name -> secret value).
    public struct Extraction: Equatable, Hashable, Sendable {
        public let template: String
        public let params: [String: String]

        public init(template: String, params: [String: String]) {
            self.template = template
            self.params = params
        }

        public var found: Bool { !params.isEmpty }
    }

    private static let keyNames: Set<String> = [
        "apikey", "api_key", "api-key", "key", "token", "access_token", "accesstoken", "appid", "app_id",
    ]
    /// Kotlin `([?&])([A-Za-z0-9_\-]+)=([^&#]*)`.
    private static let queryPair = try! NSRegularExpression(pattern: "([?&])([A-Za-z0-9_\\-]+)=([^&#]*)")
    private static let maskLength = 6

    /// Rewrites `template`; keys that already point at a `{$...}` placeholder are left alone. When a parameter of the
    /// same name already exists with another value, the new one is called `name-2`, `name-3`...
    public static func extract(_ template: String, existingParams: [String: String] = [:]) -> Extraction {
        var taken = existingParams
        var extracted: [String: String] = [:]
        let text = template as NSString
        var out = ""
        var last = 0
        for match in queryPair.matches(in: template, options: [], range: NSRange(location: 0, length: text.length)) {
            let whole = match.range
            out += text.substring(with: NSRange(location: last, length: whole.location - last))
            let separator = text.substring(with: match.range(at: 1))
            let name = text.substring(with: match.range(at: 2))
            let value = text.substring(with: match.range(at: 3))
            if !keyNames.contains(name.lowercased()) || value.isEmpty || value.contains("{$") {
                out += text.substring(with: whole)
            } else {
                let param = freeName(name.lowercased(), value, taken)
                taken[param] = value
                extracted[param] = value
                out += separator + name + "={$param:" + param + "}"
            }
            last = whole.location + whole.length
        }
        out += text.substring(from: last)
        return Extraction(template: out, params: extracted)
    }

    /// `••••••n_ws`: the last `visible` characters of `secret`, for display next to a masked field.
    public static func masked(_ secret: String, visible: Int = 4) -> String {
        if secret.count <= visible {
            return String(repeating: "•", count: secret.count)
        }
        return String(repeating: "•", count: maskLength) + String(secret.suffix(visible))
    }

    private static func freeName(_ base: String, _ value: String, _ taken: [String: String]) -> String {
        var candidate = base
        var n = 2
        while let existing = taken[candidate], existing != value {
            candidate = "\(base)-\(n)"
            n += 1
        }
        return candidate
    }
}
