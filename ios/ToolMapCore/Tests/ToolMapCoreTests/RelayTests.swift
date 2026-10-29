// Port of core/src/test/kotlin/fr/toolmap/core/p2p/RelayTest.kt: server addresses, invitations, states.

import Testing
@testable import ToolMapCore

struct RelayTests {
    private let server = RelayConfig.of("https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app/", " fake-key ")

    private func invite(_ name: String = "Jean", at: Int64 = 1_800_000_000) throws -> RoomInvite {
        try RoomInvite(
            at,
            (0 ..< 16).map { UInt8($0) },
            (0 ..< 32).map { UInt8($0 * 3) },
            0x1234_ABCD,
            name,
            server
        )
    }

    @Test("server config is cleaned and validated")
    func config() {
        #expect(server.base == "https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app")
        #expect(server.isUsable)
        #expect(RelayConfig.problem(RelayConfig("http://insecure.example", "k")) == .badUrl)
        #expect(RelayConfig.problem(RelayConfig("https://a.b/x?y=1", "k")) == .badUrl)
        #expect(RelayConfig.problem(RelayConfig("https://a.firebaseio.com", "")) == .badKey)
        #expect(RelayConfig.problem(RelayConfig("https://a.firebaseio.com", "has space")) == .badKey)
    }

    @Test("an invitation survives the QR text and the link")
    func invitationTexts() throws {
        let i = try invite()
        for text in [RoomInviteCodec.toQrText(i), RoomInviteCodec.toLink(i), "  " + RoomInviteCodec.toLink(i) + "\n"] {
            let back = try #require(try RoomInviteCodec.parseOrNull(text))
            #expect(back.groupId == i.groupId)
            #expect(back.groupKey == i.groupKey)
            #expect(back.inviterId == i.inviterId)
            #expect(back.inviterName == "Jean")
            #expect(back.server == server)
            #expect(back.createdAtSec == i.createdAtSec)
        }
    }

    @Test("an invitation fits a QR code of a reasonable size")
    func invitationSize() throws {
        #expect(try RoomInviteCodec.encodeBytes(invite()).count < 260)
    }

    @Test("other codes are not invitations")
    func notInvitations() throws {
        #expect(try RoomInviteCodec.parseOrNull("toolmap://pair?d=AAAA") == nil)
        #expect(try RoomInviteCodec.parseOrNull("hello") == nil)
        #expect(try RoomInviteCodec.parseOrNull("") == nil)
    }

    @Test("a damaged invitation is refused")
    func damaged() throws {
        let i = try invite()
        let bytes = RoomInviteCodec.encodeBytes(i)
        #expect(throws: WireError.self) { try RoomInviteCodec.decodeBytes(Array(bytes.prefix(bytes.count - 5))) }
        #expect(throws: WireError.self) { try RoomInviteCodec.decodeBytes(bytes + [1]) }
    }

    @Test("an invitation expires after a week")
    func expiry() throws {
        let i = try invite(at: 1_000_000)
        #expect(RoomInviteCodec.staleness(i, 1_000_000 + 3600) == nil)
        #expect(RoomInviteCodec.staleness(i, 1_000_000 + 8 * 86400) == .expired)
        #expect(RoomInviteCodec.staleness(i, 1_000_000 - 8 * 86400) == .fromTheFuture)
    }

    @Test("member keys round trip, including ids with the high bit set and all-digit ids")
    func memberKeys() {
        for id: Int64 in [1, -1, 0x1234_5678_9012_3456, Int64.min, 0x7FFF_FFFF_FFFF_FFFF] {
            let key = RelayPaths.member(id)
            #expect(key.hasPrefix("m"))
            #expect(RelayPaths.memberOf(key) == id)
        }
        #expect(RelayPaths.memberOf("1234") == nil)
        #expect(RelayPaths.memberOf("mzz") == nil)
        #expect(RelayPaths.memberOf("m") == nil)
        #expect(RelayPaths.room([0, 1, 2, 3]) == "00010203")
    }

    private func view(_ status: MemberStatus, _ lastHeardMs: Int64 = 0, _ fix: PeerFix? = nil, isSelf: Bool = false) -> MemberView {
        MemberView(1, "x", 0, status, isSelf, lastHeardMs, fix, [])
    }

    private let fix = PeerFix(1.0, 2.0, timeSec: 1)

    @Test("peer states follow the link, the silence and the group")
    func peerStates() {
        let now: Int64 = 10_000_000
        #expect(PeerState.of(view(.direct, now, fix), now) == .online)
        #expect(PeerState.of(view(.relayed, now, fix), now) == .online)
        #expect(PeerState.of(view(.lost), now) == .paired)
        #expect(PeerState.of(view(.lost, now - 60_000, fix), now) == .lostSync)
        #expect(PeerState.of(view(.lost, now - 11 * 60_000, fix), now) == .offline)
        #expect(PeerState.of(view(.left, now, fix), now) == .unpaired)
    }

    @Test("without the server this device can only vouch for nobody")
    func serverDown() {
        let now: Int64 = 1
        #expect(PeerState.of(view(.direct, isSelf: true), now, serverUp: false) == .offline)
        #expect(PeerState.of(view(.direct, isSelf: true), now, serverUp: true) == .online)
        #expect(PeerState.of(view(.direct, 1, fix), now, serverUp: false) == .lostSync)
        #expect(PeerState.of(view(.left), now, serverUp: false) == .unpaired)
        #expect(!PeerState.allCases.isEmpty)
    }
}
