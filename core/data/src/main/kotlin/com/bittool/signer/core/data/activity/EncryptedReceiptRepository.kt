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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * A journal whose Keystore key is provably gone (QA L1: removing the screen lock deletes it) is the one exception to
 * "never overwritten silently": nothing could ever open it again, so [startOverIfKeyLost] removes it (the next write
 * seals under a new key) after recording the person's one-time notice in `receipts.restarted` (an empty marker, no
 * data). A damaged journal whose key is in place is never touched.
 *
 * The plaintext is wiped after sealing and after decoding. Operations are serialised by one [Mutex] and run on [io].
 * The journal is the only file [AtomicFiles] stages directly in `@SignerDataDir` (the vault and the sidecars stage in
 * their own folders, which their owners tidy), so the first operation removes `.pending-*` leftovers there: a process
 * that died between staging and renaming would otherwise leave up to 2 MiB behind for good.
 */
@Singleton
class EncryptedReceiptRepository @Inject constructor(
    @SignerDataDir private val dataDir: File,
    private val cipher: JournalCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReceiptRepository {

    /** `@SignerDataDir/receipts.bin`. */
    val file: File = File(dataDir, SignerFiles.RECEIPTS_FILE)

    /** `@SignerDataDir/receipts.restarted`: present while the notice of a started-again journal is pending (QA L1). */
    val restartNoticeFile: File = File(dataDir, RESTART_NOTICE_FILE)

    private val mutex = Mutex()
    private var tidied = false

    override suspend fun read(): List<SigningReceipt> = locked {
        val sealed = try {
            AtomicFiles.readBounded(file, MAX_SEALED_BYTES)
        } catch (e: Exception) {
            throw invalid(e)
        } ?: return@locked emptyList()
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

    override suspend fun write(records: List<SigningReceipt>) = locked {
        val plaintext = ReceiptJournal.encode(records)
        val sealed = try {
            cipher.seal(plaintext)
        } finally {
            plaintext.wipe()
        }
        AtomicFiles.write(file, sealed)
    }

    override suspend fun startOverIfKeyLost(): Boolean = locked {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@locked false
        // Proven here, under this lock, never taken from an earlier read: only a key that is gone for good lets a
        // journal be removed without the person's "Clear Activity".
        if (!cipher.discardLostKey()) return@locked false
        try {
            // The notice first: a journal must never restart without the person being told.
            AtomicFiles.write(restartNoticeFile, ByteArray(0))
            Files.deleteIfExists(file.toPath())
            true
        } catch (e: IOException) {
            // Nothing restarted: the next load proves the loss again and retries; no notice without a restart.
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) AtomicFiles.delete(restartNoticeFile)
            false
        }
    }

    override suspend fun hasRestartNotice(): Boolean = locked { Files.exists(restartNoticeFile.toPath(), LinkOption.NOFOLLOW_LINKS) }

    override suspend fun acknowledgeRestartNotice() = locked {
        try {
            Files.deleteIfExists(restartNoticeFile.toPath())
        } catch (_: IOException) {
            // Shown again after the next launch; harmless.
        }
        Unit
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        withContext(io) {
            if (!tidied) {
                tidied = true
                AtomicFiles.deleteStalePending(dataDir)
            }
            block()
        }
    }

    companion object {
        /** Magic "BTSR" (4) + version (1) + IV (12) + GCM tag (16) around at most `ReceiptJournal.FILE_LIMIT` bytes. */
        const val ENVELOPE_OVERHEAD = 33
        const val MAX_SEALED_BYTES = ReceiptJournal.FILE_LIMIT + ENVELOPE_OVERHEAD

        /** The marker of a pending restart notice (QA L1), next to [SignerFiles.RECEIPTS_FILE]. */
        const val RESTART_NOTICE_FILE = "receipts.restarted"

        private fun invalid(cause: Throwable) = SignerException(UiText.Ios("Invalid activity record."), cause)
    }
}
