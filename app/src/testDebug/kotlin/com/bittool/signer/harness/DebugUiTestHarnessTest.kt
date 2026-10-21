package com.bittool.signer.harness

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.crypto.SystemSecureRandomSource
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.preferences.DataStoreSignerPreferences
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.BiometricPromptSpec
import com.bittool.signer.core.model.UiText
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The debug UI-test harness (gaps.md G3; iOS `SimulatorUITestContext`): opt-in by launch extras on an emulator only,
 * isolated storage, reset, clamped fixtures, the overrides, the first configuration of a process wins; and the
 * file-backed fixture keys that let a cold launch with the same run open the same wallets.
 */
@RunWith(RobolectricTestRunner::class)
class DebugUiTestHarnessTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val runId = "0C1F5A26-6D7E-4B62-9E37-2A7D3B1F0E55"

    @Before
    fun setUp() {
        DebugUiTestHarness.resetForTest()
        DebugUiTestHarness.isEmulator = { true }
        DebugUiTestHarness.createsFixtures = false
    }

    @After
    fun tearDown() = DebugUiTestHarness.resetForTest()

    private fun intent(vararg extras: Pair<String, Any>) = Intent().apply {
        extras.forEach { (key, value) ->
            when (value) {
                is String -> putExtra(key, value)
                is Int -> putExtra(key, value)
                is Boolean -> putExtra(key, value)
            }
        }
    }

    @Test
    fun withoutARunIdNothingChanges() {
        assertNull(DebugUiTestHarness.configure(context, Intent()))
        assertNull(DebugUiTestHarness.run)
        DebugUiTestHarness.resetForTest()
        DebugUiTestHarness.isEmulator = { true }
        assertNull(DebugUiTestHarness.configure(context, intent(DebugUiTestHarness.EXTRA_RUN to "not-a-uuid")))
    }

    @Test
    fun aRunIsIsolatedAndCarriesItsOverrides() {
        val run = assertNotNull(
            DebugUiTestHarness.configure(
                context,
                intent(
                    DebugUiTestHarness.EXTRA_RUN to runId,
                    DebugUiTestHarness.EXTRA_FIXTURES to 12,
                    DebugUiTestHarness.EXTRA_APP_LOCK to true,
                    DebugUiTestHarness.EXTRA_APPEARANCE to "Dark",
                    DebugUiTestHarness.EXTRA_ALLOW_CAPTURE to true,
                ),
            ),
        )
        assertEquals(runId.lowercase(), run.id)
        assertEquals(File(context.cacheDir, "ui-tests/${runId.lowercase()}"), run.root)
        assertTrue(run.root.isDirectory)
        assertEquals("ui-tests-${runId.lowercase()}", run.preferencesName)
        assertEquals(8, run.fixtures, "clamped to 0…8 like iOS")
        assertEquals(true, run.appLock)
        assertEquals("dark", run.appearance)
        assertEquals("en-US", run.localeTag)
        assertTrue(run.allowCapture)
        assertEquals(run, DebugUiTestHarness.run)
    }

    @Test
    fun stringExtrasAreAcceptedAndUnknownValuesIgnored() {
        val run = assertNotNull(
            DebugUiTestHarness.configure(
                context,
                intent(
                    DebugUiTestHarness.EXTRA_RUN to runId,
                    DebugUiTestHarness.EXTRA_FIXTURES to "2",
                    DebugUiTestHarness.EXTRA_APP_LOCK to "false",
                    DebugUiTestHarness.EXTRA_APPEARANCE to "sepia",
                    DebugUiTestHarness.EXTRA_LOCALE to "de-DE",
                ),
            ),
        )
        assertEquals(2, run.fixtures)
        assertEquals(false, run.appLock)
        assertNull(run.appearance)
        assertEquals("de-DE", run.localeTag)
        assertFalse(run.allowCapture)
    }

    @Test
    fun theFirstConfigurationOfAProcessWins() {
        val first = DebugUiTestHarness.configure(context, intent(DebugUiTestHarness.EXTRA_RUN to runId))
        val later = DebugUiTestHarness.configure(
            context,
            intent(DebugUiTestHarness.EXTRA_RUN to "11111111-2222-3333-4444-555555555555", DebugUiTestHarness.EXTRA_FIXTURES to 3),
        )
        assertEquals(first, later)
    }

    @Test
    fun onlyAnEmulatorRunsTheHarness() {
        DebugUiTestHarness.isEmulator = { false }
        assertNull(DebugUiTestHarness.configure(context, intent(DebugUiTestHarness.EXTRA_RUN to runId)))
        assertNull(DebugUiTestHarness.run)
    }

    @Test
    fun resetDeletesTheRunsFilesAndPreferences() {
        val root = File(context.cacheDir, "ui-tests/${runId.lowercase()}").apply { mkdirs() }
        File(root, "Vaults").mkdirs()
        File(root, "Vaults/old.bsvault").writeText("x")
        val preferences = DataStoreSignerPreferences.preferencesFile(context, "ui-tests-${runId.lowercase()}")
        preferences.parentFile?.mkdirs()
        preferences.writeText("x")
        DebugUiTestHarness.configure(context, intent(DebugUiTestHarness.EXTRA_RUN to runId, DebugUiTestHarness.EXTRA_RESET to true))
        assertFalse(File(root, "Vaults/old.bsvault").exists())
        assertTrue(root.isDirectory, "the run's root exists again, empty")
        assertFalse(preferences.exists())
    }

    @Test
    fun fixtureKeysSurviveANewProcessAndKeepTheKeystoreContract() {
        val directory = File(context.cacheDir, "fixture-keys-test").apply { deleteRecursively() }
        val id = WalletId.random()
        val sealed = FixtureDeviceKeyStore(directory).run {
            assertTrue(isDeviceSecure())
            create(id)
            assertFailsWith<VaultError.DeviceKeyUnavailable> { create(id) }
            seal(id, "public test bytes".toByteArray(), aad = "aad".toByteArray())
        }
        // A new instance (a cold launch with the same run) opens what the first sealed.
        val reopened = FixtureDeviceKeyStore(directory)
        assertContentEquals("public test bytes".toByteArray(), reopened.open(id, sealed, "aad".toByteArray()))
        assertFailsWith<VaultError.AuthenticationFailed> { reopened.open(id, sealed, "other".toByteArray()) }
        assertFailsWith<VaultError.DeviceKeyUnavailable> { reopened.open(WalletId.random(), sealed, "aad".toByteArray()) }

        assertNull(reopened.publicDataKey(createIfMissing = false))
        val tag = assertNotNull(reopened.publicDataKey(createIfMissing = true)).mac("index".toByteArray())
        assertTrue(assertNotNull(FixtureDeviceKeyStore(directory).publicDataKey(createIfMissing = false)).verify("index".toByteArray(), tag))

        reopened.delete(id)
        assertFailsWith<VaultError.DeviceKeyUnavailable> { reopened.open(id, sealed, "aad".toByteArray()) }
    }

    /**
     * QA round 1 sign-01: in a run the Activity journal is sealed with the run's file key, not the Keystore key that an
     * emulator without a screen lock cannot generate; without a run the production Keystore cipher stays bound.
     */
    @Test
    fun aRunSealsTheActivityJournalWithItsOwnKey() {
        val keystore = javax.inject.Provider<KeystoreJournalCipher> { error("the Keystore cipher must not be created in a run") }
        val run = assertNotNull(DebugUiTestHarness.configure(context, intent(DebugUiTestHarness.EXTRA_RUN to runId)))
        val cipher = DebugUiTestModule.journalCipher(keystore)
        assertTrue(cipher is FixtureJournalCipher, "bound $cipher")
        val sealed = cipher.seal("[]".toByteArray())
        assertTrue(File(run.root, "FixtureKeys/${FixtureJournalCipher.KEY_FILE}").isFile)
        // A cold launch with the same run (a new binding) reads the same journal.
        assertContentEquals("[]".toByteArray(), DebugUiTestModule.journalCipher(keystore).open(sealed))

        DebugUiTestHarness.resetForTest()
        val production = KeystoreJournalCipher()
        assertSame(production, DebugUiTestModule.journalCipher { production })
    }

    @Test
    fun theFixtureJournalCipherKeepsTheKeystoreContract() {
        val keyFile = File(context.cacheDir, "fixture-journal-test/journal.key").apply { parentFile?.deleteRecursively() }
        val journal = """[{"id":"public test receipt"}]""".toByteArray()
        val sealed = FixtureJournalCipher(keyFile).seal(journal)
        // "BTSR" ‖ 0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16).
        assertContentEquals(byteArrayOf(0x42, 0x54, 0x53, 0x52, 0x01), sealed.copyOfRange(0, 5))
        assertEquals(5 + 12 + journal.size + 16, sealed.size)
        assertContentEquals(journal, FixtureJournalCipher(keyFile).open(sealed))

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<VaultError.AuthenticationFailed> { FixtureJournalCipher(keyFile).open(tampered) }
        assertFailsWith<VaultError.AuthenticationFailed> { FixtureJournalCipher(keyFile).open(sealed.copyOfRange(0, 20)) }
        val otherVersion = sealed.copyOf().also { it[4] = 0x02 }
        assertFailsWith<VaultError.AuthenticationFailed> { FixtureJournalCipher(keyFile).open(otherVersion) }

        // The key is never replaced: once it worked, a missing key fails the seal instead of orphaning the journal.
        val cipher = FixtureJournalCipher(keyFile)
        cipher.open(sealed)
        keyFile.delete()
        assertFailsWith<VaultError.DeviceKeyUnavailable> { cipher.seal(journal) }
        assertFailsWith<VaultError.AuthenticationFailed> { cipher.open(sealed) }
        // A new process without a key (and so without a readable journal) creates a new one.
        assertContentEquals(journal, FixtureJournalCipher(keyFile).let { it.open(it.seal(journal)) })
    }

    /**
     * Backlog B24: a deleted fixture key is provably lost, so the lost-key removal (B9) is reachable in a run; the
     * `bts.uitest.loseDeviceKeys` switch deletes the run's wallet keys, and only those, before anything is injected.
     */
    @Test
    fun theLoseDeviceKeysSwitchMakesTheRunsWalletsRemovableWithoutTheirPassword() = runTest {
        val root = File(context.cacheDir, "ui-tests/${runId.lowercase()}").apply { deleteRecursively() }
        val keys = FixtureDeviceKeyStore(File(root, FixtureDeviceKeyStore.DIRECTORY))
        val vault = DefaultWalletVault(root, keys, SystemSecureRandomSource(), { 1_760_000_000_000 }, Dispatchers.Default, Dispatchers.IO)
        val id = WalletId.random()
        vault.create("Public fixture 1", SecretText.of(PUBLIC_PHRASE), SecretText.of(""), SecretText.of("Public-Test-Password1!"), id)
        keys.publicDataKey(createIfMissing = true)
        FixtureJournalCipher(File(root, "FixtureKeys/${FixtureJournalCipher.KEY_FILE}")).seal("[]".toByteArray())
        assertFalse(keys.isKeyLost(id))
        assertFalse(vault.isDeviceKeyLost(id))

        DebugUiTestHarness.configure(
            context,
            intent(DebugUiTestHarness.EXTRA_RUN to runId, DebugUiTestHarness.EXTRA_LOSE_DEVICE_KEYS to true),
        )
        assertFalse(File(root, "FixtureKeys/$id.key").exists())
        assertTrue(File(root, "FixtureKeys/public-data.key").isFile, "the public-data key is kept")
        assertTrue(File(root, "FixtureKeys/${FixtureJournalCipher.KEY_FILE}").isFile, "the journal key is kept")

        assertTrue(keys.isKeyLost(id))
        assertTrue(vault.isDeviceKeyLost(id))
        vault.removeWithLostDeviceKey(id)
        assertTrue(vault.list().isEmpty())
    }

    @Test
    fun aFixtureKeyThatIsNotAKeyIsLostAndAPresentOneIsNot() {
        val directory = File(context.cacheDir, "fixture-lost-test").apply { deleteRecursively() }
        val keys = FixtureDeviceKeyStore(directory)
        val present = WalletId.random()
        keys.create(present)
        assertFalse(keys.isKeyLost(present))
        assertTrue(keys.isKeyLost(WalletId.random()))
        File(directory, "$present.key").writeBytes(ByteArray(3))
        assertTrue(keys.isKeyLost(present))
        assertEquals(1, FixtureDeviceKeyStore.loseWalletKeys(directory))
        assertEquals(0, FixtureDeviceKeyStore.loseWalletKeys(File(directory, "missing")))
    }

    @Test
    fun theFixtureBiometricAlwaysFailsWithItsMessage() = runTest {
        val biometric = FixtureBiometricAuthenticator()
        assertTrue(biometric.isAvailable())
        val error = assertFailsWith<AppIssue> {
            biometric.authenticate(BiometricPromptSpec(UiText.Verbatim("t"), null, UiText.Verbatim("n")))
        }
        assertEquals(UiText.Ios(FixtureBiometricAuthenticator.MESSAGE), error.text)
    }

    private companion object {
        /** The published BIP39 test vector the harness's fixtures use (never a real wallet). */
        const val PUBLIC_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
