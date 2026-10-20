package com.bittool.signer.core.ui.password

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * The text menu of a field holding a secret (passwords, passphrases, the recovery-phrase editor): **Paste and Select
 * All only**. Compose adds more to every text field's selection menu, and each of the others hands the selected text to
 * someone else: Copy / Cut (the clipboard; ARCHITECTURE §4.4), Autofill, the platform's PROCESS_TEXT actions
 * ("Translate", "Search", "Share", any installed app's text action, which start that app with the selected text) and the
 * smart-selection assistant action. An allowlist, so a menu item a later Compose release adds is left out too. iOS turns
 * Writing Tools off on the phrase editor for the same reason (setup-backup §7.3). [PasswordField] applies it itself.
 */
fun Modifier.secretTextMenu(): Modifier = filterTextContextMenuComponents(SecretTextMenuFilter)

/** Which items [secretTextMenu] keeps (separators are never filtered by Compose). */
val SecretTextMenuFilter: (TextContextMenuComponent) -> Boolean = { component ->
    component.key === TextContextMenuKeys.PasteKey || component.key === TextContextMenuKeys.SelectAllKey
}

/**
 * Empties a secret field **and its undo history**. `TextFieldState.clearText()` alone records the deletion as an
 * undoable edit, so Ctrl+Z (a hardware keyboard, or a keyboard app that sends it) would bring back a password or a
 * phrase that backgrounding, a commit or "Discard" was meant to have erased (setup-backup §2.3, DECISIONS §6). Use it
 * for every secret state, also one held by a ViewModel while its field is not on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
fun TextFieldState.wipe() {
    clearText()
    undoState.clearHistory()
}

/**
 * Consumes the undo and redo shortcuts (Ctrl / Meta + Z, Shift + Z, Y) before the text field sees them: a secret field
 * has no undo, so no shortcut can type back a password that was cleared. Part of [PasswordField].
 */
internal fun Modifier.noUndoShortcuts(): Modifier = onPreviewKeyEvent(::isUndoShortcut)

/** Whether [event] is an undo or redo shortcut on Android (Compose maps Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y). Pure. */
internal fun isUndoShortcut(event: KeyEvent): Boolean =
    (event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.Z || event.key == Key.Y)
