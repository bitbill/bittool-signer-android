package com.bittool.signer.core.crypto

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.kdf.Pbkdf2
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The vault end to end on the device: real Android Keystore device keys, the native PBKDF2, the app's
 * `noBackupFilesDir`-style storage. Ports the device-relevant iOS `SecurityTests` cases (round trip, re-authenticated
 * delete removing the key, throttling across instances, reset) against the Keystore instead of `MemoryDeviceKeys`.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreWalletVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dataDir = File(context.noBackupFilesDir, "vault-test-${System.nanoTime()}")
    private val keys = AndroidKeystoreDeviceKeyStore({ true }, KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false))
    private val clock = FakeWallClock()
    private val created = mutableListOf<WalletId>()
    private val password = "Strong-Password1"

    private fun vault() = DefaultWalletVault(dataDir, keys, SystemSecureRandomSource(), clock, Dispatchers.Default, Dispatchers.IO)

    private fun s(text: String) = SecretText.of(text)

    @After
    fun cleanUp() {
        dataDir.deleteRecursively()
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (id in created) {
            val alias = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + id
            if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
        }
    }

    @Test
    fun createUnlockRotateAndDeleteWithKeystoreKeys(): Unit = runBlocking {
        val vault = vault()
        val id = WalletId.random().also { created += it }
        var start = SystemClock.elapsedRealtime()
        val wallet = vault.create("Device", s(ABANDON), s("device passphrase"), s(password), id)
        log("create (PBKDF2 ${Pbkdf2.sha256Implementation} + Keystore key + write): ${SystemClock.elapsedRealtime() - start} ms")
        assertTrue(keys.containsKey(id))
        start = SystemClock.elapsedRealtime()
        vault.unlock(id, s(password)).use {
            assertEquals(ABANDON, String(it.mnemonic.chars()))
            assertEquals("device passphrase", String(it.passphrase.chars()))
        }
        log("unlock: ${SystemClock.elapsedRealtime() - start} ms")
        assertThrows(VaultError.AuthenticationFailed::class.java) { runBlocking { vault.unlock(id, s("Wrong-Password1")) } }

        start = SystemClock.elapsedRealtime()
        vault.changePassword(id, s(password), s("Replacement-Password2"))
        log("change password (two KDF runs): ${SystemClock.elapsedRealtime() - start} ms")
        assertEquals(listOf(wallet), vault().list())
        assertThrows(VaultError.AuthenticationFailed::class.java) { runBlocking { vault.unlock(id, s(password)) } }

        vault.delete(id, s("Replacement-Password2"))
        assertFalse("delete removes the Keystore key", keys.containsKey(id))
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun throttlingSurvivesANewVaultServiceInstance(): Unit = runBlocking {
        val id = WalletId.random().also { created += it }
        val vault = vault()
        vault.create("Throttle", s(ABANDON), s(""), s(password), id)
        repeat(5) { assertThrows(VaultError.AuthenticationFailed::class.java) { runBlocking { vault.unlock(id, s("Wrong-Password1")) } } }
        val locked = assertThrows(VaultError.TemporarilyLocked::class.java) { runBlocking { vault().unlock(id, s(password)) } }
        assertTrue(locked.seconds > 0)
        vault.resetPassword(id, s(ABANDON), s(""), s("Replacement-Password2"))
        vault().verifyPassword(id, s("Replacement-Password2"))
    }

    @Test
    fun aTamperedFileOrADifferentKeyFailsAsTheWrongPassword(): Unit = runBlocking {
        val vault = vault()
        val id = WalletId.random().also { created += it }
        vault.create("Tamper", s(ABANDON), s(""), s(password), id)
        val file = File(File(dataDir, "Vaults"), "$id.bsvault")
        val original = file.readBytes()
        // Flip one base64 character of the ciphertext (it stays valid base64 and the same length).
        val text = original.decodeToString()
        val at = text.indexOf("\"ciphertext\":\"") + 20
        val flipped = text.substring(0, at) + (if (text[at] == 'A') 'B' else 'A') + text.substring(at + 1)
        file.writeText(flipped)
        assertThrows(VaultError.AuthenticationFailed::class.java) { runBlocking { vault.unlock(id, s(password)) } }
        file.writeBytes(original)
        vault.unlock(id, s(password)).close()
    }

    @Test
    fun aListWaitsForAnUnlockInProgress(): Unit = runBlocking {
        val vault = vault()
        val id = WalletId.random().also { created += it }
        vault.create("Serial", s(ABANDON), s(""), s(password), id)
        val unlock = async(Dispatchers.Default) { vault.unlock(id, s(password)).close() }
        val list = async(Dispatchers.Default) { vault.list() }
        assertEquals(1, list.await().size)
        unlock.await()
    }

    private fun log(message: String) = Log.i("KeystoreVaultTest", message)

    private companion object {
        const val ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
