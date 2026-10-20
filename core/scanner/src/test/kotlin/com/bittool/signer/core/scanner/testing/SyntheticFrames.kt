package com.bittool.signer.core.scanner.testing

import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.ZxingQrCodeEncoder
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.common.DecoderResult
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.random.Random

/** Module grid of a QR symbol, without quiet zone. */
internal class Modules(val size: Int, private val dark: (Int, Int) -> Boolean) {
    operator fun get(x: Int, y: Int): Boolean = dark(x, y)

    /** The same symbol seen in a mirror (front cameras). */
    fun mirrored(): Modules = Modules(size) { x, y -> dark(size - 1 - x, y) }

    /** The same symbol turned by 90°. */
    fun rotated(): Modules = Modules(size) { x, y -> dark(y, size - 1 - x) }

    /** ZXing's own decoding of the symbol: EC level, ECI (symbology modifier) and text, for parity checks. */
    fun decodeWithZxing(): DecoderResult {
        val bits = BitMatrix(size)
        for (y in 0 until size) for (x in 0 until size) if (dark(x, y)) bits.set(x, y)
        return Decoder().decode(bits)
    }

    companion object {
        fun of(matrix: QrMatrix): Modules = Modules(matrix.size) { x, y -> matrix[x, y] }

        /** The app encoder's symbol for [text]. */
        fun text(text: String): Modules = of(ZxingQrCodeEncoder().encode(text))

        /**
         * A byte-mode symbol carrying exactly [bytes] with **no ECI header** (ZXing's default ISO-8859-1 maps each
         * byte to one character): how other encoders write binary payloads such as a Compact SeedQR, or UTF-8 text
         * without declaring it.
         */
        fun bytes(bytes: ByteArray, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): Modules {
            val code = Encoder.encode(String(bytes, Charsets.ISO_8859_1), level, null)
            val matrix = code.matrix
            return Modules(matrix.width) { x, y -> matrix.get(x, y).toInt() == 1 }
        }
    }
}

/** An 8-bit luminance camera frame (row-major, packed) on which test codes are painted. */
internal class LumaFrame(val width: Int, val height: Int, background: Int = 170) {
    val luma = ByteArray(width * height) { background.toByte() }

    /** Paints [modules] with a [quiet]-module quiet zone, [scale] pixels per module, top-left of the zone at ([left], [top]). */
    fun paint(
        modules: Modules,
        left: Int,
        top: Int,
        scale: Int,
        dark: Int = 30,
        light: Int = 225,
        quiet: Int = 4,
    ): LumaFrame {
        val side = (modules.size + 2 * quiet) * scale
        require(left >= 0 && top >= 0 && left + side <= width && top + side <= height) { "code does not fit the frame" }
        for (py in 0 until side) {
            val my = py / scale - quiet
            for (px in 0 until side) {
                val mx = px / scale - quiet
                val isDark = mx in 0 until modules.size && my in 0 until modules.size && modules[mx, my]
                luma[(top + py) * width + left + px] = (if (isDark) dark else light).toByte()
            }
        }
        return this
    }

    /** Light modules on a dark plate, as on a dark-mode screen. */
    fun paintInverted(modules: Modules, left: Int, top: Int, scale: Int): LumaFrame =
        paint(modules, left, top, scale, dark = 225, light = 25)

    /** A 3 × 3 box blur and ±[amplitude] uniform noise: a slightly soft, grainy sensor image. */
    fun soften(amplitude: Int = 18, seed: Int = 7): LumaFrame {
        val source = luma.copyOf()
        val random = Random(seed)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = x + dx
                val sy = y + dy
                if (sx in 0 until width && sy in 0 until height) {
                    sum += source[sy * width + sx].toInt() and 0xFF
                    count++
                }
            }
            val noisy = sum / count + random.nextInt(-amplitude, amplitude + 1)
            luma[y * width + x] = noisy.coerceIn(0, 255).toByte()
        }
        return this
    }

    /** The frame as a camera Y plane whose rows are [rowStride] bytes long (padding filled with [padding]). */
    fun withRowStride(rowStride: Int, padding: Int = 0): ByteArray {
        require(rowStride >= width)
        val out = ByteArray(rowStride * (height - 1) + width) { padding.toByte() }
        for (y in 0 until height) System.arraycopy(luma, y * width, out, y * rowStride, width)
        return out
    }

    /** The frame as an interleaved plane with [pixelStride] bytes per pixel. */
    fun withPixelStride(pixelStride: Int): Pair<ByteArray, Int> {
        val rowStride = width * pixelStride
        val out = ByteArray(rowStride * height)
        for (y in 0 until height) for (x in 0 until width) out[y * rowStride + x * pixelStride] = luma[y * width + x]
        return out to rowStride
    }
}

/** A deterministic upper-case UR part of [length] characters (alphanumeric QR mode), like the engine's animated frames. */
internal fun urFrame(index: Int, count: Int, length: Int): String {
    val prefix = "UR:CRYPTO-PSBT/$index-$count/"
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val random = Random(index * 7919 + count)
    return prefix + String(CharArray(length - prefix.length) { alphabet[random.nextInt(alphabet.length)] })
}
