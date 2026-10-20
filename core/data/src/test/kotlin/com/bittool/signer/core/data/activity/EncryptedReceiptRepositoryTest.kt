package com.bittool.signer.core.data.activity

import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.data.doubles.AesGcmJournalCipher
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.ReceiptJournal
import com.bittool.signer.core.model.signing.SigningReceipt
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** The sealed Activity journal (DECISIONS.md §9.3, activity-settings.md §14.2; iOS `readReceipts`/`saveReceipts`). */
class EncryptedReceiptRepositoryTest {
    private val dir: File = Files.createTempDirectory("journal").toFile()
    private val sealer = AesGcmJournalCipher()
    private val opens = AtomicInteger()
    private val cipher = object : JournalCipher {
        override fun seal(plaintext: ByteArray) = sealer.seal(plaintext)
        override fun open(sealed: ByteArray): ByteArray {
            opens.incrementAndGet()
            return sealer.open(sealed)
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repository(test: TestScope) = EncryptedReceiptRepository(dir, cipher, StandardTestDispatcher(test.testScheduler))

    private fun receipt(title: String = "Send ETH") = SigningReceipt(
        id = UUID.randomUUID().toString(),
        date = 1_760_000_000_000L,
        chain = Chain.Evm,
        title = title,
        requestHash = "a".repeat(64),
        requestJson = """{"chain":"evm"}""",
        responseText = "UR:ETH-SIGNATURE/X",
        responseFrames = listOf("UR:ETH-SIGNATURE/X"),
    )

    private suspend fun assertInvalid(block: suspend () -> Unit) {
        val error = assertFailsWith<SignerException> { block() }
        assertEquals(UiText.Ios("Invalid activity record."), error.text)
    }

    @Test
    fun aMissingJournalIsEmptyAndAWrittenOneReadsBack() = runTest {
        val repository = repository(this)
        assertEquals(emptyList(), repository.read())
        val records = listOf(receipt("Newest"), receipt("Older"))
        repository.write(records)
        assertEquals(records, repository.read())
        assertEquals(File(dir, "receipts.bin"), repository.file)
    }

    @Test
    fun theFileIsSealedNotPlainJson() = runTest {
        val repository = repository(this)
        repository.write(listOf(receipt()))
        val bytes = repository.file.readBytes()
        assertTrue(bytes.copyOfRange(0, 5).contentEquals(AesGcmJournalCipher.HEADER), "magic BTSR + version 1")
        val text = bytes.decodeToString()
        assertFalse(text.contains("requestJSON") || text.contains("Send ETH") || text.contains("UR:ETH"))
    }

    @Test
    fun aTamperedOrUndecryptableJournalIsInvalidAndLeftAsItIs() = runTest {
        val repository = repository(this)
        repository.write(listOf(receipt()))
        val original = repository.file.readBytes()
        val flipped = original.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        repository.file.writeBytes(flipped)
        assertInvalid { repository.read() }
        assertTrue(repository.file.readBytes().contentEquals(flipped), "A failed read never rewrites the file.")
        repository.file.writeBytes(original)
        sealer.loseKey()
        assertInvalid { repository.read() }
    }

    @Test
    fun anOversizedSealedFileIsRefusedBeforeDecrypting() = runTest {
        val repository = repository(this)
        dir.mkdirs()
        repository.file.writeBytes(ByteArray(EncryptedReceiptRepository.MAX_SEALED_BYTES + 1))
        assertInvalid { repository.read() }
        assertEquals(0, opens.get(), "The size bound applies before any decryption.")
        assertEquals(ReceiptJournal.FILE_LIMIT + 33, EncryptedReceiptRepository.MAX_SEALED_BYTES)
    }

    @Test
    fun authenticContentThatBreaksAJournalRuleIsInvalid() = runTest {
        val repository = repository(this)
        dir.mkdirs()
        repository.file.writeBytes(sealer.seal("not json".encodeToByteArray()))
        assertInvalid { repository.read() }
        repository.write(List(ReceiptJournal.MAX_RECORDS + 1) { receipt() })
        assertInvalid { repository.read() }
        repository.write(listOf(receipt().copy(requestHash = "not-a-hash")))
        assertInvalid { repository.read() }
    }

    @Test
    fun aSymlinkIsNotFollowed() = runTest {
        val repository = repository(this)
        repository.write(listOf(receipt()))
        val target = File(dir, "elsewhere.bin")
        repository.file.renameTo(target)
        Files.createSymbolicLink(repository.file.toPath(), target.toPath())
        assertInvalid { repository.read() }
    }
}
