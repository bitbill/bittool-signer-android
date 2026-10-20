package com.bittool.signer.core.scanner.analysis

import com.google.zxing.LuminanceSource

/**
 * A packed 8-bit luminance frame (row stride = [width]) handed to ZXing without another copy: [getMatrix] returns the
 * caller's buffer itself, which is all `HybridBinarizer` reads. The buffer is reused frame after frame by
 * [QrFrameAnalyzer], so a source must not outlive the decode it was made for.
 */
internal class LumaSource(private val luma: ByteArray, width: Int, height: Int) : LuminanceSource(width, height) {
    init {
        require(luma.size >= width * height) { "luma buffer smaller than the frame" }
    }

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        require(y in 0 until height) { "row $y outside the frame" }
        val out = if (row == null || row.size < width) ByteArray(width) else row
        System.arraycopy(luma, y * width, out, 0, width)
        return out
    }

    override fun getMatrix(): ByteArray = luma
}
