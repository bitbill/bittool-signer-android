package com.bittool.signer.core.security.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricCancellationException.Reason
import com.bittool.signer.core.security.BiometricException
import com.bittool.signer.core.security.access.AppLockPrompts
import com.bittool.signer.core.security.testing.FakeAppLifecycleMonitor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/** app-shell.md §15.6: availability, the resumed-activity rule and the prompt as the person sees it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AndroidBiometricAuthenticatorTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun strongBiometrics(enrolled: Boolean) {
        val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        Shadow.extract<ShadowBiometricManager>(manager).apply {
            setAuthenticatorType(Authenticators.BIOMETRIC_STRONG)
            setCanAuthenticate(enrolled)
        }
    }

    @Test
    fun availabilityMeansAnEnrolledStrongBiometric() {
        val authenticator = AndroidBiometricAuthenticator(context, FakeAppLifecycleMonitor())
        strongBiometrics(enrolled = false)
        assertFalse(authenticator.isAvailable())
        strongBiometrics(enrolled = true)
        assertTrue(authenticator.isAvailable())
    }

    @Test
    fun withoutAResumedActivityThePromptIsNotShown() = runTest {
        strongBiometrics(enrolled = true)
        val monitor = FakeAppLifecycleMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor)
        val missing = assertFailsWith<BiometricCancellationException> { authenticator.authenticate(AppLockPrompts.unlock) }
        assertEquals(Reason.NotResumed, missing.reason)

        // An activity that is only started (a system dialog in front) does not get a prompt either.
        monitor.foregroundActivity = Robolectric.buildActivity(FragmentActivity::class.java).create().start().get()
        val started = assertFailsWith<BiometricCancellationException> { authenticator.authenticate(AppLockPrompts.unlock) }
        assertEquals(Reason.NotResumed, started.reason)
    }

    @Test
    fun anUnavailableBiometricReportsTheUnavailableText() = runTest {
        strongBiometrics(enrolled = false)
        val monitor = FakeAppLifecycleMonitor()
        monitor.foregroundActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val authenticator = AndroidBiometricAuthenticator(context, monitor)
        val error = assertFailsWith<BiometricException> { authenticator.authenticate(AppLockPrompts.unlock) }
        assertEquals(BiometricException.Kind.Unavailable, error.kind)
    }

    @Test
    fun thePromptIsStrongOnlyWithoutConfirmationInTheAppLanguage() {
        val unlock = AndroidBiometricAuthenticator.promptInfo(AppLockPrompts.unlock, context.resources)
        assertEquals("Unlock BitTool Signer", unlock.title)
        assertEquals("Signing still requires the wallet password.", unlock.description)
        assertEquals("Use a Wallet Password", unlock.negativeButtonText)
        assertEquals(Authenticators.BIOMETRIC_STRONG, unlock.allowedAuthenticators)
        assertFalse(unlock.isConfirmationRequired)

        val enable = AndroidBiometricAuthenticator.promptInfo(AppLockPrompts.enable, context.resources)
        assertEquals("Lock BitTool Signer", enable.title)
        assertEquals("Cancel", enable.negativeButtonText)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], qualifiers = "b+zh+Hans")
    fun catalogueTextsOfThePromptAreTranslated() {
        val unlock = AndroidBiometricAuthenticator.promptInfo(AppLockPrompts.unlock, context.resources)
        assertEquals(context.getString(com.bittool.signer.core.l10n.R.string.use_a_wallet_password), unlock.negativeButtonText)
        assertTrue(unlock.negativeButtonText != "Use a Wallet Password", "The iOS catalogue string is localised")
    }
}
