package com.bittool.signer.feature.setup.phrase

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.PhraseKeyboard
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.flow.WalletSetupViewModel
import com.bittool.signer.feature.setup.testBip39
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Ownbit 5.0's suggestion cases of `Bip39KeyboardEditsTest`, adapted to the signer: a chip of the in-app keyboard (and
 * of the suggestion bar over the system keyboard) is the editor's "Insert" action, iOS `complete(with:)` — the run at
 * the end of the text matching `[^\s,，、]+$` becomes the word and a space, and the caret goes to the end; the chips
 * are for that trailing word whatever the caret or the selection. (Ownbit replaced the word under the caret.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhraseKeyboardInsertTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var model: WalletSetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = WalletSetupViewModel(
            SetupIntent.Restore, FakeSignerStore(), testBip39, SeededRandomSource(), FakeRecoverySessionFactory(), dispatcher,
            InMemoryDeviceKeyStore(),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val phrase: TextFieldState get() = model.fields.phrase

    /** Puts `"ab|c"` / `"a[bc]d"` into the editor. */
    private fun set(marked: String) {
        val caret = marked.indexOf('|')
        val (text, selection) = if (caret >= 0) {
            marked.removeRange(caret, caret + 1) to TextRange(caret)
        } else {
            marked.replace("[", "").replace("]", "") to TextRange(marked.indexOf('['), marked.indexOf(']') - 1)
        }
        phrase.edit {
            replace(0, length, text)
            this.selection = selection
        }
    }

    private fun marked(): String {
        val caret = phrase.selection.start
        return phrase.text.substring(0, caret) + "|" + phrase.text.substring(caret)
    }

    private fun insert(marked: String, word: String): String {
        set(marked)
        model.insertCandidate(word)
        return marked()
    }

    @Test fun aSuggestionCompletesTheWordAtTheEndAndAddsASpace() = assertEquals("abandon ability |", insert("abandon abi|", "ability"))

    @Test fun theTrailingWordIsCompletedWhereverTheCaretIs() = assertEquals("abandon abi zoo |", insert("abandon ab|i zo", "zoo"))

    @Test fun aTextEndingInASeparatorIsLeftAlone() = assertEquals("abandon abil |", insert("abandon abil |", "ability"))

    @Test fun theFirstWordIsCompletedToo() = assertEquals("abandon |", insert("aba|", "abandon"))

    @Test fun nothingIsInsertedAfterASpace() = assertEquals("abandon |", insert("abandon |", "zoo"))

    @Test fun aSelectionDoesNotChangeWhatIsCompleted() = assertEquals("abandon ability |", insert("abandon [abx]", "ability"))

    /** Ownbit's `wordPrefixAtCaret`: the chips follow the word at the end of the text, case-insensitively. */
    @Test
    fun theChipsAreForTheTrailingWord() {
        fun candidates(text: String) = testBip39.candidates(testBip39.draft(text))
        assertEquals("ability", assertIs<Bip39Candidates.Words>(candidates("abandon abi")).words.first())
        assertEquals("ability", assertIs<Bip39Candidates.Words>(candidates("abandon ABI")).words.first())
        assertEquals(Bip39Candidates.TypeAWord, candidates("abandon "))
        assertEquals(Bip39Candidates.ChineseHint, candidates("的一"))
        assertEquals(Bip39Candidates.NoMatch, candidates("abandon qx"))
    }

    @Test
    fun everyPresentationStartsWithTheInAppKeyboard() {
        assertEquals(PhraseKeyboard.InApp, model.phraseKeyboard)
        model.usePhraseKeyboard(PhraseKeyboard.System)
        assertEquals(PhraseKeyboard.System, model.phraseKeyboard)
    }
}
