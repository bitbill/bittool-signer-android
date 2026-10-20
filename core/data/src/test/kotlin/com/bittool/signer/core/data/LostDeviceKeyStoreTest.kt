package com.bittool.signer.core.data

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.JvmStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The lost-device-key dead end at store level (QA backlog B9, Android-only: iOS has no way out of it). A wallet whose
 * device key is gone can be neither unlocked, reset, deleted with its password nor re-imported, because its sidecar
 * still holds its `walletKeyId`; `removeWalletWithLostDeviceKey` removes it without a password exactly like a deletion
 * (AppStore.swift:520-531), which frees the phrase for a new import. Every other wallet keeps the password.
 */
class LostDeviceKeyStoreTest {
    private val phrase = FakeEngine.PHRASE

    private suspend fun JvmStore.resetPassword(id: WalletId) =
        phrase.asSecret { words -> "".asSecret { pass -> "Replacement-Password2".asSecret { pw -> store.resetPassword(id, words, pass, pw) } } }

    @Test
    fun aWalletWithALostKeyIsADeadEndUntilItIsRemovedAndThenItsPhraseImportsAgain() = JvmStore.run {
        store.reload()
        val wallet = create()
        vault.loseDeviceKey(wallet.id)

        // The dead end: no password path works and the phrase cannot be imported again.
        assertFailsWith<VaultError.DeviceKeyUnavailable> { PASSWORD.asSecret { store.deleteWallet(wallet.id, it) } }
        assertFailsWith<VaultError.DeviceKeyUnavailable> { resetPassword(wallet.id) }
        val duplicate = assertFailsWith<AppIssue> { create("Again") }
        assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), duplicate.text)
        assertEquals(listOf(wallet.id), state.wallets.map { it.id })

        // The way out.
        assertTrue(store.isDeviceKeyLost(wallet.id))
        store.prepare(FakeEngine.request())
        val epoch = state.securityEpoch
        store.removeWalletWithLostDeviceKey(wallet.id)
        assertTrue(state.wallets.isEmpty())
        assertFalse(vault.contains(wallet.id))
        assertFalse(sidecar(wallet.id).exists(), "the sidecar holding the walletKeyId is removed too")
        assertNull(state.review, "removing clears the request, like a deletion")
        assertEquals(epoch + 1, state.securityEpoch)
        assertEquals(1, state.navigationResetEpoch)
        assertNull(state.error)
        assertEquals(0, vault.unlockAttempts(wallet.id), "no password was ever tried")

        val restored = create("Restored")
        assertNotEquals(wallet.id, restored.id)
        assertEquals(listOf(restored.id), state.wallets.map { it.id })
        assertNotNull(state.publicMetadata(restored.id))
    }

    @Test
    fun aWalletWhoseKeyIsNotProvablyGoneKeepsThePasswordDeletion() = JvmStore.run {
        store.reload()
        val wallet = create()
        store.prepare(FakeEngine.request())
        val before = state
        assertFalse(store.isDeviceKeyLost(wallet.id))
        val refused = assertFailsWith<VaultError.DeviceKeyLossUnconfirmed> { store.removeWalletWithLostDeviceKey(wallet.id) }
        assertEquals(refused.text, VaultError.DeviceKeyLossUnconfirmed().text)
        assertEquals(listOf(wallet.id), state.wallets.map { it.id })
        assertTrue(sidecar(wallet.id).exists())
        assertEquals(before.securityEpoch, state.securityEpoch, "a refused removal leaves the session alone")
        assertEquals(before.review, state.review)
        assertEquals(0, state.navigationResetEpoch)
        PASSWORD.asSecret { store.deleteWallet(wallet.id, it) }
        assertTrue(state.wallets.isEmpty())
    }

    @Test
    fun onlyAListedWalletCanBeLost() = JvmStore.run {
        store.reload()
        val wallet = create()
        vault.loseDeviceKey(wallet.id)
        assertFalse(store.isDeviceKeyLost(WalletId.random()))
        assertFailsWith<VaultError.WalletNotFound> { store.removeWalletWithLostDeviceKey(WalletId.random()) }
        assertEquals(listOf(wallet.id), state.wallets.map { it.id })
    }
}
