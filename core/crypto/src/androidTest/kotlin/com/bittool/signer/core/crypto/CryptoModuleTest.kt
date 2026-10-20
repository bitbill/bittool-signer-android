package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.WalletVault
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The production Hilt graph of `:core:crypto` (ARCHITECTURE.md §4.10) resolves with `:core:common`'s modules: every
 * interface gets its implementation, singletons are shared, and without a debug override [DeviceKeyStore] is the
 * Android Keystore. On the lock-screen-less emulator the vault refuses to create a wallet, as it must.
 */
@HiltAndroidTest
class CryptoModuleTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var vault: WalletVault
    @Inject lateinit var deviceKeys: DeviceKeyStore
    @Inject lateinit var journal: JournalCipher
    @Inject lateinit var bip39: Bip39
    @Inject lateinit var otherBip39: Bip39
    @Inject lateinit var seed: Bip39Seed
    @Inject lateinit var random: SecureRandomSource

    @Before
    fun inject() = hilt.inject()

    @Test
    fun everyCryptoInterfaceIsBoundToItsProductionImplementation(): Unit = runBlocking {
        assertTrue(vault is DefaultWalletVault)
        assertTrue(deviceKeys is AndroidKeystoreDeviceKeyStore)
        assertTrue(journal is KeystoreJournalCipher)
        assertTrue(bip39 is DefaultBip39)
        assertSame(bip39, otherBip39)
        assertTrue(seed is Pbkdf2Bip39Seed)
        assertTrue(random is SystemSecureRandomSource)

        assertTrue(bip39.wordlist(Bip39Language.English).isAvailable)
        assertEquals(64, random.entropyHex(24).size)
        if (!deviceKeys.isDeviceSecure()) {
            assertThrows(VaultError.DevicePasscodeRequired::class.java) {
                runBlocking {
                    vault.create("Hilt", SecretText.of(ABANDON), SecretText.empty(), SecretText.of("Strong-Password1"), WalletId.random())
                }
            }
        }
        assertTrue(vault.list().isEmpty())
    }

    private companion object {
        const val ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
