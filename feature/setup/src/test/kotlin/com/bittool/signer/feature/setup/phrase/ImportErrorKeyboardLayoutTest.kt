package com.bittool.signer.feature.setup.phrase

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.feature.setup.Look
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.SetupFlow
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import com.bittool.signer.feature.setup.testBip39
import com.bittool.signer.feature.setup.unclippedBounds
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * QA activity-keyboard-03: at font scale 2.0, an import error ("Invalid recovery phrase: it has 4 words. Recovery
 * phrases have 12, 15, 18, 21 or 24 words.", five lines) in the bar above the in-app keyboard left about 145 dp for the
 * page: the status line ("Unknown: “xyzzy”") sat under the top bar and the editor showed two lines. While the keyboard
 * is up the error stays above it (what the person is fixing, iOS) but shortened to two lines, so the status line and
 * the whole editor stay visible; TalkBack still reads it whole, and it shows in full again once the keyboard is put
 * away. At the default size it is never shortened.
 *
 * Real text layout (native graphics), on a Pixel 3a's window (the QA device, 393 × 807 dp) without its status and
 * navigation bars, which Robolectric does not draw.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h735dp-xhdpi")
class ImportErrorKeyboardLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore()
    private val factory = FakeRecoverySessionFactory()
    private lateinit var model: WalletSetupViewModel

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).unclippedBounds()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** The error's text as TalkBack reads it (the callout is one merged element). */
    private fun errorText(): String = compose.onNodeWithTag(ERROR).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    /** Continue with a four-word phrase, then back into the editor. Returns the error's height before the keyboard. */
    private fun importWithAnError(look: Look): Float {
        model = WalletSetupViewModel(SetupIntent.Restore, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory, look) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
        compose.runOnIdle { model.fields.phrase.setTextAndPlaceCursorAtEnd("dragon madera jamón xyzzy") }
        compose.onNodeWithTag(CONTINUE).performClick()
        compose.waitForIdle()
        assertTrue(errorText().startsWith("Invalid recovery phrase"), "precondition: the length error (${errorText()})")
        val whole = bounds(ERROR).height
        compose.onNodeWithTag(EDITOR).performScrollTo()
        compose.onNodeWithTag(EDITOR).performClick()
        compose.waitForIdle()
        assertTrue(exists(Bip39KeyboardTags.KEYBOARD), "the in-app keyboard is up")
        return whole
    }

    @Test
    fun atFontScale2AnErrorLeavesTheStatusLineAndTheEditorAboveTheKeyboard() {
        val whole = importWithAnError(Look.FontScale2)
        val topBarBottom = bounds("setup.cancel").bottom
        // The bar starts 8 dp above the callout (its first element), whose text is inset by 14 dp.
        val barTop = bounds(ERROR).top - with(compose.density) { (8 + 14).dp.toPx() }
        assertTrue(bounds(ERROR).bottom <= bounds(Bip39KeyboardTags.KEYBOARD).top + 1f, "the error stays above the keyboard")
        val status = bounds("recovery.status")
        assertTrue(status.top >= topBarBottom - 1f, "the status line starts at ${status.top}, under the top bar at $topBarBottom")
        assertTrue(status.bottom <= barTop + 1f, "the status line ends at ${status.bottom}, under the bar at $barTop")
        val editor = bounds(EDITOR)
        assertTrue(editor.top >= status.bottom, "the editor follows the status line")
        assertTrue(editor.bottom <= barTop + 1f, "the whole editor shows: it ends at ${editor.bottom}, the bar starts at $barTop")
        assertTrue(bounds(ERROR).height < whole, "the error is shortened while the keyboard is up (${bounds(ERROR).height} of $whole px)")
        assertTrue(errorText().endsWith("12, 15, 18, 21 or 24 words."), "TalkBack reads the whole error (${errorText()})")
    }

    /** Done puts the keyboard away: the error shows in full again. */
    @Test
    fun atFontScale2TheWholeErrorComesBackWithDone() {
        val whole = importWithAnError(Look.FontScale2)
        compose.onNodeWithTag(Bip39KeyboardTags.DONE).performClick()
        compose.waitForIdle()
        assertTrue(!exists(Bip39KeyboardTags.KEYBOARD), "the keyboard is away")
        assertEquals(whole, bounds(ERROR).height, "the whole error is drawn")
    }

    /** At the default size there is room: the error is drawn in full above the keyboard, as before. */
    @Test
    fun atTheDefaultSizeTheWholeErrorStaysAboveTheKeyboard() {
        val whole = importWithAnError(Look.Light)
        assertEquals(whole, bounds(ERROR).height, "the whole error is drawn")
        assertTrue(bounds(ERROR).bottom <= bounds(Bip39KeyboardTags.KEYBOARD).top + 1f, "above the keyboard")
        assertTrue(bounds("recovery.status").top >= bounds("setup.cancel").bottom - 1f, "the status line is visible")
    }

    private companion object {
        const val EDITOR = "recovery.input.visible"
        const val CONTINUE = "setup.import"
        const val ERROR = "setup.error"
    }
}
