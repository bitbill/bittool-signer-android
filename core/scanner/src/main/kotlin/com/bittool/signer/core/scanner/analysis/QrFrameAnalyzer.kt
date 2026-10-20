package com.bittool.signer.core.scanner.analysis

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.Buffer
import java.nio.BufferUnderflowException

/**
 * CameraX analyzer feeding [QrFrameDecoder] (scan-sign.md §23.3). Runs on its own single-thread executor, separate
 * from the engine thread; with `STRATEGY_KEEP_ONLY_LATEST` the camera never queues frames behind a slow decode.
 *
 * - Reads the **Y plane only** (pixel stride 1 for `YUV_420_888`; no colour conversion): in place when the plane is
 *   a packed heap array, otherwise into one reused packed buffer with a single bulk copy when the row stride equals
 *   the width, or one bulk copy per row. The frame is not rotated (QR decoding is rotation-free) and not cropped
 *   (iOS scans the full frame).
 * - [onValues] is called on the analysis thread, only for frames in which at least one QR code was read, with the
 *   "prefer readable" values; the caller posts them to the main thread for `QrScanFrameGate`.
 * - [enabled] false (the camera session was released while a frame was queued) skips the decoding work entirely.
 * - Battery: while codes are being read every frame is analysed (animated codes need every part); after
 *   [IDLE_AFTER_MILLIS] without any code, frames are analysed at most every [IDLE_FRAME_INTERVAL_MILLIS] (~15 fps)
 *   so a camera pointed at nothing does not keep a core busy. The first frame starts in the fast mode.
 *
 * The image is always closed, also when decoding fails.
 */
internal class QrFrameAnalyzer(
    private val onValues: (List<String?>) -> Unit,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer {
    private val decoder = QrFrameDecoder()
    private var luma = ByteArray(0)
    private var lastReadAt: Long? = null
    private var lastAnalyzedAt: Long? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            if (!enabled()) return
            val now = clock()
            val lastRead = lastReadAt ?: now.also { lastReadAt = it }
            val lastAnalyzed = lastAnalyzedAt
            if (now - lastRead > IDLE_AFTER_MILLIS && lastAnalyzed != null && now - lastAnalyzed < IDLE_FRAME_INTERVAL_MILLIS) {
                return
            }
            lastAnalyzedAt = now
            val width = image.width
            val height = image.height
            val plane = image.planes.firstOrNull() ?: return
            val frame = copyLuma(plane, width, height) ?: return
            val values = decoder.decode(frame, width, height) ?: return
            lastReadAt = now
            onValues(values)
        }
    }

    /**
     * The Y plane as packed `width × height` bytes, or null for a malformed plane: the plane's own array when it is
     * a tightly packed heap buffer (no copy), else the reused buffer. Camera planes are direct buffers, so this is one
     * copy per frame, without allocation once the size is known.
     */
    private fun copyLuma(plane: ImageProxy.PlaneProxy, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val size = width * height
        val buffer = plane.buffer
        if (plane.pixelStride == 1 && plane.rowStride == width && buffer.hasArray() && buffer.arrayOffset() == 0 &&
            buffer.array().size == size
        ) {
            return buffer.array()
        }
        if (luma.size != size) luma = ByteArray(size)
        val out = luma
        // Positioned through java.nio.Buffer: the covariant ByteBuffer.position(int) only exists from API 34.
        val cursor: Buffer = buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        try {
            when {
                pixelStride == 1 && rowStride == width -> {
                    cursor.position(0)
                    buffer.get(out, 0, size)
                }
                pixelStride == 1 -> for (y in 0 until height) {
                    cursor.position(y * rowStride)
                    buffer.get(out, y * width, width)
                }
                else -> for (y in 0 until height) {
                    val row = y * rowStride
                    val target = y * width
                    for (x in 0 until width) out[target + x] = buffer.get(row + x * pixelStride)
                }
            }
        } catch (e: BufferUnderflowException) {
            return null
        } catch (e: IndexOutOfBoundsException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return out
    }

    companion object {
        /** No code read for this long → idle frame pacing. */
        const val IDLE_AFTER_MILLIS = 1_000L

        /** Minimum time between analysed frames while idle. */
        const val IDLE_FRAME_INTERVAL_MILLIS = 66L
    }
}
