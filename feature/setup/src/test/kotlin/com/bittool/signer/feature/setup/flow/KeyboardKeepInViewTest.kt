package com.bittool.signer.feature.setup.flow

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.setKeyboardHeight
import com.bittool.signer.feature.setup.testBip39
import com.bittool.signer.feature.setup.unclippedBounds
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA setup-02: with the keyboard up, what is being typed stays visible above the bottom bar (iOS scrolls the focused
 * field above the keyboard toolbar, `WalletSetupFlow.swift:107-119`): the wallet password with its requirements
 * checklist, "Confirm passphrase", the phrase editor. The keyboard is raised through the window insets, like the
 * platform does; its height is chosen from the page's own layout, so the case does not depend on font metrics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w393dp-h760dp-xhdpi")
class KeyboardKeepInViewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore()
    private val factory = FakeRecoverySessionFactory()

    private fun flow(intent: SetupIntent): WalletSetupViewModel {
        val model = WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
        return model
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).unclippedBounds()

    private val gap: Float get() = with(compose.density) { 8.dp.toPx() }

    /** The top edge of the bottom bar: [button]'s top minus the bar's 8 dp padding (nothing sits above the button). */
    private fun barTop(button: String): Float = bounds(button).top - gap

    /** The bottom edge of the top bar. */
    private fun topBarBottom(): Float = maxOf(bounds("setup.cancel").bottom, 0f)

    private var keyboard = 0

    /** Raises the keyboard until the bottom bar's top edge is at [y] (in the layout before it rose further). */
    private fun raiseKeyboardTo(y: Float, button: String) {
        val rise = (barTop(button) - y).roundToInt()
        assertTrue(rise > 0, "nothing to cover")
        keyboard += rise
        compose.runOnUiThread { compose.activity.setKeyboardHeight(keyboard) }
        compose.waitForIdle()
    }

    private fun assertAbove(what: String, bottom: Float, barTop: Float) =
        assertTrue(bottom <= barTop + 1f, "$what ends at $bottom, under the bottom bar at $barTop")

    private fun assertBelowTopBar(what: String, top: Float) =
        assertTrue(top >= topBarBottom() - 1f, "$what starts at $top, under the top bar at ${topBarBottom()}")

    @Test
    fun thePasswordChecklistStaysVisibleWhileThePasswordIsTyped() {
        val model = flow(SetupIntent.Restore)
        compose.onNodeWithTag("recovery.input.visible").performTextInput(FakeSignerStore.PUBLIC_FIXTURE_12)
        compose.onNodeWithTag("setup.import").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(model.uiState.value.step == SetupStep.Password) }

        val password = PasswordKind.WalletPassword.english
        compose.onNodeWithTag(password).requestFocus()
        compose.waitForIdle()
        // A keyboard that leaves room for the fields and the checklist below the top bar (with 40 dp to spare), but
        // covers the checklist where the page stands: Compose alone keeps the focused field, still visible, and
        // leaves the checklist under the bar (QA `16-pw-weak.png`).
        val checklistBottom = compose.onNodeWithText("Passwords match").unclippedBounds().bottom
        val group = checklistBottom - bounds(password).top
        val keyboardTop = topBarBottom() + group + with(compose.density) { (8 + 20 + 40).dp.toPx() }
        assertTrue(keyboardTop < checklistBottom - gap, "the keyboard must cover the checklist at first")
        raiseKeyboardTo(keyboardTop, "setup.finish")
        compose.onNodeWithTag(password).performTextInput("Abcdefgh")
        compose.waitForIdle()

        val barTop = barTop("setup.finish")
        assertAbove("the checklist", compose.onNodeWithText("Passwords match").unclippedBounds().bottom, barTop)
        assertBelowTopBar("the password field", bounds(password).top)
    }

    @Test
    fun confirmPassphraseIsBroughtAboveTheBar() {
        flow(SetupIntent.Create)
        compose.onNodeWithTag("setup.usePassphrase").performScrollTo().performClick()
        compose.waitForIdle()
        val first = PasswordKind.Passphrase.english
        val confirmation = PasswordKind.ConfirmPassphrase.english
        val pair = bounds(confirmation).bottom - bounds(first).top
        // The bar already covers the lower half of "Confirm passphrase" when it is tapped (the first frames of the
        // keyboard), then the keyboard rises the rest of the way (QA `50-confirm-focused.png`). Compose alone brings
        // the caret into view, not the field, and does not follow a field that was already partly covered.
        val confirmBounds = bounds(confirmation)
        raiseKeyboardTo(confirmBounds.top + confirmBounds.height / 2, "setup.create")
        compose.onNodeWithTag(confirmation).requestFocus()
        compose.waitForIdle()
        raiseKeyboardTo(topBarBottom() + pair + with(compose.density) { (8 + 20 + 40).dp.toPx() }, "setup.create")
        compose.onNodeWithTag(confirmation).performTextInput("TREZOR")
        compose.waitForIdle()

        assertAbove("Confirm passphrase", bounds(confirmation).bottom, barTop("setup.create"))
        assertBelowTopBar("BIP39 passphrase", bounds(first).top)
    }

    @Test
    fun theWholeEditorStaysAboveTheBarAndItsSuggestions() {
        flow(SetupIntent.Restore)
        val editor = "recovery.input.visible"
        compose.onNodeWithTag(editor).requestFocus()
        compose.onNodeWithTag(editor).performTextInput("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ab")
        compose.waitForIdle()
        // The keyboard would cover all but the editor's first line; the suggestion row then rises on top of it.
        raiseKeyboardTo(bounds(editor).top + 40 * compose.density.density, "setup.import")

        assertAbove("the editor", bounds(editor).bottom, barTop("setup.import"))
        assertBelowTopBar("the status line", bounds("recovery.status").top)
        compose.onNodeWithTag("recovery.keyboardDone").assertExists()
    }

    /**
     * An editor taller than the space the keyboard leaves: the block cannot be shown whole, so the line being typed
     * (the caret's) is kept above the bar instead, not the top of the block.
     */
    @Test
    fun aTallEditorKeepsTheLineBeingTypedAboveTheBar() {
        flow(SetupIntent.Restore)
        val editor = "recovery.input.visible"
        compose.onNodeWithTag(editor).requestFocus()
        compose.onNodeWithTag(editor).performTextInput((1..20).joinToString("\n") { "abandon" })
        compose.waitForIdle()
        raiseKeyboardTo(topBarBottom() + 200 * compose.density.density, "setup.import")
        assertTrue(bounds(editor).height > barTop("setup.import") - topBarBottom(), "precondition: the editor does not fit")

        val node = compose.onNodeWithTag(editor).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        // The text starts 16 dp below the editor's top (its decorator padding); the caret is on the last line.
        val caretBottom = node.positionInRoot.y + 16 * compose.density.density + layout.getLineBottom(layout.lineCount - 1)
        assertAbove("the line being typed", caretBottom, barTop("setup.import"))
        assertBelowTopBar("the line being typed", caretBottom - (layout.getLineBottom(0) - layout.getLineTop(0)))
    }
}
