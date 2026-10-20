package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test

/**
 * Commit boundaries under cancellation and main-safety of [com.bittool.signer.core.crypto.vault.DefaultWalletVault].
 *
 * iOS actor methods cannot be interrupted (`Core/WalletVault.swift:61-279`): once `create`, `changePassword`,
 * `resetPassword` or `delete` runs, it finishes and reports what it did. On Android the caller's coroutine can be
 * cancelled at any time (a ViewModel cancels in-flight work when the app goes to the background), and `SignerStore`
 * relies on the vault's outcome: a `create` that throws makes it delete the new public sidecar, and its post-commit
 * steps after `resetPassword`/`delete` run only after a normal return (SignerStore KDoc). So a cancellation that
 * arrives once the persistent write has started must not surface as "nothing happened", and one that arrives earlier
 * must leave nothing behind.
 */
class VaultCancellationTest {
    private val password = "Strong-Password1"
    private val dataDir = tempDataDir()
    private val files = VaultFiles(dataDir)

    @After
    fun cleanUp() {
        dataDir.deleteRecursively()
    }

    @Test
    fun aCreationThatReachedItsCommitReturnsAlthoughTheCallerWasCancelled(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        val id = WalletId.random()
        val gate = keys.hold("create")
        var created: VaultMetadata? = null
        val job = launch(Dispatchers.Default) {
            created = vault.create("Commit", secret(ABANDON_PHRASE), secret(""), secret(password), id)
        }
        gate.awaitEntered()
        job.cancel()
        gate.release()
        job.join()
        assertNotNull(created, "the wallet was committed, so create must report it instead of a cancellation")
        assertTrue(files.vault(id).exists())
        assertTrue(keys.delegate.containsKey(id))
        assertEquals(listOf(id), vault.list().map { it.id })
        vault.unlock(id, secret(password)).close()
    }

    @Test
    fun aCreationCancelledBeforeItsCommitLeavesNeitherFileNorKey(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        val id = WalletId.random()
        val gate = keys.hold("isDeviceSecure")
        var created: VaultMetadata? = null
        val job = launch(Dispatchers.Default) {
            created = vault.create("Early", secret(ABANDON_PHRASE), secret(""), secret(password), id)
        }
        gate.awaitEntered()
        job.cancel()
        gate.release()
        job.join()
        assertNull(created)
        assertFalse(files.vault(id).exists())
        assertEquals(0, keys.delegate.keyCount)
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun aDeletionThatReachedItsCommitCompletesAndReturns(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        val wallet = vault.create("Delete", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val gate = keys.hold("delete")
        var deleted = false
        val job = launch(Dispatchers.Default) {
            vault.delete(wallet.id, secret(password))
            deleted = true
        }
        gate.awaitEntered()
        job.cancel()
        gate.release()
        job.join()
        assertTrue(deleted, "the vault file is gone, so delete must return normally for the store to finish its steps")
        assertFalse(files.vault(wallet.id).exists())
        assertFalse(files.attempts(wallet.id).exists())
        assertFalse(keys.delegate.containsKey(wallet.id))
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun aPasswordResetThatReachedItsCommitAlsoClearsTheCounter(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        val wallet = vault.create("Reset", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(5) { assertFailsWith<VaultError.AuthenticationFailed> { vault.unlock(wallet.id, secret("Wrong-Password1")) } }
        assertFailsWith<VaultError.TemporarilyLocked> { vault.unlock(wallet.id, secret(password)) }
        // The first seal after the KDF is the new vault file; the counter reset follows in the same commit.
        val gate = keys.hold("seal")
        var reset = false
        val job = launch(Dispatchers.Default) {
            vault.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret("Replacement-Password2"))
            reset = true
        }
        gate.awaitEntered()
        job.cancel()
        gate.release()
        job.join()
        assertTrue(reset)
        assertFailsWith<VaultError.AuthenticationFailed> { vault.unlock(wallet.id, secret(password)) }
        vault.unlock(wallet.id, secret("Replacement-Password2")).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }
    }

    @Test
    fun aPasswordChangeThatReachedItsCommitIsReported(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        val wallet = vault.create("Change", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        // Seals of a change: the counted attempt, the counter reset, then the re-encrypted vault (the commit).
        val gate = keys.hold("seal", occurrence = 3)
        var changed = false
        val job = launch(Dispatchers.Default) {
            vault.changePassword(wallet.id, secret(password), secret("Replacement-Password2"))
            changed = true
        }
        gate.awaitEntered()
        job.cancel()
        gate.release()
        job.join()
        assertTrue(changed, "the new password is in effect, so the change must not be reported as cancelled")
        vault.unlock(wallet.id, secret("Replacement-Password2")).close()
    }

    @Test
    fun keystoreAndLockScreenCallsNeverRunOnTheCallersThread(): Unit = runBlocking {
        val keys = GatedDeviceKeyStore()
        val vault = vault(dataDir, keys)
        Executors.newSingleThreadExecutor { Thread(it, "caller-main") }.asCoroutineDispatcher().use { main ->
            withContext(main) {
                val wallet = vault.create("Main safe", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
                vault.list()
                vault.unlock(wallet.id, secret(password)).close()
                vault.verifyPassword(wallet.id, secret(password))
                vault.changePassword(wallet.id, secret(password), secret("Replacement-Password2"))
                vault.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret(password))
                vault.publicDataKey(createIfMissing = true)
                vault.publicDataKey(createIfMissing = false)
                vault.delete(wallet.id, secret(password))
            }
        }
        assertTrue(keys.threads.isNotEmpty())
        assertTrue(keys.threads.none { it.name == "caller-main" }, "a DeviceKeyStore call ran on the caller's thread")
    }
}
