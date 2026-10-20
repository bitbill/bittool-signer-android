package com.bittool.signer.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.testing.TestKeystore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The public test factory other modules' instrumented tests use instead of reflecting on the `internal` test-policy
 * constructors (QA backlog B8): on the shared emulator, which has no lock screen, it builds the production Keystore
 * classes with keys that need none, and its lock-screen gate answers as told.
 */
@RunWith(AndroidJUnit4::class)
class TestKeystoreTest {
    private val ids = mutableListOf<WalletId>()

    private fun newId() = WalletId.random().also { ids += it }

    @After
    fun cleanUp() {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = ids.map { AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + it } + SignerFiles.RECEIPTS_KEY_ALIAS
        for (alias in aliases) if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    @Test
    fun theFactoryBuildsTheProductionKeystoreClassesForADeviceWithoutALockScreen() {
        val store = TestKeystore.deviceKeyStore()
        val id = newId()
        store.create(id)
        assertTrue(store.containsKey(id))
        val aad = "aad".encodeToByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3), store.open(id, store.seal(id, byteArrayOf(1, 2, 3), aad), aad))
        assertThrows(VaultError.DevicePasscodeRequired::class.java) { TestKeystore.deviceKeyStore { false }.create(newId()) }

        val journal = "[]".encodeToByteArray()
        val sealed = TestKeystore.journalCipher().seal(journal)
        // Another instance (a new process) opens it under the same Keystore key.
        assertArrayEquals(journal, TestKeystore.journalCipher().open(sealed))
    }
}
