package com.bittool.signer.feature.sign.common

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

/** iOS `ScanHomeView.importFile` rules (`Features/Sign/ScanTab.swift:225-239`; scan-sign.md §5, §23.6). */
class RequestFileTest {
    @Test
    fun aBinaryPsbtBecomesPaddedBase64WithoutLineBreaks() {
        val psbt = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte(), 0x01, 0x00, 0x71, 0x02) + ByteArray(80) { it.toByte() }
        val text = RequestFile.text(psbt)
        assertEquals(java.util.Base64.getEncoder().encodeToString(psbt), text)
        assertEquals(false, text.contains('\n'))
        assert(text.startsWith("cHNidP8"))
    }

    @Test
    fun textFilesAreUsedUnchanged() {
        val ur = " UR:CRYPTO-PSBT/1-2/LPADAO\r\nUR:CRYPTO-PSBT/2-2/LPAOAO\n"
        assertEquals(ur, RequestFile.text(ur.toByteArray()))
        assertEquals("cHNidP8BAHECAAAAAQ==", RequestFile.text("cHNidP8BAHECAAAAAQ==".toByteArray()), "a Base64 PSBT text file")
        assertEquals("Ünïcödé ✓", RequestFile.text("Ünïcödé ✓".toByteArray()))
    }

    @Test
    fun anythingElseAsksForAPsbtOrTextFile() {
        val issue = assertFailsWith<AppIssue> { RequestFile.text(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0xC3.toByte(), 0x28)) }
        assertEquals(UiText.Ios("Choose a PSBT file or a text file containing a UR request."), issue.text)
        // A truncated magic is not a PSBT.
        assertFailsWith<AppIssue> { RequestFile.text(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFE.toByte(), 0xFF.toByte())) }
    }

    @Test
    fun theLimitIsDecimal512000Bytes() {
        val atLimit = ByteArray(RequestFile.MAX_BYTES) { 'A'.code.toByte() }
        assertEquals(RequestFile.MAX_BYTES, RequestFile.readBounded(ByteArrayInputStream(atLimit)).size)
        val over = assertFailsWith<AppIssue> { RequestFile.readBounded(ByteArrayInputStream(ByteArray(RequestFile.MAX_BYTES + 1))) }
        assertEquals(UiText.Ios("This file is larger than 512 KB."), over.text)
        assertFailsWith<AppIssue> { RequestFile.text(ByteArray(524_288)) }
    }

    @Test
    fun aStreamWithoutAKnownSizeIsNeverReadPastTheLimit() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 'A'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill('A'.code.toByte(), off, off + len)
                served += len
                return len
            }
        }
        assertFailsWith<AppIssue> { RequestFile.readBounded(endless) }
        assertEquals(RequestFile.MAX_BYTES + 1L, served)
    }

    @Test
    fun shortReadsAreAssembled() {
        val text = "UR:BYTES/" + "X".repeat(5_000)
        val trickle = object : InputStream() {
            private val bytes = text.toByteArray()
            private var at = 0
            override fun read(): Int = if (at < bytes.size) bytes[at++].toInt() else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (at >= bytes.size) return -1
                b[off] = bytes[at++]
                return 1
            }
        }
        assertEquals(text, RequestFile.text(RequestFile.readBounded(trickle)))
    }
}
