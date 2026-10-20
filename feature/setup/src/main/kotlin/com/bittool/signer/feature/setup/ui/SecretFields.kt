package com.bittool.signer.feature.setup.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.ui.Modifier

/**
 * The text menu of a field holding recovery words or a secret (the phrase editor, the passphrase and password
 * fields): **Paste and Select All only**. Compose adds more to every text field's selection menu, and each of the
 * others hands the selected text to someone else: Copy / Cut (the clipboard; ARCHITECTURE §4.4), Autofill, the
 * platform's PROCESS_TEXT actions ("Translate", "Search", any installed app's text action, which start that app with
 * the selected words) and the smart-selection assistant action. An allowlist, so a menu item a later Compose release
 * adds is left out too. iOS turns Writing Tools off on the phrase editor for the same reason (setup-backup §7.3).
 */
internal fun Modifier.secretTextMenu(): Modifier = filterTextContextMenuComponents(SecretTextMenuFilter)

/** Which items [secretTextMenu] keeps (separators are never filtered by Compose). */
internal val SecretTextMenuFilter: (TextContextMenuComponent) -> Boolean = { component ->
    component.key === TextContextMenuKeys.PasteKey || component.key === TextContextMenuKeys.SelectAllKey
}

/**
 * Empties a secret field **and its undo history**. `TextFieldState.clearText()` alone records the deletion as an
 * undoable edit, so Ctrl+Z (a hardware keyboard, or a keyboard app that sends it) would bring back a password or a
 * phrase that backgrounding, a commit or "Discard" was meant to have erased (setup-backup §2.3, DECISIONS §6).
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun TextFieldState.wipe() {
    clearText()
    undoState.clearHistory()
}
