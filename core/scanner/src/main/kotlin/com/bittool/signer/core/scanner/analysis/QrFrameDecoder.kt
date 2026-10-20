package com.bittool.signer.core.scanner.analysis

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.DetectorResult
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.detector.MultiDetector
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads every QR code in one luminance frame: the ZXing side of iOS `metadataOutput(_:didOutput:from:)`
 * (`Features/Sign/QRScannerView.swift:357-371`; scan-sign.md §23.3). Not thread-safe: one instance per analysis thread
 * (it reuses its inversion buffer and remembers which polarity read last).
 *
 * Per frame:
 * 1. Binarise once (`HybridBinarizer`, full frame like iOS's default region of interest) and run ZXing's multi-code
 *    detector, decoding each candidate symbol, so **several codes in one frame are all seen** (a binary code never
 *    hides a readable request next to it). When it finds no candidate at all, the single-code detector gets one more
 *    try: its finder-pattern selection tolerates more perspective than the multi detector's 10 % geometry cut-offs.
 * 2. Nothing read → the same on the **inverted** frame (light-on-dark codes). The polarity that read last is tried
 *    first on the next frame, so an inverted animated code costs one pass per part, not two. When the first pass
 *    located a symbol but could not decode it (motion blur between animated parts, the common failure), the second
 *    polarity is tried on every other such frame only: the code is almost certainly of the first polarity, and the
 *    saved pass goes to the next camera frame. An inverted code next to a spurious normal-polarity candidate is
 *    therefore still read, one frame later at most.
 * 3. Each decoded code's value is its text, or `null` when any byte segment is not **strict** UTF-8 (iOS
 *    `stringValue == nil`: Compact SeedQR, arbitrary binary). ZXing alone would substitute U+FFFD or guess
 *    Shift_JIS; byte segments are decoded as UTF-8 unless the symbol declares another ECI.
 * 4. iOS's "prefer readable" rule: every distinct readable value in detection order, or `[null]` when the frame only
 *    holds unreadable codes.
 *
 * Measured on a desktop JVM at 1920 × 1080: ~5 ms for a frame with a dense 81-module code, ~7 ms for an empty frame,
 * up to ~40 ms for a very noisy frame without a readable code (both polarities). No code text is ever logged.
 */
internal class QrFrameDecoder {
    private val decoder = Decoder()
    private val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var inverted = ByteArray(0)
    private var invertedFirst = false
    private var skipSecondPolarity = false

    /**
     * The values of the QR codes read in a packed [width] × [height] luminance frame (see the class rules), or `null`
     * when no QR code could be read at all. [luma] is not modified.
     */
    fun decode(luma: ByteArray, width: Int, height: Int): List<String?>? {
        require(width > 0 && height > 0 && luma.size >= width * height) { "invalid frame" }
        val firstInverted = invertedFirst
        for (pass in 0..1) {
            val invert = if (pass == 0) firstInverted else !firstInverted
            val pixels = if (invert) invertedCopy(luma, width * height) else luma
            val read = readAll(BinaryBitmap(HybridBinarizer(LumaSource(pixels, width, height))))
            if (read.values.isNotEmpty()) {
                invertedFirst = invert
                val readable = read.values.filterNotNull().distinct()
                return readable.ifEmpty { listOf(null) }
            }
            if (pass == 0 && read.located) {
                skipSecondPolarity = !skipSecondPolarity
                if (skipSecondPolarity) return null
            }
        }
        return null
    }

    /** What one polarity gave: the values of the decoded codes, and whether any symbol was located at all. */
    private class Pass(val values: List<String?>, val located: Boolean)

    /** The value of every code decoded in [bitmap], in detection order (null entries = codes without text). */
    private fun readAll(bitmap: BinaryBitmap): Pass {
        val matrix: BitMatrix = try {
            bitmap.blackMatrix
        } catch (e: NotFoundException) {
            return Pass(emptyList(), located = false)
        }
        var detections: Array<DetectorResult> = try {
            MultiDetector(matrix).detectMulti(DETECT_HINTS)
        } catch (e: ReaderException) {
            emptyArray()
        } catch (e: RuntimeException) {
            // ZXing's geometry occasionally throws on degenerate candidates; a camera loop must never die on one.
            emptyArray()
        }
        if (detections.isEmpty()) {
            val single = try {
                Detector(matrix).detect(DETECT_HINTS)
            } catch (e: ReaderException) {
                null
            } catch (e: RuntimeException) {
                null
            }
            if (single != null) detections = arrayOf(single)
        }
        val values = ArrayList<String?>(detections.size)
        for (detection in detections) decodeOrNull(detection)?.let { values += it.value }
        return Pass(values, located = detections.isNotEmpty())
    }

    private class Decoded(val value: String?)

    private fun decodeOrNull(detection: DetectorResult): Decoded? {
        val result = try {
            decoder.decode(detection.bits, DECODE_HINTS)
        } catch (e: ReaderException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        val readable = result.byteSegments?.all(::isStrictUtf8) ?: true
        return Decoded(if (readable) result.text else null)
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean = try {
        utf8.reset()
        utf8.decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun invertedCopy(luma: ByteArray, length: Int): ByteArray {
        if (inverted.size != length) inverted = ByteArray(length)
        val out = inverted
        for (i in 0 until length) out[i] = (luma[i].toInt() xor 0xFF).toByte()
        return out
    }

    private companion object {
        /** Detection runs without TRY_HARDER: row skipping keeps 1080p frames fast; the next frame is ~33 ms away. */
        val DETECT_HINTS: Map<DecodeHintType, Any> = emptyMap()

        /** Byte segments without an ECI header are read as UTF-8 (strictness is checked separately). */
        val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(DecodeHintType.CHARACTER_SET to Charsets.UTF_8.name())
    }
}
