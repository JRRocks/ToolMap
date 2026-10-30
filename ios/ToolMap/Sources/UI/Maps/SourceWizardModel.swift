import Foundation
import Observation
import ToolMapCore

/// How the map is described: a tile URL template, or a WMTS / WMS service whose layers are listed.
enum WizardKind {
    case xyz
    case wmts
    case wms

    var service: OgcService? {
        switch self {
        case .xyz: return nil
        case .wmts: return .wmts
        case .wms: return .wms
        }
    }

    var title: String {
        switch self {
        case .xyz: return "Adresse de tuiles (XYZ)"
        case .wmts: return "Service WMTS"
        case .wms: return "Service WMS"
        }
    }
}

/// The five steps of the wizard (Android `WizardStep`); the kind of map is chosen before, in « Ajouter une carte ».
enum WizardStep: Int, CaseIterable, Comparable {
    case address
    case access
    case zooms
    case test
    case save

    var title: String {
        switch self {
        case .address: return "Adresse"
        case .access: return "Accès"
        case .zooms: return "Zooms"
        case .test: return "Test"
        case .save: return "Enregistrer"
        }
    }

    static func < (lhs: WizardStep, rhs: WizardStep) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

/// A map saved by the wizard, and whether to show it.
struct WizardResult {
    let id: String
    let show: Bool
}

/// The capabilities request of a WMTS / WMS service.
enum WizardCapabilities {
    case idle
    case loading
    case loaded(url: String, document: CapabilitiesDocument)
    case failed(String)
}

/// The layer picked in a capabilities list, with the options the user can still change.
struct WizardLayerChoice: Equatable {
    var layerId: String
    var styleId = ""
    var matrixSetId = ""
    var format = ""
}

/// What the test tile showed (Android `ProbeVerdict`).
enum WizardVerdict {
    case ok
    case keyOrReferer
    case notFound
    case badRequest
    case rateLimited
    case serverError
    case network
    case cleartextBlocked
    case certificateRejected
    case notAnImage
    case empty
    case templateError
    case outOfRange

    var text: String {
        switch self {
        case .ok:
            return "Le serveur a répondu par une image : la carte devrait s'afficher."
        case .keyOrReferer:
            return "Le serveur refuse la demande (401 ou 403) : une clé d'API, un Referer, un cookie ou un en-tête manque ou n'est pas accepté. Vérifiez l'étape « Accès »."
        case .notFound:
            return "Le serveur ne connaît pas cette tuile (404) : l'adresse, la couche ou le lieu du test est peut-être faux, ou la carte n'a rien à cet endroit."
        case .badRequest:
            return "Le serveur rejette la requête : l'adresse est probablement incorrecte (paramètre manquant, nom de couche faux)."
        case .rateLimited:
            return "Trop de requêtes (429) : le serveur limite le débit. Réessayez plus tard ou réduisez les téléchargements simultanés."
        case .serverError:
            return "Le serveur est en panne ou en erreur (5xx). Réessayez plus tard."
        case .network:
            return "Aucune réponse du serveur (réseau coupé, nom de serveur inconnu, délai dépassé ou certificat refusé)."
        case .cleartextBlocked:
            return "iOS refuse cette connexion non chiffrée (http://). Utilisez l'adresse https:// du serveur si elle existe."
        case .certificateRejected:
            return "Le serveur présente un certificat qu'iOS ne reconnaît pas. S'il vient de votre entreprise ou de votre administration, installez son profil puis activez-le dans Réglages ▸ Général ▸ Informations ▸ Réglages des certificats. ToolMap l'acceptera ensuite."
        case .notAnImage:
            return "Le serveur répond, mais pas par une image (page d'erreur, de connexion ou XML). Vérifiez l'adresse et les identifiants."
        case .empty:
            return "Le serveur répond sans contenu : il n'a pas de tuile à cet endroit. Essayez un autre lieu de test."
        case .templateError:
            return "L'adresse ne peut pas être construite : un paramètre est inconnu ou manquant."
        case .outOfRange:
            return "Cette carte n'a pas de tuile à ce zoom."
        }
    }
}

/// The answer to the test tile (Android `ProbeResult`).
struct WizardProbeResult {
    let verdict: WizardVerdict
    let tile: PreviewTile
    /// Status of the server's answer; nil when it never answered.
    var httpCode: Int?
    var contentType: String?
    /// The image, when `verdict` is `ok`.
    var image: Data?
    var sizeBytes = 0
    var latencyMillis = 0
    /// The address that was requested, secrets hidden.
    var url: String?
    /// Start of the server's own message, or the network error, secrets hidden.
    var detail: String?
}

/// State and actions of the add-a-map wizard (Android `SourceWizardViewModel`, N2).
@Observable
final class SourceWizardModel {
    /// Issues that keep the « Suivant » of each step off.
    static let addressIssues: Set<SourceDraftIssue> = [.urlEmpty, .urlNotHttp, .noTilePlaceholder, .unknownPlaceholder, .missingParam]
    static let accessIssues: Set<SourceDraftIssue> = [.headerInvalid, .threadsInvalid, .updateDelayInvalid]
    static let zoomIssues: Set<SourceDraftIssue> = [.nameEmpty, .zoomInvalid, .tileSizeInvalid, .opacityInvalid]
    private static let copySuffix = " (copie)"
    private static let maxBytes = 25 * 1024 * 1024

    var step = WizardStep.address
    /// The furthest step reached: the stops up to it can be touched (all of them in a modification).
    var reached = WizardStep.address
    var kind = WizardKind.xyz
    var draft = SourceDraft() {
        didSet {
            if draft != oldValue { probe = nil }
        }
    }
    /// Address typed for a WMTS / WMS server.
    var serviceAddress = ""
    var capabilities = WizardCapabilities.idle
    var layerFilter = ""
    var choice: WizardLayerChoice?
    /// Names of the parameters an API key was moved to.
    var keysMoved: [String] = []
    var probe: WizardProbeResult?
    var probing = false
    /// The map being modified, or nil when a new one is made.
    var editingId: String?
    var addToMap = true
    var redactKeys = true
    var savedId: String?
    var error: String?
    /// Centre of the map when the wizard was opened: a place to test the tile at.
    var mapCenter: LonLatZoom?

    @ObservationIgnored private weak var maps: MapsController?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var originalServers: [String] = []
    @ObservationIgnored private var originalTileSize = 0
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    @ObservationIgnored private var probeTask: Task<Void, Never>?

    var isEditing: Bool { editingId != nil }
    var issues: [SourceDraftIssue] { draft.validate() }
    var blockingIssues: [SourceDraftIssue] { issues.filter(\.isError) }

    /// Opens the wizard for a new map of `kind`, or for `sourceId` (an imported one is modified, a provided one copied).
    func start(kind: WizardKind, sourceId: String?, maps: MapsController, center: LonLatZoom) {
        guard !started else { return }
        started = true
        self.maps = maps
        self.kind = kind
        mapCenter = center
        guard let id = sourceId, let source = maps.source(id), let found = SourceDraft.fromSource(source) else { return }
        if maps.isImported(source.id) {
            originalServers = found.urls
            originalTileSize = found.tileSize
            editingId = source.id
            reached = .save
            draft = found
        } else {
            var copy = found
            copy.id = ""
            copy.name = source.name + SourceWizardModel.copySuffix
            draft = copy
        }
    }

    // MARK: Navigation

    /// Whether « Suivant » (or a stop further on) can leave the current step.
    var canContinue: Bool {
        let blocked = Set(blockingIssues)
        switch step {
        case .address: return blocked.isDisjoint(with: SourceWizardModel.addressIssues)
        case .access: return blocked.isDisjoint(with: SourceWizardModel.accessIssues)
        case .zooms: return blocked.isDisjoint(with: SourceWizardModel.zoomIssues)
        case .test: return true
        case .save: return false
        }
    }

    /// Goes to `target`. Leaving the address step, API keys written in the URL move to parameters (kept apart,
    /// redacted on export).
    func goTo(_ target: WizardStep) {
        if step == .address, target != .address {
            let moved = draft.extractKeys()
            if moved != draft {
                keysMoved = moved.params.keys.filter { draft.params[$0] == nil }.sorted()
                draft = moved
            }
        }
        step = target
        if target > reached { reached = target }
        error = nil
    }

    func next() {
        guard let following = WizardStep(rawValue: step.rawValue + 1) else { return }
        goTo(following)
    }

    func back() {
        guard let previous = WizardStep(rawValue: step.rawValue - 1) else { return }
        goTo(previous)
    }

    // MARK: Address

    /// One server per non-blank line, spaces trimmed; an empty text gives a single empty entry.
    static func parseUrls(_ text: String) -> [String] {
        let lines = text.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return lines.isEmpty ? [""] : lines
    }

    func setUrls(_ text: String) {
        let urls = SourceWizardModel.parseUrls(text)
        if urls != draft.urls { draft.urls = urls }
    }

    /// The secret found in the first URL, for the masked display next to the detection notice.
    var detectedKey: (String, String)? {
        for url in draft.urls {
            let extraction = ApiKeyExtractor.extract(url)
            if let first = extraction.params.sorted(by: { $0.key < $1.key }).first {
                return (first.key, first.value)
            }
        }
        return nil
    }

    /// Moves the API keys found in the URLs to parameters right now.
    func extractKeysNow() {
        let before = draft
        let after = before.extractKeys()
        keysMoved = after.params.keys.filter { before.params[$0] == nil }.sorted()
        draft = after
    }

    func readCapabilities() {
        guard let service = kind.service else { return }
        capabilities = .loading
        choice = nil
        let address = serviceAddress
        let settings = draft
        fetchTask?.cancel()
        fetchTask = Task { [weak self] in
            let state = await SourceWizardModel.fetch(address, service, settings)
            if Task.isCancelled { return }
            await MainActor.run {
                self?.capabilities = state
            }
        }
    }

    func pickWmtsLayer(_ layer: WmtsLayer) {
        guard case let .loaded(url, document) = capabilities, case let .wmts(caps) = document,
              let first = WmtsSourceBuilder.defaultChoice(caps, layer)
        else { return }
        applyWmts(caps, url, first)
    }

    /// Changes the style, the tile matrix set or the format of the chosen WMTS layer.
    func changeWmts(_ change: (inout WizardLayerChoice) -> Void) {
        guard case let .loaded(url, document) = capabilities, case let .wmts(caps) = document, var current = choice
        else { return }
        change(&current)
        let wanted = WmtsChoice(
            layerId: current.layerId,
            styleId: current.styleId,
            matrixSetId: current.matrixSetId,
            format: current.format
        )
        applyWmts(caps, url, wanted)
    }

    private func applyWmts(_ caps: WmtsCapabilities, _ url: String, _ wanted: WmtsChoice) {
        guard let spec = try? WmtsSourceBuilder.build(caps, wanted, url) else { return }
        choice = WizardLayerChoice(
            layerId: wanted.layerId,
            styleId: wanted.styleId,
            matrixSetId: wanted.matrixSetId,
            format: wanted.format
        )
        var copy = draft
        if copy.name.trimmingCharacters(in: .whitespaces).isEmpty || editingId == nil {
            copy.name = spec.name
        }
        copy.urls = [spec.template]
        copy.zoomSpec = "\(spec.minZoom)-\(spec.maxZoom)"
        copy.tileSize = spec.tileSize
        copy.copyright = spec.copyright ?? ""
        copy.isLayer = false
        copy.bboxCrs = LevelDef.crsWGS84
        draft = copy
    }

    func pickWmsLayer(_ layer: WmsLayer) {
        guard case let .loaded(url, document) = capabilities, case let .wms(caps) = document else { return }
        var format = WmsSourceBuilder.defaultFormat(caps)
        if let previous = choice?.format, caps.formats.contains(previous) {
            format = previous
        }
        applyWms(caps, url, layer, format)
    }

    func changeWmsFormat(_ format: String) {
        guard case let .loaded(url, document) = capabilities, case let .wms(caps) = document,
              let layer = caps.layers.first(where: { $0.name == choice?.layerId })
        else { return }
        applyWms(caps, url, layer, format)
    }

    private func applyWms(_ caps: WmsCapabilities, _ url: String, _ layer: WmsLayer, _ format: String) {
        guard let spec = try? WmsSourceBuilder.build(caps, layer, format, url) else { return }
        choice = WizardLayerChoice(layerId: layer.name, format: format)
        var copy = draft
        if copy.name.trimmingCharacters(in: .whitespaces).isEmpty || editingId == nil {
            copy.name = spec.name
        }
        copy.urls = [spec.template]
        copy.zoomSpec = "0-18"
        copy.tileSize = 256
        copy.copyright = caps.title ?? ""
        copy.isLayer = format.lowercased().contains("png")
        copy.bboxCrs = spec.bboxCrs
        draft = copy
    }

    // MARK: Zooms and test place

    /// Uses the place the map showed when the wizard opened for the test tile.
    func usePreviewHere() {
        guard let center = mapCenter else { return }
        draft.previewLocation = center
    }

    // MARK: Test

    func runProbe() {
        guard draft.isValid else { return }
        var copy = draft
        if copy.id.trimmingCharacters(in: .whitespaces).isEmpty { copy.id = "probe" }
        guard let source = try? copy.toSource(), let level = source.levels.first,
              let tile = try? PreviewTile.choose(level.zooms, draft.previewLocation)
        else { return }
        probing = true
        probe = nil
        probeTask?.cancel()
        probeTask = Task { [weak self] in
            let result = await SourceWizardModel.probe(source, tile)
            if Task.isCancelled { return }
            await MainActor.run {
                self?.probe = result
                self?.probing = false
            }
        }
    }

    // MARK: Save and export

    /// True when saving will erase stored tiles: the address of the map being edited changed and its old tiles are
    /// shown by no other map (a tile set shared with another map is kept, ADR-031).
    var willPurgeStoredTiles: Bool {
        !purgeTargets().isEmpty
    }

    private func purgeTargets() -> Set<String> {
        guard let id = editingId, let maps, let old = maps.source(id) else { return [] }
        if draft.urls == originalServers, draft.tileSize == originalTileSize { return [] }
        var copy = draft
        copy.id = id
        guard let replacement = try? copy.toSource() else { return [] }
        var kept = Set(MapTileContext.shared.storageIds(of: replacement))
        for other in maps.sources where other.id != id {
            kept.formUnion(MapTileContext.shared.storageIds(of: other))
        }
        var result = Set<String>()
        for storageId in MapTileContext.shared.storageIds(of: old) where !kept.contains(storageId) {
            if MapTileContext.shared.existingFile(storageId) != nil { result.insert(storageId) }
        }
        return result
    }

    /// Stores the map (a new id when needed). Returns its id, or nil when the draft is not valid.
    func save() -> String? {
        guard draft.isValid, let maps else { return nil }
        let typed = draft.id.trimmingCharacters(in: .whitespaces)
        let base = typed.isEmpty ? SourceIds.fromName(draft.name) : typed
        let id = editingId ?? savedId ?? SourceIds.unique(base) { maps.source($0) != nil }
        var copy = draft
        copy.id = id
        do {
            let source = try copy.toSource()
            let purge = purgeTargets()
            try maps.saveSource(source)
            for storageId in purge {
                TileService.shared.clear(storageId)
            }
            savedId = id
            draft = copy
            error = nil
            return id
        } catch {
            self.error = "Opération impossible : \(error.localizedDescription)"
            return nil
        }
    }

    /// The map as a temporary `.aqx` file to share; keys blanked when asked.
    func exportFile() -> URL? {
        guard draft.isValid else { return nil }
        var copy = draft
        let typed = copy.id.trimmingCharacters(in: .whitespaces)
        copy.id = savedId ?? (typed.isEmpty ? SourceIds.fromName(draft.name) : typed)
        guard let source = try? copy.toSource() else { return nil }
        let redacted: Set<String> = redactKeys ? Set(source.params.keys) : Set<String>()
        let text = AqxWriter.write([source], name: source.name, redactedParams: redacted)
        let url = AppFiles.temporary(SourceIds.fromName(draft.name) + ".aqx")
        do {
            try Data(text.utf8).write(to: url, options: .atomic)
            return url
        } catch {
            self.error = "Opération impossible : \(error.localizedDescription)"
            return nil
        }
    }

    // MARK: Network

    /// Downloads and reads a WMTS / WMS capabilities document (Android `CapabilitiesFetcher`).
    static func fetch(_ input: String, _ service: OgcService, _ draft: SourceDraft) async -> WizardCapabilities {
        let address: String
        do {
            address = try CapabilitiesUrl.normalize(input, service)
        } catch let error as CapabilitiesUrlError {
            switch error.problem {
            case .empty: return .failed("Saisissez l'adresse du service.")
            case .tileRequest:
                return .failed("Cette adresse demande déjà une tuile ou une image : choisissez « Adresse de tuiles (XYZ) ».")
            case .notHttp: return .failed("L'adresse doit commencer par http:// ou https://.")
            }
        } catch {
            return .failed("L'adresse doit commencer par http:// ou https://.")
        }
        guard let url = URL(string: address) else { return .failed("L'adresse doit commencer par http:// ou https://.") }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("text/xml,application/xml,*/*", forHTTPHeaderField: "Accept")
        apply(draft, to: &request)
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if !(200 ..< 300).contains(status) {
                return .failed("Le serveur répond par une erreur HTTP \(status). Si une clé ou un Referer est nécessaire, saisissez-le à l'étape « Accès » puis relisez.")
            }
            if data.count > maxBytes { return .failed("Réponse trop volumineuse (25 Mo au maximum).") }
            do {
                let document = try CapabilitiesReader.read(data)
                return .loaded(url: address, document: document)
            } catch {
                return .failed("Ce n'est pas un document WMTS ou WMS lisible : \(error)")
            }
        } catch {
            return .failed("Le serveur ne répond pas : \(error.localizedDescription)")
        }
    }

    /// Referer, User-Agent, headers and cookie of the draft.
    private static func apply(_ draft: SourceDraft, to request: inout URLRequest) {
        let agent = draft.userAgent.trimmingCharacters(in: .whitespaces)
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        request.setValue(agent.isEmpty ? "ToolMap/\(version) (iOS)" : agent, forHTTPHeaderField: "User-Agent")
        let referer = draft.referer.trimmingCharacters(in: .whitespaces)
        if !referer.isEmpty { request.setValue(referer, forHTTPHeaderField: "Referer") }
        for header in draft.headers {
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }
        let cookie = draft.cookie.trimmingCharacters(in: .whitespaces)
        if !cookie.isEmpty { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
    }

    /// Fetches one tile of `source` the way the map would (same URL building, Referer, headers), with nothing stored,
    /// and says what happened (Android `TileProbe`).
    static func probe(_ source: TileSource, _ tile: PreviewTile) async -> WizardProbeResult {
        guard let level = source.levelFor(tile.z) else {
            return WizardProbeResult(verdict: .outOfRange, tile: tile)
        }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("toolmap-probe", isDirectory: true)
        let service = TileService(directory: folder, userAgent: "ToolMap/\(version) (iOS)")
        service.register([source], builtins: [])
        let leaf = TileRecipe.Leaf(source: source, level: level, tile: TileId(tile.z, tile.x, tile.y))
        guard let request = service.request(leaf) else {
            return WizardProbeResult(verdict: .templateError, tile: tile)
        }
        let shownUrl = hide(request.url?.absoluteString, source)
        let started = Date()
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let elapsed = Int(Date().timeIntervalSince(started) * 1000)
            let http = response as? HTTPURLResponse
            let code = http?.statusCode ?? 0
            var result = WizardProbeResult(verdict: verdict(code, data), tile: tile)
            result.httpCode = code
            result.contentType = http?.value(forHTTPHeaderField: "Content-Type")
            result.sizeBytes = data.count
            result.latencyMillis = elapsed
            result.url = shownUrl
            if result.verdict == .ok {
                result.image = data
            } else if result.verdict == .notAnImage, code == 200 {
                result.detail = "unrecognised image data"
            } else {
                result.detail = hide(excerpt(data), source)
            }
            return result
        } catch {
            let elapsed = Int(Date().timeIntervalSince(started) * 1000)
            let code = (error as NSError).code
            let certificate = [
                NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasBadDate,
                NSURLErrorServerCertificateHasUnknownRoot, NSURLErrorServerCertificateNotYetValid,
            ]
            var verdict = WizardVerdict.network
            if certificate.contains(code) {
                verdict = .certificateRejected
            } else if code == NSURLErrorAppTransportSecurityRequiresSecureConnection {
                verdict = .cleartextBlocked
            }
            var result = WizardProbeResult(verdict: verdict, tile: tile)
            result.latencyMillis = elapsed
            result.url = shownUrl
            result.detail = hide(error.localizedDescription, source)
            return result
        }
    }

    private static func verdict(_ code: Int, _ data: Data) -> WizardVerdict {
        if code == 200, ImageSniffer.formatOf(data) != nil { return .ok }
        if code == 204 || (code == 200 && data.isEmpty) { return .empty }
        if code == 200 { return .notAnImage }
        if code == 401 || code == 403 { return .keyOrReferer }
        if code == 404 { return .notFound }
        if code == 429 { return .rateLimited }
        if code >= 500 { return .serverError }
        return .badRequest
    }

    /// First readable characters of a server's error page, markup removed.
    private static func excerpt(_ data: Data) -> String? {
        let text = String(decoding: data.prefix(2048), as: UTF8.self)
        var plain = ""
        var inTag = false
        for character in text {
            if character == "<" {
                inTag = true
            } else if character == ">" {
                inTag = false
                plain.append(" ")
            } else if !inTag {
                plain.append(character)
            }
        }
        let words = plain.split(whereSeparator: { $0.isWhitespace })
        let joined = words.joined(separator: " ")
        return joined.isEmpty ? nil : String(joined.prefix(200))
    }

    /// The key a user typed must not come back in a message they might copy to a forum.
    private static func hide(_ text: String?, _ source: TileSource) -> String? {
        guard var out = text else { return nil }
        for secret in source.params.values where secret.count >= 4 {
            out = out.replacingOccurrences(of: secret, with: "***")
        }
        return out
    }
}
