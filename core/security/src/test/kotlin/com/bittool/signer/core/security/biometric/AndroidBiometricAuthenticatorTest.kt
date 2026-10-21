package com.bittool.signer.core.security.biometric

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.os.Build
import android.os.Looper
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricCancellationException.Reason
import com.bittool.signer.core.security.BiometricException
import com.bittool.signer.core.security.access.AppLockPrompts
import com.bittool.signer.core.security.testing.FakeAppLifecycleMonitor
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
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
    fun cancelEndsAShowingPromptAsASystemCancel() = runTest {
        strongBiometrics(enrolled = true)
        val monitor = FakeAppLifecycleMonitor()
        monitor.foregroundActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val authenticator = AndroidBiometricAuthenticator(context, monitor)
        val prompt = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        assertFalse(prompt.isCompleted, "The prompt waits for the person")
        authenticator.cancel() // the app went to the background
        val ended = assertIs<BiometricCancellationException>(prompt.await().exceptionOrNull())
        assertEquals(Reason.SystemCanceled, ended.reason)
    }

    @Test
    fun aNewPromptReplacesTheShowingOne() = runTest {
        strongBiometrics(enrolled = true)
        val monitor = FakeAppLifecycleMonitor()
        monitor.foregroundActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val authenticator = AndroidBiometricAuthenticator(context, monitor)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        assertEquals(Reason.SystemCanceled, assertIs<BiometricCancellationException>(first.await().exceptionOrNull()).reason)
        assertFalse(second.isCompleted)
        second.cancel()
    }

    /**
     * androidx's `BiometricViewModel` as the sequencing sees it: one client callback per activity, replaced by every
     * launch, so an outcome reaches whichever prompt launched last.
     */
    private class ScriptedPlatform : BiometricPlatform {
        val launches = mutableListOf<BiometricPrompt.PromptInfo>()
        var cancels = 0
            private set
        private var current: BiometricPlatform.Outcome? = null

        override fun launch(
            activity: FragmentActivity,
            info: BiometricPrompt.PromptInfo,
            outcome: BiometricPlatform.Outcome,
        ): BiometricPlatform.Handle {
            launches += info
            current = outcome
            return BiometricPlatform.Handle { cancels += 1 }
        }

        /** The sensor accepted the person: delivered to the callback androidx holds now. */
        fun succeed() = checkNotNull(current).succeeded()

        fun report(errorCode: Int) = checkNotNull(current).failed(errorCode)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun resumedMonitor(): Pair<FakeAppLifecycleMonitor, ActivityController<FragmentActivity>> {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        return FakeAppLifecycleMonitor().apply { foregroundActivity = controller.get() } to controller
    }

    @Test
    fun aCancelledPromptsLateSuccessNeverCompletesTheNextPrompt() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, _) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { authenticator.authenticate(AppLockPrompts.confirm(UiText.Ios("Confirm before deleting this wallet."))) }
        }
        assertEquals(Reason.SystemCanceled, assertIs<BiometricCancellationException>(first.await().exceptionOrNull()).reason)
        assertEquals(1, platform.cancels, "The replaced prompt is taken off the screen")
        assertEquals(1, platform.launches.size, "androidx would not show a prompt while the cancelled one still counts as showing")

        // The cancelled prompt had already accepted a finger: its success arrives after the cancel.
        platform.succeed()
        idle()
        assertFalse(second.isCompleted, "A success given to another prompt never completes this one")
        assertEquals(2, platform.launches.size, "Handed to the platform once the previous prompt has ended")
        assertEquals("Confirm before deleting this wallet.", platform.launches.last().title)

        platform.succeed()
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun aPlatformThatNeverReportsDelaysTheNextPromptByAtMostOneSecond() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, _) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AndroidBiometricAuthenticator.PREVIOUS_PROMPT_END_TIMEOUT_MILLIS - 1))
        assertEquals(1, platform.launches.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(2, platform.launches.size)
        platform.succeed()
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun aRequestWaitingForThePlatformEndsAtOnceWhenCancelled() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, _) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        authenticator.cancel() // the app went to the background
        assertEquals(Reason.SystemCanceled, assertIs<BiometricCancellationException>(waiting.await().exceptionOrNull()).reason)
        platform.report(BiometricPrompt.ERROR_CANCELED)
        idle()
        assertEquals(1, platform.launches.size, "A cancelled request is never shown")
    }

    @Test
    fun cancellingTheCallerTakesThePromptOffTheScreenOnce() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, _) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        val caller = launch(start = CoroutineStart.UNDISPATCHED) { authenticator.authenticate(AppLockPrompts.unlock) }
        caller.cancel()
        authenticator.cancel()
        testScheduler.runCurrent()
        assertEquals(1, platform.cancels)
        platform.report(BiometricPrompt.ERROR_CANCELED)
        // The cancelled prompt has ended: the next one starts at once.
        val next = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        assertEquals(2, platform.launches.size)
        platform.succeed()
        assertTrue(next.await().isSuccess)
    }

    @Test
    fun platformErrorsAreMappedForTheCaller() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, _) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        val lockedOut = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        platform.report(BiometricPrompt.ERROR_LOCKOUT)
        assertEquals(BiometricException.Kind.Lockout, assertIs<BiometricException>(lockedOut.await().exceptionOrNull()).kind)
        val password = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        platform.report(BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        assertEquals(Reason.NegativeButton, assertIs<BiometricCancellationException>(password.await().exceptionOrNull()).reason)
    }

    @Test
    fun aDestroyedActivityEndsItsPromptSoTheNextOneCanRun() = runTest {
        strongBiometrics(enrolled = true)
        val platform = ScriptedPlatform()
        val (monitor, controller) = resumedMonitor()
        val authenticator = AndroidBiometricAuthenticator(context, monitor, platform)
        val prompt = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        // Recreated for a configuration change the manifest does not handle (a hardware keyboard): the prompt's
        // fragment and callback go with the old activity and nothing would ever report back.
        controller.pause().stop().destroy()
        assertEquals(Reason.SystemCanceled, assertIs<BiometricCancellationException>(prompt.await().exceptionOrNull()).reason)

        monitor.foregroundActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val next = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authenticator.authenticate(AppLockPrompts.unlock) } }
        assertEquals(2, platform.launches.size, "No wait for a prompt whose activity is gone")
        platform.succeed()
        assertTrue(next.await().isSuccess)
    }

    @Test
    fun thePromptIsStrongOnlyWithoutConfirmationInTheAppLanguage() {
        val unlock = AndroidBiometricAuthenticator.promptInfo(AppLockPrompts.unlock, context.resources)
        assertEquals("Unlock BitTool Signer", unlock.title)
        assertEquals("Signing still requires the wallet password.", unlock.description)
        assertEquals("Use a Wallet Password", unlock.negativeButtonText)
        assertEquals(Authenticators.BIOMETRIC_WEAK, unlock.allowedAuthenticators)
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
