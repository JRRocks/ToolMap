import CoreLocation
import Foundation
import Network
import Observation
import ToolMapCore
import UIKit
import UserNotifications

/// What the user can be told went wrong with a code or a group (Android `ShareProblem`).
enum ShareProblem: Equatable {
    case badCode
    case expired
    case clockOff
    case otherGroup
    case notExpected
    /// A pairing code of the direct mode (WebRTC), which the iPhone does not have.
    case directCode
    /// No coordination server: neither one built into the app nor one chosen in the settings.
    case noServer

    var title: String {
        switch self {
        case .noServer: "Serveur de coordination non configuré"
        default: "Code refusé"
        }
    }

    var text: String {
        switch self {
        case .badCode:
            "Ce code n'est pas un code ToolMap, ou il est abîmé (lien coupé par la messagerie, QR code lu à moitié). Recommencez."
        case .expired:
            "Ce code a expiré (10 minutes pour un code direct, 7 jours pour une invitation du serveur). Demandez-en un nouveau."
        case .clockOff:
            "L'heure des deux téléphones diffère trop : vérifiez qu'ils sont réglés sur l'heure automatique."
        case .otherGroup:
            "Ce code vient d'un autre groupe que le vôtre. Quittez votre groupe pour rejoindre celui-là."
        case .notExpected:
            "Ce code n'est pas celui qu'on attend maintenant (une réponse sans invitation en cours, par exemple)."
        case .directCode:
            "Ce code est celui d'une liaison directe entre téléphones, que l'iPhone ne sait pas faire. Demandez une invitation d'un groupe qui passe par un serveur."
        case .noServer:
            "Les groupes passent par un serveur Firebase. Indiquez son adresse et sa clé dans Réglages du partage ▸ Serveur de coordination, ou rejoignez le groupe d'un autre avec son invitation."
        }
    }
}

/// An alert of the group (Android `SosAlert`, P22.2).
struct ShareSos: Identifiable, Equatable {
    let memberId: Int64
    let name: String
    let fix: PeerFix?
    let text: String
    let receivedAtMs: Int64
    /// The user saw it: the dialog does not come back for the repeats, the banner stays while they last.
    var acknowledged: Bool
    /// When the first send of this alert came, for « son alerte de 10:41 » once it is lifted.
    let firstAtMs: Int64

    var id: Int64 { memberId }
}

/// An alert of the group that its author lifted (Android `SosLifted`): shown for a minute, and for as long as its
/// `dialog` waits for « OK ».
struct ShareSosLifted: Identifiable, Equatable {
    let memberId: Int64
    let name: String
    let atMs: Int64
    let alertAtMs: Int64
    var dialog: Bool

    var id: Int64 { memberId }
}

/// A message of the group (Android `ShareMessage`).
struct ShareChatMessage: Identifiable, Equatable {
    let id: Int
    let memberId: Int64
    let name: String
    let text: String
    let timeMs: Int64
    let mine: Bool
}

/// An invitation ready to be shown as a QR code or sent as a link (Android `ShareCode`).
struct ShareInviteCode: Equatable {
    let qrText: String
    let link: String
    let expiresAtSec: Int64
}

/// A member whose sheet is open, for `.sheet(item:)`.
struct ShareMemberRef: Identifiable, Equatable {
    let id: Int64
}

/// Partage de position (Android `ShareController`, SHARE, P13, P22): the group (a `MeshNode` over the room of a
/// `FirebaseRelay`), the persisted settings, the invitation being shown, the position sent at the `ShareCadence`, the
/// members, the messages and the alerts of the group. The direct mode (WebRTC) is not on iPhone: every group lives on a
/// coordination server. State changes happen on the main thread; the relay's threads hop to it (`ShareMeshEvents`).
@Observable
final class ShareController {
    private static let seqMargin: Int64 = 1_000
    private static let seqKeepMs: Int64 = 10_000
    private static let maxMessages = 50
    private static let sosStaleMs: Int64 = 120_000
    private static let liftedShowMs: Int64 = 60_000
    private static let msPerS: Int64 = 1000
    /// The sharing was on when the app last ran: it carries on at the next launch (Android `resumeIfInterrupted`).
    private static let resumeKey = "ToolMapShareOn"

    /// The server built into the app (`ToolMapFirebaseDbUrl`, `ToolMapFirebaseApiKey` of Info.plist, filled by the CI
    /// from its secrets); nil when they are empty.
    static let builtInServer: RelayConfig? = {
        let info = Bundle.main.infoDictionary
        let url = info?["ToolMapFirebaseDbUrl"] as? String ?? ""
        let key = info?["ToolMapFirebaseApiKey"] as? String ?? ""
        let config = RelayConfig.of(url, key)
        return config.isUsable ? config : nil
    }()

    @ObservationIgnored unowned let app: AppModel
    /// `-ToolMapDemo YES`: a group of made-up members, offline, nothing saved.
    let demo: Bool

    private(set) var settings = ShareSettings()
    private(set) var inGroup = false
    /// This device sends its position.
    private(set) var sharing = false
    private(set) var selfId: Int64 = 0
    /// Every member of the group, this device first.
    private(set) var members: [MemberView] = []
    private(set) var onServer = false
    private(set) var server: ServerState = ServerState.none
    private(set) var sos: [ShareSos] = []
    private(set) var sosLifted: [ShareSosLifted] = []
    /// This device's alert is on, from « Envoyer » to « Annuler l'alerte ».
    private(set) var ownSos = false
    private(set) var ownSosAtMs: Int64 = 0
    private(set) var messages: [ShareChatMessage] = []
    private(set) var unread = 0
    var invitation: ShareInviteCode?
    var problem: ShareProblem?
    /// The others removed this device from the group.
    var excluded = false
    /// A short message of the sharing screen (« … est connecté(e). »).
    var toast: String?
    /// The member whose sheet is open over the map.
    var mapMember: ShareMemberRef?

    /// « Aller vers ce membre »: set by the navigation (target of « Aller vers un point »); without it, the map is
    /// centred on the member.
    @ObservationIgnored var goToHandler: ((LonLat, String) -> Void)?

    private let store: ShareStore
    private let authStore: FileAuthStore
    @ObservationIgnored private var group: GroupRecord?
    @ObservationIgnored private var node: MeshNode?
    @ObservationIgnored private var relay: FirebaseRelay?
    @ObservationIgnored private var forwarder: ShareLinkForwarder?
    @ObservationIgnored private var events: ShareMeshEvents?
    @ObservationIgnored private var ticker: Timer?
    @ObservationIgnored private var pathMonitor: NWPathMonitor?
    @ObservationIgnored private var networkName: String?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var lastSent: SentPosition?
    @ObservationIgnored private var lastFix: PeerFix?
    @ObservationIgnored private var lastLocationTime: Date?
    @ObservationIgnored private var keptSeq: Int64 = 0
    @ObservationIgnored private var keptSeqAtMs: Int64 = 0
    @ObservationIgnored private var nextMessageId = 0
    /// What the system notification of each alerting member shows now.
    @ObservationIgnored private var notifiedKind: [Int64: ShareNotifications.Kind] = [:]

    init(app: AppModel) {
        self.app = app
        store = ShareStore(AppFiles.folder("share").appendingPathComponent("share.json"))
        authStore = FileAuthStore(AppFiles.folder("share-auth", backedUp: false).appendingPathComponent("firebase-auth.txt"))
        demo = UserDefaults.standard.bool(forKey: "ToolMapDemo")
        app.tapHandlers[ShareLayers.membersId] = { [weak self] tap in
            self?.mapTapped(tap)
        }
        if demo {
            loadDemo()
        } else {
            load()
        }
        // The map and the sharing carry on once the screen that created this controller is drawn.
        DispatchQueue.main.async { [weak self] in
            self?.started()
        }
    }

    // MARK: - What the screens read

    var me: MemberView? {
        members.first { $0.isSelf }
    }

    var others: [MemberView] {
        members.filter { !$0.isSelf }
    }

    /// The name the others see: the one chosen, else the phone's.
    var displayName: String {
        let trimmed = settings.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? UIDevice.current.name : settings.name
    }

    /// The server a group made now lives on: the user's choice, else the built-in one; nil when there is none.
    var serverForNewGroups: RelayConfig? {
        guard let config = settings.server ?? ShareController.builtInServer, config.isUsable else { return nil }
        return config
    }

    var serverIsBuiltIn: Bool {
        settings.server == nil && serverForNewGroups != nil
    }

    /// `false` when this device cannot reach its coordination server, so it cannot vouch for anyone.
    var serverUp: Bool {
        !onServer || (server != ServerState.unreachable && server != ServerState.refused)
    }

    /// The members linked right now, this device left out.
    var connectedCount: Int {
        others.filter { $0.status == MemberStatus.direct }.count
    }

    /// « Partage actif · 1 connecté(s) » or « Groupe · 4 membres » (you included): the chip of the map and the
    /// subtitle of the screen.
    var hudText: String {
        if sharing {
            return "Partage actif · \(connectedCount) connecté(s)"
        }
        return "Groupe · " + ShareText.members(members.count)
    }

    /// The line « Partage de position » of the position panel (Android L7): « Aucun groupe », « 4 membres » or
    /// « 4 membres · position envoyée ».
    var panelSummary: String {
        guard inGroup, !members.isEmpty else { return "Aucun groupe" }
        let count = ShareText.members(members.count)
        return sharing ? "\(count) · position envoyée" : count
    }

    /// The phone does not let ToolMap read the position: nothing can be sent.
    var locationDenied: Bool {
        let status = app.location.authorization
        return status == .denied || status == .restricted
    }

    // MARK: - Group

    /// Makes a new group on the coordination server; `false` (and « Serveur de coordination non configuré ») when there
    /// is no server to make it on.
    @discardableResult
    func createGroup() -> Bool {
        if group != nil { return true }
        guard let config = serverForNewGroups else {
            problem = .noServer
            return false
        }
        let made = GroupRecord.create(server: config)
        EventLog.event("share.group.create", ("server", true))
        group = made
        save()
        startNode(made)
        excluded = false
        problem = nil
        ShareNotifications.requestPermission()
        refresh()
        return true
    }

    /// Leaves the group: the others are told and the room is left.
    func leaveGroup() {
        EventLog.event("share.group.leave")
        node?.leave()
        discardGroup()
    }

    /// Takes the member out of the group; the group key changes so that it cannot read anything from now on.
    func removeMember(_ memberId: Int64) {
        EventLog.event("share.member.remove", ("member", MeshNode.hex(memberId)))
        relay?.ban(memberId)
        node?.remove(memberId)
        sos.removeAll { $0.memberId == memberId }
        sosLifted.removeAll { $0.memberId == memberId }
        refresh()
    }

    // MARK: - Invitations

    /// Prepares an invitation (a group is made first if there is none): it appears in `invitation`.
    func invite() {
        guard createGroup(), let current = group, let config = current.server else { return }
        let nowSec = EventLog.currentTimeMillis() / ShareController.msPerS
        guard let made = try? RoomInvite(nowSec, current.groupId, current.key, current.selfId, displayName, config) else { return }
        EventLog.event("share.invite", ("server", true))
        invitation = ShareInviteCode(
            qrText: RoomInviteCodec.toQrText(made),
            link: RoomInviteCodec.toLink(made),
            expiresAtSec: made.createdAtSec + RoomInviteCodec.maxAgeSec
        )
    }

    /// A `toolmap://join?d=…` invitation opened from another app (Messages, Mail, the camera).
    func openLink(_ url: URL) {
        app.panel = nil
        app.screen = .share
        handleCode(url.absoluteString)
    }

    /// Something was scanned or pasted: an invitation to a group on a server is joined at once.
    func handleCode(_ text: String) {
        let room: RoomInvite?
        do {
            room = try RoomInviteCodec.parseOrNull(text)
        } catch {
            report(.badCode)
            return
        }
        EventLog.event("share.code", ("room", room != nil), ("chars", text.utf16.count))
        if let room {
            joinRoom(room)
        } else if (try? PairingCodec.parse(text)) != nil {
            report(.directCode)
        } else {
            report(.badCode)
        }
    }

    private func joinRoom(_ invite: RoomInvite) {
        let nowSec = EventLog.currentTimeMillis() / ShareController.msPerS
        if let stale = RoomInviteCodec.staleness(invite, nowSec) {
            report(stale == .expired ? .expired : .clockOff)
            return
        }
        if let existing = group {
            if existing.groupId != invite.groupId {
                report(.otherGroup)
                return
            }
            problem = nil
            notify("\(invite.inviterName) est connecté(e).")
            return
        }
        let joined = GroupRecord(invite.groupId, invite.groupKey, GroupRecord.newMemberId(), server: invite.server)
        EventLog.event(
            "share.group.join",
            ("inviter", MeshNode.hex(invite.inviterId)),
            ("me", MeshNode.hex(joined.selfId))
        )
        group = joined
        save()
        startNode(joined)
        excluded = false
        problem = nil
        notify("\(invite.inviterName) est connecté(e).")
        ShareNotifications.requestPermission()
        refresh()
    }

    private func report(_ found: ShareProblem) {
        EventLog.event("share.problem", ("problem", String(describing: found)))
        problem = found
    }

    // MARK: - Sending

    /// Starts or stops sending this device's position.
    func setSharing(_ on: Bool) {
        EventLog.event("share.sharing", ("on", on), ("was", sharing))
        if !on {
            sharing = false
            node?.setSharing(false)
            remember(false)
            app.location.keepRunningInBackground("share", false)
            refresh()
            return
        }
        if locationDenied {
            notify("Sans l'autorisation de position, impossible de partager la vôtre.")
            return
        }
        guard createGroup(), !sharing else { return }
        app.location.start()
        lastSent = nil
        lastLocationTime = nil
        sharing = true
        node?.setSharing(true)
        remember(true)
        // The position goes on with the screen off, as long as the sharing is on.
        app.location.keepRunningInBackground("share", true)
        ShareNotifications.requestPermission()
        sendLocation()
        refresh()
    }

    private func remember(_ on: Bool) {
        if !demo { UserDefaults.standard.set(on, forKey: ShareController.resumeKey) }
    }

    /// Sends the newest GPS fix when the cadence says it is worth it (called every second).
    private func sendLocation() {
        guard sharing, let mesh = node, let location = app.location.location, location.horizontalAccuracy >= 0 else { return }
        if location.timestamp == lastLocationTime { return }
        lastLocationTime = location.timestamp
        let fix = peerFix(location)
        lastFix = fix
        guard settings.cadence().shouldSend(lastSent, fix) else { return }
        lastSent = SentPosition(fix.timeSec, fix.lat, fix.lon)
        mesh.publish(fix)
    }

    private func peerFix(_ location: CLLocation) -> PeerFix {
        let altitude: Int? = location.verticalAccuracy >= 0 && location.altitude.isFinite ? Int(location.altitude.rounded()) : nil
        let accuracy: Int? = location.horizontalAccuracy.isFinite ? Int(location.horizontalAccuracy.rounded()) : nil
        let speed: Double? = location.speed >= 0 ? location.speed : nil
        let course: Double? = location.course >= 0 ? location.course : nil
        let measured = Int64(location.timestamp.timeIntervalSince1970)
        let time = measured > 0 ? measured : EventLog.currentTimeMillis() / ShareController.msPerS
        return PeerFix(
            location.coordinate.latitude,
            location.coordinate.longitude,
            altM: altitude,
            accuracyM: accuracy,
            speedMs: speed,
            bearingDeg: course,
            timeSec: time,
            battery: batteryPercent()
        )
    }

    private func batteryPercent() -> Int? {
        let device = UIDevice.current
        if !device.isBatteryMonitoringEnabled {
            device.isBatteryMonitoringEnabled = true
        }
        let level = device.batteryLevel
        return level >= 0 ? Int((level * 100).rounded()) : nil
    }

    // MARK: - Messages and alerts

    func sendText(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        if !demo {
            guard let mesh = node else { return }
            EventLog.event("share.text.out", ("chars", clean.utf16.count))
            mesh.sendText(clean)
        }
        let message = ShareChatMessage(
            id: takeMessageId(),
            memberId: selfId,
            name: settings.name,
            text: clean,
            timeMs: EventLog.currentTimeMillis(),
            mine: true
        )
        append(message)
    }

    /// The page of the messages is open: every message received so far is read.
    func markMessagesRead() {
        if unread != 0 { unread = 0 }
    }

    /// Sends an alert with the last known position, repeated for two minutes; it stays on until `cancelSos` (P22.2).
    func startSos(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        var fix = lastFix
        if fix == nil, let location = app.location.location, location.horizontalAccuracy >= 0 {
            fix = peerFix(location)
        }
        EventLog.event("share.sos.start", ("fix", fix != nil))
        node?.startSos(fix, clean)
        ownSos = true
        ownSosAtMs = EventLog.currentTimeMillis()
    }

    /// « Annuler l'alerte »: the others are told it is lifted.
    func cancelSos() {
        EventLog.event("share.sos.cancel")
        node?.cancelSos()
        ownSos = false
    }

    func acknowledgeSos(_ memberId: Int64) {
        guard let index = sos.firstIndex(where: { $0.memberId == memberId }), !sos[index].acknowledged else { return }
        sos[index].acknowledged = true
        notifyAlerts()
    }

    /// « OK » on « Alerte levée »: its banner then goes with the minute.
    func acknowledgeLift(_ memberId: Int64) {
        guard let index = sosLifted.firstIndex(where: { $0.memberId == memberId }), sosLifted[index].dialog else { return }
        sosLifted[index].dialog = false
    }

    // MARK: - Settings

    /// Sets what the others see of this device and how often it sends; the values are cleaned as the saved file reads
    /// them back.
    func updateSettings(_ change: (inout ShareSettings) -> Void) {
        var next = settings
        change(&next)
        next = ShareState.fromJson(ShareState(settings: next, group: nil).toJson()).settings
        guard next != settings else { return }
        settings = next
        save()
        if let current = group {
            relay?.identity = identity(current)
            node?.setSelf(displayName, next.look())
            node?.setPicture(next.pictureBytes())
        }
        refresh()
    }

    // MARK: - Map

    /// « Voir sur la carte »: back to the map, centred on the position.
    func showOnMap(_ fix: PeerFix) {
        mapMember = nil
        app.panel = nil
        app.screen = nil
        app.show(LonLat(lon: fix.lon, lat: fix.lat), zoom: max(app.zoom, 15))
    }

    /// « Aller vers ce membre ».
    func goTo(_ member: MemberView) {
        guard let fix = member.fix else { return }
        let target = LonLat(lon: fix.lon, lat: fix.lat)
        mapMember = nil
        app.panel = nil
        app.screen = nil
        if let goToHandler {
            goToHandler(target, member.name)
        } else {
            app.show(target, zoom: max(app.zoom, 15))
        }
    }

    /// Opens the sheet of a member over the map (a tap on its marker); a panel sheet closes first.
    func openMemberOnMap(_ memberId: Int64) {
        if app.panel != nil {
            app.panel = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { [weak self] in
                self?.mapMember = ShareMemberRef(id: memberId)
            }
        } else {
            mapMember = ShareMemberRef(id: memberId)
        }
    }

    private func mapTapped(_ tap: FeatureTap) {
        guard let text = tap.properties["mid"] as? String, let memberId = Int64(text) else { return }
        openMemberOnMap(memberId)
    }

    /// A short message: on the sharing screen when it shows, else at the bottom of the map.
    func notify(_ text: String) {
        if app.screen == .share {
            toast = text
        } else {
            app.say(text)
        }
    }

    // MARK: - Mesh events, on the main thread (`ShareMeshEvents`)

    func received(text: String, from member: Int64) {
        EventLog.event("share.text.in", ("member", MeshNode.hex(member)), ("chars", text.utf16.count))
        let message = ShareChatMessage(
            id: takeMessageId(),
            memberId: member,
            name: memberName(member),
            text: text,
            timeMs: EventLog.currentTimeMillis(),
            mine: false
        )
        append(message)
        unread += 1
    }

    func receivedSos(_ member: Int64, _ fix: PeerFix?, _ text: String) {
        EventLog.event("share.sos.in", ("member", MeshNode.hex(member)), ("fix", fix != nil))
        let now = EventLog.currentTimeMillis()
        let index = sos.firstIndex { $0.memberId == member }
        let before: ShareSos? = index.map { sos[$0] }
        let alert = ShareSos(
            memberId: member,
            name: memberName(member),
            fix: fix,
            text: text,
            receivedAtMs: now,
            acknowledged: before?.acknowledged ?? false,
            firstAtMs: before?.firstAtMs ?? now
        )
        if let index {
            sos[index] = alert
        } else {
            sos.append(alert)
        }
        sosLifted.removeAll { $0.memberId == member }
        refresh()
    }

    func receivedLift(_ member: Int64) {
        EventLog.event("share.sos.lifted", ("member", MeshNode.hex(member)))
        let now = EventLog.currentTimeMillis()
        let alert = sos.first { $0.memberId == member }
        sos.removeAll { $0.memberId == member }
        let lifted = ShareSosLifted(
            memberId: member,
            name: memberName(member),
            atMs: now,
            alertAtMs: alert?.firstAtMs ?? now,
            dialog: alert.map { !$0.acknowledged } ?? false
        )
        if let index = sosLifted.firstIndex(where: { $0.memberId == member }) {
            sosLifted[index] = lifted
        } else {
            sosLifted.append(lifted)
        }
        refresh()
    }

    func rekeyed(_ key: [UInt8]) {
        EventLog.event("share.rekey")
        guard let current = group else { return }
        group = GroupRecord(current.groupId, key, current.selfId, server: current.server)
        save()
    }

    func wasExcluded() {
        EventLog.event("share.excluded")
        discardGroup()
        excluded = true
    }

    func serverChanged(_ state: ServerState) {
        if relay != nil, state != server { server = state }
    }

    func linkOpened(_ name: String) {
        notify("\(name) est connecté(e).")
    }

    // MARK: - Plumbing

    /// Reads the saved settings and group; a saved group is joined again.
    private func load() {
        let saved = store.read()
        settings = saved.settings
        if let record = saved.group {
            if record.server != nil {
                group = record
                startNode(record)
            } else {
                // A group of the direct mode (WebRTC, restored from an Android backup) cannot be reached from an iPhone.
                save()
            }
        }
        EventLog.event("share.load", ("group", group != nil), ("intervalS", settings.intervalSec))
        updateMembers()
    }

    private func started() {
        if !demo, group != nil, UserDefaults.standard.bool(forKey: ShareController.resumeKey) {
            setSharing(true)
        }
        refresh()
    }

    private func identity(_ record: GroupRecord) -> TransportIdentity {
        TransportIdentity(record.groupId, record.selfId, displayName, settings.look())
    }

    private func save() {
        if demo { return }
        try? store.write(ShareState(settings: settings, group: group))
    }

    /// Opens the room of `record` and the group logic over it.
    private func startNode(_ record: GroupRecord) {
        guard let config = record.server else { return }
        EventLog.event("share.node.start", ("server", true), ("me", MeshNode.hex(record.selfId)))
        let links = ShareLinkForwarder()
        let listener = ShareMeshEvents(owner: self)
        let room = FirebaseRelay(config, record.groupId, links, authStore)
        room.identity = identity(record)
        let floor = store.readSeq().map { $0 + ShareController.seqMargin } ?? 0
        guard let mesh = try? MeshNode(
            record.groupId,
            record.key,
            record.selfId,
            displayName,
            settings.look(),
            room,
            listener,
            seqFloor: floor
        ) else {
            room.close()
            return
        }
        room.onState = { [weak listener] state in
            listener?.serverChanged(state)
        }
        mesh.setPicture(settings.pictureBytes())
        // The others see from the first link whether this device shares or only listens (P22.3).
        mesh.setSharing(sharing)
        links.attach(mesh, listener)
        forwarder = links
        events = listener
        relay = room
        node = mesh
        inGroup = true
        selfId = record.selfId
        onServer = true
        server = room.state
        startWatching()
        room.start()
        startTicker()
    }

    /// Forgets the group (after leaving or being removed). The links forwarder lets go of the node first, so that the
    /// closing room cannot reach it: nothing keeps the node, the room or this controller alive.
    private func discardGroup() {
        EventLog.event("share.group.discard")
        ticker?.invalidate()
        ticker = nil
        stopWatching()
        forwarder?.attach(nil, nil)
        forwarder = nil
        relay?.onState = nil
        relay?.close()
        relay = nil
        node = nil
        events = nil
        group = nil
        lastSent = nil
        lastFix = nil
        lastLocationTime = nil
        for memberId in notifiedKind.keys {
            ShareNotifications.remove(memberId)
        }
        notifiedKind = [:]
        sos = []
        sosLifted = []
        messages = []
        unread = 0
        ownSos = false
        invitation = nil
        sharing = false
        inGroup = false
        selfId = 0
        members = []
        onServer = false
        server = ServerState.none
        mapMember = nil
        remember(false)
        app.location.keepRunningInBackground("share", false)
        save()
        publishMap()
    }

    private func startTicker() {
        ticker?.invalidate()
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            self?.tick()
        }
        RunLoop.main.add(timer, forMode: .common)
        ticker = timer
    }

    /// About once a second: the group logic's clock, the position to send, the alerts that go stale.
    private func tick() {
        guard let mesh = node else { return }
        mesh.tick()
        sendLocation()
        keepSeq(mesh)
        let now = EventLog.currentTimeMillis()
        // An alert not seen yet stays (P22.2); a seen one goes two minutes after its last repeat.
        let keptSos = sos.filter { !($0.acknowledged && now - $0.receivedAtMs > ShareController.sosStaleMs) }
        if keptSos != sos { sos = keptSos }
        let keptLifted = sosLifted.filter { $0.dialog || now - $0.atMs <= ShareController.liftedShowMs }
        if keptLifted != sosLifted { sosLifted = keptLifted }
        if let code = invitation, code.expiresAtSec <= now / ShareController.msPerS {
            invitation = nil
        }
        refresh()
    }

    /// Keeps the last message number at most every 10 s, so that the next launch starts above it (P21.5 m).
    private func keepSeq(_ mesh: MeshNode) {
        let seq = mesh.lastSeq
        let now = EventLog.currentTimeMillis()
        if seq == keptSeq || (now >= keptSeqAtMs && now - keptSeqAtMs < ShareController.seqKeepMs) { return }
        keptSeq = seq
        keptSeqAtMs = now
        store.writeSeq(seq)
    }

    /// The network and the clock of the phone, told to the room (P21.5 f, h, p, n), and the return to the screen.
    private func startWatching() {
        stopWatching()
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            self?.pathChanged(path)
        }
        monitor.start(queue: DispatchQueue.main)
        pathMonitor = monitor
        let center = NotificationCenter.default
        let clock = center.addObserver(forName: .NSSystemClockDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.relay?.onClockChanged()
        }
        let foreground = center.addObserver(
            forName: Notification.Name("UIApplicationWillEnterForegroundNotification"),
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.relay?.reconnectNow()
        }
        observers = [clock, foreground]
    }

    private func stopWatching() {
        pathMonitor?.cancel()
        pathMonitor = nil
        for observer in observers {
            NotificationCenter.default.removeObserver(observer)
        }
        observers = []
        networkName = nil
    }

    private func pathChanged(_ path: NWPath) {
        guard let room = relay else { return }
        if path.status == .satisfied {
            let names = path.availableInterfaces.map { $0.name }.joined(separator: ",")
            let name = names.isEmpty ? "default" : names
            let changed = name != networkName
            networkName = name
            room.onNetwork(name)
            if changed { room.reconnectNow() }
        } else {
            networkName = nil
            room.onOffline()
        }
    }

    /// Reads the members again and redraws them on the map.
    func refresh() {
        updateMembers()
        publishMap()
        notifyAlerts()
    }

    private func updateMembers() {
        if demo { return }
        let list = ShareController.sorted(node?.members() ?? [])
        if list != members { members = list }
        let state = relay?.state ?? ServerState.none
        if state != server { server = state }
    }

    /// This device first, then by name.
    private static func sorted(_ list: [MemberView]) -> [MemberView] {
        list.sorted { a, b in
            if a.isSelf != b.isSelf { return a.isSelf }
            return a.name.lowercased() < b.name.lowercased()
        }
    }

    /// The members and their trails as GeoJSON for `ShareLayers`, with the marker images they need.
    private func publishMap() {
        guard inGroup else {
            setFeature(ShareLayers.trailsId, nil)
            setFeature(ShareLayers.membersId, nil)
            setFeature(ShareLayers.labelsId, nil)
            return
        }
        let nowMs = EventLog.currentTimeMillis()
        let alerting = Set(sos.map { $0.memberId })
        // A member that only listens has no marker, unless its alert brought a position (P22.3).
        for m in members where !m.isSelf && m.fix != nil && (m.sharing != false || alerting.contains(m.id)) {
            let key = MemberLook.imageKey(m.color, m.name, m.picture)
            ShareMarkerImages.shared.ensure(key, look: m.color, name: m.name, picture: m.picture)
        }
        let membersJson = ShareGeoJson.members(members, alerting, nowMs)
        let trailsJson = settings.showTrails ? ShareGeoJson.trails(members) : FeatureLayerSet.empty
        let labelsJson = app.settings.placemarks.labels ? membersJson : FeatureLayerSet.empty
        setFeature(ShareLayers.trailsId, trailsJson)
        setFeature(ShareLayers.membersId, membersJson)
        setFeature(ShareLayers.labelsId, labelsJson)
    }

    private func setFeature(_ id: String, _ text: String?) {
        if app.mapFeatures[id] != text {
            app.mapFeatures[id] = text
        }
    }

    /// A system notification for each alert not yet seen, taken back once it is; an alert lifted while notified is
    /// replaced by « Alerte levée par … », without sound (Android `notifySos`).
    private func notifyAlerts() {
        if demo { return }
        let pending = sos.filter { !$0.acknowledged }
        for alert in pending where notifiedKind[alert.memberId] != .alert {
            ShareNotifications.alert(alert)
            notifiedKind[alert.memberId] = .alert
        }
        let still = Set(pending.map { $0.memberId })
        for (memberId, kind) in notifiedKind where !still.contains(memberId) {
            let lifted = sosLifted.first { $0.memberId == memberId }
            let next: ShareNotifications.Kind = lifted == nil ? .removed : .lifted
            if next != kind {
                if let lifted {
                    ShareNotifications.lifted(lifted)
                } else {
                    ShareNotifications.remove(memberId)
                }
                notifiedKind[memberId] = next
            }
            if next == .removed, !sos.contains(where: { $0.memberId == memberId }) {
                notifiedKind[memberId] = nil
            }
        }
    }

    private func memberName(_ memberId: Int64) -> String {
        node?.members().first(where: { $0.id == memberId })?.name ?? "?"
    }

    private func takeMessageId() -> Int {
        nextMessageId += 1
        return nextMessageId
    }

    private func append(_ message: ShareChatMessage) {
        var list = messages
        list.append(message)
        if list.count > ShareController.maxMessages {
            list.removeFirst(list.count - ShareController.maxMessages)
        }
        messages = list
    }

    // MARK: - Demonstration (`-ToolMapDemo YES`)

    /// A group of four made-up members around the centre of the map, with messages and an alert already seen; no
    /// connection is made and nothing is saved.
    private func loadDemo() {
        let now = EventLog.currentTimeMillis()
        let nowS = now / ShareController.msPerS
        let center = app.center
        settings = ShareSettings(name: "iPhone", colorIndex: 5)
        let made = GroupRecord.create(server: RelayConfig("https://toolmap-demo.invalid", "demo-key"))
        group = made
        selfId = made.selfId
        let pixel: Int64 = 0x1111_2222_3333
        let galaxy: Int64 = 0x2222_3333_4444
        let older: Int64 = 0x3333_4444_5555
        let listening: Int64 = 0x4444_5555_6666
        let orange = Int32(bitPattern: 0xFFF5_7C00)
        let blue = Int32(bitPattern: 0xFF19_76D2)
        let green = Int32(bitPattern: 0xFF38_8E3C)
        let grey = Int32(bitPattern: 0xFF45_5A64)
        let selfFix = ShareController.demoFix(center, 0, 0, nowS - 5, 1760, 1.2, 88)
        let pixelFix = ShareController.demoFix(center, 0.0042, 0.0061, nowS - 20, 1874, 1.1, 76)
        let galaxyFix = ShareController.demoFix(center, -0.0051, 0.0033, nowS - 45, 1702, 0, 41)
        let olderFix = ShareController.demoFix(center, 0.0024, -0.0072, nowS - 900, 1655, 0.8, 18)
        var list: [MemberView] = []
        list.append(MemberView(made.selfId, "iPhone", settings.look(), .direct, true, now, selfFix, [], sharing: true))
        list.append(MemberView(
            pixel, "Pixel 7", MemberLook.pack(3, orange), .direct, false, now - 5_000, pixelFix,
            ShareController.demoTrail(pixelFix, -0.0005, -0.0004), sharing: true
        ))
        list.append(MemberView(
            galaxy, "Galaxy S23", MemberLook.pack(2, blue), .direct, false, now - 15_000, galaxyFix,
            ShareController.demoTrail(galaxyFix, 0.0004, -0.0003), sharing: true
        ))
        list.append(MemberView(
            older, "iPhone 13", MemberLook.pack(0, green), .lost, false, now - 900_000, olderFix,
            ShareController.demoTrail(olderFix, -0.0003, 0.0005), sharing: true
        ))
        list.append(MemberView(listening, "Moto G", MemberLook.pack(0, grey), .direct, false, now - 8_000, nil, [], sharing: false))
        members = ShareController.sorted(list)
        inGroup = true
        sharing = true
        onServer = true
        server = .online
        messages = [
            ShareChatMessage(id: 1, memberId: pixel, name: "Pixel 7", text: "On fait la pause au col ?", timeMs: now - 600_000, mine: false),
            ShareChatMessage(id: 2, memberId: made.selfId, name: "iPhone", text: "Oui, dans 20 minutes", timeMs: now - 540_000, mine: true),
            ShareChatMessage(id: 3, memberId: galaxy, name: "Galaxy S23", text: "Je vous attends au refuge", timeMs: now - 120_000, mine: false),
        ]
        nextMessageId = 3
        unread = 1
        sos = [
            ShareSos(
                memberId: galaxy,
                name: "Galaxy S23",
                fix: galaxyFix,
                text: "Cheville tordue, j'attends ici",
                receivedAtMs: now - 30_000,
                acknowledged: true,
                firstAtMs: now - 30_000
            ),
        ]
    }

    private static func demoFix(
        _ center: LonLat,
        _ dLat: Double,
        _ dLon: Double,
        _ timeSec: Int64,
        _ altitude: Int,
        _ speed: Double,
        _ battery: Int
    ) -> PeerFix {
        PeerFix(
            center.lat + dLat,
            center.lon + dLon,
            altM: altitude,
            accuracyM: 4,
            speedMs: speed,
            timeSec: timeSec,
            battery: battery
        )
    }

    /// Eight positions a minute apart leading to `end`.
    private static func demoTrail(_ end: PeerFix, _ stepLat: Double, _ stepLon: Double) -> [PeerFix] {
        var points: [PeerFix] = []
        for i in stride(from: 8, through: 1, by: -1) {
            let k = Double(i)
            points.append(PeerFix(end.lat + stepLat * k, end.lon + stepLon * k, timeSec: end.timeSec - Int64(i) * 60))
        }
        points.append(end)
        return points
    }
}

/// The group logic's listener: called from the relay's threads and from the main one, it hands every event to the
/// controller on the main thread. It keeps the controller weakly; the node keeps it.
final class ShareMeshEvents: MeshListener, @unchecked Sendable {
    private weak var owner: ShareController?
    private let lock = NSLock()
    private var refreshQueued = false

    init(owner: ShareController) {
        self.owner = owner
    }

    private func onMain(_ body: @escaping (ShareController) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let target = self?.owner else { return }
            body(target)
        }
    }

    /// Many changes come in a burst: one refresh for them all.
    func onChanged() {
        lock.lock()
        let queued = refreshQueued
        refreshQueued = true
        lock.unlock()
        if queued { return }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.refreshQueued = false
            self.lock.unlock()
            self.owner?.refresh()
        }
    }

    func onText(_ member: Int64, _ text: String) {
        onMain { $0.received(text: text, from: member) }
    }

    func onSos(_ member: Int64, _ fix: PeerFix?, _ text: String) {
        onMain { $0.receivedSos(member, fix, text) }
    }

    func onSosLifted(_ member: Int64) {
        onMain { $0.receivedLift(member) }
    }

    func onRekeyed(_ key: [UInt8]) {
        onMain { $0.rekeyed(key) }
    }

    func onExcluded() {
        onMain { $0.wasExcluded() }
    }

    func serverChanged(_ state: ServerState) {
        onMain { $0.serverChanged(state) }
    }

    func linkOpened(_ name: String) {
        onMain { $0.linkOpened(name) }
    }
}

/// What the room tells about its links, passed to the node (Android's `links` listener). It keeps the node weakly and
/// lets go of it when the group is left: the room (which keeps this forwarder) and the node (which keeps the room)
/// then form no cycle.
final class ShareLinkForwarder: LinkListener, @unchecked Sendable {
    private let lock = NSLock()
    private weak var node: MeshNode?
    private weak var events: ShareMeshEvents?

    func attach(_ node: MeshNode?, _ events: ShareMeshEvents?) {
        lock.lock()
        self.node = node
        self.events = events
        lock.unlock()
    }

    private func current() -> (MeshNode?, ShareMeshEvents?) {
        lock.lock()
        defer { lock.unlock() }
        return (node, events)
    }

    func onLinkOpen(_ peer: Int64, _ name: String, _ color: Int32, _ link: any MeshLink) {
        let pair = current()
        guard let target = pair.0 else {
            link.close()
            return
        }
        target.onLinkOpen(peer, name, color, link)
        pair.1?.linkOpened(name)
    }

    func onLinkClosed(_ peer: Int64) {
        current().0?.onLinkClosed(peer)
    }

    func onFrame(_ peer: Int64, _ bytes: [UInt8]) {
        current().0?.onFrame(peer, bytes)
    }
}

/// The system notifications of the alerts of the group (Android channel « Alertes du groupe »).
enum ShareNotifications {
    enum Kind: Equatable {
        case alert
        case lifted
        case removed
    }

    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private static func identifier(_ memberId: Int64) -> String {
        "toolmap.share.alert." + MeshNode.hex(memberId)
    }

    static func alert(_ sos: ShareSos) {
        let content = UNMutableNotificationContent()
        content.title = "Alerte de \(sos.name)"
        content.body = sos.text
        content.sound = .default
        post(content, sos.memberId)
    }

    static func lifted(_ lifted: ShareSosLifted) {
        let content = UNMutableNotificationContent()
        content.title = "Alerte levée par \(lifted.name)"
        content.body = "\(lifted.name) a annulé son alerte de \(ShareText.clock(lifted.alertAtMs))."
        post(content, lifted.memberId)
    }

    static func remove(_ memberId: Int64) {
        let id = identifier(memberId)
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.removePendingNotificationRequests(withIdentifiers: [id])
    }

    private static func post(_ content: UNMutableNotificationContent, _ memberId: Int64) {
        let request = UNNotificationRequest(identifier: identifier(memberId), content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}
