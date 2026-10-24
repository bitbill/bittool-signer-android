package com.bittool.signer.core.data

import com.bittool.signer.core.data.doubles.AesGcmJournalCipher
import com.bittool.signer.core.data.doubles.HmacPublicDataKey
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.MatchingIssue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone unlocked by face recognition or Smart Lock can count as locked for Keystore (Android 12–13 and some builds
 * after them): the public-data key refuses with `UserNotAuthenticatedException` until the screen lock is used. The
 * wallets are not damaged then: no "Repair", no "needs repair" match, and the next foreground loads them again.
 * Robolectric for the Android exception the refusal carries.
 */
@RunWith(RobolectricTestRunner::class)
class KeysLockedStoreTest {

    @Test
    fun aPhoneThatCountsAsLockedIsNotRepairAndRecoversOnceUnlocked() = JvmStore.run {
        store.reload()
        val wallet = create()
        val key = vault.publicDataKey(createIfMissing = false) as HmacPublicDataKey
        key.locked = true
        store.reload()
        assertTrue(state.keysLocked)
        assertEquals(PublicMetadataStatus.Invalid, state.metadataStatus(wallet.id))

        store.prepare(FakeEngine.request())
        assertEquals(MatchingIssue.KeysLocked, state.matchingIssue)
        assertTrue(state.matchingIssue!!.canRetry)

        // Unlocked with the PIN: the app comes back to the foreground.
        key.locked = false
        store.refreshDeviceKeys()
        assertFalse(state.keysLocked)
        assertEquals(PublicMetadataStatus.Ready, state.metadataStatus(wallet.id))
        store.retryMatching()
        assertNotNull(state.plan)
    }

    /** The Activity journal's key is locked the same way: no "could not be read" alert, and it reads again later. */
    @Test
    fun aLockedJournalIsNotUnreadableAndIsReadAgainOnceUnlocked() = JvmStore.run {
        store.reload()
        create()
        store.prepare(FakeEngine.request())
        sign(state.plan!!.tasks.single().id)
        assertEquals(1, state.receipts.size)
        val journal = fixture.cipher as AesGcmJournalCipher
        journal.locked = true
        store.reload()
        assertTrue(state.activityLocked)
        assertFalse(state.activityUnreadable)
        assertNull(state.error, "no \"could not be read\" alert")
        journal.locked = false
        store.refreshDeviceKeys()
        assertFalse(state.activityLocked)
        assertEquals(1, state.receipts.size)
    }

    @Test
    fun aKeystoreLockedRefusalSaysToUnlockWithTheScreenLock() {
        val error = com.bittool.signer.core.crypto.vault.VaultError.DeviceKeyUnavailable(
            android.security.keystore.UserNotAuthenticatedException(),
        )
        assertTrue(error.deviceLocked)
        assertEquals(UiText.Resource(R.string.crypto_device_locked), error.text)
        assertFalse(com.bittool.signer.core.crypto.vault.VaultError.DeviceKeyUnavailable().deviceLocked)
    }
}
