package com.bittool.signer.core.data.activity

import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.SignerDataDir
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.data.ReceiptRepository
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.ReceiptJournal
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.util.wipe
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Activity journal `@SignerDataDir/receipts.bin` (DECISIONS.md §9.3, activity-settings.md §14.2, gaps.md G7.2):
 * the iOS `receipts.json` content (`ReceiptJournal.encode`, newest first) sealed by [JournalCipher] (AES-GCM under a
 * non-exportable Keystore key) and written atomically with [AtomicFiles] — the Android equivalent of iOS
 * `completeFileProtection` (`AppStore.swift:592-600`).
 *
 * Reading (`AppStore.swift:541-553`):
 * - a missing file is an empty journal;
 * - the sealed file is bounded **before** decrypting: `ReceiptJournal.FILE_LIMIT + 33` bytes (magic 4, version 1,
 *   IV 12, tag 16), a non-regular file (symlink) is refused;
 * - the plaintext goes through `ReceiptJournal.decode` (every iOS limit);
 * - any failure — bad tag, unknown header, lost Keystore key, invalid content, I/O — throws "Invalid activity
 *   record.". The file is left as it is: it is never overwritten silently (the store reports it as unreadable and
 *   only the explicit "Clear Activity" replaces it).
 *
 * The plaintext is wiped after sealing and after decoding. Operations are serialised by one [Mutex] and run on [io].
 */
@Singleton
class EncryptedReceiptRepository @Inject constructor(
    @SignerDataDir dataDir: File,
    private val cipher: JournalCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReceiptRepository {

    /** `@SignerDataDir/receipts.bin`. */
    val file: File = File(dataDir, SignerFiles.RECEIPTS_FILE)

    private val mutex = Mutex()

    override suspend fun read(): List<SigningReceipt> = mutex.withLock {
        withContext(io) {
            val sealed = try {
                AtomicFiles.readBounded(file, MAX_SEALED_BYTES)
            } catch (e: Exception) {
                throw invalid(e)
            } ?: return@withContext emptyList()
            val plaintext = try {
                cipher.open(sealed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw invalid(e)
            }
            try {
                ReceiptJournal.decode(plaintext)
            } finally {
                plaintext.wipe()
            }
        }
    }

    override suspend fun write(records: List<SigningReceipt>) = mutex.withLock {
        withContext(io) {
            val plaintext = ReceiptJournal.encode(records)
            val sealed = try {
                cipher.seal(plaintext)
            } finally {
                plaintext.wipe()
            }
            AtomicFiles.write(file, sealed)
        }
    }

    companion object {
        /** Magic "BTSR" (4) + version (1) + IV (12) + GCM tag (16) around at most `ReceiptJournal.FILE_LIMIT` bytes. */
        const val ENVELOPE_OVERHEAD = 33
        const val MAX_SEALED_BYTES = ReceiptJournal.FILE_LIMIT + ENVELOPE_OVERHEAD

        private fun invalid(cause: Throwable) = SignerException(UiText.Ios("Invalid activity record."), cause)
    }
}
