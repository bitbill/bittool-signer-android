package com.bittool.signer.feature.setup.flow

import android.app.admin.DevicePolicyManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.testBip39
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * QA wallets-04: on a device without a secure lock screen the first step says so before anything is typed, with "Open
 * Settings" as the way out, instead of the vault refusing after the phrase, the password and the key derivation. Coming
 * back with a screen lock (the flow resumes) unlocks the step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DeviceLockNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore()
    private val factory = FakeRecoverySessionFactory()
    private val keys = InMemoryDeviceKeyStore(deviceSecure = false)

    private fun flow(intent: SetupIntent) {
        val model = WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, keys)
        compose.setContent { SetupTestHost(factory) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
    }

    private val notice = "Set a screen lock (PIN, pattern or password) to create or use a wallet."

    @Test
    fun createSaysAScreenLockIsNeededBeforeThePhraseIsMade() {
        flow(SetupIntent.Create)
        compose.onNodeWithTag(DEVICE_LOCK_NOTICE_TAG).assertExists()
        compose.onNodeWithText(notice, substring = true).assertExists()
        compose.onNodeWithText("Screen Lock Required").assertExists()
        compose.onNodeWithTag("setup.create").assertIsNotEnabled()

        compose.onNodeWithTag(OPEN_LOCK_SETTINGS_TAG).performClick()
        compose.runOnIdle {
            assertEquals(DevicePolicyManager.ACTION_SET_NEW_PASSWORD, shadowOf(compose.activity).nextStartedActivity?.action)
        }

        // Back from Settings with a screen lock: the notice goes and Create works.
        keys.deviceSecure = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithTag(DEVICE_LOCK_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithTag("setup.create").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 2 of 4").assertExists()
    }

    @Test
    fun importSaysItBeforeAnyWordIsTyped() {
        flow(SetupIntent.Restore)
        compose.onNodeWithTag(DEVICE_LOCK_NOTICE_TAG).assertExists()
        // Above the editor: the first thing on the page after the header.
        val noticeBottom = compose.onNodeWithTag(DEVICE_LOCK_NOTICE_TAG).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(noticeBottom <= compose.onNodeWithTag("recovery.status").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("recovery.input.visible").performScrollTo().performTextInput(FakeSignerStore.PUBLIC_FIXTURE_12)
        compose.onNodeWithTag("setup.import").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(store.calls<Call.CreateWallet>().isEmpty()) }
    }
}
