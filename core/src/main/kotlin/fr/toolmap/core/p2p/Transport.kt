package fr.toolmap.core.p2p

/** Who this device is in the group, as a transport needs it to build pairing codes. */
class TransportIdentity(
    val groupId: ByteArray,
    val memberId: Long,
    val name: String,
    val color: Int,
)

/** What a transport tells the group logic about its links. */
interface LinkListener {
    fun onLinkOpen(
        peer: Long,
        name: String,
        color: Int,
        link: MeshLink,
    )

    fun onLinkClosed(peer: Long)

    fun onFrame(
        peer: Long,
        bytes: ByteArray,
    )
}

/**
 * A way of making links between members: WebRTC in the app, a simulated network in tests. [invite] and [join] are
 * the first pairing (the codes go through a QR code or a link); the [MeshTransport] part links members already in the
 * group together through the mesh.
 */
interface PairingTransport : MeshTransport {
    /** An invitation being shown; a link opens when the answer to it is given to [accept]. */
    interface Invitation {
        val payload: PairingPayload

        fun accept(answer: PairingPayload)

        fun cancel()
    }

    var identity: TransportIdentity?

    /** Prepares an invitation carrying [groupKey]; `null` when it could not be made (no network). */
    fun invite(
        groupKey: ByteArray,
        done: (Invitation?) -> Unit,
    )

    /** Answers an invitation: `done` gets the answer to give back, `null` on failure. */
    fun join(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    )

    fun close()
}

/** Creates the transport of a group once the listener that will receive its links is known. */
fun interface TransportFactory {
    fun create(
        listener: LinkListener,
        stunServers: () -> List<String>,
    ): PairingTransport
}

/**
 * How the test tools of the debug build talk to the other members of the group (P21): opaque payloads, sealed with
 * the group key like every frame ([Msg.Diag]), sent to every member and never shown. A release build never sends any
 * and drops those it receives.
 */
interface DiagChannel {
    /** This device in its group; 0 when it is in no group. */
    val selfId: Long

    /** A payload sent now can reach someone: a link is open and, for a server group, the server answers. */
    val canSend: Boolean

    /** The way out is busy (writes to the server waiting): a payload sent now would delay the positions. */
    val busy: Boolean

    /** Sends [body] (at most [Msg.Diag.MAX_BYTES]) to every member; `false` when it cannot be sent. */
    fun sendDiag(body: ByteArray): Boolean

    /** Called with each payload another member sent, from any thread. */
    var diagReceiver: ((member: Long, body: ByteArray) -> Unit)?
}
