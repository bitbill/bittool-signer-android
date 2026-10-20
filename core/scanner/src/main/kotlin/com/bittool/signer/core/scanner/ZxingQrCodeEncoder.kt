package com.bittool.signer.core.scanner

import com.bittool.signer.core.model.QrCodeEncoder
import com.bittool.signer.core.model.QrEncodingException
import com.bittool.signer.core.model.QrErrorCorrection
import com.bittool.signer.core.model.QrMatrix
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import javax.inject.Inject

/**
 * The app's QR encoder: a port of iOS `QRRenderer.image(for:)` (`Design/QRCodeView.swift:11-19`) on ZXing core.
 *
 * - Error correction **M** when the frame is at most 600 UTF-8 bytes, otherwise **L** ([QrErrorCorrection.forPayload],
 *   `QRCodeView.swift:15`): short payloads get more redundancy, dense animated parts stay scannable.
 * - The text is encoded exactly as given: never trimmed, re-cased or normalised.
 * - ZXing picks numeric, alphanumeric or byte mode for the whole text, like CoreImage: upper-case UR frames
 *   (`UR:CRYPTO-PSBT/…`) use the denser alphanumeric mode (700 upper-case characters at L → version 15, 77 modules,
 *   CoreImage's measured 79 × 79 px with its quiet zone; design-system.md §5.12).
 * - `CHARACTER_SET = UTF-8` only when the text contains non-ASCII characters (design-system.md §9.5). ZXing writes an
 *   ECI header whenever that hint is present in byte mode, which would change the symbol version of every ASCII
 *   frame; without the hint ZXing's byte mode is ISO-8859-1, which is byte-identical to ASCII. Non-ASCII text (for
 *   example a recovery phrase in a non-English wordlist) therefore carries ECI 26 so every reader decodes it as UTF-8.
 * - The returned [QrMatrix] has **no quiet zone**; renderers add CoreImage's one-module margin
 *   ([QrRendering.QUIET_ZONE_MODULES]) inside the white plate.
 *
 * Stateless and thread-safe; CPU-bound, so call it off the main thread (`QrCodeView` uses `Dispatchers.Default`).
 * Bound to [QrCodeEncoder] by [ScannerModule].
 */
class ZxingQrCodeEncoder @Inject constructor() : QrCodeEncoder {

    /** @throws QrEncodingException when the text does not fit a version-40 symbol at the chosen level. */
    override fun encode(text: String): QrMatrix {
        val level = when (QrErrorCorrection.forPayload(text)) {
            QrErrorCorrection.M -> ErrorCorrectionLevel.M
            QrErrorCorrection.L -> ErrorCorrectionLevel.L
        }
        val hints = if (text.isAscii()) null else UTF8_HINTS
        val code = try {
            Encoder.encode(text, level, hints)
        } catch (e: WriterException) {
            throw QrEncodingException("The text does not fit a QR code", e)
        }
        val matrix = code.matrix
        val size = matrix.width
        val modules = BooleanArray(size * size)
        for (y in 0 until size) {
            val row = matrix.array[y]
            for (x in 0 until size) modules[y * size + x] = row[x].toInt() == 1
        }
        return QrMatrix(size, modules)
    }

    private fun String.isAscii(): Boolean {
        for (char in this) if (char.code > 0x7F) return false
        return true
    }

    private companion object {
        val UTF8_HINTS: Map<EncodeHintType, Any> = mapOf(EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name())
    }
}
