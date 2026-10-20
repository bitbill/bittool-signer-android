package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Behaviour of the generic components (iOS `Design/DesignSystem.swift`). */
@RunWith(RobolectricTestRunner::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun content(body: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { SignerTheme(darkTheme = false, applySystemBarAppearance = false) { Box { body() } } }
    }

    @Test
    fun aBusyButtonKeepsItsLabelAndIgnoresTaps() {
        var taps = 0
        var busy by mutableStateOf(true)
        content { PrimaryButton("Sign with Main", onClick = { taps++ }, icon = SignerIcons.Signature, busy = busy) }
        // The label stays laid out (invisible) so the width is stable and TalkBack still reads it.
        compose.onNodeWithText("Sign with Main", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Sign with Main")).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.onNode(hasText("Sign with Main")).performClick()
        assertEquals(0, taps)
        busy = false
        compose.onNode(hasText("Sign with Main")).performClick()
        assertEquals(1, taps)
    }

    @Test
    fun aDisabledButtonCannotBeTapped() {
        var taps = 0
        content { SecondaryButton("Cannot Sign", onClick = { taps++ }, enabled = false) }
        compose.onNode(hasText("Cannot Sign")).assertIsNotEnabled()
        assertEquals(0, taps)
    }

    @Test
    fun theSegmentedControlIsAGroupOfTabs() {
        var selected by mutableIntStateOf(0)
        var calls = 0
        content {
            SegmentedControl(listOf("Wallet Level", "Coin Level"), selected, { selected = it; calls++ })
        }
        compose.onNodeWithText("Wallet Level").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        compose.onNodeWithText("Coin Level").assertIsNotSelected().performClick()
        assertEquals(1, selected)
        compose.onNodeWithText("Coin Level").assertIsSelected().performClick()
        assertEquals(1, calls, "selecting the current segment again does nothing")
    }

    @Test
    fun aToastStaysForOnePointSixSecondsAndANewerOneReplacesIt() {
        val state = StatusToastState()
        content { StatusToastHost(state) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.show("Copied") }
        advance(500)
        compose.onNodeWithText("Copied").assertExists()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle { state.show("Password Changed") }
        advance(1_200)
        compose.onNodeWithText("Password Changed").assertExists()
        compose.onNodeWithText("Copied").assertDoesNotExist()
        advance(SignerMotion.TOAST_MILLIS)
        assertNull(state.current, "dismissed 1.6 s after it appeared")
        advance(1_000)
        compose.onNodeWithText("Password Changed").assertDoesNotExist()
    }

    /** Advances the paused clock in frame-sized steps, letting snapshot changes and effects run in between. */
    private fun advance(millis: Long) {
        var left = millis
        while (left > 0) {
            val step = minOf(left, 100L)
            compose.mainClock.advanceTimeBy(step)
            compose.waitForIdle()
            left -= step
        }
    }

    @Test
    fun anOlderTimerNeverDismissesANewerToast() {
        val state = StatusToastState()
        state.show("Copied")
        val first = state.current!!
        state.show("Copied")
        state.dismiss(first)
        assertEquals("Copied", state.current?.message)
    }

    @Test
    fun switchRowsToggleAsOneControl() {
        var on by mutableStateOf(false)
        content { SwitchRow("App Lock", SignerIcons.ShieldLock, on, { on = it }) }
        compose.onNodeWithText("App Lock").assertIsOff().performClick()
        compose.onNodeWithText("App Lock").assertIsOn()
        assertEquals(true, on)
    }

    @Test
    fun navRowsAreButtonsAndCalloutsReadAsOneElement() {
        var opened = 0
        content {
            androidx.compose.foundation.layout.Column {
                NavRow("Language", SignerIcons.Language, onClick = { opened++ }, value = "English")
                Callout("Read every warning above before signing.", title = "Review", tone = Tone.Warning)
            }
        }
        compose.onNodeWithText("Language").performClick()
        assertEquals(1, opened)
        // Title and text merge into one node (iOS `.accessibilityElement(children: .combine)`).
        compose.onNode(hasText("Review") and hasText("Read every warning above before signing.")).assertExists()
    }
}
