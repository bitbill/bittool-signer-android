package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * The lost-device-key dead end (QA backlog B9): a wallet whose Keystore key is provably gone can be removed without its
 * password, which frees its id and files for a re-import, while every wallet whose key may still exist keeps the
 * password-authorised deletion of iOS (`WalletVault.delete`, `Core/WalletVault.swift:194-203`).
 */
class LostDeviceKeyTest {
    private val password = "Strong-Password1"
    private val dataDir = tempDataDir()
    private val files = VaultFiles(dataDir)
    private val keys = InMemoryDeviceKeyStore()
    private val store = vault(dataDir, keys)

    @After
    fun cleanUp() {
        dataDir.deleteRecursively()
    }

    private suspend fun createWallet(name: String = "Lost key") =
        store.create(name, secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())

    @Test
    fun aWalletWithItsKeyIsNotLostAndCannotBeRemovedWithoutItsPassword(): Unit = runBlocking {
        val wallet = createWallet()
        assertFalse(store.isDeviceKeyLost(wallet.id))
        val refused = assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWithLostDeviceKey(wallet.id) }
        assertEquals(UiText.Resource(R.string.crypto_device_key_loss_unconfirmed), refused.text)
        assertTrue(files.vault(wallet.id).exists())
        assertTrue(keys.containsKey(wallet.id))
        assertFalse(files.attempts(wallet.id).exists(), "no password was tried")
        store.unlock(wallet.id, secret(password)).close()
    }

    @Test
    fun aWalletWhoseKeyIsGoneIsALostWalletAndTheDeadEndIsReal(): Unit = runBlocking {
        val wallet = createWallet()
        // An attempts file exists: the counter was sealed with the key that is about to disappear.
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) }
        keys.forgetKey(wallet.id)
        assertTrue(store.isDeviceKeyLost(wallet.id))
        // Every password path fails before any password work: the wallet can be neither unlocked, reset nor deleted.
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.unlock(wallet.id, secret(password)) }
        assertFailsWith<VaultError.DeviceKeyUnavailable> {
            store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret("Replacement-Password2"))
        }
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.delete(wallet.id, secret(password)) }
        assertEquals(listOf(wallet.id), store.list().map { it.id })
    }

    @Test
    fun removingAWalletWithALostKeyDeletesItsFileAndCounterWithoutAPassword(): Unit = runBlocking {
        val kept = createWallet("Kept")
        val lost = createWallet("Lost")
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(lost.id, secret("Wrong-Password1")) }
        assertTrue(files.attempts(lost.id).exists())
        val keptBytes = files.vault(kept.id).readBytes()
        keys.forgetKey(lost.id)

        store.removeWithLostDeviceKey(lost.id)

        assertFalse(files.vault(lost.id).exists())
        assertFalse(files.attempts(lost.id).exists())
        assertEquals(listOf(kept.id), store.list().map { it.id })
        assertContentEquals(keptBytes, files.vault(kept.id).readBytes(), "the other wallet is untouched")
        store.unlock(kept.id, secret(password)).close()
        assertFailsWith<VaultError.WalletNotFound> { store.removeWithLostDeviceKey(lost.id) }
        assertFailsWith<VaultError.WalletNotFound> { store.isDeviceKeyLost(lost.id) }
        // The same phrase can be stored again (a new id and key, as a re-import does).
        val again = createWallet("Lost")
        store.unlock(again.id, secret(password)).close()
    }

    @Test
    fun anInvalidatedKeyIsLostAndItsRemnantIsDeletedWithTheWallet(): Unit = runBlocking {
        val wallet = createWallet()
        keys.invalidateKey(wallet.id)
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.unlock(wallet.id, secret(password)) }
        assertTrue(store.isDeviceKeyLost(wallet.id))
        assertTrue(keys.containsKey(wallet.id))
        store.removeWithLostDeviceKey(wallet.id)
        assertFalse(keys.containsKey(wallet.id), "no key remnant is left behind")
        assertFalse(files.vault(wallet.id).exists())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun aKeystoreThatDoesNotAnswerProvesNothing(): Unit = runBlocking {
        // The API 26–30 binder pitfall: a live key reads as missing. That is never a reason to remove anything.
        val wallet = createWallet()
        keys.unreachable = true
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.unlock(wallet.id, secret(password)) }
        assertFalse(store.isDeviceKeyLost(wallet.id))
        assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWithLostDeviceKey(wallet.id) }
        assertTrue(files.vault(wallet.id).exists())
        keys.unreachable = false
        store.unlock(wallet.id, secret(password)).close()
    }

    @Test
    fun aRemovalNeedsASecureLockScreenAndAnExistingWallet(): Unit = runBlocking {
        val wallet = createWallet()
        keys.forgetKey(wallet.id)
        keys.deviceSecure = false
        assertFalse(store.isDeviceKeyLost(wallet.id), "without a lock screen every operation asks for one first")
        assertFailsWith<VaultError.DevicePasscodeRequired> { store.removeWithLostDeviceKey(wallet.id) }
        assertTrue(files.vault(wallet.id).exists())
        keys.deviceSecure = true
        assertFailsWith<VaultError.WalletNotFound> { store.removeWithLostDeviceKey(WalletId.random()) }
        store.removeWithLostDeviceKey(wallet.id)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun theLossIsProvenAgainAtRemovalTime(): Unit = runBlocking {
        // The page saw a lost key, but by the time of the removal Keystore no longer proves it: nothing is removed.
        val wallet = createWallet()
        keys.forgetKey(wallet.id)
        assertTrue(store.isDeviceKeyLost(wallet.id))
        keys.unreachable = true
        assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWithLostDeviceKey(wallet.id) }
        assertTrue(files.vault(wallet.id).exists())
        assertEquals(listOf(wallet.id), store.list().map { it.id })
    }
}
