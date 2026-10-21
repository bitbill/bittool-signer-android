package com.bittool.signer.core.ui.keyboard

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.nativeClipboardManager

/**
 * Whether the clipboard holds text to paste, decided as the editor's own Paste menu item decides it (Compose's
 * `ClipboardPasteState`): a primary clip whose description has a text MIME type ([hasTextToPaste]). Only the clip's
 * description is asked for, never the clip itself, so nothing is read before the person asks for it and Android 12+
 * shows no "pasted from your clipboard" notice; the clip is read once, by the Paste key itself.
 *
 * Kept current while the keyboard is shown: on every clipboard change this app hears of (the platform tells only the
 * app with the focus), and again whenever the window gains the focus (back from another app, where something may have
 * been copied, or from a dialog). [clipboard] is the one Paste reads (`LocalClipboard`); its platform clipboard is
 * asked, or the context's when it has none.
 */
@Composable
internal fun rememberClipboardHasText(clipboard: Clipboard): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(clipboard, context) { clipboard.platformClipboard(context) }
    val hasText: MutableState<Boolean> = remember(manager) { mutableStateOf(manager.hasTextToPaste()) }
    DisposableEffect(manager) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { hasText.value = manager.hasTextToPaste() }
        manager?.addPrimaryClipChangedListener(listener)
        onDispose { manager?.removePrimaryClipChangedListener(listener) }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(manager, windowFocused) {
        if (windowFocused) hasText.value = manager.hasTextToPaste()
    }
    return hasText
}

/** The platform clipboard behind [this] (the Paste key's), or [context]'s. */
internal fun Clipboard.platformClipboard(context: Context): ClipboardManager? =
    runCatching { nativeClipboardManager }.getOrNull() ?: context.getSystemService(ClipboardManager::class.java)

/**
 * Whether there is text to paste, from the clip's description only (`hasPrimaryClip`, `primaryClipDescription`: no
 * read of the clip, no clipboard-access notice). False when the clipboard cannot be asked (another app has the focus).
 */
internal fun ClipboardManager?.hasTextToPaste(): Boolean {
    if (this == null) return false
    return runCatching { hasPrimaryClip() && primaryClipDescription?.hasMimeType("text/*") == true }.getOrDefault(false)
}
