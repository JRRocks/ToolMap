// Port of core/src/test/kotlin/fr/toolmap/core/p2p/MeshNodeTest.kt: the protocol of a group on a simulated network.

import Foundation
import Testing
@testable import ToolMapCore

/// A tiny network: links deliver frames through a queue pumped by the test, so everything is deterministic.
private final class Net: @unchecked Sendable {
    var nowMs: Int64 = 1_790_000_000_000
    var peers: [Int64: MeshPeer] = [:]
    private var queue: [() -> Void] = []
    private var head = 0
    var cut: Set<[Int64]> = []

    func pump() {
        var guardCount = 0
        while head < queue.count {
            guardCount += 1
            if guardCount >= 10_000 {
                Issue.record("the network does not settle")
                break
            }
            let job = queue[head]
            head += 1
            job()
        }
        queue.removeAll()
        head = 0
    }

    func deliver(_ from: Int64, _ to: Int64, _ frame: [UInt8]) {
        queue.append { [self] in
            if !cut.contains([from, to]) { peers[to]?.node.onFrame(from, frame) }
        }
    }

    /// Opens a link between `a` and `b` the way a successful pairing would.
    func connect(_ a: Int64, _ b: Int64) {
        guard let pa = peers[a], let pb = peers[b] else {
            Issue.record("unknown peer")
            return
        }
        let toB = NetLink(self, a, b)
        let toA = NetLink(self, b, a)
        pa.links[b] = toB
        pb.links[a] = toA
        pa.node.onLinkOpen(b, pb.name, pb.color, toB)
        pb.node.onLinkOpen(a, pa.name, pa.color, toA)
        pump()
    }

    /// Both ends see the link close, as a lost connection would show.
    func disconnect(_ a: Int64, _ b: Int64) {
        peers[a]?.links.removeValue(forKey: b)
        peers[b]?.links.removeValue(forKey: a)
        peers[a]?.node.onLinkClosed(b)
        peers[b]?.node.onLinkClosed(a)
        pump()
    }

    func tickAll(_ seconds: Int, every: Int = 1) {
        var t = 0
        while t < seconds {
            nowMs += Int64(every) * 1000
            for id in peers.keys.sorted() {
                peers[id]?.node.tick()
            }
            pump()
            t += every
        }
    }
}

private final class NetLink: MeshLink, @unchecked Sendable {
    private let net: Net
    private let from: Int64
    private let to: Int64
    var closed = false
    var sent = 0

    init(_ net: Net, _ from: Int64, _ to: Int64) {
        self.net = net
        self.from = from
        self.to = to
    }

    func send(_ frame: [UInt8], _ onPositionChannel: Bool) -> Bool {
        if closed { return false }
        sent += 1
        net.deliver(from, to, frame)
        return true
    }

    func close() {
        closed = true
    }
}

private final class MeshPeer: MeshTransport, MeshListener, @unchecked Sendable {
    let net: Net
    let id: Int64
    let name: String
    let groupId: [UInt8]
    var allowOffers: Bool
    let color: Int32
    var links: [Int64: NetLink] = [:]
    var positions: [(Int64, PeerFix)] = []
    var texts: [(Int64, String)] = []
    var sos: [(Int64, String)] = []
    var lifted: [Int64] = []
    var diags: [(Int64, [UInt8])] = []
    var rekeyed: [UInt8]?
    var excluded = false
    var changes = 0
    var offersMade = 0
    var answersMade = 0
    private var pendingOffers: [String: Int64] = [:]
    private var token = 0
    /// How far this device's clock is from the network's, to move one clock alone.
    var skewMs: Int64 = 0
    var node: MeshNode!

    init(
        _ net: Net,
        _ id: Int64,
        _ name: String,
        _ groupKey: [UInt8],
        _ groupId: [UInt8],
        allowOffers: Bool = true,
        seqFloor: Int64 = 0
    ) throws {
        self.net = net
        self.id = id
        self.name = name
        self.groupId = groupId
        self.allowOffers = allowOffers
        color = Int32(id % 12)
        node = try MeshNode(
            groupId,
            groupKey,
            id,
            name,
            color,
            self,
            self,
            clock: { [self] in net.nowMs + skewMs },
            random: SystemSecureRandom(),
            seqFloor: seqFloor
        )
        net.peers[id] = self
    }

    private func payload(_ kind: PairingKind, _ ufrag: String) -> PairingPayload? {
        try? PairingPayload(
            kind: kind,
            createdAtSec: net.nowMs / 1000,
            groupId: groupId,
            groupKey: nil,
            memberId: id,
            name: name,
            color: color,
            iceUfrag: ufrag,
            icePwd: String(repeating: "p", count: 24),
            dtlsFingerprint: [UInt8](repeating: 0, count: 32),
            dtlsSetup: .actpass,
            candidates: []
        )
    }

    func offer(_ peer: Int64, _ done: @escaping @Sendable (PairingPayload?) -> Void) {
        if !allowOffers {
            done(nil)
            return
        }
        offersMade += 1
        let ufrag = "o\(id)-\(token)"
        token += 1
        pendingOffers[ufrag] = peer
        done(payload(.meshOffer, ufrag))
    }

    func answer(_ offer: PairingPayload, _ done: @escaping @Sendable (PairingPayload?) -> Void) {
        answersMade += 1
        // The answer carries the ufrag of the offer so the offerer knows which link it is for.
        done(payload(.answer, offer.iceUfrag))
    }

    func accept(_ answer: PairingPayload) {
        guard let target = pendingOffers.removeValue(forKey: answer.iceUfrag) else { return }
        #expect(target == answer.memberId)
        net.connect(id, answer.memberId)
    }

    func drop(_ peer: Int64) {
        links.removeValue(forKey: peer)?.close()
    }

    func onChanged() {
        changes += 1
    }

    func onPosition(_ member: Int64, _ fix: PeerFix) {
        positions.append((member, fix))
    }

    func onText(_ member: Int64, _ text: String) {
        texts.append((member, text))
    }

    func onSos(_ member: Int64, _ fix: PeerFix?, _ text: String) {
        sos.append((member, text))
    }

    func onSosLifted(_ member: Int64) {
        lifted.append(member)
    }

    func onDiag(_ member: Int64, _ body: [UInt8]) {
        diags.append((member, body))
    }

    func onRekeyed(_ key: [UInt8]) {
        rekeyed = key
    }

    func onExcluded() {
        excluded = true
    }

    func status(_ other: Int64) -> MemberStatus? { node.members().first { $0.id == other }?.status }

    func view(_ other: Int64) -> MemberView? { node.members().first { $0.id == other } }

    func sentFrames() -> Int { links.values.reduce(0) { $0 + $1.sent } }
}

struct MeshNodeTests {
    private let key = GroupCrypto.randomKey()
    private let groupId = [UInt8](repeating: 9, count: 16)
    private let net = Net()

    private func peer(_ id: Int64, _ name: String) throws -> MeshPeer {
        try MeshPeer(net, id, name, key, groupId)
    }

    private func fix(_ lat: Double = 45.0, _ lon: Double = 6.0, t: Int64? = nil) -> PeerFix {
        PeerFix(lat, lon, timeSec: t ?? net.nowMs / 1000, battery: 80)
    }

    /// Three members, A (id 1) invited B (2) and C (3), who then find each other through the mesh.
    private func threeMembers() throws -> (MeshPeer, MeshPeer, MeshPeer) {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        let c = try peer(3, "Chloé")
        net.connect(1, 2)
        net.connect(1, 3)
        return (a, b, c)
    }

    private func fixOf(_ p: MeshPeer, _ other: Int64) throws -> PeerFix {
        let view = try #require(p.view(other))
        return try #require(view.fix)
    }

    @Test("a joiner receives the member list and the inviter learns the joiner")
    func joiner() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        #expect(a.node.members().map(\.name).sorted() == ["Alice", "Bruno"])
        #expect(b.node.members().map(\.name).sorted() == ["Alice", "Bruno"])
        #expect(a.status(2) == .direct)
        #expect(a.node.linkedIds() == [2])
        #expect(a.node.members().first { $0.isSelf }?.id == 1)
    }

    @Test("a third member pairs once and the two others link up through the relayed signal")
    func thirdMember() throws {
        let (a, b, c) = try threeMembers()
        #expect(b.node.linkedIds() == [1, 3])
        #expect(c.node.linkedIds() == [1, 2])
        #expect(a.node.linkedIds() == [2, 3])
        // Of B and C the smaller id made the offer, once; A (id 1) did not need to call anyone.
        #expect(b.offersMade == 1)
        #expect(c.offersMade == 0)
        #expect(c.answersMade == 1)
        #expect(a.offersMade == 0)
        #expect(c.status(2) == .direct)
    }

    @Test("a position reaches everyone exactly once even when two paths lead to it")
    func positionOnce() throws {
        let (a, b, c) = try threeMembers()
        b.node.publish(fix(45.1, 6.1))
        net.pump()
        #expect(a.positions.filter { $0.0 == 2 }.count == 1)
        #expect(c.positions.filter { $0.0 == 2 }.count == 1)
        #expect(b.positions.isEmpty)
        #expect(try abs(fixOf(c, 2).lat - 45.1) <= 1e-6)
        #expect(c.view(2)?.trail.count == 1)
    }

    @Test("when the direct link is down the position is relayed by the third member")
    func relayed() throws {
        let (a, b, c) = try threeMembers()
        net.disconnect(2, 3)
        b.node.publish(fix(45.2, 6.2))
        net.pump()
        #expect(c.positions.filter { $0.0 == 2 }.count == 1)
        #expect(c.status(2) == .relayed)
        #expect(try abs(fixOf(c, 2).lat - 45.2) <= 1e-6)
        #expect(a.positions.filter { $0.0 == 2 }.count == 1)
    }

    @Test("a picture reaches everyone, relayed when needed, then reaches a late link, and can be removed")
    func picture() throws {
        let (a, b, c) = try threeMembers()
        net.disconnect(2, 3)
        let picture = (0 ..< 2000).map { UInt8(truncatingIfNeeded: $0) }
        b.node.setPicture(picture)
        net.pump()
        #expect(a.view(2)?.picture == picture)
        #expect(c.view(2)?.picture == picture)
        // The same picture again sends nothing.
        let sent = b.sentFrames()
        b.node.setPicture(Array(picture))
        net.pump()
        #expect(b.sentFrames() == sent)
        b.node.setPicture(nil)
        net.pump()
        #expect(a.view(2)?.picture == nil)
        #expect(c.view(2)?.picture == nil)
        // Too large: refused, nothing changes.
        b.node.setPicture([UInt8](repeating: 0, count: MemberLook.maxPictureBytes + 1))
        net.pump()
        #expect(a.view(2)?.picture == nil)
    }

    @Test("a diagnostic payload reaches every other member once, relayed when needed")
    func diagnostic() throws {
        let (a, b, c) = try threeMembers()
        net.disconnect(2, 3)
        let body = (0 ..< 1000).map { UInt8(truncatingIfNeeded: $0) }
        #expect(try b.node.sendDiag(body))
        net.pump()
        #expect(a.diags.map { $0.0 } == [2])
        #expect(c.diags.map { $0.0 } == [2])
        #expect(c.diags.count == 1)
        #expect(c.diags.first?.1 == body)
        #expect(b.diags.isEmpty)
        let alone = try peer(9, "Seul")
        #expect(try !alone.node.sendDiag(body))
    }

    @Test("the time to live stops a flood after four hops")
    func ttlStopsFlood() throws {
        var peers: [MeshPeer] = []
        for id in Int64(1) ... 6 {
            let p = try MeshPeer(net, id, "P\(id)", key, groupId, allowOffers: false)
            peers.append(p)
        }
        for i in Int64(1) ..< 6 {
            net.connect(i, i + 1)
        }
        let sentBefore = peers.reduce(0) { $0 + $1.sentFrames() }
        peers[0].node.publish(fix(46.0, 7.0))
        net.pump()
        for i in 1 ... 4 {
            #expect(peers[i].positions.count == 1, "P\(i + 1) is \(i) hops away")
        }
        #expect(peers[5].positions.isEmpty, "P6 is five hops away")
        // One position, one frame per hop: four frames in all, no echo storm.
        #expect(peers.reduce(0) { $0 + $1.sentFrames() } - sentBefore == 4)
    }

    @Test("a frame seen twice is handled once")
    func seenTwice() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        a.node.publish(fix())
        net.pump()
        #expect(b.positions.count == 1)
        let crypto = try GroupCrypto(key)
        let frame = try Wire.encode(.pos(Msg.Pos(1, 777, fix(46.0, 7.0))), crypto)
        b.node.onFrame(1, frame)
        b.node.onFrame(1, frame)
        #expect(b.positions.count == 2)
    }

    @Test("frames of another group or garbage are dropped without effect")
    func garbage() throws {
        _ = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        let stranger = try GroupCrypto(GroupCrypto.randomKey())
        let foreign = try Wire.encode(.pos(Msg.Pos(1, 5, fix())), stranger)
        b.node.onFrame(1, foreign)
        b.node.onFrame(1, [UInt8](repeating: 0, count: 40))
        b.node.onFrame(1, [])
        #expect(b.positions.isEmpty)
        #expect(b.view(1)?.fix == nil)
    }

    @Test("texts and SOS are flooded, the SOS repeated until lifted")
    func textsAndSos() throws {
        let (a, b, c) = try threeMembers()
        b.node.sendText("On y va")
        net.pump()
        #expect(a.texts.map { $0.0 } == [2])
        #expect(a.texts.map { $0.1 } == ["On y va"])
        #expect(c.texts.map { $0.1 } == ["On y va"])

        b.node.startSos(fix(45.3, 6.3), "Blessé")
        net.pump()
        #expect(a.sos.count == 1)
        #expect(c.sos.count == 1)
        net.tickAll(16)
        #expect(a.sos.count == 2)
        b.node.cancelSos()
        net.pump()
        #expect(a.lifted == [2])
        #expect(c.lifted == [2])
        net.tickAll(40)
        #expect(a.sos.count == 2)
        #expect(try abs(fixOf(a, 2).lat - 45.3) <= 1e-6)
    }

    @Test("an alert is repeated for two minutes, stays on after them and is lifted once however often the lift comes")
    func alertRepeats() throws {
        let (a, b, _) = try threeMembers()
        b.node.startSos(fix(), "Cheville")
        net.tickAll(300)
        // The first send, then one every 15 s for 120 s.
        #expect(a.sos.count == Int(MeshNode.sosSends))
        #expect(a.sos.count == 9)
        #expect(b.node.sosActive)

        b.node.cancelSos()
        net.tickAll(300)
        #expect(!b.node.sosActive)
        #expect(a.lifted == [2])
        #expect(a.sos.count == 9)
        // Nothing to lift any more: a second cancel sends nothing.
        b.node.cancelSos()
        net.tickAll(30)
        #expect(a.lifted == [2])
    }

    @Test("a member cut off when the alert was lifted hears the lift when it comes back within two minutes")
    func liftAfterComingBack() throws {
        let (a, b, c) = try threeMembers()
        net.connect(2, 3)
        b.node.startSos(fix(), "Perdu")
        net.pump()
        #expect(c.sos.count == 1)
        net.disconnect(1, 3)
        net.disconnect(2, 3)
        b.node.cancelSos()
        net.tickAll(30)
        #expect(a.lifted == [2])
        #expect(c.lifted.isEmpty)
        net.connect(1, 3)
        net.tickAll(16)
        #expect(c.lifted == [2])
    }

    @Test("an alert sent with no link goes as soon as a link opens, and its two minutes start then")
    func alertWithoutLink() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        b.node.startSos(fix(), "Aide")
        net.tickAll(60)
        net.connect(1, 2)
        net.tickAll(1)
        #expect(a.sos.count == 1)
        net.tickAll(300)
        #expect(a.sos.count == 9)
    }

    @Test("a repeat overtaken by the lift, and a lift older than a new alert, are ignored")
    func overtaken() throws {
        let (a, _, _) = try threeMembers()
        let crypto = try GroupCrypto(key)
        func from2(_ msg: Msg) throws {
            let frame = try Wire.encode(msg, crypto)
            a.node.onFrame(2, frame)
            net.pump()
        }
        // The lift of an alert this node never heard: nothing to lift.
        try from2(.sosEnd(Msg.SosEnd(2, 105)))
        #expect(a.lifted.isEmpty)
        // A repeat of that alert arriving after its lift is over already.
        try from2(.sos(Msg.Sos(2, 103, nil, "ancienne")))
        #expect(a.sos.isEmpty)
        // A new alert, then a late repeat of the previous lift: the alert stays.
        try from2(.sos(Msg.Sos(2, 110, nil, "nouvelle")))
        try from2(.sosEnd(Msg.SosEnd(2, 107)))
        #expect(a.sos.map { $0.0 } == [2])
        #expect(a.sos.map { $0.1 } == ["nouvelle"])
        #expect(a.lifted.isEmpty)
        try from2(.sosEnd(Msg.SosEnd(2, 112)))
        #expect(a.lifted == [2])
    }

    @Test("whether a member shares or only listens reaches everyone, new links included, the latest winning")
    func sharingState() throws {
        let (a, b, c) = try threeMembers()
        // A member of a version without STATE is unknown, neither sharing nor listening.
        #expect(a.view(2)?.sharing == nil)
        b.node.setSharing(false)
        net.pump()
        #expect(a.view(2)?.sharing == false)
        #expect(c.view(2)?.sharing == false)
        #expect(b.node.members().first { $0.isSelf }?.sharing == false)
        b.node.setSharing(true)
        net.pump()
        #expect(a.view(2)?.sharing == true)
        // A late copy of the earlier state changes nothing.
        let crypto = try GroupCrypto(key)
        let late = try Wire.encode(.state(Msg.State(2, 1, sharing: false)), crypto)
        a.node.onFrame(2, late)
        net.pump()
        #expect(a.view(2)?.sharing == true)
        // Someone who joins later hears it on its first link.
        let d = try peer(4, "Denis")
        net.connect(2, 4)
        #expect(d.view(2)?.sharing == true)
    }

    @Test("a frame of a type this version does not know is dropped and the group goes on")
    func unknownType() throws {
        // What a version without SOS_END (up to 0.4.113, types 1 to 13) does with a lift: its type is unknown to it.
        // Here an unassigned type plays that part for this version.
        let (a, b, _) = try threeMembers()
        var lift = try Wire.encode(.sosEnd(Msg.SosEnd(2, 1)), GroupCrypto(key))
        lift[0] = 0x7F
        a.node.onFrame(2, lift)
        net.pump()
        #expect(a.lifted.isEmpty)
        b.node.sendText("Toujours là")
        net.pump()
        #expect(a.texts.map { $0.1 } == ["Toujours là"])
        #expect(a.status(2) == .direct)
    }

    @Test("pings keep links alive and a silent neighbour is dropped after 45 seconds")
    func pings() throws {
        let a = try peer(1, "Alice")
        _ = try peer(2, "Bruno")
        net.connect(1, 2)
        net.tickAll(120, every: 5)
        #expect(a.status(2) == .direct)
        #expect(a.node.linkedIds() == [2])
        net.cut.insert([2, 1])
        net.cut.insert([1, 2])
        // The last ping was at most 15 s ago: 20 s of silence is not yet 45.
        net.tickAll(20, every: 5)
        #expect(a.status(2) == .direct)
        net.tickAll(35, every: 5)
        #expect(a.node.linkedIds().isEmpty)
        #expect(a.status(2) == .lost)
        #expect(a.links.isEmpty || a.links.values.allSatisfy(\.closed))
    }

    @Test("a member that stops speaking is lost and a lost link is called again by the smaller id")
    func callAgain() throws {
        let (_, b, c) = try threeMembers()
        net.disconnect(2, 3)
        net.tickAll(25, every: 5)
        // B (id 2) calls C (id 3) again through A, whose links are intact.
        #expect(b.node.linkedIds() == [1, 3])
        #expect(b.offersMade == 2)
        #expect(c.status(2) == .direct)
    }

    @Test("removing a member rekeys the others, closes its links and ignores what it still sends")
    func removing() throws {
        let (a, b, c) = try threeMembers()
        a.node.remove(3)
        net.pump()
        #expect(c.excluded)
        #expect(c.node.isClosed)
        #expect(a.rekeyed == b.rekeyed)
        #expect(a.rekeyed != nil)
        #expect(a.node.linkedIds() == [2])
        #expect(b.node.linkedIds() == [1])
        #expect(!a.node.members().contains { $0.id == 3 })
        #expect(!b.node.members().contains { $0.id == 3 })
        // What A and B say next is sealed with the new key: C could not read it and sends nothing back.
        a.node.sendText("secret")
        net.pump()
        #expect(b.texts.map { $0.0 } == [1])
        #expect(b.texts.map { $0.1 } == ["secret"])
        #expect(!c.texts.contains { $0.1 == "secret" })
        // A frame C sealed with the old key is dropped by the author check.
        let old = try GroupCrypto(key)
        let intruder = try Wire.encode(.text(Msg.Text(3, 99, "intrus")), old)
        b.node.onFrame(1, intruder)
        #expect(!b.texts.contains { $0.1 == "intrus" })
        #expect(a.rekeyed == b.rekeyed)
    }

    @Test("leaving tells the neighbours and closes the links")
    func leaving() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        b.node.leave()
        net.pump()
        #expect(b.node.isClosed)
        #expect(a.status(2) == .left)
        #expect(a.node.linkedIds().isEmpty)
        // A closed node does nothing more.
        b.node.publish(fix())
        net.pump()
        #expect(a.positions.isEmpty)
    }

    @Test("positions taken without any link are sent as a trail to the first neighbour")
    func heldTrail() throws {
        let a = try peer(1, "Alice")
        a.node.publish(fix(45.0, 6.0, t: 1))
        a.node.publish(fix(45.001, 6.0, t: 2))
        a.node.publish(fix(45.002, 6.0, t: 3))
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        #expect(b.view(1)?.trail.count == 3)
        #expect(b.view(1)?.trail.map(\.timeSec) == [1, 2, 3])
        // The newest fix of the trail is the position shown (P21.5 l).
        #expect(b.view(1)?.fix?.timeSec == 3)
        #expect(b.positions.map { $0.1.timeSec } == [3])
        _ = a
    }

    @Test("a trail older than the known position does not move it")
    func olderTrail() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        a.node.publish(fix(45.0, 6.0, t: 10))
        net.pump()
        let crypto = try GroupCrypto(key)
        let trail = try Wire.encode(.trail(Msg.Trail(1, [fix(44.0, 6.0, t: 5)])), crypto)
        b.node.onFrame(1, trail)
        #expect(b.view(1)?.fix?.timeSec == 10)
        #expect(b.positions.map { $0.1.timeSec } == [10])
    }

    @Test("a node started again above its last message number is heard at once")
    func seqFloor() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        // A burst of messages takes the numbers ahead of the clock, as the test mode does.
        for i in 0 ..< 50 {
            a.node.sendText("t\(i)")
        }
        net.pump()
        let last = a.node.lastSeq
        // A new process in the same second, numbered from the clock alone: B knows these numbers and drops them.
        let fresh = try MeshPeer(net, 1, "Alice", key, groupId)
        net.connect(1, 2)
        fresh.node.sendText("perdu")
        net.pump()
        #expect(!b.texts.contains { $0.1 == "perdu" })
        // Started above the kept number (P21.5 m), it is heard.
        let again = try MeshPeer(net, 1, "Alice", key, groupId, seqFloor: last + 1000)
        net.connect(1, 2)
        again.node.sendText("reçu")
        net.pump()
        #expect(b.texts.last?.1 == "reçu")
    }

    @Test("pings go on when the clock goes back")
    func clockBack() throws {
        let a = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        net.tickAll(30, every: 5)
        // A's clock goes back by 815 s, as the emulator's did: B must keep hearing its pings (P21.5 n).
        a.skewMs = -815_000
        net.tickAll(120, every: 5)
        #expect(b.node.linkedIds() == [1])
        #expect(a.node.linkedIds() == [2])
    }

    @Test("a signal for another group, from another member or too old is ignored")
    func signals() throws {
        _ = try peer(1, "Alice")
        let b = try peer(2, "Bruno")
        net.connect(1, 2)
        let crypto = try GroupCrypto(key)
        var seq: Int64 = 5_000
        func signal(_ payload: PairingPayload) throws -> [UInt8] {
            let frame = try Wire.encode(.signal(Msg.Signal(seq, 1, 2, PairingCodec.encodeBytes(payload))), crypto)
            seq += 1
            return frame
        }
        func offer(group: [UInt8]? = nil, member: Int64 = 1, created: Int64? = nil) throws -> PairingPayload {
            try PairingPayload(
                kind: .meshOffer,
                createdAtSec: created ?? net.nowMs / 1000,
                groupId: group ?? groupId,
                groupKey: nil,
                memberId: member,
                name: "A",
                color: 0,
                iceUfrag: "u",
                icePwd: "p",
                dtlsFingerprint: [UInt8](repeating: 0, count: 32),
                dtlsSetup: .actpass,
                candidates: []
            )
        }
        let otherGroup = try offer(group: [UInt8](repeating: 0, count: 16))
        let otherMember = try offer(member: 7)
        let tooOld = try offer(created: net.nowMs / 1000 - 3_600)
        for payload in [otherGroup, otherMember, tooOld] {
            let frame = try signal(payload)
            b.node.onFrame(1, frame)
        }
        #expect(b.answersMade == 0)
        let good = try offer()
        let goodFrame = try signal(good)
        b.node.onFrame(1, goodFrame)
        #expect(b.answersMade == 1)
    }

    @Test("an unreachable member is called again after a back off, not every twenty seconds")
    func backOff() throws {
        _ = try peer(1, "Alice")
        let b = try MeshPeer(net, 2, "Bruno", key, groupId, allowOffers: false)
        _ = try peer(3, "Chloé")
        net.connect(1, 3)
        net.connect(1, 2)
        // B has to call C and cannot (its transport refuses): it retries, but spaced out.
        net.tickAll(300, every: 5)
        #expect(b.offersMade == 0)
        b.allowOffers = true
        net.tickAll(120, every: 5)
        #expect(b.offersMade >= 1)
        #expect(b.node.linkedIds() == [1, 3])
    }

    @Test("renaming is announced")
    func renaming() throws {
        let (a, b, c) = try threeMembers()
        a.node.setSelf("Alice M.", 5)
        net.pump()
        #expect(b.view(1)?.name == "Alice M.")
        #expect(c.view(1)?.color == 5)
    }
}
