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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `receipts.bin` sealing (DECISIONS.md §9.3): `"BTSR" ‖ 0x01 ‖ iv ‖ ciphertext ‖ tag` under the Keystore key
 * `com.bittool.signer.activity.v1`, created on the first seal and replaced only when it is provably lost
 * (ARCHITECTURE §6 item 19, QA L1). The test policy omits `setUnlockedDeviceRequired` because the shared emulator has
 * no lock screen (see KeystoreDeviceKeyStoreTest).
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

    /**
     * QA L1 (ARCHITECTURE §6 item 19) on a real Keystore. Removing the screen lock deletes the journal key; here the test
     * deletes it, so it is absent while Keystore answers, which `KeystoreLossProof` proves. The old ciphertext stays
     * unreadable for good, and a fresh key is created only on that proof, through `discardLostKey` (the path
     * `ReceiptRepository.startOverIfKeyLost` takes). A usable key is never "lost".
     */
    @Test
    fun aLostKeyLeavesItsJournalUnreadableAndIsReplacedOnlyOnTheProof() {
        val journal = "[]".encodeToByteArray()
        val cipher = KeystoreJournalCipher(policy)
        val sealed = cipher.seal(journal)
        assertFalse("a usable key is not lost", cipher.discardLostKey())
        assertArrayEquals(journal, cipher.open(sealed))

        removeKey()
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed) }
        assertTrue("absent while Keystore answers: proven lost", cipher.discardLostKey())
        assertFalse("the proof creates nothing under the alias", keystore().containsAlias(SignerFiles.RECEIPTS_KEY_ALIAS))
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed) }

        // The next journal is sealed under a new key, readable in this process and the next one.
        val fresh = cipher.seal(journal)
        assertTrue(keystore().containsAlias(SignerFiles.RECEIPTS_KEY_ALIAS))
        assertArrayEquals(journal, cipher.open(fresh))
        assertArrayEquals(journal, KeystoreJournalCipher(policy).open(fresh))
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed) }
        assertFalse("the new key is usable", cipher.discardLostKey())
    }

    /**
     * The seal itself replaces a key this process used only when the same proof shows it gone. A real Keystore cannot be
     * made to read a working key as missing (the API 26–30 binder flakiness) or to stop answering; JournalKeyLossTest
     * covers those with the JVM stand-in, where the seal then fails with `DeviceKeyUnavailable`. Here the key is
     * provably absent, so the seal creates the new key, and what the lost key sealed never opens again.
     */
    @Test
    fun aKeyThisProcessUsedIsCreatedAgainBySealOnlyOnTheProof() {
        val journal = "[]".encodeToByteArray()
        val cipher = KeystoreJournalCipher(policy)
        val sealed = cipher.seal(journal)
        removeKey()
        val fresh = cipher.seal(journal)
        assertArrayEquals(journal, cipher.open(fresh))
        assertThrows(VaultError.AuthenticationFailed::class.java) { cipher.open(sealed) }
        // A new process (e.g. after "Clear Activity") reads the new journal and still not the old one.
        val next = KeystoreJournalCipher(policy)
        assertArrayEquals(journal, next.open(fresh))
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
        // The cipher has no usable key under the alias, but that is no proven loss: nothing is discarded or replaced.
        assertFalse(cipher.discardLostKey())
        assertThrows(VaultError.DeviceKeyUnavailable::class.java) { cipher.seal("[]".encodeToByteArray()) }
        assertEquals("HmacSHA256", keystore().getKey(SignerFiles.RECEIPTS_KEY_ALIAS, null).algorithm)
    }
}
