package com.bittool.signer.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.crypto.vault.VaultError
import java.security.KeyStore
import javax.crypto.KeyGenerator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `receipts.bin` sealing (DECISIONS.md §9.3): `"BTSR" ‖ 0x01 ‖ iv ‖ ciphertext ‖ tag` under the Keystore key
 * `com.bittool.signer.activity.v1`, created on the first seal and never replaced. The test policy omits
 * `setUnlockedDeviceRequired` because the shared emulator has no lock screen (see KeystoreDeviceKeyStoreTest).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreJournalCipherTest {
    private val policy = KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false)

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Before
    @After
    fun removeKey() {
        val keystore = keystore()
        if (keystore.containsAlias(SignerFiles.RECEIPTS_KEY_ALIAS)) keystore.deleteEntry(SignerFiles.RECEIPTS_KEY_ALIAS)
    }

    @Test
    fun aJournalRoundTripsAndTamperingFailsAuthentication() {
        val cipher = KeystoreJournalCipher(policy)
        val journal = ("[" + List(2_000) { """{"id":"$it","title":"Signed"}""" }.joinToString(",") + "]").encodeToByteArray()
        val sealed = cipher.seal(journal)
        assertEquals(journal.size + 33, sealed.size)
        assertArrayEquals("BTSR".encodeToByteArray() + byteArrayOf(1), sealed.copyOf(5))
        assertArrayEquals(journal, cipher.open(sealed))
        // A new process reads it with the same key.
        assertArrayEquals(journal, KeystoreJournalCipher(policy).open(sealed))
        assertArrayEquals(ByteArray(0), cipher.open(cipher.seal(ByteArray(0))))

        val flipped = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(flipped) }
        val wrongVersion = sealed.copyOf().also { it[4] = 2 }
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(wrongVersion) }
        val wrongMagic = sealed.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(wrongMagic) }
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed.copyOf(32)) }
    }

    @Test
    fun aLostKeyReadsAsUnreadableAndIsNotSilentlyReplaced() {
        val cipher = KeystoreJournalCipher(policy)
        val sealed = cipher.seal("[]".encodeToByteArray())
        removeKey()
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed) }
        // This process used the key: a missing key now means loss (or a flaky Keystore), never "first use".
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { cipher.seal("[]".encodeToByteArray()) }
        // A new process (e.g. after "Clear Activity") starts a new journal key.
        val next = KeystoreJournalCipher(policy)
        assertArrayEquals("[]".encodeToByteArray(), next.open(next.seal("[]".encodeToByteArray())))
        assertThrows(VaultError.AuthenticationFailed::class.java) { next.open(sealed) }
    }

    @Test
    fun aForeignEntryUnderTheAliasIsNeverOverwritten() {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(SignerFiles.RECEIPTS_KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        val cipher = KeystoreJournalCipher(policy)
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { cipher.seal("[]".encodeToByteArray()) }
        assertEquals("HmacSHA256", keystore().getKey(SignerFiles.RECEIPTS_KEY_ALIAS, null).algorithm)
    }
}
