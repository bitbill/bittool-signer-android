package com.bittool.signer.core.ui.keyboard

import androidx.compose.ui.text.TextRange
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

/**
 * Port of Ownbit 5.0's `Bip39KeyboardEditsTest` (insert, delete, paste) adapted to the signer: edits are
 * [KeyboardEdit]s applied to the editor's `TextFieldState`, and Paste keeps the editor's own Paste semantics (the
 * clipboard text goes in unchanged). Ownbit's suggestion cases test the editor's "Insert" action in the signer
 * (`:feature:setup` `PhraseKeyboardInsertTest`), and its `caretAfterSeparator` case (an Ownbit-only auto-close) becomes
 * the redaction check below.
 */
class Bip39KeyboardEditsTest {

    /** A text with its selection: `"ab|c"` is the caret at 2, `"a[bc]d"` the selection 1..3. */
    private class Marked(val text: String, val selection: TextRange)

    private fun v(marked: String): Marked {
        val caret = marked.indexOf('|')
        if (caret >= 0) return Marked(marked.removeRange(caret, caret + 1), TextRange(caret))
        val start = marked.indexOf('[')
        val end = marked.indexOf(']') - 1
        return Marked(marked.replace("[", "").replace("]", ""), TextRange(start, end))
    }

    /** The text after [edit], with `|` at its caret. */
    private fun Marked.after(edit: KeyboardEdit?): String {
        assertNotNull(edit, "an edit")
        val result = edit.applyTo(text)
        return result.substring(0, edit.caret) + "|" + result.substring(edit.caret)
    }

    private fun insert(marked: String, s: String) = v(marked).let { it.after(Bip39KeyboardEdits.insert(it.text, it.selection, s)) }
    private fun delete(marked: String) = v(marked).let { it.after(Bip39KeyboardEdits.deleteBackward(it.text, it.selection)) }
    private fun paste(marked: String, clip: String?) = v(marked).let { it.after(Bip39KeyboardEdits.paste(it.text, it.selection, clip)) }

    // ---- insert

    @Test fun insertAtEnd() = assertEquals("abandon a|", insert("abandon |", "a"))

    @Test fun insertInTheMiddle() = assertEquals("aba|ndon", insert("ab|ndon", "a"))

    @Test fun insertAtStart() = assertEquals("x|abandon", insert("|abandon", "x"))

    @Test fun insertReplacesSelection() = assertEquals("abx|on", insert("ab[and]on", "x"))

    @Test fun insertSpace() = assertEquals("abandon |ability", insert("abandon|ability", " "))

    // ---- delete

    @Test fun deleteBeforeCaret() = assertEquals("abando|", delete("abandon|"))

    @Test fun deleteInTheMiddle() = assertEquals("a|andon", delete("ab|andon"))

    @Test fun deleteSelection() = assertEquals("ab|on", delete("ab[and]on"))

    @Test
    fun deleteAtStartDoesNothing() {
        val start = v("|abandon")
        assertNull(Bip39KeyboardEdits.deleteBackward(start.text, start.selection))
        assertNull(Bip39KeyboardEdits.deleteBackward("", TextRange.Zero))
    }

    @Test
    fun deleteRemovesAWholeSurrogatePairAndCjkCharacter() {
        assertEquals("ab|", delete("ab😀|"))
        assertEquals("的一|", delete("的一是|"))
        // A letter typed with a combining accent goes as one character too.
        assertEquals("dragon |", delete("dragon é|"))
    }

    @Test
    fun deleteRepeatedlyEmptiesTheField() {
        var text = "ab c"
        var selection = TextRange(text.length)
        repeat(10) {
            val edit = Bip39KeyboardEdits.deleteBackward(text, selection) ?: return@repeat
            text = edit.applyTo(text)
            selection = TextRange(edit.caret)
        }
        assertEquals("", text)
        assertEquals(TextRange.Zero, selection)
    }

    // ---- paste (the editor's Paste menu item: the clipboard text unchanged, over the selection, caret after it)

    @Test
    fun pasteKeepsTheClipboardTextAsItIs() {
        // Spaces, new lines, case and numbering stay: the tokenizer reads them (setup-backup §11.2).
        assertEquals("  Abandon\tability\n\n 1. able 　about \r\n|", paste("|", "  Abandon\tability\n\n 1. able 　about \r\n"))
        assertEquals("的　一  是|", paste("|", "的　一  是"))
    }

    @Test fun pasteIntoEmptyField() = assertEquals("  abandon\nability |", paste("|", "  abandon\nability "))

    @Test fun pasteAfterAWord() = assertEquals("abandon ability  able|", paste("abandon|", " ability  able"))

    @Test fun pasteBeforeAWord() = assertEquals("ability |abandon", paste("|abandon", "ability "))

    @Test fun pasteBeforeExistingSpace() = assertEquals("abandon ability| zoo", paste("abandon | zoo", "ability"))

    @Test fun pasteReplacesSelection() = assertEquals("abandon ABILITY|", paste("abandon [xyz]", "ABILITY"))

    @Test
    fun pasteOfNothingIsIgnored() {
        val value = v("abandon|")
        assertNull(Bip39KeyboardEdits.paste(value.text, value.selection, null))
        assertNull(Bip39KeyboardEdits.paste(value.text, value.selection, ""))
    }

    // ---- signer: typed words never reach a log

    @Test
    fun editsNeverPrintTheirText() {
        val edit = Bip39KeyboardEdits.insert("legal winner", TextRange(12), " thank")
        assertFalse("thank" in edit.toString(), "KeyboardEdit.toString() names the range only")
        assertFalse("thank" in KeyboardSuggestions.Words(listOf("thank", "that")).toString())
    }
}
