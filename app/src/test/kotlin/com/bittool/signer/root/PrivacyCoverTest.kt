package com.bittool.signer.root

import android.app.UiModeManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.bittool.signer.appearance.appCompatNightMode
import com.bittool.signer.appearance.uiModeNightMode
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.security.ScenePhase
import com.bittool.signer.lock.LockWindowRoot
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Port of iOS `PrivacyShieldTests` (BitToolSignerTests/PrivacyShieldTests.swift, 2 cases): "The privacy window is
 * separate from the SwiftUI window, so it must take the in-app appearance itself; otherwise the app-switcher card can
 * show the other appearance than the app." On Android the cover is drawn in the main window and in the lock window;
 * both take the Theme choice and follow it live.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacyCoverTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appearanceSettingMapsToANightMode() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, Appearance.Light.appCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, Appearance.Dark.appCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, Appearance.System.appCompatNightMode())
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, Appearance.fromWire(null).appCompatNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_NO, Appearance.Light.uiModeNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_YES, Appearance.Dark.uiModeNightMode())
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, Appearance.System.uiModeNightMode())
    }

    @Test
    fun theCoverFollowsTheInAppAppearanceAndLeavesWhenActive() {
        val appearance = MutableStateFlow(Appearance.Light)
        val phase = MutableStateFlow(ScenePhase.Active)
        val captured = MutableStateFlow(false)
        val seenDark = mutableListOf<Boolean>()
        compose.setContent {
            LockWindowRoot(appearance = appearance, phase = phase, captured = captured, exposeTestTags = true) {
                seenDark += SignerTheme.isDark
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(RootTags.PRIVACY_COVER).assertDoesNotExist()

        phase.value = ScenePhase.Inactive
        compose.waitForIdle()
        compose.onNodeWithTag(RootTags.PRIVACY_COVER).assertExists()
        compose.onNodeWithText("Hidden while your screen is recorded or mirrored").assertDoesNotExist()

        // Changing Settings › Appearance while covered updates the visible cover.
        appearance.value = Appearance.Dark
        compose.waitForIdle()
        assertEquals(true, seenDark.last())
        appearance.value = Appearance.Light
        compose.waitForIdle()
        assertEquals(false, seenDark.last())

        phase.value = ScenePhase.Active
        captured.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Hidden while your screen is recorded or mirrored").assertExists()

        captured.value = false
        compose.waitForIdle()
        compose.onNodeWithTag(RootTags.PRIVACY_COVER).assertDoesNotExist()
    }
}
