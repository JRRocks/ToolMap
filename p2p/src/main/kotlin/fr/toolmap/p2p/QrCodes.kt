package fr.toolmap.p2p

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as a grid of modules, without its quiet zone. */
class QrMatrix(
    val size: Int,
    private val dark: BooleanArray,
) {
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    /** The QR version (1..40) the size corresponds to. */
    val version: Int get() = (size - MODULES_V1) / MODULES_PER_VERSION + 1

    private companion object {
        const val MODULES_V1 = 21
        const val MODULES_PER_VERSION = 4
    }
}

/** QR codes through ZXing, without Google Play Services. */
object QrCodes {
    /** Encodes [text] with level M error correction; alphanumeric text uses the dense alphanumeric mode. */
    fun encode(text: String): QrMatrix {
        val m =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
            )
        val dark = BooleanArray(m.width * m.height) { m[it % m.width, it / m.width] }
        return QrMatrix(m.width, dark)
    }

    /**
     * Reads a QR code in a frame of 8-bit luminance values (the Y plane of a camera image): [data] has [rowStride]
     * bytes per row, the image is [width] × [height]. `null` when there is no readable code, which is the usual case
     * for most frames.
     */
    fun decode(
        data: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        val hints =
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        for (candidate in listOf(source, source.invert())) {
            try {
                return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (e: NotFoundException) {
                continue
            } catch (e: ChecksumException) {
                continue
            } catch (e: FormatException) {
                continue
            }
        }
        return null
    }
}
