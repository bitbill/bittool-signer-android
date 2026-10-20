package com.bittool.signer.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceLockScreen
import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import java.security.KeyStore
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android Keystore device layer on the device (vault-crypto.md §7, §17.3–§17.4, ARCHITECTURE.md §3 W1-crypto
 * acceptance: "Keystore, StrongBox fallback, alias never replaced").
 *
 * The shared emulator has no lock screen and no StrongBox, and changing its security settings is out of bounds for
 * tests, so most cases inject an always-secure [DeviceLockScreen] and a policy without `setUnlockedDeviceRequired`
 * (API 31 Keystore refuses to generate such a key without a lock screen). The production configuration is exercised
 * by [theProductionConfigurationNeedsALockScreenAndThenGeneratesUsableKeys] (its positive half needs a lock screen).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceKeyStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testPolicy = KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false)
    private val store = AndroidKeystoreDeviceKeyStore({ true }, testPolicy)
    private val created = mutableListOf<WalletId>()

    private fun newId() = WalletId.random().also { created += it }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @After
    fun deleteTestKeys() {
        val keystore = keystore()
        val aliases = created.map { AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + it } +
            (AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID)
        for (alias in aliases) if (keystore.containsAlias(alias)) keystore.deleteEntry(alias)
    }

    @Test
    fun sealOpenAndDeleteWithANonExportableKey() {
        val id = newId()
        store.create(id)
        assertTrue(store.containsKey(id))
        val aad = "com.bittool.signer.vault.v2/device\u0000header".encodeToByteArray()
        val plaintext = ByteArray(32_000) { it.toByte() }
        val sealed = store.seal(id, plaintext, aad)
        assertEquals(12 + plaintext.size + 16, sealed.size)
        assertArrayEquals(plaintext, store.open(id, sealed, aad))
        // A fresh IV every time (randomised encryption).
        assertFalse(sealed.copyOf(12).contentEquals(store.seal(id, plaintext, aad).copyOf(12)))
        val info = store.keyInfo(id)
        log("device key: insideSecureHardware=${info.isInsideSecureHardware} ${securityLevel(info)}")
        assertEquals(256, info.keySize)

        assertThrows(VaultError.AuthenticationFailed::class.java) { store.open(id, sealed, "other aad".encodeToByteArray()) }
        val flipped = sealed.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        assertThrows(VaultError.AuthenticationFailed::class.java) { store.open(id, flipped, aad) }
        assertThrows(VaultError.AuthenticationFailed::class.java) { store.open(id, sealed.copyOf(27), aad) }
        assertThrows(VaultError.AuthenticationFailed::class.java) { store.open(newId().also { store.create(it) }, sealed, aad) }

        store.delete(id)
        assertFalse(store.containsKey(id))
        store.delete(id) // absent is success
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { store.open(id, sealed, aad) }
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { store.seal(id, plaintext, aad) }
        assertFalse("a missing key is never recreated by seal or open", store.containsKey(id))
    }

    @Test
    fun anExistingAliasIsNeverReplaced() {
        val id = newId()
        store.create(id)
        val aad = "aad".encodeToByteArray()
        val sealed = store.seal(id, "kept".encodeToByteArray(), aad)
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { store.create(id) }
        assertArrayEquals("kept".encodeToByteArray(), store.open(id, sealed, aad))
        // Neither by another instance (a new process), nor for the reserved public-data id.
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { AndroidKeystoreDeviceKeyStore({ true }, testPolicy).create(id) }
        assertArrayEquals("kept".encodeToByteArray(), store.open(id, sealed, aad))
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { store.create(AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID) }
    }

    @Test
    fun strongBoxFallsBackToTheTeeWhenUnavailable() {
        val strongBoxFirst = AndroidKeystoreDeviceKeyStore({ true }, testPolicy.copy(strongBox = true))
        val id = newId()
        strongBoxFirst.create(id)
        val aad = "aad".encodeToByteArray()
        assertArrayEquals(byteArrayOf(1, 2, 3), strongBoxFirst.open(id, strongBoxFirst.seal(id, byteArrayOf(1, 2, 3), aad), aad))
        val info = strongBoxFirst.keyInfo(id)
        log("StrongBox requested on a device ${if (KeystoreKeyPolicy.forDevice(context).strongBox) "with" else "without"} StrongBox: ${securityLevel(info)}")
        if (!KeystoreKeyPolicy.forDevice(context).strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(info.securityLevel != KeyProperties.SECURITY_LEVEL_STRONGBOX)
        }
    }

    @Test
    fun noKeyIsCreatedWithoutASecureLockScreen() {
        val insecure = AndroidKeystoreDeviceKeyStore({ false }, testPolicy)
        val id = newId()
        assertFalse(insecure.isDeviceSecure())
        assertThrows(VaultError.DevicePasscodeRequired::class.java) { insecure.create(id) }
        assertFalse(insecure.containsKey(id))
        assertThrows(VaultError.DevicePasscodeRequired::class.java) { insecure.publicDataKey(createIfMissing = true) }
        assertNull(insecure.publicDataKey(createIfMissing = false))
        // The production check is KeyguardManager.isDeviceSecure().
        log("this device: isDeviceSecure=${DeviceLockScreen.of(context).isSecure()}")
    }

    @Test
    fun thePublicDataKeyIsCreatedOnceAndNeverRegenerated() {
        assertNull(store.publicDataKey(createIfMissing = false))
        val key = requireNotNull(store.publicDataKey(createIfMissing = true))
        val message = ByteArray(300_000) { (it % 251).toByte() }
        val start = System.nanoTime()
        val tag = key.mac(message)
        log("public-data HMAC of 300 KB: ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(32, tag.size)
        assertTrue(key.verify(message, tag))
        assertFalse(key.verify(message, tag.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        assertArrayEquals(tag, store.publicDataKey(createIfMissing = true)!!.mac(message))
        assertArrayEquals(tag, AndroidKeystoreDeviceKeyStore({ true }, testPolicy).publicDataKey(false)!!.mac(message))

        // A key this process already used that now reads as missing (flaky Keystore) is never regenerated.
        keystore().deleteEntry(AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID)
        assertNull(store.publicDataKey(createIfMissing = false))
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { store.publicDataKey(createIfMissing = true) }
        // A new process may create it (iOS: a lost key makes every wallet need Repair).
        assertNotNull(AndroidKeystoreDeviceKeyStore({ true }, testPolicy).publicDataKey(createIfMissing = true))
    }

    @Test
    fun anUnusableEntryUnderThePublicAliasIsKeptAndRefused() {
        val alias = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
            generateKey()
        }
        val fresh = AndroidKeystoreDeviceKeyStore({ true }, testPolicy)
        assertNull(fresh.publicDataKey(createIfMissing = false))
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { fresh.publicDataKey(createIfMissing = true) }
        assertEquals("AES", keystore().getKey(alias, null).algorithm)
    }

    /**
     * The Hilt-constructed store (KeyguardManager gate, `setUnlockedDeviceRequired`, StrongBox when present): on a device
     * with a lock screen it generates usable keys; without one (the shared emulator) it refuses before generating any.
     */
    @Test
    fun theProductionConfigurationNeedsALockScreenAndThenGeneratesUsableKeys() {
        val production = AndroidKeystoreDeviceKeyStore(context)
        val id = newId()
        if (production.isDeviceSecure()) {
            production.create(id)
            val aad = "aad".encodeToByteArray()
            assertArrayEquals(byteArrayOf(9), production.open(id, production.seal(id, byteArrayOf(9), aad), aad))
            log("production device key on a secure device: ${securityLevel(production.keyInfo(id))}")
            assertNotNull(production.publicDataKey(createIfMissing = true))
        } else {
            log("no lock screen: the production configuration refuses to create keys")
            assertThrows(VaultError.DevicePasscodeRequired::class.java) { production.create(id) }
            assertThrows(VaultError.DevicePasscodeRequired::class.java) { production.publicDataKey(createIfMissing = true) }
            assertFalse(store.containsKey(id))
            assertNull(production.publicDataKey(createIfMissing = false))
        }
    }

    private fun securityLevel(info: android.security.keystore.KeyInfo): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "level ${info.securityLevel}"
            }
        } else {
            "insideSecureHardware=${info.isInsideSecureHardware}"
        }

    private fun log(message: String) = Log.i("KeystoreTest", message)
}
