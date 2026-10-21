package com.bittool.signer.core.data

import com.bittool.signer.core.data.JvmStore.Companion.PASSWORD
import com.bittool.signer.core.data.doubles.AesGcmJournalCipher
import com.bittool.signer.core.data.doubles.HmacPublicDataKey
import com.bittool.signer.core.data.doubles.asSecret
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The store's side of the lost-key items of the QA round 2 production tour (Android-only: removing the device's screen
 * lock deletes every `setUnlockedDeviceRequired` Keystore key — the wallets' device keys, the public-data key and the
 * Activity journal key — and setting a new lock brings none of them back; iOS never meets this state):
 * - L1 (production-01): a journal whose key is provably gone starts again, with a one-time notice, instead of "could
 *   not be read" on every load and "could not be saved" after every signature, for good;
 * - L3 (production-03): the phrase of a wallet whose public index cannot be authenticated any more is recognised from
 *   the identity its sidecar claims, so it never becomes a second wallet with the same keys;
 * - L4 (production-04): which wallets can never be unlocked, so the UI shows "This Wallet Can't Be Unlocked" instead of
 *   a Repair that cannot work, and whether a screen lock is needed first.
 */
class LostKeyRecoveryStoreTest {
    private val JvmStore.cipher get() = fixture.cipher as AesGcmJournalCipher

    private suspend fun JvmStore.signOnce(nonce: Int = 0) {
        store.prepare(FakeEngine.request(nonce))
        sign(state.pendingTasks.single().id)
    }

    /** What removing the screen lock and setting a new one does: every Keystore key of the app is gone for good. */
    private fun JvmStore.loseEveryKey(vararg wallets: VaultMetadata) {
        wallets.forEach { vault.loseDeviceKey(it.id) }
        vault.replacePublicDataKey(null)
        cipher.loseKeyForGood()
    }

    // ---- L1: the Activity journal ----------------------------------------------------------------------------------

    @Test
    fun aJournalWhoseKeyIsProvablyGoneStartsAgainOnceWithANoticeInsteadOfFailingForGood() = JvmStore.run {
        store.reload()
        val wallet = create()
        signOnce()
        assertEquals(1, state.receipts.size)

        loseEveryKey(wallet)
        val relaunched = fixture.relaunch()
        relaunched.reload()
        relaunched.state.value.let {
            assertNull(it.error, "no \"Unable to Continue\" alert: the journal could never be read again")
            assertFalse(it.activityUnreadable)
            assertTrue(it.receipts.isEmpty())
            assertTrue(it.activityRestarted, "the person is told once, in Activity")
        }
        assertFalse(fixture.journalFile.exists(), "the journal nothing can open is gone; the next one gets a new key")

        relaunched.reload()
        assertNull(relaunched.state.value.error, "nothing repeats on later loads")
        assertTrue(relaunched.state.value.activityRestarted, "the notice waits until it was read")

        relaunched.acknowledgeActivityRestart()
        assertFalse(relaunched.state.value.activityRestarted)
        val third = fixture.relaunch()
        third.reload()
        assertFalse(third.state.value.activityRestarted, "read once: never again, also after a relaunch")
        assertNull(third.state.value.error)
    }

    @Test
    fun aSignatureAfterTheJournalKeyWasLostIsSavedInTheNewJournal() = JvmStore.run {
        // Within one process: the first record after the loss starts the journal again instead of "could not be saved".
        store.reload()
        create()
        signOnce()
        cipher.loseKeyForGood()
        store.clearRequest()
        signOnce(nonce = 1)
        assertNull(state.error, "not \"The local activity record could not be saved\"")
        assertEquals(1, state.receipts.size, "the new journal holds the new signature only")
        assertTrue(state.activityRestarted)
        assertEquals(1, fixture.relaunch().also { it.reload() }.state.value.receipts.size, "sealed under the new key")
    }

    @Test
    fun anUnreadableJournalWhoseKeyIsNotProvablyGoneKeepsTodaysBehaviour() = JvmStore.run {
        store.reload()
        create()
        signOnce()
        cipher.loseKey() // a different key: the file does not open, but nothing proves the old key gone
        val sealed = fixture.journalFile.readBytes()
        val relaunched = fixture.relaunch()
        relaunched.reload()
        relaunched.state.value.let {
            assertTrue(it.activityUnreadable)
            assertFalse(it.activityRestarted)
            assertEquals(UiText.Resource(R.string.data_activity_unreadable), it.error)
        }
        assertTrue(cipher.lossChecks.get() > 0, "the loss was asked about")
        assertTrue(fixture.journalFile.readBytes().contentEquals(sealed), "and the journal was left exactly as it is")
    }

    // ---- L4: wallets that can never be unlocked ---------------------------------------------------------------------

    @Test
    fun walletsWithoutPublicDataAreToldApartIntoLostKeysAndRepairs() = JvmStore.run {
        store.reload()
        val lost = create("Lost")
        val repairable = create("Repairable", passphrase = "other account")
        vault.loseDeviceKey(lost.id)
        vault.replacePublicDataKey(HmacPublicDataKey()) // every sidecar fails its MAC: both need "Repair" on iOS
        store.reload()
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(lost.id))
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(repairable.id))
        assertEquals(setOf(lost.id), state.lostKeyWalletIds)
        assertTrue(state.hasLostDeviceKey(lost.id))
        assertFalse(state.hasLostDeviceKey(repairable.id), "its key is fine: the password repairs it")
        assertTrue(state.deviceSecure)

        repeat(2) { store.reload() }
        assertEquals(1, vault.lossChecks[lost.id], "a proven loss is final: not proven again on every load")
        assertEquals(3, vault.lossChecks[repairable.id])

        PASSWORD.asSecret { store.completeSetup(repairable.id, it) }
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(repairable.id))
        assertEquals(setOf(lost.id), state.lostKeyWalletIds)
        store.removeWalletWithLostDeviceKey(lost.id)
        assertTrue(state.lostKeyWalletIds.isEmpty())
        assertEquals(listOf(repairable.id), state.wallets.map { it.id })
    }

    @Test
    fun walletsWithAReadyIndexAreNotLookedAt() = JvmStore.run {
        // The proof is reserved for wallets without public data, so an ordinary load costs no Keystore probing.
        store.reload()
        val wallet = create()
        repeat(3) { store.reload() }
        assertNull(vault.lossChecks[wallet.id])
        assertTrue(state.lostKeyWalletIds.isEmpty())
    }

    @Test
    fun aLossProvenForAWalletWithAReadyIndexIsShownFromThenOn() = JvmStore.run {
        // Review of L4/B21: setup's duplicate check (and the Delete page) prove the loss of a wallet whose index is still
        // readable (its key went, the public-data key did not). Setup then says "Open that wallet, choose Remove This
        // Wallet", so its page must offer exactly that — a load never probes a ready wallet, so the store keeps the proof.
        store.reload()
        val lost = create("Lost")
        val fine = create("Fine", passphrase = "other account")
        vault.loseDeviceKey(lost.id)
        store.reload()
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(lost.id))
        assertTrue(state.lostKeyWalletIds.isEmpty(), "not probed by a load")

        assertFalse(store.isDeviceKeyLost(fine.id))
        assertTrue(store.isDeviceKeyLost(lost.id))
        assertEquals(setOf(lost.id), state.lostKeyWalletIds, "the proof is remembered for every page")

        repeat(2) { store.reload() }
        store.refreshDeviceKeys()
        assertEquals(setOf(lost.id), state.lostKeyWalletIds, "kept by later loads and foreground refreshes")
        assertEquals(1, vault.lossChecks[lost.id], "a proven loss is final: never proven again by a load")

        vault.deviceSecure = false
        store.refreshDeviceKeys()
        assertTrue(state.lostKeyWalletIds.isEmpty(), "without a screen lock, a lock comes first")
        vault.deviceSecure = true
        store.refreshDeviceKeys()
        assertEquals(setOf(lost.id), state.lostKeyWalletIds)

        store.removeWalletWithLostDeviceKey(lost.id)
        assertTrue(state.lostKeyWalletIds.isEmpty())
        assertEquals(listOf(fine.id), state.wallets.map { it.id })
    }

    @Test
    fun withoutAScreenLockNoWalletCountsAsLostAndTheStateSaysALockIsNeeded() = JvmStore.run {
        store.reload()
        val wallet = create()
        loseEveryKey(wallet)
        vault.deviceSecure = false
        store.reload()
        assertFalse(state.deviceSecure)
        assertTrue(state.lostKeyWalletIds.isEmpty(), "every wallet needs a screen lock first")
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id))

        // A screen lock set in Settings while the app was in the background: the foreground refresh sees it.
        vault.deviceSecure = true
        store.refreshDeviceKeys()
        assertTrue(state.deviceSecure)
        assertEquals(setOf(wallet.id), state.lostKeyWalletIds)
    }

    @Test
    fun removingTheScreenLockWhileTheAppWasAwayReloadsTheWholeList() = JvmStore.run {
        store.reload()
        val wallet = create()
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
        store.refreshDeviceKeys()
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id), "nothing changed: nothing reloaded")

        loseEveryKey(wallet)
        vault.deviceSecure = false
        store.refreshDeviceKeys()
        assertFalse(state.deviceSecure)
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id), "the public data is gone with the key")
    }

    // ---- L3: re-importing the phrase of a wallet whose public index cannot be authenticated -------------------------

    @Test
    fun thePhraseOfAWalletWhoseIndexCannotBeAuthenticatedNeverBecomesASecondWallet() = JvmStore.run {
        store.reload()
        val wallet = create()
        loseEveryKey(wallet)
        store.reload()
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id))

        val found = FakeEngine.PHRASE.asSecret { words -> "".asSecret { store.findExistingWallet(words, it) } }
        assertEquals(ExistingWalletMatch(wallet.id, verified = false), found)
        val ready = FakeEngine.PHRASE.asSecret { words -> "".asSecret { store.existingWallet(words, it) } }
        assertNull(ready, "existingWallet keeps the iOS meaning: wallets with an authenticated index only")
        val other = FakeEngine.PHRASE.asSecret { words -> "other".asSecret { store.findExistingWallet(words, it) } }
        assertNull(other, "another passphrase is another wallet")

        val refused = assertFailsWith<AppIssue> { create("Again") }
        assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), refused.text)
        assertEquals(listOf(wallet.id), state.wallets.map { it.id }, "production-03: no second wallet with the same keys")

        // The way out (B9), then the phrase imports again.
        store.removeWalletWithLostDeviceKey(wallet.id)
        val restored = create("Restored")
        assertEquals(listOf(restored.id), state.wallets.map { it.id })
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(restored.id))
    }

    @Test
    fun theUnauthenticatedIdentityNeverResetsAPasswordNorMakesADuplicate() = JvmStore.run {
        store.reload()
        val wallet = create()
        vault.replacePublicDataKey(HmacPublicDataKey())
        store.reload()
        val reset = assertFailsWith<AppIssue> {
            FakeEngine.PHRASE.asSecret { words -> "".asSecret { pass -> "Replacement-Password2".asSecret { store.resetPassword(wallet.id, words, pass, it) } } }
        }
        assertEquals(UiText.Ios("This wallet needs repair before its password can be reset."), reset.text)
        assertTrue(state.duplicateWalletIds.isEmpty(), "an unverified claim is no duplicate")
        assertNull(state.publicMetadata(wallet.id), "and never published as the wallet's index")
        assertEquals(0, vault.unlockAttempts(wallet.id))
    }

    @Test
    fun aSidecarThatNamesAnotherWalletClaimsNothing() = JvmStore.run {
        store.reload()
        val first = create("First")
        val second = create("Second", passphrase = "second")
        vault.replacePublicDataKey(HmacPublicDataKey())
        // The first wallet's sidecar copied over the second's: it names the first wallet, so it is no claim for the second.
        sidecar(first.id).copyTo(sidecar(second.id), overwrite = true)
        sidecar(first.id).delete()
        store.reload()
        val found = FakeEngine.PHRASE.asSecret { words -> "".asSecret { store.findExistingWallet(words, it) } }
        assertNull(found, "a claim counts only for the wallet the sidecar itself names")
        assertEquals(2, state.wallets.size)
    }
}
