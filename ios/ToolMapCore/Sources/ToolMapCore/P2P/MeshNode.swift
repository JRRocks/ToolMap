// Port of core/src/main/kotlin/fr/toolmap/core/p2p/MeshNode.kt: the protocol of a group, independent of any network.

import Foundation

/// One open connection to a neighbour; the server room or WebRTC in the app, a simulated one in the tests.
public protocol MeshLink: AnyObject {
    /// Sends `frame` on the unordered channel when `onPositionChannel`, else on the reliable one.
    func send(_ frame: [UInt8], _ onPositionChannel: Bool) -> Bool

    func close()

    /// `true` when everything sent on this link is heard by every member at once (a shared server room): the node then
    /// never passes on what it received, since the author already reached everyone directly.
    var isBroadcastMedium: Bool { get }
}

extension MeshLink {
    public var isBroadcastMedium: Bool { false }
}

/// What the node needs to open connections; the offers and answers travel through the node's own SIGNAL messages.
public protocol MeshTransport: AnyObject {
    /// Prepares a link towards `peer` and hands over the offer to relay; `nil` when it could not be made.
    func offer(_ peer: Int64, _ done: @escaping @Sendable (PairingPayload?) -> Void)

    /// Accepts a relayed `offer` and hands over the answer to send back; `nil` when it failed.
    func answer(_ offer: PairingPayload, _ done: @escaping @Sendable (PairingPayload?) -> Void)

    /// Applies the `answer` to the link prepared by `offer`.
    func accept(_ answer: PairingPayload)

    /// Gives up the link being negotiated with `peer`, or closes it.
    func drop(_ peer: Int64)
}

public protocol MeshListener: AnyObject {
    /// The member table or a member's state changed.
    func onChanged()

    func onPosition(_ member: Int64, _ fix: PeerFix)

    func onText(_ member: Int64, _ text: String)

    func onSos(_ member: Int64, _ fix: PeerFix?, _ text: String)

    /// `member` lifted the alert last given to `onSos` (P22.2): called once per alert, however often it is repeated.
    func onSosLifted(_ member: Int64)

    /// The group key changed: persist it.
    func onRekeyed(_ key: [UInt8])

    /// The others removed this device from the group.
    func onExcluded()

    /// A payload of the debug build's test tools (`Msg.Diag`) came from `member`.
    func onDiag(_ member: Int64, _ body: [UInt8])
}

extension MeshListener {
    public func onChanged() {}

    public func onPosition(_ member: Int64, _ fix: PeerFix) {}

    public func onText(_ member: Int64, _ text: String) {}

    public func onSos(_ member: Int64, _ fix: PeerFix?, _ text: String) {}

    public func onSosLifted(_ member: Int64) {}

    public func onRekeyed(_ key: [UInt8]) {}

    public func onExcluded() {}

    public func onDiag(_ member: Int64, _ body: [UInt8]) {}
}

public enum MemberStatus: CaseIterable, Sendable, CustomStringConvertible {
    /// A link is open to it.
    case direct
    /// No link, but its messages still reach us through other members.
    case relayed
    /// Nothing heard for a while.
    case lost
    /// It said goodbye.
    case left

    public var description: String {
        switch self {
        case .direct: "DIRECT"
        case .relayed: "RELAYED"
        case .lost: "LOST"
        case .left: "LEFT"
        }
    }
}

public struct MemberView: Equatable, Hashable, Sendable {
    public let id: Int64
    public let name: String
    /// The packed icon and colour (`MemberLook`).
    public let color: Int32
    public let status: MemberStatus
    public let isSelf: Bool
    /// Epoch millis of the last time anything was heard from or about it.
    public let lastHeardMs: Int64
    public let fix: PeerFix?
    public let trail: [PeerFix]
    /// The member's own picture (a small JPEG), once received; `nil` when it has none.
    public let picture: [UInt8]?
    /// Whether it shares its position (`true`) or only listens to the group (`false`, P22.3); `nil` until it said so,
    /// as a version without the STATE message never does.
    public let sharing: Bool?

    public init(
        _ id: Int64,
        _ name: String,
        _ color: Int32,
        _ status: MemberStatus,
        _ isSelf: Bool,
        _ lastHeardMs: Int64,
        _ fix: PeerFix?,
        _ trail: [PeerFix],
        picture: [UInt8]? = nil,
        sharing: Bool? = nil
    ) {
        self.id = id
        self.name = name
        self.color = color
        self.status = status
        self.isSelf = isSelf
        self.lastHeardMs = lastHeardMs
        self.fix = fix
        self.trail = trail
        self.picture = picture
        self.sharing = sharing
    }

    /// The opaque colour of the member.
    public var argb: Int32 { MemberLook.argb(color) }

    /// The icon id of the member (`MemberLook.icons`).
    public var icon: Int { MemberLook.icon(color) }
}

/// The protocol of a group, independent of any network: who is in the group, which links are open, what to relay, when
/// to open the missing links and when a member counts as lost. Calls may come from any thread; the listener is called
/// outside the node's lock (a recursive lock, as Kotlin's `synchronized`, since a transport may answer at once).
///
/// Rules (`docs/specs/p2p-sharing.md` §4, §5): every member should end up linked to every other; of two members the
/// one with the smaller id makes the offer; offers and answers are relayed as SIGNAL messages along the links that
/// exist; positions, texts and SOS are flooded with a time to live and are never handled twice. An alert and its lift
/// are repeated for `sosRepeatForS` seconds (P22.2); of the two, the one with the higher number wins.
public final class MeshNode: @unchecked Sendable {
    public static let pingEveryS: Int64 = 15
    public static let lostAfterS: Int64 = 45
    public static let ensureEveryS: Int64 = 20
    public static let negotiationTimeoutS: Int64 = 30
    public static let sosRepeatS: Int64 = 15
    /// How long an alert, and then its lift, is repeated (P22.2): two minutes.
    public static let sosRepeatForS: Int64 = 120
    /// Sends of an alert or of its lift: the first one, then one every `sosRepeatS` for `sosRepeatForS`.
    public static let sosSends: Int64 = 1 + sosRepeatForS / sosRepeatS
    private static let msPerS: Int64 = 1000
    private static let seenCap = 4096
    private static let maxTrail = 200
    private static let maxMissed = 60
    private static let maxBackoffShift: Int64 = 4

    /// A member id as the event log and the server write it.
    public static func hex(_ id: Int64) -> String { P2PHex.long(id) }

    private final class Member {
        let id: Int64
        var name: String
        var color: Int32
        var lastHeardMs: Int64 = 0
        var fix: PeerFix?
        var left = false
        var trail: [PeerFix] = []
        var picture: [UInt8]?
        var sharing: Bool?
        var stateSeq: Int64 = -1

        init(_ id: Int64, _ name: String, _ color: Int32) {
            self.id = id
            self.name = name
            self.color = color
        }
    }

    private struct SeenKey: Hashable {
        let member: Int64
        let seq: Int64
    }

    public let groupId: [UInt8]
    public let selfId: Int64
    private let transport: any MeshTransport
    private let listener: any MeshListener
    private let clock: @Sendable () -> Int64
    private let random: any SecureRandomSource
    private let crypto: GroupCrypto
    private let lock = NSRecursiveLock()

    /// The members in the order they became known (Kotlin `LinkedHashMap`).
    private var memberTable: [Int64: Member] = [:]
    private var memberOrder: [Int64] = []
    private var links: [Int64: any MeshLink] = [:]
    private var lastFrameMs: [Int64: Int64] = [:]
    private var negotiating: [Int64: Int64] = [:]
    private var retryAt: [Int64: Int64] = [:]
    private var attempts: [Int64: Int] = [:]
    private var removed: Set<Int64> = []
    private var seen: Set<SeenKey> = []
    private var seenOrder: [SeenKey] = []
    private var seenStart = 0
    private var missed: [PeerFix] = []
    private var events: [() -> Void] = []
    private var seq: Int64
    private var lastPingMs: Int64 = 0
    private var lastEnsureMs: Int64 = 0

    /// This device's alert, on until `cancelSos` even once its repeats are over.
    private var sos: (fix: PeerFix?, text: String)?
    private var sosSendsLeft: Int64 = 0
    private var lastSosMs: Int64 = 0

    /// Sends of the lift of this device's alert still to make.
    private var liftSendsLeft: Int64 = 0
    private var lastLiftMs: Int64 = 0

    /// The highest numbers of the alerts and lifts heard of each member: a frame overtaken by the other is over.
    private var alertSeq: [Int64: Int64] = [:]
    private var liftSeq: [Int64: Int64] = [:]

    /// The members whose alert was handed to the listener and not lifted since.
    private var alerting: Set<Int64> = []
    private var closed = false

    private let me: Member

    /// The sequence number of the last AVATAR this node sent, sent again unchanged to each new link.
    private var pictureSeq: Int64 = 0

    /// The sequence number of the last STATE this node sent, sent again unchanged to each new link (P22.3).
    private var stateSeq: Int64 = 0

    /// - Parameter seqFloor: the lowest message number to start from: a new process must not reuse the numbers of the
    ///   previous one, which the others still remember and would drop as duplicates (P21.5 m). `0` starts from the
    ///   clock alone.
    public init(
        _ groupId: [UInt8],
        _ groupKey: [UInt8],
        _ selfId: Int64,
        _ selfName: String,
        _ selfColor: Int32,
        _ transport: any MeshTransport,
        _ listener: any MeshListener,
        clock: @escaping @Sendable () -> Int64 = { EventLog.currentTimeMillis() },
        random: any SecureRandomSource = SystemSecureRandom(),
        seqFloor: Int64 = 0
    ) throws {
        self.groupId = groupId
        self.selfId = selfId
        self.transport = transport
        self.listener = listener
        self.clock = clock
        self.random = random
        crypto = try GroupCrypto(groupKey, random: random)
        seq = Swift.max(clock() / MeshNode.msPerS, seqFloor)
        me = Member(selfId, selfName, selfColor)
        memberTable[selfId] = me
        memberOrder.append(selfId)
    }

    /// The last message number used, kept by the caller so that the next process starts above it (P21.5 m).
    public var lastSeq: Int64 {
        lock.lock()
        defer { lock.unlock() }
        return seq
    }

    /// `true` once this node has left the group or been removed from it.
    public var isClosed: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closed
    }

    /// Runs `block` under the lock, then the listener calls it queued, outside the lock.
    private func locked(_ block: () -> Void) {
        lock.lock()
        block()
        let toRun = events
        events.removeAll()
        lock.unlock()
        for e in toRun {
            e()
        }
    }

    private func now() -> Int64 { clock() }

    // MARK: - links

    /// A link to `peer` (known by `name` and `color` from its pairing code) is open.
    public func onLinkOpen(_ peer: Int64, _ name: String, _ color: Int32, _ link: any MeshLink) {
        locked {
            if closed || peer == selfId {
                link.close()
                return
            }
            if let old = links.removeValue(forKey: peer), old !== link { old.close() }
            links[peer] = link
            EventLog.event("mesh.link.open", ("peer", MeshNode.hex(peer)), ("name", name), ("links", links.count))
            negotiating[peer] = nil
            attempts[peer] = nil
            retryAt[peer] = nil
            removed.remove(peer)
            let m = memberOrPut(peer, name, color)
            m.name = name
            m.color = color
            m.left = false
            m.lastHeardMs = now()
            lastFrameMs[peer] = now()
            sendTo(peer, .hello(Msg.Hello(selfId, me.name, me.color)))
            if let picture = me.picture { sendTo(peer, .avatar(Msg.Avatar(selfId, pictureSeq, picture))) }
            if let sharing = me.sharing { sendTo(peer, .state(Msg.State(selfId, stateSeq, sharing: sharing))) }
            sendTo(peer, .peers(peersMessage()))
            if !missed.isEmpty { sendTo(peer, .trail(Msg.Trail(selfId, missed))) }
            broadcast(.peers(peersMessage()), except: peer)
            changed()
        }
    }

    public func onLinkClosed(_ peer: Int64) {
        locked {
            if links.removeValue(forKey: peer) != nil {
                EventLog.event("mesh.link.closed", ("peer", MeshNode.hex(peer)), ("links", links.count))
                changed()
            }
            lastFrameMs[peer] = nil
            negotiating[peer] = nil
        }
    }

    /// A frame came in from the neighbour `peer`. Frames that do not decrypt or parse are dropped silently.
    public func onFrame(_ peer: Int64, _ bytes: [UInt8]) {
        locked {
            if closed { return }
            let frame: Frame
            do {
                frame = try Wire.decode(bytes, crypto)
            } catch {
                EventLog.event(
                    "mesh.frame.bad",
                    ("peer", MeshNode.hex(peer)),
                    ("type", Wire.typeOf(bytes)),
                    ("bytes", bytes.count),
                    ("fid", Wire.frameId(bytes)),
                    ("why", (error as? WireError)?.message ?? String(describing: error))
                )
                return
            }
            EventLog.detail(
                "mesh.frame.in",
                ("peer", MeshNode.hex(peer)),
                ("type", frame.msg.type),
                ("ttl", frame.ttl),
                ("bytes", bytes.count),
                ("fid", Wire.frameId(bytes))
            )
            // A removed member can still have frames in flight, sealed with the key it knew: they count for nothing.
            if let author = authorOf(frame.msg), removed.contains(author) { return }
            lastFrameMs[peer] = now()
            if let m = memberTable[peer] {
                m.lastHeardMs = now()
                m.left = false
            }
            handle(peer, frame, bytes)
        }
    }

    private func handle(_ from: Int64, _ frame: Frame, _ raw: [UInt8]) {
        switch frame.msg {
        case let .hello(msg):
            if msg.memberId != from { return }
            if let m = memberTable[from] {
                m.name = msg.name
                m.color = msg.color
            }
            changed()
        case let .peers(msg):
            onPeers(msg)
        case let .signal(msg):
            onSignal(from, msg, frame, raw)
        case let .pos(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            EventLog.event(
                "mesh.pos.in",
                ("member", MeshNode.hex(msg.memberId)),
                ("via", MeshNode.hex(from)),
                ("seq", msg.seq),
                ("fid", Wire.frameId(raw)),
                ("fixT", msg.fix.timeSec),
                ("ageS", now() / MeshNode.msPerS - msg.fix.timeSec),
                ("acc", msg.fix.accuracyM),
                ("bat", msg.fix.battery)
            )
            recordFix(msg.memberId, msg.fix)
            let listener = self.listener
            events.append { listener.onPosition(msg.memberId, msg.fix) }
            relay(raw, from)
        case let .trail(msg):
            EventLog.event("mesh.trail.in", ("member", MeshNode.hex(msg.memberId)), ("fixes", msg.fixes.count))
            guard let m = memberTable[msg.memberId] else { return }
            m.trail.append(contentsOf: msg.fixes)
            if m.trail.count > MeshNode.maxTrail { m.trail.removeFirst(m.trail.count - MeshNode.maxTrail) }
            // The newest fix of a trail is where the member is now when no POS brought a newer one, as for the
            // start position taken before the first link opened (P21.5 l).
            if let newest = msg.fixes.max(by: { $0.timeSec < $1.timeSec }), newest.timeSec > (m.fix?.timeSec ?? Int64.min) {
                m.fix = newest
                let listener = self.listener
                events.append { listener.onPosition(msg.memberId, newest) }
            }
            changed()
        case .ping:
            break
        case let .text(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            EventLog.event(
                "mesh.text.in",
                ("member", MeshNode.hex(msg.memberId)),
                ("seq", msg.seq),
                ("chars", msg.text.utf16.count)
            )
            touch(msg.memberId)
            let listener = self.listener
            events.append { listener.onText(msg.memberId, msg.text) }
            relay(raw, from)
        case let .sos(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            let lifted = msg.seq < (liftSeq[msg.memberId] ?? -1)
            EventLog.event(
                "mesh.sos.in",
                ("member", MeshNode.hex(msg.memberId)),
                ("seq", msg.seq),
                ("fix", msg.fix != nil),
                ("lifted", lifted)
            )
            touch(msg.memberId)
            relay(raw, from)
            // A repeat that a lift of the same member overtook on the way (P22.2).
            if lifted { return }
            if let fix = msg.fix { recordFix(msg.memberId, fix) }
            alertSeq[msg.memberId] = Swift.max(msg.seq, alertSeq[msg.memberId] ?? 0)
            alerting.insert(msg.memberId)
            let listener = self.listener
            events.append { listener.onSos(msg.memberId, msg.fix, msg.text) }
        case let .sosEnd(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            // A lift older than an alert already heard belongs to a previous alert.
            let stale = msg.seq < (alertSeq[msg.memberId] ?? -1)
            EventLog.event(
                "mesh.sos.end.in",
                ("member", MeshNode.hex(msg.memberId)),
                ("seq", msg.seq),
                ("stale", stale)
            )
            touch(msg.memberId)
            relay(raw, from)
            if stale { return }
            liftSeq[msg.memberId] = Swift.max(msg.seq, liftSeq[msg.memberId] ?? 0)
            if alerting.remove(msg.memberId) != nil {
                let listener = self.listener
                events.append { listener.onSosLifted(msg.memberId) }
            }
        case let .state(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            EventLog.event(
                "mesh.state.in",
                ("member", MeshNode.hex(msg.memberId)),
                ("seq", msg.seq),
                ("sharing", msg.sharing)
            )
            relay(raw, from)
            guard let m = memberOrNew(msg.memberId) else { return }
            m.lastHeardMs = now()
            m.left = false
            if msg.seq > m.stateSeq {
                m.stateSeq = msg.seq
                m.sharing = msg.sharing
            }
            changed()
        case let .rekey(msg):
            onRekey(from, msg, raw)
        case let .avatar(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            if let m = memberTable[msg.memberId] {
                m.picture = msg.image.isEmpty ? nil : msg.image
                changed()
            }
            relay(raw, from)
        case let .kick(msg):
            EventLog.event("mesh.kick.in", ("target", MeshNode.hex(msg.target)), ("me", msg.target == selfId))
            if msg.target == selfId {
                close(sayBye: false)
                let listener = self.listener
                events.append { listener.onExcluded() }
            }
        case let .diag(msg):
            if msg.memberId == selfId || !firstTime(msg.memberId, msg.seq) { return }
            let listener = self.listener
            events.append { listener.onDiag(msg.memberId, msg.body) }
            relay(raw, from)
        case let .bye(msg):
            EventLog.event("mesh.bye.in", ("member", MeshNode.hex(msg.memberId)), ("via", MeshNode.hex(from)))
            memberTable[msg.memberId]?.left = true
            if msg.memberId == from {
                links.removeValue(forKey: from)?.close()
                transport.drop(from)
            }
            changed()
        }
    }

    private func authorOf(_ msg: Msg) -> Int64? {
        switch msg {
        case let .hello(m): m.memberId
        case let .signal(m): m.from
        case let .pos(m): m.memberId
        case let .trail(m): m.memberId
        case let .ping(m): m.memberId
        case let .text(m): m.memberId
        case let .sos(m): m.memberId
        case let .sosEnd(m): m.memberId
        case let .state(m): m.memberId
        case let .rekey(m): m.memberId
        case let .bye(m): m.memberId
        case let .avatar(m): m.memberId
        case let .diag(m): m.memberId
        case .peers, .kick: nil
        }
    }

    private func onPeers(_ msg: Msg.Peers) {
        var grew = false
        for p in msg.peers {
            if p.memberId == selfId || removed.contains(p.memberId) { continue }
            if let known = memberTable[p.memberId] {
                if known.left, p.lastSeenAgoSec < MeshNode.lostAfterS {
                    known.left = false
                }
            } else {
                let m = Member(p.memberId, p.name, p.color)
                m.lastHeardMs = now() - p.lastSeenAgoSec * MeshNode.msPerS
                putMember(m)
                grew = true
            }
        }
        if grew { changed() }
        ensureLinks()
    }

    // MARK: - positions and texts

    /// Sends the position of this device to every member, and keeps it for those not linked yet.
    public func publish(_ fix: PeerFix) {
        locked {
            if closed { return }
            me.fix = fix
            me.lastHeardMs = now()
            let msg = Msg.Pos(selfId, nextSeq(), fix)
            if links.isEmpty {
                missed.append(fix)
                if missed.count > MeshNode.maxMissed { missed.removeFirst(missed.count - MeshNode.maxMissed) }
                EventLog.event("mesh.pos.held", ("seq", msg.seq), ("fixT", fix.timeSec), ("held", missed.count))
            }
            if let frame = broadcast(.pos(msg)) {
                EventLog.event(
                    "mesh.pos.out",
                    ("seq", msg.seq),
                    ("fid", Wire.frameId(frame)),
                    ("fixT", fix.timeSec),
                    ("acc", fix.accuracyM),
                    ("links", links.count),
                    ("bytes", frame.count)
                )
            }
            changed()
        }
    }

    /// Sends `text` to every member (cut at `Msg.maxTextChars` characters on the wire, as on Android).
    public func sendText(_ text: String) {
        locked {
            if !closed { broadcast(.text(Msg.Text(selfId, nextSeq(), text))) }
        }
    }

    /// Sends a payload of the debug build's test tools to every member; `false` when no link is open. A payload over
    /// `Msg.Diag.maxBytes` is refused (Kotlin `require`).
    public func sendDiag(_ body: [UInt8]) throws -> Bool {
        try require(body.count <= Msg.Diag.maxBytes, "diagnostic payload too large")
        var sent = false
        locked {
            if closed || links.isEmpty { return }
            let msg = Msg.Diag(selfId, nextSeq(), body)
            _ = firstTime(selfId, msg.seq)
            sent = broadcast(.diag(msg)) != nil
        }
        return sent
    }

    /// Starts an alert: sent now, then every `sosRepeatS` seconds for `sosRepeatForS` seconds (P22.2). Only the
    /// sends that reach a neighbour count: with no link, it goes as soon as one opens. It stays on until `cancelSos`.
    public func startSos(_ fix: PeerFix?, _ text: String) {
        locked {
            if closed { return }
            sos = (fix, text)
            sosSendsLeft = MeshNode.sosSends
            liftSendsLeft = 0
            lastSosMs = 0
            sendSosNow()
        }
    }

    /// Lifts this device's alert, even once its repeats are over: the others are told now and again for
    /// `sosRepeatForS` seconds, like the alert. Nothing is sent when no alert is on.
    public func cancelSos() {
        locked {
            if closed || sos == nil { return }
            sos = nil
            sosSendsLeft = 0
            liftSendsLeft = MeshNode.sosSends
            lastLiftMs = 0
            sendLiftNow()
        }
    }

    /// `true` from `startSos` to `cancelSos`.
    public var sosActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        return sos != nil
    }

    private func sendSosNow() {
        guard let alert = sos else { return }
        if links.isEmpty { return }
        lastSosMs = now()
        sosSendsLeft -= 1
        let msg = Msg.Sos(selfId, nextSeq(), alert.fix ?? me.fix, alert.text)
        EventLog.event("mesh.sos.out", ("seq", msg.seq), ("left", sosSendsLeft), ("links", links.count))
        broadcast(.sos(msg))
    }

    private func sendLiftNow() {
        if links.isEmpty { return }
        lastLiftMs = now()
        liftSendsLeft -= 1
        let msg = Msg.SosEnd(selfId, nextSeq())
        EventLog.event("mesh.sos.end.out", ("seq", msg.seq), ("left", liftSendsLeft), ("links", links.count))
        broadcast(.sosEnd(msg))
    }

    // MARK: - signalling

    /// Sends `payload` to member `to` by the links that exist.
    private func sendSignal(_ to: Int64, _ payload: PairingPayload) {
        let msg = Msg.Signal(nextSeq(), selfId, to, PairingCodec.encodeBytes(payload))
        _ = firstTime(selfId, msg.seq)
        if links[to] != nil {
            sendTo(to, .signal(msg))
        } else {
            broadcast(.signal(msg))
        }
    }

    private func onSignal(_ from: Int64, _ msg: Msg.Signal, _ frame: Frame, _ raw: [UInt8]) {
        if !firstTime(msg.from, msg.seq) { return }
        if msg.to != selfId {
            if let direct = links[msg.to] {
                if !direct.isBroadcastMedium, let relayed = Wire.relayed(raw) { _ = direct.send(relayed, false) }
            } else {
                relay(raw, from)
            }
            return
        }
        guard let payload = try? PairingCodec.decodeBytes(msg.payload) else { return }
        if payload.groupId != groupId || payload.memberId != msg.from { return }
        if PairingCodec.staleness(payload, now() / MeshNode.msPerS) != nil { return }
        switch payload.kind {
        case .meshOffer:
            guard let m = memberTable[msg.from], !m.left else { return }
            let peer = msg.from
            transport.answer(payload) { [self] answer in
                if let answer {
                    locked { sendSignal(peer, answer) }
                }
            }
        case .answer:
            transport.accept(payload)
        case .offer:
            break
        }
    }

    /// Opens the links to members this node should call: those with a larger id, not yet linked or being linked.
    private func ensureLinks() {
        if closed || links.isEmpty { return }
        let t = now()
        lastEnsureMs = t
        for id in memberOrder {
            guard let m = memberTable[id] else { continue }
            if m.id == selfId || m.left || links[m.id] != nil || negotiating[m.id] != nil { continue }
            if selfId >= m.id || t < (retryAt[m.id] ?? 0) { continue }
            negotiating[m.id] = t
            let peer = m.id
            transport.offer(peer) { [self] offer in
                locked {
                    if let offer {
                        sendSignal(peer, offer)
                    } else {
                        negotiating[peer] = nil
                    }
                }
            }
        }
    }

    // MARK: - keys and leaving

    /// Removes `memberId`: a new key is sent to everyone else, so the member cannot read what comes next.
    public func remove(_ memberId: Int64) {
        locked {
            if closed || memberId == selfId { return }
            let newKey = GroupCrypto.randomKey(random)
            let msg = Msg.Rekey(selfId, nextSeq(), memberId, newKey)
            if let frame = try? Wire.encode(.rekey(msg), crypto) {
                for id in links.keys.sorted() where id != memberId {
                    _ = links[id]?.send(frame, false)
                }
            }
            // The member is told it is out with the old key it knows, before the new one becomes the sealing key.
            dropMember(memberId)
            try? crypto.rekey(newKey)
            let listener = self.listener
            events.append { listener.onRekeyed(newKey) }
            changed()
        }
    }

    private func onRekey(_ from: Int64, _ msg: Msg.Rekey, _ raw: [UInt8]) {
        if !firstTime(msg.memberId, msg.seq) { return }
        if msg.excluded == selfId {
            // Only reachable through a relay that did not know it had to keep the key from us: leave at once.
            close(sayBye: false)
            let listener = self.listener
            events.append { listener.onExcluded() }
            return
        }
        if msg.excluded != 0 { dropMember(msg.excluded) }
        try? crypto.rekey(msg.key)
        let listener = self.listener
        events.append { listener.onRekeyed(msg.key) }
        // The frame is passed on as it came, still sealed with the previous key, to everyone but the excluded.
        if let relayed = Wire.relayed(raw) {
            for id in links.keys.sorted() where id != from && id != msg.excluded {
                if let link = links[id], !link.isBroadcastMedium { _ = link.send(relayed, false) }
            }
        }
        changed()
    }

    private func dropMember(_ id: Int64) {
        // Tell it on its own link, with the old key, that it is out; the new key is not in that message.
        if let link = links[id], let kick = try? Wire.encode(.kick(Msg.Kick(id)), crypto) {
            _ = link.send(kick, false)
        }
        removed.insert(id)
        retryAt[id] = nil
        attempts[id] = nil
        links.removeValue(forKey: id)?.close()
        lastFrameMs[id] = nil
        negotiating[id] = nil
        transport.drop(id)
        removeMember(id)
        alertSeq[id] = nil
        liftSeq[id] = nil
        alerting.remove(id)
    }

    /// Leaves the group: tells the neighbours and closes every link.
    public func leave() {
        locked { close(sayBye: true) }
    }

    private func close(sayBye: Bool) {
        if closed { return }
        if sayBye { broadcast(.bye(Msg.Bye(selfId))) }
        closed = true
        for id in links.keys.sorted() {
            links[id]?.close()
            transport.drop(id)
        }
        for id in negotiating.keys.sorted() {
            transport.drop(id)
        }
        links.removeAll()
        negotiating.removeAll()
        changed()
    }

    // MARK: - clock

    /// Call about once a second: pings, dead links, missing links, SOS repeats.
    public func tick() {
        locked {
            if closed { return }
            let t = now()
            if due(lastPingMs, t, MeshNode.pingEveryS) {
                lastPingMs = t
                if let frame = broadcast(.ping(Msg.Ping(selfId, t / MeshNode.msPerS))) {
                    EventLog.detail("mesh.ping.out", ("fid", Wire.frameId(frame)), ("links", links.count))
                }
            }
            for id in lastFrameMs.keys.sorted() {
                guard let last = lastFrameMs[id] else { continue }
                if t - last > MeshNode.lostAfterS * MeshNode.msPerS {
                    EventLog.event("mesh.link.lost", ("peer", MeshNode.hex(id)), ("silentS", (t - last) / MeshNode.msPerS))
                    links.removeValue(forKey: id)?.close()
                    transport.drop(id)
                    lastFrameMs[id] = nil
                    changed()
                }
            }
            for id in negotiating.keys.sorted() {
                guard let started = negotiating[id] else { continue }
                if t - started > MeshNode.negotiationTimeoutS * MeshNode.msPerS {
                    negotiating[id] = nil
                    transport.drop(id)
                    let n = (attempts[id] ?? 0) + 1
                    attempts[id] = n
                    let shift = Swift.min(Int64(n - 1), MeshNode.maxBackoffShift)
                    retryAt[id] = t + (MeshNode.negotiationTimeoutS << shift) * MeshNode.msPerS
                }
            }
            if due(lastEnsureMs, t, MeshNode.ensureEveryS) { ensureLinks() }
            if sos != nil, sosSendsLeft > 0, due(lastSosMs, t, MeshNode.sosRepeatS) { sendSosNow() }
            if liftSendsLeft > 0, due(lastLiftMs, t, MeshNode.sosRepeatS) { sendLiftNow() }
        }
    }

    /// `true` when `everyS` seconds passed since `last`, or when the clock went back past it (P21.5 n).
    private func due(_ last: Int64, _ t: Int64, _ everyS: Int64) -> Bool {
        t < last || t - last >= everyS * MeshNode.msPerS
    }

    // MARK: - views

    public func members() -> [MemberView] {
        lock.lock()
        defer { lock.unlock() }
        let t = now()
        return memberOrder.compactMap { id -> MemberView? in
            guard let m = memberTable[id] else { return nil }
            let status: MemberStatus
            if m.id == selfId {
                status = .direct
            } else if m.left {
                status = .left
            } else if links[m.id] != nil {
                status = .direct
            } else if t - m.lastHeardMs <= MeshNode.lostAfterS * MeshNode.msPerS {
                status = .relayed
            } else {
                status = .lost
            }
            return MemberView(
                m.id,
                m.name,
                m.color,
                status,
                m.id == selfId,
                m.lastHeardMs,
                m.fix,
                m.trail,
                picture: m.picture,
                sharing: m.sharing
            )
        }
    }

    /// Ids of the neighbours linked right now.
    public func linkedIds() -> Set<Int64> {
        lock.lock()
        defer { lock.unlock() }
        return Set(links.keys)
    }

    public func setSelf(_ name: String, _ color: Int32) {
        locked {
            me.name = name
            me.color = color
            broadcast(.hello(Msg.Hello(selfId, name, color)))
            changed()
        }
    }

    /// Tells the group whether this device shares its position or only listens (P22.3): sent at once when it changes,
    /// and to each link that opens.
    public func setSharing(_ on: Bool) {
        locked {
            if closed || me.sharing == on { return }
            me.sharing = on
            stateSeq = nextSeq()
            _ = firstTime(selfId, stateSeq)
            EventLog.event("mesh.state.out", ("seq", stateSeq), ("sharing", on), ("links", links.count))
            broadcast(.state(Msg.State(selfId, stateSeq, sharing: on)))
            changed()
        }
    }

    /// Shares `picture` (a small JPEG, at most `MemberLook.maxPictureBytes`) with the group; `nil` removes it.
    public func setPicture(_ picture: [UInt8]?) {
        locked {
            var next: [UInt8]?
            if let p = picture, !p.isEmpty, p.count <= MemberLook.maxPictureBytes { next = p }
            if next == me.picture { return }
            me.picture = next
            pictureSeq = nextSeq()
            _ = firstTime(selfId, pictureSeq)
            broadcast(.avatar(Msg.Avatar(selfId, pictureSeq, next ?? [])))
            changed()
        }
    }

    // MARK: - plumbing

    private func nextSeq() -> Int64 {
        seq += 1
        return seq & 0xFFFF_FFFF
    }

    private func changed() {
        let listener = self.listener
        events.append { listener.onChanged() }
    }

    private func putMember(_ m: Member) {
        if memberTable[m.id] == nil { memberOrder.append(m.id) }
        memberTable[m.id] = m
    }

    private func removeMember(_ id: Int64) {
        if memberTable.removeValue(forKey: id) != nil {
            memberOrder.removeAll { $0 == id }
        }
    }

    private func memberOrPut(_ id: Int64, _ name: String, _ color: Int32) -> Member {
        if let m = memberTable[id] { return m }
        let m = Member(id, name, color)
        putMember(m)
        return m
    }

    private func memberOrNew(_ memberId: Int64) -> Member? {
        if removed.contains(memberId) { return nil }
        // A member can be heard of through a relay before its name reaches us in a PEERS list.
        return memberOrPut(memberId, "?", 0)
    }

    private func touch(_ memberId: Int64) {
        if let m = memberOrNew(memberId) {
            m.lastHeardMs = now()
            m.left = false
        }
    }

    private func recordFix(_ memberId: Int64, _ fix: PeerFix) {
        guard let m = memberOrNew(memberId) else { return }
        m.lastHeardMs = now()
        m.left = false
        m.fix = fix
        m.trail.append(fix)
        if m.trail.count > MeshNode.maxTrail { m.trail.removeFirst(m.trail.count - MeshNode.maxTrail) }
        changed()
    }

    /// `true` the first time a `(member, seq)` pair is seen; remembers the last `seenCap` ones.
    private func firstTime(_ member: Int64, _ seq: Int64) -> Bool {
        let key = SeenKey(member: member, seq: seq)
        if seen.contains(key) { return false }
        seen.insert(key)
        seenOrder.append(key)
        if seen.count > MeshNode.seenCap {
            seen.remove(seenOrder[seenStart])
            seenStart += 1
            if seenStart > MeshNode.seenCap {
                seenOrder.removeFirst(seenStart)
                seenStart = 0
            }
        }
        return true
    }

    private func peersMessage() -> Msg.Peers {
        let t = now()
        var list: [PeerInfo] = []
        for id in memberOrder {
            guard let m = memberTable[id], !m.left else { continue }
            let ago = m.id == selfId ? 0 : Swift.max((t - m.lastHeardMs) / MeshNode.msPerS, 0)
            list.append(PeerInfo(m.id, m.name, m.color, ago))
        }
        return Msg.Peers(list)
    }

    private func sendTo(_ peer: Int64, _ msg: Msg) {
        guard let link = links[peer], let frame = try? Wire.encode(msg, crypto) else { return }
        _ = link.send(frame, msg.onPositionChannel)
    }

    /// Sends `msg` to every neighbour but `except`; the frame sent, `nil` when there is no neighbour.
    @discardableResult
    private func broadcast(_ msg: Msg, except: Int64? = nil) -> [UInt8]? {
        if links.isEmpty { return nil }
        guard let frame = try? Wire.encode(msg, crypto) else { return nil }
        for id in links.keys.sorted() where id != except {
            guard let link = links[id] else { continue }
            if !link.send(frame, msg.onPositionChannel) {
                EventLog.event(
                    "mesh.send.fail",
                    ("peer", MeshNode.hex(id)),
                    ("type", msg.type),
                    ("fid", Wire.frameId(frame))
                )
            }
        }
        return frame
    }

    /// Passes `raw` to every neighbour but `from`, one hop shorter.
    private func relay(_ raw: [UInt8], _ from: Int64) {
        guard let relayed = Wire.relayed(raw) else { return }
        let onPos = Int(raw[0]) == MsgType.pos.code
        for id in links.keys.sorted() where id != from {
            if let link = links[id], !link.isBroadcastMedium { _ = link.send(relayed, onPos) }
        }
    }
}

/// The node takes the links of a transport directly (the Android controller forwards them the same way).
extension MeshNode: LinkListener {}
