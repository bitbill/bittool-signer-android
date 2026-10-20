package com.bittool.signer.harness

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.preferences.DataStoreSignerPreferences
import com.bittool.signer.core.model.AppIssue
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
import kotlin.test.assertTrue
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

    @Test
    fun theFixtureBiometricAlwaysFailsWithItsMessage() = runTest {
        val biometric = FixtureBiometricAuthenticator()
        assertTrue(biometric.isAvailable())
        val error = assertFailsWith<AppIssue> {
            biometric.authenticate(BiometricPromptSpec(UiText.Verbatim("t"), null, UiText.Verbatim("n")))
        }
        assertEquals(UiText.Ios(FixtureBiometricAuthenticator.MESSAGE), error.text)
    }
}
