package com.bittool.signer.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.common.time.WallClock
import com.bittool.signer.core.crypto.testing.TestKeystore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.KeyGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lost-Keystore-key proof on the device (QA backlog B9): `AndroidKeystoreDeviceKeyStore.isKeyLost` and the vault's
 * removal without a password, on real Android Keystore keys. The shared emulator has no lock screen, hence the test key
 * policy ([TestKeystore], see KeystoreDeviceKeyStoreTest). A permanently invalidated key cannot be produced here without
 * changing the device's lock screen; the JVM `LostDeviceKeyTest` covers that state through the in-memory store.
 */
@RunWith(AndroidJUnit4::class)
class LostKeystoreKeyTest {
    private val store: AndroidKeystoreDeviceKeyStore = TestKeystore.deviceKeyStore()
    private val created = mutableListOf<WalletId>()
    private val dataDir: File = Files.createTempDirectory("lost-key").toFile()

    private fun newId() = WalletId.random().also { created += it }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun alias(id: WalletId) = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + id

    private fun probes() = keystore().aliases().toList().filter { it.startsWith(AndroidKeystoreDeviceKeyStore.PROBE_PREFIX) }

    @After
    fun cleanUp() {
        val keystore = keystore()
        val aliases = created.map(::alias) + SignerFiles.RECEIPTS_KEY_ALIAS +
            (AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID)
        for (alias in aliases) if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        dataDir.deleteRecursively()
    }

    @Test
    fun onlyAKeyKeystoreProvablyLacksIsLost() {
        val id = newId()
        store.create(id)
        assertFalse("a usable key is not lost", store.isKeyLost(id))
        keystore().deleteEntry(alias(id))
        assertTrue("Keystore answers and has no entry for it", store.isKeyLost(id))
        assertTrue("an id that never had a key", store.isKeyLost(newId()))
        assertFalse("the public-data key is never a wallet's lost key", store.isKeyLost(AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID))
        assertFalse("the proof creates nothing under the wallet's alias", store.containsKey(id))
        assertTrue("every probe key is deleted again", probes().isEmpty())
    }

    @Test
    fun anEntryThatIsNotAWalletKeyIsNotProvablyLost() {
        // Something else under a wallet's alias (an HMAC key): unusable for the vault, but not proof that a key is gone.
        val id = newId()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        assertFalse(store.isKeyLost(id))
        assertTrue(keystore().containsAlias(alias(id)))
    }

    @Test
    fun aProbeKeyLeftByAKilledProcessIsRemoved() {
        val leftover = AndroidKeystoreDeviceKeyStore.PROBE_PREFIX + "left-behind"
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(leftover, KeyProperties.PURPOSE_ENCRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
            generateKey()
        }
        val id = newId()
        store.create(id)
        assertFalse(store.isKeyLost(id))
        assertTrue(probes().isEmpty())
    }

    @Test
    fun aWalletWhoseKeystoreKeyIsGoneIsRemovedWithoutItsPassword(): Unit = runBlocking {
        val vault = DefaultWalletVault(dataDir, store, SystemSecureRandomSource(), WallClock { System.currentTimeMillis() }, Dispatchers.Default, Dispatchers.IO)
        val kept = SecretText.of(ABANDON_PHRASE).use { phrase ->
            SecretText.of("").use { pass -> SecretText.of(PASSWORD).use { pw -> vault.create("Kept", phrase, pass, pw, newId()) } }
        }
        val lost = SecretText.of(ABANDON_PHRASE).use { phrase ->
            SecretText.of("").use { pass -> SecretText.of(PASSWORD).use { pw -> vault.create("Lost", phrase, pass, pw, newId()) } }
        }
        assertFalse(vault.isDeviceKeyLost(lost.id))
        assertThrows(VaultError.DeviceKeyLossUnconfirmed::class.java) { runBlocking { vault.removeWithLostDeviceKey(lost.id) } }

        keystore().deleteEntry(alias(lost.id))
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) {
            runBlocking { SecretText.of(PASSWORD).use { vault.unlock(lost.id, it) }.close() }
        }
        assertTrue(vault.isDeviceKeyLost(lost.id))
        vault.removeWithLostDeviceKey(lost.id)

        assertEquals(listOf(kept.id), vault.list().map { it.id })
        assertFalse(File(dataDir, "${SignerFiles.VAULTS_DIR}/${lost.id}.${SignerFiles.VAULT_EXTENSION}").exists())
        assertFalse(File(dataDir, "${SignerFiles.VAULTS_DIR}/.${lost.id}.attempts").exists())
        assertTrue("the other wallet's key is untouched", store.containsKey(kept.id))
        SecretText.of(PASSWORD).use { vault.unlock(kept.id, it) }.close()
        assertTrue(probes().isEmpty())
    }

    private companion object {
        const val PASSWORD = "Strong-Password1"
        const val ABANDON_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
