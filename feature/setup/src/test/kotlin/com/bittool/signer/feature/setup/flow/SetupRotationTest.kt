package com.bittool.signer.feature.setup.flow

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.feature.setup.LANDSCAPE
import com.bittool.signer.feature.setup.PORTRAIT
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.rotateItself
import com.bittool.signer.feature.setup.testBip39
import com.bittool.signer.feature.setup.unclippedBounds
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA activity-keyboard-01: the app's activity handles rotation itself, so a setup page stays composed through a
 * rotation and must lay itself out for the new window, both ways. With the in-app keyboard up on the import step,
 * landscape lets "Continue" step aside and gives the keyboard short rows, so the line being typed stays between the top
 * bar and the keyboard; portrait pins "Continue" again above full rows. The backup step moves Security Tips into the
 * page in landscape and back into the bar in portrait. Below API 33 the old `LocalWindowInfo` size stayed one
 * orientation behind there; the rotation is delivered in the platform's order (`rotateItself`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.BAKLAVA], qualifiers = "$PORTRAIT-xhdpi")
class SetupRotationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store = FakeSignerStore().apply { onGenerateMnemonic = { SecretText.of(Phrases.VECTOR_24) } }
    private val factory = FakeRecoverySessionFactory()

    private fun flow(intent: SetupIntent) {
        val model = WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), factory, Dispatchers.Unconfined, InMemoryDeviceKeyStore())
        compose.setContent { SetupTestHost(factory) { SetupFlow(model, onFinished = {}, onCancelled = {}) } }
        compose.waitForIdle()
    }

    private fun rotate(qualifiers: String) {
        compose.runOnUiThread { compose.activity.rotateItself(qualifiers) }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).unclippedBounds()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** A key cell is its row's full height. */
    private fun keyRow(): Float = bounds(Bip39KeyboardTags.letter('q')).height

    /**
     * The line with the caret (the phrase's last line: the caret is at the end) lies between the top bar and the bottom
     * bar, which starts at "Continue" when it is there and at the keyboard otherwise.
     */
    private fun assertTheLineBeingTypedIsVisible(where: String) {
        val node = compose.onNodeWithTag(EDITOR).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        // The text starts 16 dp below the editor's top (its decorator padding).
        val lineBottom = node.positionInRoot.y + 16 * compose.density.density + layout.getLineBottom(layout.lineCount - 1)
        val lineTop = lineBottom - (layout.getLineBottom(0) - layout.getLineTop(0))
        val barTop = if (exists(CONTINUE)) bounds(CONTINUE).top else bounds(Bip39KeyboardTags.KEYBOARD).top
        val topBarBottom = bounds("setup.cancel").bottom
        assertTrue(lineBottom <= barTop + 1f, "$where: the line being typed ends at $lineBottom, under the bottom bar at $barTop")
        assertTrue(lineTop >= topBarBottom - 1f, "$where: the line being typed starts at $lineTop, under the top bar at $topBarBottom")
    }

    @Test
    fun theImportStepFollowsARotationWithTheKeyboardUpBothWays() {
        flow(SetupIntent.Restore)
        compose.onNodeWithTag(EDITOR).performTextInput("abandon abandon ab")
        compose.waitForIdle()
        assertTrue(exists(Bip39KeyboardTags.KEYBOARD), "the in-app keyboard is up")
        val fullRow = keyRow()
        assertTrue(exists(CONTINUE), "portrait: Continue is pinned above the keyboard")
        assertTheLineBeingTypedIsVisible("portrait")

        rotate(LANDSCAPE)
        assertFalse(exists(CONTINUE), "landscape: Continue steps aside while the keyboard is up")
        assertTrue(keyRow() < fullRow, "landscape: shorter rows (${keyRow()} px, ${fullRow} px in portrait)")
        assertTheLineBeingTypedIsVisible("landscape")

        rotate(PORTRAIT)
        assertTrue(exists(CONTINUE), "portrait again: Continue is back above the keyboard")
        assertTrue(bounds(CONTINUE).bottom <= bounds(Bip39KeyboardTags.KEYBOARD).top + 1f, "Continue sits above the keyboard")
        assertTrue(keyRow() == fullRow, "portrait again: full rows (${keyRow()} px, $fullRow px before)")
        assertTheLineBeingTypedIsVisible("portrait again")
    }

    /** Started in landscape (a cold start there was already right), then turned to portrait and back. */
    @Test
    @Config(qualifiers = "$LANDSCAPE-xhdpi")
    fun anImportStartedInLandscapeGetsThePortraitLayoutAfterARotation() {
        flow(SetupIntent.Restore)
        compose.onNodeWithTag(EDITOR).performScrollTo().performTextInput("abandon ab")
        compose.waitForIdle()
        assertFalse(exists(CONTINUE), "landscape: Continue steps aside while the keyboard is up")
        val shortRow = keyRow()

        rotate(PORTRAIT)
        assertTrue(exists(CONTINUE), "portrait: Continue is pinned again")
        assertTrue(keyRow() > shortRow, "portrait: full rows (${keyRow()} px, $shortRow px in landscape)")
        assertTheLineBeingTypedIsVisible("portrait")
        // Done and a new tap: still the portrait layout (QA: "also after Done and refocus").
        compose.onNodeWithTag(Bip39KeyboardTags.DONE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(EDITOR).performClick()
        compose.waitForIdle()
        assertTrue(exists(CONTINUE), "portrait after Done and a new tap: Continue is pinned")

        rotate(LANDSCAPE)
        assertFalse(exists(CONTINUE), "landscape again: Continue steps aside")
        assertTheLineBeingTypedIsVisible("landscape again")
    }

    /** Whether [node] lies inside the page's vertical scroll (as opposed to the pinned bottom bar). */
    private fun scrollsWithThePage(node: SemanticsNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (SemanticsProperties.VerticalScrollAxisRange in parent.config) return true
            parent = parent.parent
        }
        return false
    }

    private fun tipsInPage(): Boolean = scrollsWithThePage(compose.onNodeWithTag("backup.tips", useUnmergedTree = true).fetchSemanticsNode())

    @Test
    fun theBackupStepMovesSecurityTipsWithARotationBothWays() {
        flow(SetupIntent.Create)
        compose.onNodeWithTag("setup.create").performClick()
        compose.waitForIdle()
        assertFalse(tipsInPage(), "portrait: Security Tips is pinned above the button, as on iOS")
        rotate(LANDSCAPE)
        assertTrue(tipsInPage(), "landscape: Security Tips scrolls with the page")
        rotate(PORTRAIT)
        assertFalse(tipsInPage(), "portrait again: Security Tips is back in the bar")
    }

    private companion object {
        const val EDITOR = "recovery.input.visible"
        const val CONTINUE = "setup.import"
    }
}
