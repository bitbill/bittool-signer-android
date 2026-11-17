package com.bittool.signer.feature.setup.backup

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.designsystem.security.LocalScreenReaders
import com.bittool.signer.core.designsystem.security.SCREEN_READER_WARNING_TAG
import com.bittool.signer.core.designsystem.security.ScreenReaders
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.Bip39KeyboardTags
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.phrase.EDITOR_TAG
import com.bittool.signer.feature.setup.phrase.RecoveryPhraseEditor
import com.bittool.signer.feature.setup.phrase.RecoverySuggestionBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-11-16 audit M-03: below API 34 an accessibility service that did not come with the phone could read revealed
 * recovery words and phrase entry. While one is on, the pages warn (B), and while no screen reader is on the words leave
 * the accessibility tree (A). [LocalScreenReaders] stands in for `AccessibilityManager`.
 */
@RunWith(RobolectricTestRunner::class)
// Below API 34 (Android 12L): from 34 the view-level marking already keeps ordinary services away from every node.
@Config(sdk = [Build.VERSION_CODES.S_V2])
class ScreenReaderProtectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()
    private val words = RecoveryWords(Phrases.VECTOR_12.split(' '))
    private val untrustedOnly = ScreenReaders(listOf("Battery Saver Pro"), screenReaderOn = false)
    private val untrustedWithTalkBack = ScreenReaders(listOf("Battery Saver Pro"), screenReaderOn = true)

    private lateinit var view: View

    private fun show(readers: ScreenReaders, content: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) { CompositionLocalProvider(LocalScreenReaders provides readers, content = content) }
        }
    }

    /**
     * How many recovery words an ordinary accessibility service gets, asked the way it asks: every semantics node's
     * `AccessibilityNodeInfo` from the view's provider (null for a node it cannot see), its text, content description,
     * state description and hint. The words themselves are never printed (ARCHITECTURE §4.9).
     */
    private fun wordsInTree(): Int {
        val ids = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().map { it.id }.toList()
        }
        val reported = compose.runOnIdle {
            val provider = checkNotNull(view.accessibilityNodeProvider)
            ids.flatMap { id ->
                val info = provider.createAccessibilityNodeInfo(id) ?: return@flatMap emptyList()
                // Every piece of node metadata a service can ask for, not only what is drawn: the resource id and each
                // advertised extra-data key, requested as a service would. Compose publishes a node's test tag this
                // way (androidx.compose.ui.semantics.testTag), which once carried the completion's word (R3 BTS-14).
                for (key in info.availableExtraData) provider.addExtraDataToAccessibilityNodeInfo(id, info, key, Bundle())
                val extras = info.extras.keySet().map { info.extras.get(it)?.toString() }
                listOf(info.text, info.contentDescription, info.stateDescription, info.hintText, info.viewIdResourceName).map { it?.toString() } + extras
            }.filterNotNull()
        }
        return words.list.toSet().sumOf { word -> reported.count { it.contains(word) } }
    }

    @Test
    fun anUntrustedServiceWithoutAScreenReaderIsWarnedAndGetsNoWords() {
        show(untrustedOnly) { RecoveryBackupPanel(words) }
        compose.onNodeWithTag(SCREEN_READER_WARNING_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(SCREEN_READER_WARNING_TAG).assert(hasText("Battery Saver Pro", substring = true))
        compose.onNodeWithTag("recovery.reveal").performClick()
        // The words are on screen (the cells exist, the tags stay for tests) but not in the accessibility tree.
        compose.onNodeWithTag("recovery.word.0").assertExists()
        assertEquals(0, wordsInTree(), "no revealed word is readable through accessibility")
    }

    @Test
    fun withAScreenReaderTheWordsStayReadableAndTheWarningRemains() {
        show(untrustedWithTalkBack) { RecoveryBackupPanel(words) }
        compose.onNodeWithTag(SCREEN_READER_WARNING_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.word.0").assert(hasText(words.list[0], substring = true))
    }

    @Test
    fun withoutAnUntrustedServiceNothingChanges() {
        show(ScreenReaders.None) { RecoveryBackupPanel(words) }
        compose.onNodeWithTag(SCREEN_READER_WARNING_TAG, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("recovery.reveal").performClick()
        compose.onNodeWithTag("recovery.word.0").assert(hasText(words.list[0], substring = true))
        // The check the other tests rely on does see words when they are there.
        assertTrue(wordsInTree() >= words.list.toSet().size, "every word reaches accessibility below API 34 by default")
    }

    @Test
    fun recoveryCardCellsLoseTheirWordsToo() {
        show(untrustedOnly) { RecoveryCardView(words, card = 0) }
        compose.onNodeWithTag("cards.card.0.word.0").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        assertEquals(0, wordsInTree())
    }

    @Test
    fun thePhraseBeingTypedIsNotInTheTree() {
        val state = TextFieldState(Phrases.VECTOR_12)
        show(untrustedOnly) { RecoveryPhraseEditor(state, onFocusChanged = {}) }
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.EditableText))
        assertEquals(0, wordsInTree())
    }

    @Test
    fun completionChipsStayButtonsWithoutTheirWords() {
        var inserted = ""
        show(untrustedOnly) { RecoverySuggestionBar(Bip39Candidates.Words(listOf("legal", "winner")), onInsert = { inserted = it }, onDone = {}) }
        assertEquals(0, wordsInTree())
        compose.onNodeWithTag(Bip39KeyboardTags.suggestion(0)).performClick()
        assertEquals("legal", inserted, "a tap still inserts the word")
    }

    @Test
    fun withoutAnUntrustedServiceTheEditorKeepsItsText() {
        val state = TextFieldState(Phrases.VECTOR_12)
        show(ScreenReaders.None) { RecoveryPhraseEditor(state, onFocusChanged = {}) }
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
    }
}
