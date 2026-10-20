package com.bittool.signer.core.scanner.analysis

import com.bittool.signer.core.scanner.testing.LumaFrame
import com.bittool.signer.core.scanner.testing.Modules
import com.bittool.signer.core.scanner.testing.urFrame
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ZXing side of iOS `metadataOutput` (`QRScannerView.swift:357-371`; scan-sign.md §6.8, §23.3): strict UTF-8,
 * inverted codes, several codes per frame and the "prefer readable" rule, on synthetic camera frames.
 */
class QrFrameDecoderTest {
    private val decoder = QrFrameDecoder()

    private fun decode(frame: LumaFrame): List<String?>? = decoder.decode(frame.luma, frame.width, frame.height)

    private fun frameWith(modules: Modules, scale: Int = 6): LumaFrame = LumaFrame(640, 480).paint(modules, 60, 40, scale)

    @Test
    fun aFrameWithoutAnyCodeReadsNothing() {
        assertNull(decode(LumaFrame(640, 480)))
        assertNull(decode(LumaFrame(640, 480, background = 40).soften()))
    }

    @Test
    fun textIsDeliveredExactlyIncludingSurroundingWhitespace() {
        // iOS QRScannerTests `wrapped`: the engine validates the raw text, nothing is trimmed or re-cased.
        val wrapped = " \nUR:BYTES/TESTFRAME\n"
        assertEquals(listOf(wrapped), decode(frameWith(Modules.text(wrapped))))
        assertEquals(listOf("ur:bytes/lower"), decode(frameWith(Modules.text("ur:bytes/lower"))))
    }

    @Test
    fun utf8TextIsReadWithOrWithoutAnEciHeader() {
        val words = "ábaco abdomen abeja abierto abogado abono aborto abrazo abrir abuelo abuso acabar"
        // The app's encoder declares UTF-8 (ECI 26) for non-ASCII text.
        assertEquals(listOf(words), decode(frameWith(Modules.text(words), scale = 4)))
        // Other encoders often write UTF-8 bytes without ECI: ZXing alone would guess (e.g. Shift_JIS for Japanese).
        val japanese = "あいこくしん あいさつ あいだ あおぞら あかちゃん あきる"
        assertEquals(listOf(japanese), decode(frameWith(Modules.bytes(japanese.toByteArray(Charsets.UTF_8)), scale = 4)))
        val chinese = "的 一 是 在 不 了 有 和 人 这 中 大"
        assertEquals(listOf(chinese), decode(frameWith(Modules.bytes(chinese.toByteArray(Charsets.UTF_8)), scale = 5)))
    }

    @Test
    fun bytesThatAreNotStrictUtf8AreNotText() {
        // A Compact SeedQR is raw entropy: iOS reports `stringValue == nil` → "doesn't contain readable text".
        val compactSeedQr = byteArrayOf(
            0x8F.toByte(), 0x3A, 0xC1.toByte(), 0x07, 0xFE.toByte(), 0x55, 0x9D.toByte(), 0x20,
            0xE4.toByte(), 0x12, 0xB8.toByte(), 0x6C, 0x01, 0xF0.toByte(), 0x7B, 0xA9.toByte(),
        )
        assertEquals(listOf(null), decode(frameWith(Modules.bytes(compactSeedQr))))
        // A lone continuation byte, an overlong encoding and an encoded surrogate are all malformed UTF-8.
        for (bytes in listOf(
            byteArrayOf(0x41, 0x80.toByte(), 0x42),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte(), 0x41),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x41),
            byteArrayOf(0x41, 0xC3.toByte()),
        )) {
            assertEquals(listOf(null), decode(frameWith(Modules.bytes(bytes))), bytes.joinToString { "%02x".format(it) })
        }
    }

    @Test
    fun invertedCodesAreRead() {
        val text = "UR:CRYPTO-PSBT/1-3/LPADAXCFAXHLCY"
        val inverted = LumaFrame(640, 480, background = 25).paintInverted(Modules.text(text), 60, 40, 6)
        assertEquals(listOf(text), decode(inverted))
        // The polarity that read last goes first; a normal code right after still reads.
        assertEquals(listOf("after"), decode(frameWith(Modules.text("after"))))
        assertEquals(listOf(text), decode(inverted))
    }

    @Test
    fun everyCodeInAFrameIsSeen() {
        val frame = LumaFrame(1280, 720)
            .paint(Modules.text("UR:BYTES/1-2/FIRSTPART"), 80, 120, 7)
            .paint(Modules.text("UR:BYTES/2-2/SECONDPART"), 720, 160, 7)
        assertEquals(setOf("UR:BYTES/1-2/FIRSTPART", "UR:BYTES/2-2/SECONDPART"), decode(frame)?.toSet())
    }

    @Test
    fun aBinaryCodeNeverHidesAReadableOneInTheSameFrame() {
        val binary = Modules.bytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x9C.toByte(), 0x81.toByte(), 0x42))
        val frame = LumaFrame(1280, 720)
            .paint(binary, 80, 120, 8)
            .paint(Modules.text("cHNidP8BAHECAAAAAQ=="), 720, 140, 7)
        assertEquals(listOf("cHNidP8BAHECAAAAAQ=="), decode(frame))
    }

    @Test
    fun onlyUnreadableCodesGiveOneNonTextObservation() {
        val frame = LumaFrame(1280, 720)
            .paint(Modules.bytes(byteArrayOf(0x80.toByte(), 0x81.toByte(), 0x82.toByte())), 80, 120, 8)
            .paint(Modules.bytes(byteArrayOf(0xF8.toByte(), 0xF9.toByte(), 0xFA.toByte(), 0xFB.toByte())), 720, 140, 8)
        assertEquals(listOf(null), decode(frame))
    }

    @Test
    fun theSameTextTwiceInOneFrameIsOneValue() {
        val code = Modules.text("UR:BYTES/TWICE")
        val frame = LumaFrame(1280, 720).paint(code, 80, 120, 8).paint(code, 720, 140, 8)
        assertEquals(listOf("UR:BYTES/TWICE"), decode(frame))
    }

    @Test
    fun mirroredAndRotatedCodesAreRead() {
        val text = "mirror-and-rotation"
        assertEquals(listOf(text), decode(frameWith(Modules.text(text).mirrored())))
        assertEquals(listOf(text), decode(frameWith(Modules.text(text).rotated())))
    }

    @Test
    fun aDenseAnimatedUrPartIsReadFromASoft1080pFrame() {
        // Wallet Level pairing parts are ~831 characters at level L (wallets-pairing.md §13.9 item 3).
        // Held so that the code spans about half of the frame's short side (6 px per module), as in the viewfinder.
        val parts = (1..3).map { urFrame(it, 12, 831) }
        for (part in parts) {
            val modules = Modules.text(part)
            val frame = LumaFrame(1920, 1080, background = 120).paint(modules, 700, 200, 6).soften()
            assertEquals(listOf(part), decode(frame))
        }
        // Held further away: 4 px per module still reads from a sharp frame.
        val far = LumaFrame(1920, 1080, background = 120).paint(Modules.text(parts[0]), 760, 300, 4)
        assertEquals(listOf(parts[0]), decode(far))
    }

    @Test
    fun aBlurredPartThatCannotBeReadYieldsNothingAndTheNextSharpPartReads() {
        val part = urFrame(2, 12, 831)
        val blurred = LumaFrame(1920, 1080, background = 120).paint(Modules.text(part), 760, 300, 4).soften(amplitude = 18)
        assertNull(decode(blurred))
        assertNull(decode(blurred))
        val sharp = LumaFrame(1920, 1080, background = 120).paint(Modules.text(part), 760, 300, 6)
        assertEquals(listOf(part), decode(sharp))
    }

    @Test
    fun aDenseByteModeCodeAtLevelLIsRead() {
        val base64Psbt = "cHNidP8BAH" + "Q".repeat(1_400)
        val modules = Modules.bytes(base64Psbt.toByteArray(Charsets.US_ASCII), ErrorCorrectionLevel.L)
        val frame = LumaFrame(1920, 1080).paint(modules, 500, 60, 6)
        assertEquals(listOf(base64Psbt), decode(frame))
    }
}
