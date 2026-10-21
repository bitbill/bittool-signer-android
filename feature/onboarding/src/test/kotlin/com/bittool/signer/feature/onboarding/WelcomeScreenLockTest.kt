package com.bittool.signer.feature.onboarding

import android.app.admin.DevicePolicyManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeLegalDocumentRepository
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.navigation.SetupIntent
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * QA wallets-04: on a device without a secure lock screen, Welcome's Create and Import say so before the introduction
 * and before anything secret is typed (the vault refused only after the phrase, the password and the key derivation),
 * with "Open Settings" as the way out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h900dp")
class WelcomeScreenLockTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val keys = InMemoryDeviceKeyStore(deviceSecure = false)
    private val started = mutableListOf<SetupIntent>()

    private fun show() {
        val viewModel = WelcomeViewModel(FakeLegalDocumentRepository(), keys)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                WelcomeScreen(onStart = { started += it }, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    private val title = "Screen Lock Required"

    @Test
    fun withoutAScreenLockCreateAndImportAskForOneFirst() {
        show()
        compose.onNodeWithTag(WelcomeTags.CREATE).performClick()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Set a screen lock (PIN, pattern or password) to create or use a wallet.", substring = true).assertExists()
        compose.onNodeWithTag(WelcomeTags.LOCK_CANCEL).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNodeWithTag(WelcomeTags.IMPORT).performClick()
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithTag(WelcomeTags.LOCK_CANCEL).performClick()
        compose.runOnIdle { assertEquals(emptyList(), started) }

        // A screen lock set meanwhile: setup starts with the chosen intent, without the dialog.
        keys.deviceSecure = true
        compose.onNodeWithTag(WelcomeTags.IMPORT).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(SetupIntent.Restore), started) }
    }

    @Test
    fun openSettingsOpensTheSystemScreenLockPage() {
        show()
        compose.onNodeWithTag(WelcomeTags.CREATE).performClick()
        compose.onNodeWithTag(WelcomeTags.LOCK_OPEN_SETTINGS).performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(DevicePolicyManager.ACTION_SET_NEW_PASSWORD, shadowOf(compose.activity).nextStartedActivity?.action)
            assertEquals(emptyList(), started)
        }
    }
}
