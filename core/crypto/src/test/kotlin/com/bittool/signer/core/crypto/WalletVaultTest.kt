package com.bittool.signer.core.crypto

import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.Hex
import java.io.File
import org.junit.After
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The vault port of iOS `SecurityTests` (`BitToolSignerTests/SecurityTests.swift`: round trip, tampering, throttling
 * across instances, rotation and re-authenticated delete, the three password-reset cases) run through
 * [com.bittool.signer.core.crypto.vault.DefaultWalletVault] with the in-memory device keys the iOS tests use
 * (`MemoryDeviceKeys`). The iOS `VaultCrypto` cases become file-level tampering, because on Android the device layer
 * is a key-store operation rather than raw key bytes.
 */
class WalletVaultTest {
    private val password = "Strong-Password1"
    private val dataDir = tempDataDir()
    private val files = VaultFiles(dataDir)

    @After
    fun cleanUp() {
        dataDir.deleteRecursively()
    }

    @Test
    fun encryptionRoundTripAndDistinctSaltAndNonce(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        val passphrase = "a separate BIP39 passphrase"
        val first = store.create("Offline wallet", secret(ABANDON_PHRASE), secret(passphrase), secret(password), WalletId.random())
        val second = store.create("Offline wallet", secret(ABANDON_PHRASE), secret(passphrase), secret(password), WalletId.random())
        assertNotEquals(files.header(first.id)["salt"], files.header(second.id)["salt"])
        assertFalse(files.ciphertext(first.id).contentEquals(files.ciphertext(second.id)))

        store.unlock(first.id, secret(password)).use {
            assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars()))
            assertEquals(passphrase, String(it.passphrase.chars()))
        }
        // A new service instance (the process restarted) reads the stored file.
        vault(dataDir, keys).unlock(first.id, secret(password)).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }

        val stored = files.vault(first.id).readText() + files.headerBytes(first.id).decodeToString()
        assertFalse(stored.contains(ABANDON_PHRASE))
        assertFalse(stored.contains("abandon"))
        assertFalse(stored.contains(password))
        assertFalse(stored.contains(passphrase))
    }

    @Test
    fun authenticationRejectsWrongPasswordDeviceAndTampering(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)

        val wrongPassword = store.create("Original", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wrongPassword.id, secret("Wrong-Password1")) }

        // A different device key (iOS: an all-zero key). No attempts file exists yet, so the vault layer is reached.
        val wrongDevice = store.create("Original", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        keys.replaceKey(wrongDevice.id)
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wrongDevice.id, secret(password)) }

        val flipped = store.create("Original", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val damaged = files.ciphertext(flipped.id).also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        files.rewrite(flipped.id, ciphertext = damaged)
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(flipped.id, secret(password)) }

        val renamed = store.create("Original", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        files.rewrite(renamed.id, headerBytes = files.encode(withName(files.header(renamed.id), "Attacker renamed")))
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(renamed.id, secret(password)) }

        val unreasonable = store.create("Original", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val huge = JsonObject(files.header(unreasonable.id) + ("iterations" to JsonPrimitive(4_294_967_295L)))
        files.rewrite(unreasonable.id, headerBytes = files.encode(huge))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(unreasonable.id, secret(password)) }

        // The untouched original still opens.
        store.unlock(wrongPassword.id, secret(password)).close()
    }

    @Test
    fun throttlingSurvivesANewVaultServiceInstance(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val clock = FakeWallClock()
        val store = vault(dataDir, keys, clock)
        val wallet = store.create("Throttle test", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(5) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) } }
        val restarted = vault(dataDir, keys, clock)
        val locked = assertFailsWith<VaultError.TemporarilyLocked> { restarted.unlock(wallet.id, secret(password)) }
        assertTrue(locked.seconds > 0)
        assertEquals(UiText.Ios("Too many unsuccessful attempts. Try again in %lld seconds.", listOf(locked.seconds)), locked.text)
    }

    @Test
    fun multipleWalletsPasswordRotationAndReauthenticatedDelete(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        assertTrue(store.list().isEmpty())
        val first = store.create("First", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val second = store.create("Second", secret(ABANDON_PHRASE), secret("different account"), secret("Another-Password2"), WalletId.random())
        assertEquals(2, store.list().size)
        store.unlock(first.id, secret(password)).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }
        store.unlock(second.id, secret("Another-Password2")).use { assertEquals("different account", String(it.passphrase.chars())) }
        store.changePassword(first.id, secret(password), secret("Replacement-Password2"))
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(first.id, secret(password)) }
        store.unlock(first.id, secret("Replacement-Password2")).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }
        assertFailsWith<VaultError.AuthenticationFailed> { store.delete(first.id, secret("Wrong-Password1")) }
        assertEquals(2, store.list().size)
        store.delete(first.id, secret("Replacement-Password2"))
        assertEquals(listOf(second.id), store.list().map { it.id })
        // The vault file, its attempts file and its device key are gone; the other wallet's are untouched.
        assertFalse(files.vault(first.id).exists())
        assertFalse(files.attempts(first.id).exists())
        assertFalse(keys.containsKey(first.id))
        assertTrue(keys.containsKey(second.id))
    }

    @Test
    fun aForgottenPasswordIsResetFromTheRecoveryPhrase(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Mine", secret(ABANDON_PHRASE), secret("account two"), secret(password), WalletId.random())
        store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret("account two"), secret("Replacement-Password2"))
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret(password)) }
        store.unlock(wallet.id, secret("Replacement-Password2")).use {
            assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars()))
            assertEquals("account two", String(it.passphrase.chars()))
        }
        val listed = store.list()
        assertEquals(1, listed.size, "the wallet is reset in place, never duplicated")
        assertEquals(wallet, listed.single())
    }

    @Test
    fun aResetStillHasToOfferAnAcceptablePasswordAndAnExistingWallet(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Mine", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val before = files.vault(wallet.id).readBytes()
        val weak = assertFailsWith<VaultError.InvalidPassword> {
            store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret("weak"))
        }
        assertEquals(UiText.Ios("Use 8–64 characters."), weak.policyMessage)
        assertContentEquals(before, files.vault(wallet.id).readBytes(), "a refused reset changes nothing")
        store.unlock(wallet.id, secret(password)).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }
        assertFailsWith<VaultError.WalletNotFound> {
            store.resetPassword(WalletId.random(), secret(ABANDON_PHRASE), secret(""), secret("Replacement-Password2"))
        }
        val longPassphrase = assertFailsWith<VaultError.InvalidPassword> {
            store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret("p".repeat(1_025)), secret("Replacement-Password2"))
        }
        assertEquals(UiText.Ios("The BIP39 passphrase exceeds 1,024 UTF-8 bytes."), longPassphrase.policyMessage)
    }

    @Test
    fun aResetClearsTheFailedAttemptDelay(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Mine", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(5) { runCatching { store.unlock(wallet.id, secret("Wrong-Password1")) } }
        assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }
        store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret("Replacement-Password2"))
        store.unlock(wallet.id, secret("Replacement-Password2")).use { assertEquals(ABANDON_PHRASE, String(it.mnemonic.chars())) }
    }

    // region HardeningTests.vaultAuthenticatesTheExactStoredHeaderBytes and the Android byte format

    @Test
    fun vaultAuthenticatesTheExactStoredHeaderBytes(): Unit = runBlocking {
        val store = vault(dataDir, clock = FakeWallClock(1_700_000_000_123L))
        val wallet = store.create("Header", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        store.unlock(wallet.id, secret(password)).close()
        // Semantically identical but differently encoded header bytes are rejected.
        val header = files.header(wallet.id)
        files.rewrite(wallet.id, headerBytes = prettyPrinted(header).encodeToByteArray())
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret(password)) }
        // format 1 is refused before anything else.
        files.rewrite(wallet.id, format = JsonPrimitive(1))
        assertFailsWith<VaultError.UnsupportedVersion> { store.unlock(wallet.id, secret(password)) }
        assertFailsWith<VaultError.UnsupportedVersion> { store.list() }
    }

    @Test
    fun theFileIsFormatTwoWithTheExactHeaderBytes(): Unit = runBlocking {
        val clock = FakeWallClock(1_760_000_000_123L)
        val store = vault(dataDir, clock = clock)
        val id = WalletId.random()
        val wallet = store.create("  Cold / “Storage”  ", secret(ABANDON_PHRASE), secret(""), secret(password), id)
        assertEquals("Cold / “Storage”", wallet.name, "the name is trimmed")
        assertEquals(1_760_000_000_123L, wallet.createdAtMillis)

        val text = files.vault(id).readText()
        assertTrue(Regex("""^\{"ciphertext":"[A-Za-z0-9+/]+=*","format":2,"header":"[A-Za-z0-9+/]+=*"}$""").matches(text), text)
        val header = files.headerBytes(id).decodeToString()
        val salt = Regex(""""salt":"([^"]+)"""").find(header)!!.groupValues[1]
        assertEquals(32, java.util.Base64.getDecoder().decode(salt).size)
        // (1_760_000_000_123 − 978_307_200_000) / 1000 seconds since 2001, millisecond precision, plain notation.
        assertEquals(
            """{"iterations":600000,"kdf":"PBKDF2-HMAC-SHA256","metadata":{"createdAt":781692800.123,"id":"${id.uppercase}",""" +
                """"name":"Cold / “Storage”"},"salt":"$salt","version":2}""",
            header,
        )
        // Two GCM layers of 12 + 16 bytes over {"mnemonic":"…","passphrase":""}.
        assertEquals("""{"mnemonic":"$ABANDON_PHRASE","passphrase":""}""".encodeToByteArray().size + 56, files.ciphertext(id).size)
        assertEquals(wallet, store.list().single(), "createdAt and name read back exactly")
    }

    @Test
    fun changingThePasswordKeepsTheHeaderMetadataUnderANewSalt(): Unit = runBlocking {
        val store = vault(dataDir, clock = FakeWallClock(1_760_000_000_001L))
        val wallet = store.create("Keep me", secret(ABANDON_PHRASE), secret("pp"), secret(password), WalletId.random())
        val before = files.header(wallet.id)
        store.changePassword(wallet.id, secret(password), secret("Replacement-Password2"))
        val after = files.header(wallet.id)
        assertEquals(before["metadata"], after["metadata"])
        assertNotEquals(before["salt"], after["salt"])
        assertEquals(listOf(wallet), store.list())
    }

    // endregion

    // region throttling (vault-crypto.md §8)

    @Test
    fun theDelayTableAndUncountedLockedAttempts(): Unit = runBlocking {
        val clock = FakeWallClock()
        val store = vault(dataDir, clock = clock)
        val wallet = store.create("Delays", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        // count after the attempt → delay before the next one: 1–4 → 0, 5 → 2, 6 → 4, … 12 → 256, 13–16 → 300.
        val expected = listOf(0L, 0, 0, 0, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300, 300, 300, 300)
        for (delay in expected) {
            assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) }
            if (delay > 0) {
                val locked = assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }
                assertEquals(delay, locked.seconds)
                clock.advanceBy(delay * 1_000 - 500)
                assertEquals(1L, assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }.seconds)
                clock.advanceBy(500)
            }
        }
        // The count saturates at 16 and a correct password works outside the window, then clears the counter.
        store.unlock(wallet.id, secret(password)).close()
        repeat(4) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) } }
        store.unlock(wallet.id, secret(password)).close()
    }

    @Test
    fun aDamagedAttemptsFileFailsClosedUntilAResetReplacesIt(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Damaged", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("Wrong-Password1")) }
        files.attempts(wallet.id).writeBytes(ByteArray(40) { 7 })
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        assertFailsWith<VaultError.MalformedVault> { store.verifyPassword(wallet.id, secret(password)) }
        files.attempts(wallet.id).writeBytes(ByteArray(2_000))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret(password))
        store.verifyPassword(wallet.id, secret(password))
    }

    @Test
    fun verifyPasswordSharesTheCounter(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Shared", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(5) { assertFailsWith<VaultError.AuthenticationFailed> { store.verifyPassword(wallet.id, secret("Wrong-Password1")) } }
        assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }
        assertFailsWith<VaultError.TemporarilyLocked> { store.delete(wallet.id, secret(password)) }
        assertTrue(files.vault(wallet.id).exists())
    }

    @Test
    fun emptyAndOversizedPasswordsAreCountedFailures(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Bounds", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        repeat(2) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("")) } }
        repeat(3) { assertFailsWith<VaultError.AuthenticationFailed> { store.unlock(wallet.id, secret("A".repeat(1_025))) } }
        assertFailsWith<VaultError.TemporarilyLocked> { store.unlock(wallet.id, secret(password)) }
    }

    // endregion

    // region device key and lock screen (vault-crypto.md §7, §17.3)

    @Test
    fun aSecureLockScreenIsRequiredAndTheRefusalIsNotCounted(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        val wallet = store.create("Locked", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        keys.deviceSecure = false
        assertFailsWith<VaultError.DevicePasscodeRequired> {
            store.create("No lock", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        }
        repeat(6) { assertFailsWith<VaultError.DevicePasscodeRequired> { store.unlock(wallet.id, secret("Wrong-Password1")) } }
        assertFailsWith<VaultError.DevicePasscodeRequired> { store.publicDataKey(createIfMissing = true) }
        assertEquals(null, store.publicDataKey(createIfMissing = false))
        assertFalse(files.attempts(wallet.id).exists(), "a refused attempt is not counted")
        assertEquals(1, keys.keyCount)
        keys.deviceSecure = true
        store.unlock(wallet.id, secret(password)).close()
    }

    @Test
    fun aMissingDeviceKeyIsUnavailableAndNeverRecreated(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        val wallet = store.create("Lost key", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        keys.forgetKey(wallet.id)
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.unlock(wallet.id, secret(password)) }
        assertFalse(keys.containsKey(wallet.id))
        assertFalse(files.attempts(wallet.id).exists(), "nothing was counted")
        assertFailsWith<VaultError.DeviceKeyUnavailable> {
            store.resetPassword(wallet.id, secret(ABANDON_PHRASE), secret(""), secret("Replacement-Password2"))
        }
        assertFalse(keys.containsKey(wallet.id))
    }

    @Test
    fun aFailedCreationLeavesNeitherAFileNorAKey(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        val id = WalletId.random()
        keys.failNextSeal = true
        assertFailsWith<VaultError.DeviceKeyUnavailable> { store.create("Fails", secret(ABANDON_PHRASE), secret(""), secret(password), id) }
        assertFalse(keys.containsKey(id))
        assertFalse(files.vault(id).exists())
        assertTrue(store.list().isEmpty())
        // The same id works afterwards.
        store.create("Works", secret(ABANDON_PHRASE), secret(""), secret(password), id)
        assertEquals(listOf(id), store.list().map { it.id })
        // An existing file is never overwritten, and its key stays.
        assertFailsWith<VaultError.StorageFailure> { store.create("Again", secret(ABANDON_PHRASE), secret(""), secret(password), id) }
        store.unlock(id, secret(password)).close()
    }

    @Test
    fun creationValidatesNamePasswordAndSecretFirst(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        assertFailsWith<VaultError.InvalidName> { store.create(" \n ", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random()) }
        assertFailsWith<VaultError.InvalidName> { store.create("x".repeat(41), secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random()) }
        store.create("👍🏽".repeat(40), secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random()) // 40 graphemes
        // A lone surrogate (impossible in Swift) would be stored as "?" and not read back as the returned name.
        assertFailsWith<VaultError.InvalidName> { store.create("Wallet \uD83D", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random()) }
        val weak = assertFailsWith<VaultError.InvalidPassword> {
            store.create("Weak", secret(ABANDON_PHRASE), secret(""), secret("alllowercase"), WalletId.random())
        }
        assertEquals(UiText.Ios("Include at least 3 of: uppercase, lowercase, numbers and symbols."), weak.policyMessage)
        assertFailsWith<VaultError.InvalidPassword> {
            store.create("Lone", secret(ABANDON_PHRASE), secret(""), secret("Abcdefg1\uD800"), WalletId.random())
        }
        assertFailsWith<VaultError.InvalidSecret> { store.create("Empty", secret(""), secret(""), secret(password), WalletId.random()) }
        assertFailsWith<VaultError.InvalidSecret> {
            store.create("Big", secret("a".repeat(8_193)), secret(""), secret(password), WalletId.random())
        }
        assertFailsWith<VaultError.InvalidSecret> {
            store.create("Passphrase", secret(ABANDON_PHRASE), secret("é".repeat(513)), secret(password), WalletId.random())
        }
        assertEquals(1, keys.keyCount, "no refused creation made a key")
    }

    @Test
    fun theWeakNewPasswordOfAChangeIsRefusedBeforeAnythingIsCounted(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Change", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        assertFailsWith<VaultError.InvalidPassword> { store.changePassword(wallet.id, secret("Wrong-Password1"), secret("weak")) }
        assertFalse(files.attempts(wallet.id).exists())
        assertFailsWith<VaultError.AuthenticationFailed> {
            store.changePassword(wallet.id, secret("Wrong-Password1"), secret("Replacement-Password2"))
        }
        store.unlock(wallet.id, secret(password)).close()
    }

    @Test
    fun thePublicDataKeyIsCreatedOnceAndOutlivesTheLastWallet(): Unit = runBlocking {
        val keys = InMemoryDeviceKeyStore()
        val store = vault(dataDir, keys)
        assertEquals(null, store.publicDataKey(createIfMissing = false))
        val created = store.publicDataKey(createIfMissing = true)!!
        val message = "com.bittool.signer.public-wallet.v2".encodeToByteArray()
        val tag = created.mac(message)
        assertEquals(32, tag.size)
        assertContentEquals(tag, store.publicDataKey(createIfMissing = true)!!.mac(message))
        assertContentEquals(tag, store.publicDataKey(createIfMissing = false)!!.mac(message))
        assertTrue(created.verify(message, tag))
        assertFalse(created.verify(message, tag.copyOf().also { it[0] = (it[0] + 1).toByte() }))
        val wallet = store.create("Only", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        store.delete(wallet.id, secret(password))
        assertContentEquals(tag, store.publicDataKey(createIfMissing = false)!!.mac(message), "never deleted")
    }

    @Test
    fun thePublicMacMatchesTheSpecVector() {
        // vault-crypto.md §10.3: key 00…1f, id 00112233-…-aabbccddeeff, the body below.
        val body = """{"displayName":"Public","localWalletId":"00112233-4455-6677-8899-AABBCCDDEEFF"}"""
        val message = "com.bittool.signer.public-wallet.v2".encodeToByteArray() + byteArrayOf(0) +
            "00112233-4455-6677-8899-aabbccddeeff".encodeToByteArray() + byteArrayOf(0) + body.encodeToByteArray()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256"))
        }.doFinal(message)
        assertEquals("5d52224242f35a071ea0fe4e9d091939e104f3cad6a2b557c9dbf18ac376f03b", Hex.encode(mac))
    }

    // endregion

    // region listing and load guards (vault-crypto.md §2.3, §6.6, §9.1)

    @Test
    fun listOrdersByCreationThenIdAndOneBadFileFailsTheWholeList(): Unit = runBlocking {
        val clock = FakeWallClock(1_760_000_000_000L)
        val store = vault(dataDir, clock = clock)
        val late = store.create("Late", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        clock.nowMillis = 1_750_000_000_000L
        val tieA = store.create("Tie A", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val tieB = store.create("Tie B", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val ties = listOf(tieA, tieB).sortedBy { it.id.uppercase }
        assertEquals(ties + late, store.list())

        // Staging leftovers and attempts files are not wallets.
        File(files.directory, ".pending-ABC").writeText("x")
        assertEquals(3, vault(dataDir, clock = clock).list().size)
        assertFalse(File(files.directory, ".pending-ABC").exists(), "stale staging files are removed")

        File(files.directory, "not-a-uuid.bsvault").writeText("{}")
        assertFailsWith<VaultError.MalformedVault> { store.list() }
        File(files.directory, "not-a-uuid.bsvault").delete()
        files.vault(late.id).writeText("{\"format\":2}")
        assertFailsWith<VaultError.MalformedVault> { store.list() }
    }

    @Test
    fun loadGuardsRejectForeignOversizedAndLinkedFiles(): Unit = runBlocking {
        val store = vault(dataDir)
        val a = store.create("A", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val b = store.create("B", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        assertFailsWith<VaultError.WalletNotFound> { store.unlock(WalletId.random(), secret(password)) }

        // A's file under B's name: the header id does not match the file name.
        val bBytes = files.vault(b.id).readBytes()
        files.vault(a.id).copyTo(files.vault(b.id), overwrite = true)
        assertFailsWith<VaultError.MalformedVault> { store.unlock(b.id, secret(password)) }
        files.vault(b.id).writeBytes(bBytes)

        files.vault(b.id).writeBytes(ByteArray(65_537) { ' '.code.toByte() })
        assertFailsWith<VaultError.MalformedVault> { store.unlock(b.id, secret(password)) }

        files.vault(b.id).delete()
        java.nio.file.Files.createSymbolicLink(files.vault(b.id).toPath(), files.vault(a.id).toPath())
        assertFailsWith<VaultError.MalformedVault> { store.unlock(b.id, secret(password)) }
    }

    @Test
    fun headerAndCiphertextBoundsAreMalformedOrUnsupported(): Unit = runBlocking {
        val store = vault(dataDir)
        val wallet = store.create("Bounds", secret(ABANDON_PHRASE), secret(""), secret(password), WalletId.random())
        val header = files.header(wallet.id)
        val original = files.vault(wallet.id).readBytes()
        fun withHeader(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>) =
            files.rewrite(wallet.id, headerBytes = files.encode(JsonObject(header + entries)))

        withHeader("version" to JsonPrimitive(3))
        assertFailsWith<VaultError.UnsupportedVersion> { store.unlock(wallet.id, secret(password)) }
        withHeader("kdf" to JsonPrimitive("scrypt"))
        assertFailsWith<VaultError.UnsupportedVersion> { store.unlock(wallet.id, secret(password)) }
        withHeader("iterations" to JsonPrimitive(599_999))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        withHeader("iterations" to JsonPrimitive(-1))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        withHeader("salt" to JsonPrimitive("AAAA"))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        withHeader("metadata" to JsonObject(header["metadata"]!!.jsonObject + ("name" to JsonPrimitive(""))))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        withHeader("metadata" to JsonObject(header["metadata"]!!.jsonObject + ("createdAt" to JsonPrimitive("today"))))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        files.rewrite(wallet.id, headerBytes = ByteArray(4_097) { ' '.code.toByte() })
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        files.vault(wallet.id).writeBytes(original)
        files.rewrite(wallet.id, ciphertext = ByteArray(55))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        files.rewrite(wallet.id, format = JsonPrimitive("2"))
        assertFailsWith<VaultError.MalformedVault> { store.unlock(wallet.id, secret(password)) }
        files.vault(wallet.id).writeBytes(original)
        store.unlock(wallet.id, secret(password)).close()
    }

    // endregion

    private fun withName(header: JsonObject, name: String): JsonObject {
        val metadata = header.getValue("metadata").jsonObject
        return JsonObject(header + ("metadata" to JsonObject(metadata + ("name" to JsonPrimitive(name)))))
    }

    /** Apple-style pretty printing of the same header object (`JSONSerialization` `.prettyPrinted`). */
    private fun prettyPrinted(header: JsonObject): String =
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), header)
}
