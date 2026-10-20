package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.testing.TestKeystore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore.Companion.LOSS_CONFIRMATIONS
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore.Companion.PROBE_PREFIX
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import java.security.UnrecoverableKeyException
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

/**
 * The lost-key proof of the production `AndroidKeystoreDeviceKeyStore` (QA backlog B9) against the Keystore failures a
 * device cannot be made to show on demand, through [FakeAndroidKeyStore] (a JVM `AndroidKeyStore` provider). The proof
 * is what lets a wallet be removed without its password, so a Keystore that does not answer, answers only sometimes,
 * loses the probe, fails to load a key or is locked must never pass for a lost key, while an absent or permanently
 * invalidated one must. The device counterparts (real Keystore, absent keys only) are `LostKeystoreKeyTest` and
 * `KeystoreDeviceKeyStoreTest`.
 *
 * Conscrypt stays off: Robolectric would install it as the JVM's first security provider for every later test of this
 * module too, and its SHA1PRNG ignores `setSeed` (SecureRandomTest relies on the JDK's deterministic one).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class KeystoreLossProofTest {
    private val keystore = FakeAndroidKeyStore()
    private var lockScreenSecure = true
    private lateinit var keys: AndroidKeystoreDeviceKeyStore
    private val dataDir = tempDataDir()
    private val aad = "aad".encodeToByteArray()
    private val message = ByteArray(1_000) { it.toByte() }

    @Before
    fun setUp() {
        keystore.install()
        keys = TestKeystore.deviceKeyStore { lockScreenSecure }
    }

    @After
    fun tearDown() {
        keystore.uninstall()
        dataDir.deleteRecursively()
    }

    private fun alias(id: WalletId) = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + id

    private val publicAlias = AndroidKeystoreDeviceKeyStore.ALIAS_PREFIX + AndroidKeystoreDeviceKeyStore.PUBLIC_DATA_KEY_ID

    private fun probesLeft() = keystore.aliases().filter { it.startsWith(PROBE_PREFIX) }

    private fun probesGenerated() = keystore.generated.count { it.startsWith(PROBE_PREFIX) }

    private fun newWalletKey(): WalletId = WalletId.random().also { keys.create(it) }

    private fun assertUsable(id: WalletId) {
        val plaintext = byteArrayOf(1, 2, 3)
        assertContentEquals(plaintext, keys.open(id, keys.seal(id, plaintext, aad), aad))
    }

    @Test
    fun aKeyKeystoreLacksWhileItDemonstrablyAnswersIsLost() {
        val id = newWalletKey()
        assertUsable(id)
        assertFalse(keys.isKeyLost(id), "a usable key")
        assertEquals(0, probesGenerated(), "a key that is there needs no probe")

        keystore.lose(alias(id))
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.seal(id, message, aad) }
        assertTrue(keys.isKeyLost(id))
        assertEquals(LOSS_CONFIRMATIONS, probesGenerated(), "one probe for each of the agreeing looks")
        assertTrue(probesLeft().isEmpty(), "every probe is deleted again")
        assertFalse(keystore.holds(alias(id)), "the proof creates nothing under the wallet's alias")
    }

    @Test
    fun aKeystoreThatDoesNotAnswerProvesNothing() {
        val live = newWalletKey()
        val gone = newWalletKey().also { keystore.lose(alias(it)) }
        keystore.outage = true
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.seal(live, message, aad) }
        assertFalse(keys.isKeyLost(live), "a live key that reads as missing")
        assertFalse(keys.isKeyLost(gone), "not even a key that is really gone: Keystore did not show that it answers")

        keystore.outage = false
        assertUsable(live)
        assertTrue(keys.isKeyLost(gone))
    }

    @Test
    fun aLiveKeyThatReadsAsMissingInOneLookIsNotLost() {
        // A binder failure during the first look only: the following looks see the key, so the looks disagree.
        val live = newWalletKey()
        var hidden = true
        keystore.readsMissing = { it == alias(live) && hidden }
        keystore.onGenerated = { if (it.startsWith(PROBE_PREFIX) && probesGenerated() == 2) hidden = false }
        assertFalse(keys.isKeyLost(live))
        assertTrue(probesGenerated() >= 2, "the second look began")
        assertUsable(live)
    }

    @Test
    fun aKeystoreThatAnswersOnlySometimesNeverMakesALiveKeyLost() {
        // Random binder failures (API 26–30): any read of any alias may report "missing". The proof needs the same
        // absence three times, each against a probe that reads as present before and after, so it never forms.
        val live = newWalletKey()
        val random = Random(20261021)
        keystore.readsMissing = { random.nextBoolean() }
        repeat(200) { assertFalse(keys.isKeyLost(live), "attempt $it") }

        keystore.readsMissing = { false }
        assertUsable(live)
        assertFalse(keys.isKeyLost(live))
        assertTrue(probesLeft().isEmpty(), "probes a failed read left behind are removed by the next call")
    }

    @Test
    fun aProbeKeystoreLosesProvesNothingAndIsRemovedLater() {
        val gone = WalletId.random()
        keystore.readsMissing = { it.startsWith(PROBE_PREFIX) }
        assertFalse(keys.isKeyLost(gone), "a probe that does not read back: Keystore did not answer")
        assertTrue(probesLeft().isNotEmpty())

        keystore.readsMissing = { false }
        assertTrue(keys.isKeyLost(gone))
        assertTrue(probesLeft().isEmpty(), "the leftover probe is deleted by the next call")
    }

    @Test
    fun aPermanentlyInvalidatedKeyIsLostButOneThatFailsToLoadOrIsLockedIsNot() {
        val id = newWalletKey()
        // keystore2 reports even a permanently invalidated entry this way from getKey: no proof.
        keystore.loadFailures[alias(id)] = UnrecoverableKeyException("Failed to obtain information about key")
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.seal(id, message, aad) }
        assertFalse(keys.isKeyLost(id), "a key that fails to load may still be usable later")
        keystore.loadFailures.clear()

        keystore.locked = true
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.seal(id, message, aad) }
        assertFalse(keys.isKeyLost(id), "a locked device")
        keystore.locked = false

        keystore.invalidated += alias(id)
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.seal(id, message, aad) }
        assertTrue(keys.isKeyLost(id), "KeyPermanentlyInvalidatedException")
        assertEquals(0, probesGenerated(), "an existing entry needs no probe")
        assertTrue(keystore.holds(alias(id)), "the proof deletes nothing")
    }

    @Test
    fun theVaultRemovesAWalletWhoseKeystoreEntryIsInvalidatedTogetherWithTheEntry(): Unit = runBlocking {
        val vault = vault(dataDir, keys)
        val files = VaultFiles(dataDir)
        val kept = vault.create("Kept", secret(ABANDON_PHRASE), secret(""), secret(PASSWORD), WalletId.random())
        val lost = vault.create("Invalidated", secret(ABANDON_PHRASE), secret(""), secret(PASSWORD), WalletId.random())
        assertFalse(vault.isDeviceKeyLost(lost.id))

        keystore.invalidated += alias(lost.id)
        assertFailsWith<VaultError.DeviceKeyUnavailable> { vault.unlock(lost.id, secret(PASSWORD)) }
        assertFailsWith<VaultError.DeviceKeyUnavailable> { vault.delete(lost.id, secret(PASSWORD)) }
        assertTrue(vault.isDeviceKeyLost(lost.id))
        vault.removeWithLostDeviceKey(lost.id)

        assertFalse(files.vault(lost.id).exists())
        assertFalse(keystore.holds(alias(lost.id)), "the invalidated remnant is deleted with the wallet")
        assertEquals(listOf(kept.id), vault.list().map { it.id })
        vault.unlock(kept.id, secret(PASSWORD)).close()
    }

    @Test
    fun aPublicDataKeyThisProcessUsedIsNeverReplacedWhileItOnlyReadsAsMissing() {
        val tag = assertNotNull(keys.publicDataKey(createIfMissing = true)).mac(message)
        keystore.outage = true
        assertNull(keys.publicDataKey(createIfMissing = false))
        assertFailsWith<VaultError.DeviceKeyUnavailable> { keys.publicDataKey(createIfMissing = true) }
        keystore.outage = false

        val random = Random(7)
        keystore.readsMissing = { random.nextBoolean() }
        repeat(100) {
            try {
                keys.publicDataKey(createIfMissing = true)
            } catch (_: VaultError.DeviceKeyUnavailable) {
                // Refused while it reads as missing: the point.
            }
        }
        keystore.readsMissing = { false }
        assertEquals(1, keystore.generated.count { it == publicAlias }, "never generated over the live key")
        assertContentEquals(tag, assertNotNull(keys.publicDataKey(createIfMissing = false)).mac(message))
    }

    @Test
    fun aPublicDataKeyThatIsProvablyGoneIsCreatedAgainInTheSameProcess() {
        // QA backlog B9: when Keystore lost every key (the wallets' and this one), the wallets removed without their
        // passwords must be importable again without a relaunch, and the import saves its public file under this key.
        val tag = assertNotNull(keys.publicDataKey(createIfMissing = true)).mac(message)
        keystore.lose(publicAlias)
        assertNull(keys.publicDataKey(createIfMissing = false))

        lockScreenSecure = false
        assertFailsWith<VaultError.DevicePasscodeRequired> { keys.publicDataKey(createIfMissing = true) }
        lockScreenSecure = true
        val again = assertNotNull(keys.publicDataKey(createIfMissing = true))
        assertFalse(again.verify(message, tag), "a new key: the old public files stay invalid")
        assertEquals(2, keystore.generated.count { it == publicAlias })
        assertTrue(probesLeft().isEmpty())
        assertContentEquals(again.mac(message), assertNotNull(keys.publicDataKey(createIfMissing = false)).mac(message))
    }

    private companion object {
        const val PASSWORD = "Strong-Password1"
    }
}
