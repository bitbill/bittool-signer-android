package com.bittool.signer.core.ui.keyboard

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextRange
import java.text.BreakIterator
import java.util.Locale

/**
 * One edit of the recovery phrase made by the in-app keyboard ([Bip39Keyboard]): the characters [start] until [end]
 * of the current text become [replacement], and the caret ends at [caret] (an offset in the new text). Holds typed
 * recovery-phrase material: [toString] names the range only, never the text (DECISIONS §6).
 */
@Immutable
class KeyboardEdit(val start: Int, val end: Int, val replacement: String, val caret: Int) {
    /** The text after this edit (tests; the keyboard edits the field's state in place, [applyTo]). */
    fun applyTo(text: CharSequence): String = buildString(text.length - (end - start) + replacement.length) {
        append(text, 0, start)
        append(replacement)
        append(text, end, text.length)
    }

    override fun equals(other: Any?): Boolean = other is KeyboardEdit && other.start == start && other.end == end &&
        other.replacement == replacement && other.caret == caret

    override fun hashCode(): Int = ((start * 31 + end) * 31 + replacement.hashCode()) * 31 + caret

    override fun toString(): String = "KeyboardEdit(start=$start, end=$end, length=${replacement.length}, caret=$caret)"
}

/**
 * The text editing of the in-app recovery-phrase keyboard: a port of Ownbit 5.0's `Bip39KeyboardEdits` (itself the old
 * Android `Bip39KeyboardView` editing code) onto the signer's [TextFieldState] editor. Pure functions of the text and
 * its selection: every edit is returned as a [KeyboardEdit], which [Bip39Keyboard] applies to the field's state, so
 * the typed words live in that state only (wiped by its owner as before) and are never copied anywhere else.
 *
 * The signer keeps its own semantics where they differ from Ownbit: a suggestion goes through the editor's "Insert"
 * action (the caller's, iOS `RecoveryPhraseAssistant.complete(with:)`), and "Paste" inserts the clipboard text exactly
 * as the editor's own Paste menu item does, unchanged; the tokenizer reads spaces, new lines and numbering later.
 */
object Bip39KeyboardEdits {

    /** Replaces the selection (or inserts at the caret) with [s]; the caret ends after the inserted text. */
    fun insert(text: CharSequence, selection: TextRange, s: String): KeyboardEdit {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(0, text.length)
        return KeyboardEdit(start, end, s, start + s.length)
    }

    /**
     * Backspace: deletes the selection, else the character before the caret as the reader sees it (one grapheme
     * cluster, so an emoji, a surrogate pair or a letter with a combining accent goes as one). Null when there is
     * nothing to delete (the caret at the very start).
     */
    fun deleteBackward(text: CharSequence, selection: TextRange): KeyboardEdit? {
        val start = selection.min.coerceIn(0, text.length)
        val end = selection.max.coerceIn(0, text.length)
        if (start != end) return KeyboardEdit(start, end, "", start)
        if (start == 0) return null
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
        characters.setText(text.toString())
        val previous = characters.preceding(start).takeIf { it != BreakIterator.DONE && it < start } ?: (start - 1)
        return KeyboardEdit(previous, start, "", previous)
    }

    /**
     * "Paste", with the semantics of the editor's own Paste menu item (Compose `TextFieldSelectionState.paste`): the
     * clipboard text replaces the selection, or goes in at the caret, unchanged, and the caret ends after it. Null when
     * the clipboard holds no text. Nothing is submitted: Continue stays the person's decision.
     */
    fun paste(text: CharSequence, selection: TextRange, clip: CharSequence?): KeyboardEdit? {
        if (clip.isNullOrEmpty()) return null
        return insert(text, selection, clip.toString())
    }
}

/** Applies [change] to this field (main thread): one programmatic edit, caret at [KeyboardEdit.caret]. */
fun TextFieldState.applyKeyboardEdit(change: KeyboardEdit) {
    edit {
        val start = change.start.coerceIn(0, length)
        val end = change.end.coerceIn(start, length)
        replace(start, end, change.replacement)
        selection = TextRange(change.caret.coerceIn(0, length))
    }
}
