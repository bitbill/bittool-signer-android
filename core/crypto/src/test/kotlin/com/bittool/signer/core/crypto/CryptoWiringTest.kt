package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.di.CryptoModule
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy
import com.bittool.signer.core.crypto.vault.PasswordLayer
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import java.util.Optional
import java.util.concurrent.CancellationException
import javax.inject.Provider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** DI selection and cancellation semantics that the device tests cannot pin down deterministically. */
class CryptoWiringTest {
    @Test
    fun theDebugOverrideReplacesTheKeystoreWithoutConstructingIt() {
        val override = InMemoryDeviceKeyStore()
        val keystore = Provider<AndroidKeystoreDeviceKeyStore> { error("the Keystore store must not be created") }
        assertSame(override, CryptoModule.deviceKeyStore(Optional.of<DeviceKeyStore>(override), keystore))
    }

    /**
     * QA round 1 sign-01: the debug UI-test harness's journal cipher replaces the Keystore one (whose
     * `setUnlockedDeviceRequired` key cannot be generated on the harness emulator without a screen lock), and without an
     * override (every release build) the Keystore cipher is bound.
     */
    @Test
    fun theDebugJournalOverrideReplacesTheKeystoreCipherWithoutConstructingIt() {
        val override = object : JournalCipher {
            override fun seal(plaintext: ByteArray) = plaintext
            override fun open(sealed: ByteArray) = sealed
        }
        val unused = Provider<KeystoreJournalCipher> { error("the Keystore cipher must not be created") }
        assertSame(override, CryptoModule.journalCipher(Optional.of<JournalCipher>(override), unused))

        val keystore = KeystoreJournalCipher(KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = true))
        assertSame(keystore, CryptoModule.journalCipher(Optional.empty(), Provider { keystore }))
    }

    @Test
    fun aCancelledDerivationIsACancellationNotAKdfFailure() {
        // Kotlin's CancellationException is an IllegalStateException; the KDF wrapper must not relabel it.
        val layer = PasswordLayer(SystemSecureRandomSource())
        assertFailsWith<CancellationException> {
            layer.deriveKey("Strong-Password1".encodeToByteArray(), ByteArray(32), 600_000) { throw CancellationException("left") }
        }
    }

    @Test
    fun anUnlockCancelledBeforeItStartsCountsNothing(): Unit = runBlocking {
        val dataDir = tempDataDir()
        try {
            val keys = InMemoryDeviceKeyStore()
            val vault = vault(dataDir, keys)
            val id = WalletId.random()
            vault.create("Cancel", secret(ABANDON_PHRASE), secret(""), secret("Strong-Password1"), id)
            // Cancelled before it can start: nothing is counted, nothing is thrown but the cancellation.
            val early = async(Dispatchers.Default, start = CoroutineStart.LAZY) { vault.unlock(id, secret("Strong-Password1")) }
            early.cancel()
            assertFailsWith<CancellationException> { early.await() }
            assertFalse(VaultFiles(dataDir).attempts(id).exists())
            // Four counted failures do not lock yet, so nothing was counted for the cancelled call.
            repeat(4) { assertFailsWith<VaultError.AuthenticationFailed> { vault.unlock(id, secret("Wrong-Password1")) } }
            vault.unlock(id, secret("Strong-Password1")).close()
            assertTrue(VaultFiles(dataDir).attempts(id).exists())
            assertEquals(1, keys.keyCount)
        } finally {
            dataDir.deleteRecursively()
        }
    }
}
