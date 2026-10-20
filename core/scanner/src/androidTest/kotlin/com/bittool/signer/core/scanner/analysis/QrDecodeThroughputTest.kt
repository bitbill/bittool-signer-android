package com.bittool.signer.core.scanner.analysis

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.scanner.ZxingQrCodeEncoder
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decoding speed on ART for the scanner's hardest regular input: a 12-part animated UR at Wallet Level density
 * (831 characters per part, 81 modules at level L) in 1920 × 1080 luminance frames, as CameraX delivers them
 * (scan-sign.md §23.3; target ≥ 10 frames per second on mid-range phones). Run on the shared emulator; the bound is
 * deliberately loose (it guards against regressions such as an extra full-frame pass, not device speed).
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeThroughputTest {
    private val encoder = ZxingQrCodeEncoder()

    @Test
    fun denseAnimatedUrPartsDecodeWellAboveTenFramesPerSecond() {
        val parts = (1..12).map { urPart(it, 12, 831) }
        val frames = parts.map { part -> paint(encoder.encode(part), scale = 6, seed = part.hashCode()) }
        val decoder = QrFrameDecoder()
        repeat(2) { frames.forEach { decoder.decode(it, WIDTH, HEIGHT) } } // warm up the JIT
        val start = SystemClock.elapsedRealtimeNanos()
        var read = 0
        repeat(ROUNDS) {
            frames.forEachIndexed { i, frame -> if (decoder.decode(frame, WIDTH, HEIGHT) == listOf(parts[i])) read++ }
        }
        val millisPerFrame = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / (ROUNDS * frames.size)
        Log.i(TAG, "dense UR part: %.1f ms per 1080p frame (%.0f fps)".format(millisPerFrame, 1_000 / millisPerFrame))
        assertEquals(ROUNDS * frames.size, read)
        assertTrue("decoding took $millisPerFrame ms per frame", millisPerFrame < 50.0)
    }

    @Test
    fun framesWithoutACodeStayCheap() {
        val random = Random(3)
        val frame = ByteArray(WIDTH * HEIGHT) { (110 + random.nextInt(-12, 13)).toByte() }
        val decoder = QrFrameDecoder()
        repeat(3) { decoder.decode(frame, WIDTH, HEIGHT) }
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(ROUNDS) { decoder.decode(frame, WIDTH, HEIGHT) }
        val millisPerFrame = (SystemClock.elapsedRealtimeNanos() - start) / 1e6 / ROUNDS
        Log.i(TAG, "empty frame: %.1f ms per 1080p frame".format(millisPerFrame))
        assertTrue("an empty frame took $millisPerFrame ms", millisPerFrame < 100.0)
    }

    /** The code at [scale] px per module with a 4-module quiet zone, on grey, softened by a 3 × 3 blur and noise. */
    private fun paint(matrix: QrMatrix, scale: Int, seed: Int): ByteArray {
        val frame = ByteArray(WIDTH * HEIGHT) { 120.toByte() }
        val quiet = 4
        val side = (matrix.size + 2 * quiet) * scale
        val left = (WIDTH - side) / 2
        val top = (HEIGHT - side) / 2
        for (py in 0 until side) for (px in 0 until side) {
            val mx = px / scale - quiet
            val my = py / scale - quiet
            val dark = mx in 0 until matrix.size && my in 0 until matrix.size && matrix[mx, my]
            frame[(top + py) * WIDTH + left + px] = (if (dark) 30 else 225).toByte()
        }
        val source = frame.copyOf()
        val random = Random(seed)
        for (y in 1 until HEIGHT - 1) for (x in 1 until WIDTH - 1) {
            var sum = 0
            for (dy in -1..1) for (dx in -1..1) sum += source[(y + dy) * WIDTH + x + dx].toInt() and 0xFF
            frame[y * WIDTH + x] = (sum / 9 + random.nextInt(-10, 11)).coerceIn(0, 255).toByte()
        }
        return frame
    }

    private fun urPart(index: Int, count: Int, length: Int): String {
        val prefix = "UR:BYTES/$index-$count/"
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = Random(index)
        return prefix + String(CharArray(length - prefix.length) { alphabet[random.nextInt(alphabet.length)] })
    }

    private companion object {
        const val TAG = "QrDecodeThroughput"
        const val WIDTH = 1920
        const val HEIGHT = 1080
        const val ROUNDS = 5
    }
}
