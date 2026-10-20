package com.bittool.signer.core.scanner

import com.bittool.signer.core.model.QrEncodingException
import com.bittool.signer.core.scanner.testing.Modules
import com.bittool.signer.core.scanner.testing.urFrame
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parity of the encoder with iOS `QRRenderer` (`Design/QRCodeView.swift:11-19`; design-system.md §5.12, §9.5):
 * error-correction level, quiet zone, ECI only for non-ASCII, mode selection and exact text.
 */
class ZxingQrCodeEncoderTest {
    private val encoder = ZxingQrCodeEncoder()

    private fun decoded(text: String) = Modules.of(encoder.encode(text)).decodeWithZxing()

    @Test
    fun framesUpTo600Utf8BytesUseLevelMAndLongerOnesLevelL() {
        assertEquals("M", decoded("a".repeat(600)).ecLevel)
        assertEquals("L", decoded("a".repeat(601)).ecLevel)
        // UTF-8 bytes, not UTF-16 units: 300 × "é" is exactly 600 bytes.
        assertEquals("M", decoded("é".repeat(300)).ecLevel)
        assertEquals("L", decoded("é".repeat(300) + "a").ecLevel)
        // Coin Level pairing (≤ 376 characters) is M; Wallet Level parts (831 characters) are L (wallets-pairing.md §3.4).
        assertEquals("M", decoded(urFrame(1, 1, 376)).ecLevel)
        assertEquals("L", decoded(urFrame(1, 12, 831)).ecLevel)
    }

    @Test
    fun theMatrixHasNoQuietZoneAndRenderingAddsCoreImagesOneModuleMargin() {
        val matrix = encoder.encode("hello")
        assertEquals(21, matrix.size) // version 1, modules only
        // The top-left finder pattern starts at the very first module.
        assertTrue(matrix[0, 0] && matrix[6, 0] && matrix[0, 6] && !matrix[1, 1] && matrix[2, 2])
        // CoreImage measured: version 1 → 23 × 23 px (one module of quiet zone on each side).
        assertEquals(23, QrRendering.sideOf(matrix))
        val pixels = QrRendering.pixels(matrix)
        assertEquals(23 * 23, pixels.size)
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        for (i in 0 until 23) {
            assertEquals(white, pixels[i], "top quiet zone")
            assertEquals(white, pixels[22 * 23 + i], "bottom quiet zone")
            assertEquals(white, pixels[i * 23], "left quiet zone")
            assertEquals(white, pixels[i * 23 + 22], "right quiet zone")
        }
        assertEquals(black, pixels[1 * 23 + 1])
        for (y in 0 until 21) for (x in 0 until 21) {
            assertEquals(if (matrix[x, y]) black else white, pixels[(y + 1) * 23 + x + 1])
        }
    }

    @Test
    fun asciiFramesCarryNoEciAndNonAsciiTextDeclaresUtf8() {
        // ZXing reports the ECI through the symbology modifier: ]Q1 without, ]Q2 with an ECI header.
        assertEquals(1, decoded("UR:BYTES/1-2/ASCII").symbologyModifier)
        assertEquals(1, decoded("cHNidP8BAHECAAAAAQ==").symbologyModifier)
        assertEquals(1, decoded("abandon ability able about above absent").symbologyModifier)
        val spanish = "ábaco abdomen abeja"
        val utf8 = decoded(spanish)
        assertEquals(2, utf8.symbologyModifier)
        assertEquals(spanish, utf8.text)
        val chinese = "的 一 是 在 不 了"
        assertEquals(chinese, decoded(chinese).text)
        // An ECI header on ASCII would change the symbol: with it, this 14-byte text would no longer fit version 1-M.
        assertEquals(21, encoder.encode("abcdefghijklmn").size)
    }

    @Test
    fun upperCaseUrFramesUseTheDenseAlphanumericMode() {
        // design-system.md §5.12: 700 upper-case characters at L → 79 × 79 px from CoreImage, i.e. version 15.
        val matrix = encoder.encode(urFrame(1, 9, 700))
        assertEquals(77, matrix.size)
        assertEquals(79, QrRendering.sideOf(matrix))
        // The same length in lower case needs byte mode and a larger symbol.
        assertTrue(encoder.encode(urFrame(1, 9, 700).lowercase()).size > 77)
    }

    @Test
    fun textIsEncodedExactlyAsGiven() {
        for (text in listOf(" \nUR:BYTES/TESTFRAME\n", "ur:crypto-psbt/1-3/lpad", "\tTabs\tand trailing spaces  ", "0123456789")) {
            assertEquals(text, decoded(text).text)
        }
    }

    @Test
    fun aTextBeyondVersion40ThrowsTheModelException() {
        assertFailsWith<QrEncodingException> { encoder.encode("a".repeat(3_000)) }
    }
}
