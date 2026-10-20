package com.bittool.signer.core.scanner.analysis

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import com.bittool.signer.core.scanner.testing.LumaFrame
import com.bittool.signer.core.scanner.testing.Modules
import java.nio.ByteBuffer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CameraX side: Y-plane copies (row and pixel strides), enabling, image closing and idle frame pacing. */
class QrFrameAnalyzerTest {
    private var now = 10_000L
    private val received = ArrayList<List<String?>>()
    private var enabled = true
    private val analyzer = QrFrameAnalyzer(onValues = { received += it }, enabled = { enabled }, clock = { now })

    private val code = LumaFrame(640, 480).paint(Modules.text("UR:BYTES/1-2/ANALYZER"), 60, 40, 6)
    private val empty = LumaFrame(640, 480)

    @Test
    fun aPaddedYPlaneIsCompactedBeforeDecoding() {
        // Many devices pad rows (row stride > width); the padding must never reach the decoder as pixels.
        val plane = FakePlane(ByteBuffer.allocateDirect(code.withRowStride(704, padding = 0).size).apply {
            put(code.withRowStride(704, padding = 0))
            rewind()
        }, rowStride = 704, pixelStride = 1)
        val image = FakeImageProxy(640, 480, plane)
        analyzer.analyze(image)
        assertEquals(listOf(listOf<String?>("UR:BYTES/1-2/ANALYZER")), received)
        assertTrue(image.closed)
    }

    @Test
    fun aTightDirectYPlaneIsCopiedInOneGoIntoAReusedBuffer() {
        fun direct(source: LumaFrame) = ByteBuffer.allocateDirect(source.luma.size).apply {
            put(source.luma)
            rewind()
        }
        val image = FakeImageProxy(640, 480, FakePlane(direct(code), rowStride = 640, pixelStride = 1))
        analyzer.analyze(image)
        // The same buffer serves the next frames of the same size; stale pixels never leak into a new frame.
        analyzer.analyze(FakeImageProxy(640, 480, FakePlane(direct(empty), 640, 1)))
        analyzer.analyze(FakeImageProxy(640, 480, FakePlane(direct(code), 640, 1)))
        assertEquals(2, received.size)
        assertTrue(image.closed)
    }

    @Test
    fun aPackedHeapPlaneIsDecodedInPlaceAndLeftUnchanged() {
        val pixels = code.luma.copyOf()
        analyzer.analyze(FakeImageProxy(640, 480, FakePlane(ByteBuffer.wrap(pixels), rowStride = 640, pixelStride = 1)))
        assertEquals(listOf(listOf<String?>("UR:BYTES/1-2/ANALYZER")), received)
        assertTrue(pixels.contentEquals(code.luma))
    }

    @Test
    fun anInterleavedPlaneIsReadPixelByPixel() {
        val (bytes, rowStride) = code.withPixelStride(2)
        analyzer.analyze(FakeImageProxy(640, 480, FakePlane(ByteBuffer.wrap(bytes), rowStride, pixelStride = 2)))
        assertEquals(listOf(listOf<String?>("UR:BYTES/1-2/ANALYZER")), received)
    }

    @Test
    fun framesWithoutCodesAreNotReported() {
        val image = FakeImageProxy(640, 480, FakePlane(ByteBuffer.wrap(empty.luma.copyOf()), 640, 1))
        analyzer.analyze(image)
        assertTrue(received.isEmpty())
        assertTrue(image.closed)
    }

    @Test
    fun disabledAnalysisSkipsDecodingButClosesTheImage() {
        enabled = false
        val image = FakeImageProxy(640, 480, FakePlane(ByteBuffer.wrap(code.luma.copyOf()), 640, 1))
        analyzer.analyze(image)
        assertTrue(received.isEmpty())
        assertTrue(image.closed)
    }

    @Test
    fun aMalformedPlaneIsSkippedAndClosed() {
        val image = FakeImageProxy(640, 480, FakePlane(ByteBuffer.allocate(1_000), 640, 1))
        analyzer.analyze(image)
        assertTrue(received.isEmpty())
        assertTrue(image.closed)
    }

    @Test
    fun idleFramesArePacedButCodesRestoreTheFullRate() {
        fun frame(source: LumaFrame) = FakeImageProxy(640, 480, FakePlane(ByteBuffer.wrap(source.luma.copyOf()), 640, 1))
        // The first second runs at full rate.
        analyzer.analyze(frame(empty))
        now += 900
        analyzer.analyze(frame(empty))
        now += 33
        analyzer.analyze(frame(code))
        assertEquals(1, received.size)
        // More than a second without codes: a frame 33 ms after the last analysed one is skipped …
        now += 1_100
        analyzer.analyze(frame(empty))
        now += 33
        val skipped = frame(code)
        analyzer.analyze(skipped)
        assertEquals(1, received.size)
        assertTrue(skipped.closed)
        // … while one 66 ms after it is analysed, and reading a code returns to every frame.
        now += 33
        analyzer.analyze(frame(code))
        assertEquals(2, received.size)
        now += 33
        analyzer.analyze(frame(code))
        assertEquals(3, received.size)
    }

    @Test
    fun onAPhoneSlowerThanTheFrameRateIdleAnalysisStaysUnderHalfACore() {
        // An empty 1080p frame costs both polarities; say 80 ms on a low-end phone, more than the 66 ms idle interval.
        // With KEEP_ONLY_LATEST the next frame is handed over as soon as an analysis returns, so a start-to-start
        // interval alone would never skip one and the decoder would run back to back.
        var clock = 0L
        var codeInView = false
        var busy = 0L
        val reads = ArrayList<List<String?>>()
        val slow = QrFrameAnalyzer(
            onValues = { reads += it },
            clock = { clock },
            decode = { _, _, _ ->
                clock += 80
                busy += 80
                if (codeInView) listOf("UR:BYTES/1-2/SLOW") else null
            },
        )
        fun runFor(millis: Long) {
            val end = clock + millis
            while (clock < end) {
                val before = clock
                slow.analyze(FakeImageProxy(64, 48, FakePlane(ByteBuffer.wrap(ByteArray(64 * 48)), 64, 1)))
                // Skipped: the next camera frame comes one frame period later; analysed: the latest frame at once.
                if (clock == before) clock += 33 else clock += 1
            }
        }
        runFor(2_000) // past the first second, which runs at full rate
        val idleStart = clock
        val busyBefore = busy
        runFor(10_000)
        val duty = (busy - busyBefore).toDouble() / (clock - idleStart)
        assertTrue(duty <= 0.51, "idle duty cycle $duty")
        assertTrue(duty >= 0.3, "idle analysis must keep looking for codes ($duty)")
        // A code in view is found within one idle gap and then every frame is analysed again.
        codeInView = true
        runFor(400)
        val first = reads.size
        assertTrue(first >= 1)
        val busyFast = busy
        val fastStart = clock
        runFor(2_000)
        assertTrue((busy - busyFast).toDouble() / (clock - fastStart) > 0.95, "reading runs back to back")
    }
}

private class FakePlane(private val buffer: ByteBuffer, private val rowStride: Int, private val pixelStride: Int) :
    ImageProxy.PlaneProxy {
    override fun getRowStride(): Int = rowStride
    override fun getPixelStride(): Int = pixelStride
    override fun getBuffer(): ByteBuffer = buffer
}

private class FakeImageProxy(private val width: Int, private val height: Int, private val y: FakePlane) : ImageProxy {
    var closed = false
        private set

    override fun close() {
        closed = true
    }

    override fun getCropRect(): Rect = throw UnsupportedOperationException()
    override fun setCropRect(rect: Rect?) = Unit
    override fun getFormat(): Int = ImageFormat.YUV_420_888
    override fun getHeight(): Int = height
    override fun getWidth(): Int = width
    override fun getPlanes(): Array<ImageProxy.PlaneProxy> = arrayOf(y, y, y)
    override fun getImageInfo(): ImageInfo = throw UnsupportedOperationException()

    @ExperimentalGetImage
    override fun getImage(): Image? = null
}
