package com.bittool.signer.feature.setup.phrase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.nativeClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.offers
import com.bittool.signer.feature.setup.processTextKey
import com.bittool.signer.feature.setup.textMenuFilters
import com.bittool.signer.feature.setup.flow.PhraseAnalysis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
            assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, inputType and InputType.TYPE_MASK_VARIATION, "no password or visible-password variation (Latin-only layouts)")
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "no capitalisation")
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, "no autocorrection")
            assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE, "no completions")
            // QA regression-01: Gboard showed its suggestion strip ("abandoned", a clipboard chip) without this flag.
            assertTrue(inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0, "no suggestion strip")
            assertTrue(inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0, "multiline")
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0, "incognito keyboard")
            assertFalse(view.isImportantForAutofill, "the window is excluded from Autofill")
            assertTrue(view.isAccessibilityDataSensitive, "a non-empty editor is accessibility-sensitive")
        }
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentType))
        compose.onNodeWithTag(EDITOR_TAG).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    /** The no-suggestions flag goes on text fields only (a number or phone field has no such flag to set). */
    @Test
    fun noSuggestionsMarksTextFieldsOnly() {
        val text = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        applyNoSuggestions(text)
        assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, text.inputType)
        val number = EditorInfo().apply { inputType = InputType.TYPE_CLASS_NUMBER }
        applyNoSuggestions(number)
        assertEquals(InputType.TYPE_CLASS_NUMBER, number.inputType)
    }

    /**
     * The editor's text menu holds nothing that hands the typed words to someone else: no Copy / Cut (the clipboard),
     * no Autofill, none of the platform's PROCESS_TEXT actions ("Translate", "Search", any installed app's text
     * action) and no smart-selection action, which start another app with the selected words (iOS turns Writing Tools
     * off on this editor). Paste and Select All stay. Checked on the filter the editor really carries, against every
     * kind of item Compose adds to a text field's menu.
     */
    @Test
    fun theTextMenuOffersNoActionThatSendsTheWordsOutOfTheApp() {
        val state = TextFieldState("legal winner thank year")
        compose.setContent { SetupTestHost(factory) { RecoveryPhraseEditor(state, onFocusChanged = {}) } }
        val filters = compose.onNodeWithTag(EDITOR_TAG).textMenuFilters()
        assertTrue(filters.isNotEmpty(), "the editor filters its text menu")
        assertTrue(offers(filters, TextContextMenuKeys.PasteKey), "Paste stays (as on iOS)")
        assertTrue(offers(filters, TextContextMenuKeys.SelectAllKey))
        assertFalse(offers(filters, TextContextMenuKeys.CopyKey), "no Copy: recovery words never reach the clipboard")
        assertFalse(offers(filters, TextContextMenuKeys.CutKey))
        assertFalse(offers(filters, TextContextMenuKeys.AutofillKey))
        assertFalse(offers(filters, processTextKey(0)), "no PROCESS_TEXT action (Translate, Search…) receives the words")
        assertFalse(offers(filters, processTextKey(3)))
        assertFalse(offers(filters, Any()), "nor any other platform item, such as the smart-selection assistant action")
    }

    /**
     * The clipboard the editor's text field is given reads the real one (Paste, and the check for text to paste) and
     * never writes to it or clears it: whatever route a copy or a cut takes, the recovery words stay off the clipboard
     * (ARCHITECTURE §4.4, final audit A01).
     */
    @Test
    fun theEditorsClipboardReadsButNeverWrites() {
        lateinit var noWrites: Clipboard
        lateinit var context: Context
        compose.setContent {
            context = LocalContext.current
            noWrites = rememberNoWriteClipboard()
        }
        val clipboard = compose.runOnIdle { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "zoo"))
        runBlocking {
            noWrites.setClipEntry(ClipEntry(ClipData.newPlainText("plain text", "legal winner")))
            noWrites.setClipEntry(null)
            assertEquals("zoo", noWrites.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString(), "Paste reads the real clipboard")
        }
        assertEquals("zoo", clipboard.primaryClip?.getItemAt(0)?.text?.toString(), "nothing was written or cleared")
        assertTrue(noWrites.nativeClipboardManager.primaryClipDescription?.hasMimeType("text/*") == true, "the field can tell there is text to paste")
    }

    /**
     * That clipboard really is the one the editor's text field copies and cuts into, with either keyboard: the field's
     * own Copy and Cut put nothing on the clipboard even on a route that none of the earlier refusals covers (final
     * audit A01). The route here is Compose's previous text toolbar, which the app does not use and which neither the
     * menu filter (`secretTextMenu`) nor the semantics guard ([refuseCopyAndCut]) touches: its items call the field's
     * `copy()` / `cut()` directly, so only the clipboard the field was given stands between them and the system one.
     * (That toolbar opens in touch mode only, which Robolectric's window never enters by itself.)
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun theFieldsOwnCopyAndCutWriteNothingWithEitherKeyboard() {
        val newContextMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        try {
            val state = TextFieldState()
            val toolbar = RecordingTextToolbar()
            var inAppKeyboard by mutableStateOf(true)
            lateinit var context: Context
            compose.setContent {
                context = LocalContext.current
                SetupTestHost(factory) {
                    CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalInputModeManager provides TouchInputMode) {
                        RecoveryPhraseEditor(state, onFocusChanged = {}, inAppKeyboard = inAppKeyboard)
                    }
                }
            }
            val clipboard = compose.runOnIdle { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
            val editor = compose.onNodeWithTag(EDITOR_TAG)
            for (inApp in listOf(true, false)) {
                compose.runOnIdle { inAppKeyboard = inApp }
                editor.requestFocus()
                compose.waitForIdle()
                for (cut in listOf(false, true)) {
                    val name = if (cut) "Cut" else "Copy"
                    compose.runOnIdle { state.setTextAndPlaceCursorAtEnd("legal winner") }
                    compose.waitForIdle()
                    clipboard.setPrimaryClip(ClipData.newPlainText("other", "zoo"))
                    toolbar.copy = null
                    toolbar.cut = null
                    // A selection made through semantics (as TalkBack makes one) opens the toolbar.
                    editor.performSemanticsAction(SemanticsActions.SetSelection) { it(0, "legal winner".length, false) }
                    compose.waitForIdle()
                    val item = assertNotNull(if (cut) toolbar.cut else toolbar.copy, "the field offers $name on this route (inAppKeyboard = $inApp)")
                    compose.runOnIdle { item() }
                    compose.waitForIdle()
                    assertEquals("zoo", clipboard.primaryClip?.getItemAt(0)?.text?.toString(), "$name put nothing on the clipboard (inAppKeyboard = $inApp)")
                }
            }
        } finally {
            ComposeFoundationFlags.isNewContextMenuEnabled = newContextMenu
        }
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

/** Compose's previous text toolbar, recording the Copy and Cut items a text field hands it. */
private class RecordingTextToolbar : TextToolbar {
    var copy: (() -> Unit)? = null
    var cut: (() -> Unit)? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        copy = onCopyRequested
        cut = onCutRequested
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}

/** A window in touch mode, where a text field shows its toolbar. */
private object TouchInputMode : InputModeManager {
    override val inputMode: InputMode = InputMode.Touch

    override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Touch
}
