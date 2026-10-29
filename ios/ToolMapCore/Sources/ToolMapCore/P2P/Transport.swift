// Port of core/src/main/kotlin/fr/toolmap/core/p2p/Transport.kt.

/// Who this device is in the group, as a transport needs it to build pairing codes.
public struct TransportIdentity: Equatable, Hashable, Sendable {
    public let groupId: [UInt8]
    public let memberId: Int64
    public let name: String
    public let color: Int32

    public init(_ groupId: [UInt8], _ memberId: Int64, _ name: String, _ color: Int32) {
        self.groupId = groupId
        self.memberId = memberId
        self.name = name
        self.color = color
    }
}

/// What a transport tells the group logic about its links.
public protocol LinkListener: AnyObject {
    func onLinkOpen(_ peer: Int64, _ name: String, _ color: Int32, _ link: any MeshLink)

    func onLinkClosed(_ peer: Int64)

    func onFrame(_ peer: Int64, _ bytes: [UInt8])
}

/// An invitation being shown; a link opens when the answer to it is given to `accept` (Kotlin
/// `PairingTransport.Invitation`).
public protocol PairingInvitation: AnyObject {
    var payload: PairingPayload { get }

    func accept(_ answer: PairingPayload)

    func cancel()
}

/// A way of making links between members: WebRTC in the app, a simulated network in tests. `invite` and `join` are
/// the first pairing (the codes go through a QR code or a link); the `MeshTransport` part links members already in the
/// group together through the mesh.
public protocol PairingTransport: MeshTransport {
    var identity: TransportIdentity? { get set }

    /// Prepares an invitation carrying `groupKey`; `nil` when it could not be made (no network).
    func invite(_ groupKey: [UInt8], _ done: @escaping @Sendable ((any PairingInvitation)?) -> Void)

    /// Answers an invitation: `done` gets the answer to give back, `nil` on failure.
    func join(_ offer: PairingPayload, _ done: @escaping @Sendable (PairingPayload?) -> Void)

    func close()
}

/// Creates the transport of a group once the listener that will receive its links is known.
public protocol TransportFactory {
    func create(_ listener: any LinkListener, _ stunServers: @escaping @Sendable () -> [String]) -> any PairingTransport
}

/// How the test tools of the debug build talk to the other members of the group (P21): opaque payloads, sealed with
/// the group key like every frame (`Msg.Diag`), sent to every member and never shown. A release build never sends any
/// and drops those it receives.
public protocol DiagChannel: AnyObject {
    /// This device in its group; 0 when it is in no group.
    var selfId: Int64 { get }

    /// A payload sent now can reach someone: a link is open and, for a server group, the server answers.
    var canSend: Bool { get }

    /// The way out is busy (writes to the server waiting): a payload sent now would delay the positions.
    var busy: Bool { get }

    /// Sends `body` (at most `Msg.Diag.maxBytes`) to every member; `false` when it cannot be sent.
    func sendDiag(_ body: [UInt8]) -> Bool

    /// Called with each payload another member sent, from any thread.
    var diagReceiver: (@Sendable (Int64, [UInt8]) -> Void)? { get set }
}
