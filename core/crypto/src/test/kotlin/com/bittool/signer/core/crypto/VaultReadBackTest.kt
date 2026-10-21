package com.bittool.signer.core.crypto

import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.VaultFormat
import com.bittool.signer.core.crypto.vault.WalletNames
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.Graphemes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * QA audit A04 and A05 (Android; iOS has the same latent A04 defect): every vault this app writes reads back, so one
 * file can never make `list()` — and with it every wallet on the device — unavailable; and a Keystore failure of the
 * device layer during an unlock is neither "The password is incorrect." nor a counted attempt.
 */
class VaultReadBackTest {
    private val password = "Strong-Password1"
    private val dataDir = tempDataDir()
    private val files = VaultFiles(dataDir)

    @After
    fun cleanUp() {
        dataDir.deleteRecursively()
    }

    /** [clusters] grapheme clusters, the i-th an "e" with its share of [totalMarks] combining acute accents (2 bytes each). */
    private fun stackedName(totalMarks: Int, clusters: Int = 40): String = buildString {
        repeat(clusters) { i -> append('e').append("́".repeat(totalMarks / clusters + if (i < totalMarks % clusters) 1 else 0)) }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)

    private val deviceLayerAad = VaultFormat.deviceAad(ByteArray(0))

    @Test
    fun aNameOfFortyHeavyClustersIsRefusedBeforeAnyKeyOrFileAndEveryWalletStaysAvailable(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        val existing = store.create("Existing", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        // 40 clusters of a letter and 60 marks (121 UTF-8 bytes each): the iOS rule accepts it, the header would be
        // about 5 KB, which the reader refuses.
        val name = stackedName(totalMarks = 40 * 60)
        assertEquals(40, Graphemes.count(name))
        assertNotNull(VaultMetadata.validName(name))
        assertNull(WalletNames.valid(name))

        val id = WalletId.random()
        val refused = assertFailsWith<VaultError.InvalidName> { store.create(name, secret(ABANDON_PHRASE), secret(""), secret(password), id) }
        assertEquals(UiText.Ios("Choose a wallet name of 1–40 characters."), refused.text)
        assertFalse(files.vault(id).exists())
        assertFalse(keys.containsKey(id))
        assertEquals(1, keys.keyCount, "no key was made for the refused name")
        assertEquals(listOf(existing.id), store.list().map { it.id })
        // A new process reads the collection too.
        assertEquals(listOf(existing.id), vault(dataDir, keys).list().map { it.id })
    }

    @Test
    fun theLongestAcceptedNameReadsBackAndOneMoreMarkIsRefused(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        var marks = 0
        while (WalletNames.valid(stackedName(marks + 1)) != null) marks++
        val longest = stackedName(marks)
        assertTrue(longest.encodeToByteArray().size > 3_800, "the bound is the header, not a small byte cap")

        val wallet = store.create(longest, secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        assertTrue(files.headerBytes(wallet.id).size <= VaultFormat.MAX_HEADER_BYTES)
        assertEquals(listOf(longest), vault(dataDir, keys).list().map { it.name })
        store.unlock(wallet.id, secret(password)).close()

        assertFailsWith<VaultError.InvalidName> {
            store.create(stackedName(marks + 1), secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        }
        assertEquals(1, keys.keyCount)
    }

    @Test
    fun aContainerTheReaderWouldRefuseIsNeverWritten(): Unit = runBlocking {
        // A device layer that returns more than the reader accepts (a ciphertext over 32,768 bytes): whatever produced
        // it, the vault must not commit a file that would make list() fail.
        val keys = InMemoryDeviceKeyStore()
        var inflate = false
        val inflating = object : DeviceKeyStore by keys {
            override fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray {
                val sealed = keys.seal(id, plaintext, aad)
                return if (inflate && aad.startsWith(deviceLayerAad)) sealed + ByteArray(40_000) else sealed
            }
        }
        val store = vault(dataDir, inflating)
        val kept = store.create("Kept", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())

        inflate = true
        val id = WalletId.random()
        assertFailsWith<VaultError.EncryptionFailure> { store.create("Inflated", secret(ABANDON_PHRASE), secret(""), secret(password), id) }
        assertFalse(files.vault(id).exists())
        assertFalse(keys.containsKey(id), "never a key without a file")
        assertFailsWith<VaultError.EncryptionFailure> { store.changePassword(kept.id, secret(password), secret("Replacement-Password2")) }

        inflate = false
        assertEquals(listOf(kept.id), store.list().map { it.id })
        store.unlock(kept.id, secret(password)).close() // the old file and password are untouched
    }

    @Test
    fun aSecretWhoseCiphertextTheReaderWouldRefuseIsInvalidBeforeAnyKey(): Unit = runBlocking {
        // 8,192 UTF-8 bytes of control characters pass the iOS byte bounds but escape to 49,152 JSON bytes. The store only
        // passes canonical words; the vault still refuses this before key generation.
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        assertFailsWith<VaultError.InvalidSecret> {
            store.create("Controls", secret("\u0001".repeat(8_192)), secret(""), secret(password), WalletId.random())
        }
        assertEquals(0, keys.keyCount)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun aDeviceKeyFailureDuringUnlockIsNotAWrongPasswordAndIsNotCounted(): Unit = runBlocking {
        // Keystore refusing the wallet key at the device layer (a provider or binder failure, the screen locking during
        // the unlock) after the attempt was counted. iOS reads its device key before counting (`WalletVault.swift:137`).
        val keys = InMemoryDeviceKeyStore()
        var failDeviceLayer = false
        val flaky = object : DeviceKeyStore by keys {
            override fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray {
                if (failDeviceLayer && aad.startsWith(deviceLayerAad)) throw VaultError.DeviceKeyUnavailable()
                return keys.open(id, combined, aad)
            }
        }
        val clock = FakeWallClock()
        val store = vault(dataDir, flaky, clock)
        val wallet = store.create("Flaky", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(4) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) } }

        failDeviceLayer = true
        repeat(3) {
            val failed = assertFailsWith<VaultError.DeviceKeyUnavailable> { store.unlock(wallet.id, secret(password)) }
            assertEquals(
                UiText.Ios("This wallet’s device key is unavailable. Restore your recovery phrase on this device."),
                failed.text,
            )
        }
        failDeviceLayer = false
        // Still four failures: the 5th-failure delay has not started and the right password opens the wallet at once.
        store.unlock(wallet.id, secret(password)).close()

        // A wrong password still counts as before: five failures, then a delay.
        repeat(5) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) } }
        assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }
    }
}
