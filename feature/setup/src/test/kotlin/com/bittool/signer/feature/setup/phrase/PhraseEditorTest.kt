package com.bittool.signer.feature.setup.phrase

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.PhraseAnalysis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Port of iOS `RecoveryInputViewTests.testRecoveryInputUsesPlainTextWithoutPasswordAutofillTraits` with the real input
 * connection, plus the status line and suggestion bar of setup-backup §7.2 and §7.4.
 */
@RunWith(RobolectricTestRunner::class)
class PhraseEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()

    @Test
    fun theRecoveryInputUsesPlainTextWithoutPasswordOrAutofillTraits() {
        val state = TextFieldState("Input traits fixture")
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) { RecoveryPhraseEditor(state, onFocusChanged = {}) }
        }
        compose.onNodeWithTag(EDITOR_TAG).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val info = EditorInfo()
            assertNotNull(view.onCreateInputConnection(info), "the focused editor has an input session")
            val inputType = info.inputType
            assertEquals(InputType.TYPE_CLASS_TEXT, inputType and InputType.TYPE_MASK_CLASS, "a text keyboard: CJK input methods stay usable")
            assertEquals(0, inputType and InputType.TYPE_MASK_VARIATION and InputType.TYPE_TEXT_VARIATION_PASSWORD, "not a password field")
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "no capitalisation")
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, "no autocorrection")
            assertTrue(inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0, "multiline")
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0, "incognito keyboard")
            assertFalse(view.isImportantForAutofill, "the window is excluded from Autofill")
            assertTrue(view.isAccessibilityDataSensitive, "a non-empty editor is accessibility-sensitive")
        }
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentType))
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test
    fun theEditorIsNamedRecoveryPhraseAndKeepsWhatIsTyped() {
        val state = TextFieldState()
        compose.setContent { SetupTestHost(factory) { RecoveryPhraseEditor(state, onFocusChanged = {}) } }
        compose.onNodeWithTag(EDITOR_TAG).assert(
            SemanticsMatcher("named Recovery phrase") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Recovery phrase" }
            },
        )
        // The placeholder is drawn only.
        compose.onNodeWithText("Enter your words separated by spaces").assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_TAG).performTextInput("abandon abandon")
        compose.runOnIdle { assertEquals("abandon abandon", state.text.toString()) }
    }

    @Test
    fun theStatusNamesTheCountTheLanguageAndUnknownWords() {
        var analysis by mutableStateOf(PhraseAnalysis.Empty)
        compose.setContent { SetupTestHost(factory) { RecoveryPhraseStatus(analysis) } }
        val status = compose.onNodeWithTag("recovery.status")
        fun texts() = status.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertEquals(listOf("Recovery phrase", "12–24 words · any BIP39 language"), texts())
        analysis = PhraseAnalysis(1, emptyList(), Bip39Language.English, false, Bip39Candidates.TypeAWord)
        compose.waitForIdle()
        assertEquals("1 word", texts().last())
        analysis = PhraseAnalysis(3, emptyList(), Bip39Language.English, false, Bip39Candidates.TypeAWord)
        compose.waitForIdle()
        assertEquals("3 words", texts().last(), "English is named only once the phrase looks complete")
        analysis = PhraseAnalysis(12, emptyList(), Bip39Language.English, true, Bip39Candidates.Hidden)
        compose.waitForIdle()
        assertEquals("12 words · English", texts().last())
        analysis = PhraseAnalysis(12, emptyList(), Bip39Language.Spanish, false, Bip39Candidates.Hidden)
        compose.waitForIdle()
        assertEquals("12 words · Español", texts().last(), "other languages are always named")
        analysis = PhraseAnalysis(4, listOf("qwerty", "zxcvb"), Bip39Language.English, false, Bip39Candidates.NoMatch)
        compose.waitForIdle()
        assertEquals("Unknown: “qwerty”, “zxcvb”", texts().last())
    }

    @Test
    fun suggestionChipsInsertTheCanonicalWord() {
        val inserted = mutableListOf<String>()
        var done = 0
        compose.setContent {
            SetupTestHost(factory) {
                RecoverySuggestionBar(Bip39Candidates.Words(listOf("abandon", "ability")), onInsert = { inserted += it }, onDone = { done++ })
            }
        }
        compose.onNodeWithContentDescription("Insert abandon").assertExists()
        compose.onNodeWithTag("suggestion.ability").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ability"), inserted)
            assertEquals(1, done)
        }
    }

    @Test
    fun theBarShowsItsHints() {
        var candidates: Bip39Candidates by mutableStateOf(Bip39Candidates.ChineseHint)
        compose.setContent { SetupTestHost(factory) { RecoverySuggestionBar(candidates, onInsert = {}, onDone = {}) } }
        compose.onNodeWithText("Each Chinese word is one character").assertExists()
        candidates = Bip39Candidates.TypeAWord
        compose.onNodeWithText("Type a word to see suggestions").assertExists()
        candidates = Bip39Candidates.NoMatch
        compose.onNodeWithText("No matching word").assertExists()
        candidates = Bip39Candidates.Hidden
        compose.onNodeWithText("No matching word").assertDoesNotExist()
    }
}
