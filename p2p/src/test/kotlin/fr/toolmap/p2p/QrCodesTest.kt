package fr.toolmap.p2p

import fr.toolmap.core.p2p.CandidateType
import fr.toolmap.core.p2p.DtlsSetup
import fr.toolmap.core.p2p.IceCandidate
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PairingKind
import fr.toolmap.core.p2p.PairingPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Draws [m] as a camera would see it: [module] pixels per module, a quiet zone of four modules, 8-bit luminance. */
private fun render(
    m: QrMatrix,
    module: Int,
    quiet: Int = 4,
): Triple<ByteArray, Int, Int> {
    val side = (m.size + 2 * quiet) * module
    val luma = ByteArray(side * side) { 255.toByte() }
    for (y in 0 until side) {
        for (x in 0 until side) {
            val mx = x / module - quiet
            val my = y / module - quiet
            if (m[mx, my]) luma[y * side + x] = 0
        }
    }
    return Triple(luma, side, side)
}

class QrCodesTest {
    private val payload =
        PairingPayload(
            PairingKind.OFFER,
            1_790_000_000,
            ByteArray(16) { (it + 1).toByte() },
            ByteArray(32) { (200 - it).toByte() },
            0x0123456789ABCDEFL,
            "Jean",
            4,
            "aB3d",
            "x7Yq0pLm9Rt2VnB4cD6eF8gH",
            ByteArray(32) { (it * 5).toByte() },
            DtlsSetup.ACTPASS,
            listOf(
                IceCandidate(CandidateType.HOST, byteArrayOf(192.toByte(), 168.toByte(), 1, 23), 51_234, 0),
                IceCandidate(CandidateType.HOST, byteArrayOf(10, 0, 0, 5), 52_000, 1),
                IceCandidate(CandidateType.SRFLX, byteArrayOf(203.toByte(), 0, 113, 7), 40_001, 2),
            ),
        )

    @Test
    fun `a pairing code goes through the QR image and comes back the same`() {
        val text = PairingCodec.toQrText(payload)
        val matrix = QrCodes.encode(text)
        assertTrue(matrix.version in 1..15, "version ${matrix.version}")
        val (luma, w, h) = render(matrix, module = 6)
        val read = QrCodes.decode(luma, w, w, h)
        assertEquals(text, read)
        assertEquals(payload, PairingCodec.parse(read!!))
    }

    @Test
    fun `a smaller image and a row stride wider than the image are still read`() {
        val text = PairingCodec.toQrText(payload)
        val (luma, w, h) = render(QrCodes.encode(text), module = 4)
        val stride = w + 24
        val padded = ByteArray(stride * h) { 128.toByte() }
        for (y in 0 until h) System.arraycopy(luma, y * w, padded, y * stride, w)
        assertEquals(text, QrCodes.decode(padded, stride, w, h))
    }

    @Test
    fun `a code printed white on black is read as well`() {
        val text = PairingCodec.toQrText(payload)
        val (luma, w, h) = render(QrCodes.encode(text), module = 6)
        for (i in luma.indices) luma[i] = (255 - (luma[i].toInt() and 0xFF)).toByte()
        assertEquals(text, QrCodes.decode(luma, w, w, h))
    }

    @Test
    fun `an image without a code gives null, and so does noise`() {
        assertNull(QrCodes.decode(ByteArray(200 * 200) { 255.toByte() }, 200, 200, 200))
        val noise = ByteArray(200 * 200).also { java.util.Random(1).nextBytes(it) }
        assertNull(QrCodes.decode(noise, 200, 200, 200))
    }

    @Test
    fun `a code dense with candidates stays readable`() {
        val many =
            PairingPayload(
                PairingKind.OFFER,
                payload.createdAtSec,
                payload.groupId,
                payload.groupKey,
                payload.memberId,
                payload.name,
                payload.color,
                payload.iceUfrag,
                payload.icePwd,
                payload.dtlsFingerprint,
                payload.dtlsSetup,
                (0 until 6).map {
                    IceCandidate(CandidateType.HOST, byteArrayOf(10, 0, it.toByte(), 9), 50_000 + it, it)
                } +
                    IceCandidate(CandidateType.HOST, ByteArray(16) { (it + 1).toByte() }, 5, 6),
            )
        val text = PairingCodec.toQrText(many)
        val matrix = QrCodes.encode(text)
        assertTrue(matrix.version <= 15, "version ${matrix.version}")
        val (luma, w, h) = render(matrix, module = 5)
        assertEquals(text, QrCodes.decode(luma, w, w, h))
    }
}
