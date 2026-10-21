package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.testing.TestKeystore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore.Companion.LOSS_CONFIRMATIONS
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore.Companion.PROBE_PREFIX
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.VaultError
import java.security.UnrecoverableKeyException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * The Activity journal's lost key (QA L1, Android-only: removing the screen lock deletes every
 * `setUnlockedDeviceRequired` key, the journal key among them) through the production `KeystoreJournalCipher` and the
 * JVM Keystore stand-in [FakeAndroidKeyStore]. Before the fix a missing key read like a damaged file (`open` fails with
 * `AuthenticationFailed` either way) and could never be told apart, so the journal stayed unreadable for good, and a
 * key this process had used was never created again. Now `discardLostKey` answers with the very proof the wallets'
 * device keys use (`KeystoreLossProofTest`): only a key Keystore lacks while it demonstrably answers, or a permanently
 * invalidated one, is lost; a damaged file, a Keystore that does not answer, a flaky read or a locked device is not.
 *
 * Conscrypt stays off, as in [KeystoreLossProofTest].
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class JournalKeyLossTest {
    private val keystore = FakeAndroidKeyStore()
    private val alias = KeystoreJournalCipher.ALIAS
    private val journal = "[{\"public\":\"activity\"}]".encodeToByteArray()

    @Before
    fun setUp() = keystore.install()

    @After
    fun tearDown() = keystore.uninstall()

    private fun probesLeft() = keystore.aliases().filter { it.startsWith(PROBE_PREFIX) }

    private fun keysGenerated() = keystore.generated.count { it == alias }

    @Test
    fun aJournalWhoseKeyIsGoneStartsAgainUnderANewKey() {
        val cipher = TestKeystore.journalCipher()
        val old = cipher.seal(journal)
        assertContentEquals(journal, cipher.open(old))
        assertFalse(cipher.discardLostKey(), "a usable key is not lost")
        assertTrue(keystore.holds(alias))

        keystore.lose(alias)
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(old) }
        assertTrue(cipher.discardLostKey())
        assertEquals(LOSS_CONFIRMATIONS, keystore.generated.count { it.startsWith(PROBE_PREFIX) }, "one probe per agreeing look")
        assertTrue(probesLeft().isEmpty(), "every probe is deleted again")
        assertEquals(1, keysGenerated(), "the proof creates nothing under the journal's alias")

        // The next journal is sealed under a new key in the same process (the key had worked here before).
        val fresh = cipher.seal(journal)
        assertEquals(2, keysGenerated())
        assertContentEquals(journal, cipher.open(fresh))
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(old) }
        assertFalse(cipher.discardLostKey(), "the new key is usable")
    }

    @Test
    fun aKeyThisProcessUsedIsCreatedAgainOnlyWhenTheProofShowsItGone() {
        // The seal itself recreates a used key only on the proof (a fresh process creates it whenever it is absent).
        val cipher = TestKeystore.journalCipher()
        cipher.seal(journal)
        keystore.lose(alias)
        keystore.outage = true
        assertFailsWith<VaultError.DeviceKeyUnavailable> { cipher.seal(journal) }
        keystore.outage = false
        assertEquals(1, keysGenerated())
        assertContentEquals(journal, cipher.open(cipher.seal(journal)))
        assertEquals(2, keysGenerated())
    }

    @Test
    fun aDamagedJournalWhoseKeyIsInPlaceIsNotALoss() {
        val cipher = TestKeystore.journalCipher()
        val sealed = cipher.seal(journal)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(sealed) }
        assertFalse(cipher.discardLostKey(), "the key opens a good journal: the file is damaged, the key is not lost")
        assertTrue(keystore.holds(alias))
        assertEquals(0, keystore.generated.count { it.startsWith(PROBE_PREFIX) }, "a key that is there needs no probe")
    }

    @Test
    fun aKeystoreThatDoesNotAnswerOrAnswersOnlySometimesNeverMakesTheLiveKeyLost() {
        val cipher = TestKeystore.journalCipher()
        val sealed = cipher.seal(journal)
        keystore.outage = true
        assertFalse(cipher.discardLostKey())
        keystore.outage = false

        val random = Random(20261021)
        keystore.readsMissing = { random.nextBoolean() }
        repeat(100) {
            assertFalse(cipher.discardLostKey(), "attempt $it")
            try {
                cipher.seal(journal)
            } catch (_: VaultError) {
                // Refused while the key reads as missing: the point.
            }
        }
        keystore.readsMissing = { false }
        assertEquals(1, keysGenerated(), "never generated over the live key")
        assertContentEquals(journal, cipher.open(sealed))
        assertFalse(cipher.discardLostKey())
        assertTrue(probesLeft().isEmpty(), "probes a failed read left behind are removed by the next proof")
    }

    @Test
    fun aKeyThatFailsToLoadOrALockedDeviceIsNotALossButAnInvalidatedKeyIsAndIsRemoved() {
        val cipher = TestKeystore.journalCipher()
        val sealed = cipher.seal(journal)

        keystore.loadFailures[alias] = UnrecoverableKeyException("Failed to obtain information about key")
        assertFalse(cipher.discardLostKey(), "a key that fails to load may still be usable later")
        keystore.loadFailures.clear()

        keystore.locked = true
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(sealed) }
        assertFalse(cipher.discardLostKey(), "a locked device")
        keystore.locked = false
        assertContentEquals(journal, cipher.open(sealed))

        keystore.invalidated += alias
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(sealed) }
        assertTrue(cipher.discardLostKey(), "KeyPermanentlyInvalidatedException")
        assertFalse(keystore.holds(alias), "the invalidated remnant is removed, so a new key can take the alias")
        keystore.invalidated.clear()
        assertContentEquals(journal, cipher.open(cipher.seal(journal)))
        assertEquals(2, keysGenerated())
    }

    @Test
    fun theJournalProofAndTheDeviceKeyProofNeverDisturbEachOther() {
        // Both use the same probe prefix; one lock serialises them, so neither deletes the other's probe mid-look.
        val cipher = TestKeystore.journalCipher()
        val keys = TestKeystore.deviceKeyStore()
        cipher.seal(journal)
        keystore.lose(alias)
        val lostWallet = com.bittool.signer.core.model.WalletId.random()
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val threads = List(4) { index ->
            Thread {
                repeat(5) { results += if (index % 2 == 0) cipher.discardLostKey() else keys.isKeyLost(lostWallet) }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(20, results.size)
        assertTrue(results.all { it }, "every proof formed: no probe was deleted under another look")
        assertTrue(probesLeft().isEmpty())
    }
}
