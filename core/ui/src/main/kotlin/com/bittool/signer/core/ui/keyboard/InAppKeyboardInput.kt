package com.bittool.signer.core.ui.keyboard

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import com.bittool.signer.core.designsystem.security.NoPersonalizedLearning
import kotlinx.coroutines.awaitCancellation

/** Which keyboard types into the recovery-phrase editor. Not a secret (it may survive the steps of a presentation). */
@Immutable
enum class PhraseKeyboard {
    /** BitTool's own [Bip39Keyboard]: the editor never has an input-method connection (the default). */
    InApp,

    /** The device's input method, chosen after [SwitchToSystemKeyboardDialog] (Chinese, Japanese, Korean phrases). */
    System,
}

/**
 * Hosts the text field the in-app [Bip39Keyboard] types into (port of Ownbit 5.0's `InAppKeyboardInput`).
 *
 * With [inAppKeyboard] = true the fields inside [content] **never reach an input method**: the platform input request
 * of every Compose text field below is swallowed, so no `InputConnection` is ever created, the hosting view is not a
 * text editor for the `InputMethodManager`, and no keyboard app — the system's or a third party's — nor its suggestion
 * strip, clipboard tool or Autofill can attach and see the words. The fields' own "show keyboard" calls do nothing.
 * The fields stay focusable, draw their caret and keep their selection handles and Paste / Select All menu; the text
 * changes only through the field's state ([Bip39Keyboard], a paste, a hardware keyboard: physical key events reach a
 * focused Compose field without any input-method connection).
 *
 * With false the fields talk to the system keyboard normally inside [NoPersonalizedLearning] (the incognito flag; the
 * editor also turns autocorrection off), as before.
 *
 * Switching the flag recreates [content] (a new input session under the new rules): focus the field again afterwards,
 * and with the system keyboard ask for it (`SoftwareKeyboardController.show()`) once the window has the focus.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InAppKeyboardInput(inAppKeyboard: Boolean, content: @Composable () -> Unit) {
    key(inAppKeyboard) {
        if (inAppKeyboard) {
            val real = LocalSoftwareKeyboardController.current
            val noShow = remember(real) { NoShowKeyboardController(real) }
            CompositionLocalProvider(LocalSoftwareKeyboardController provides noShow) {
                InterceptPlatformTextInput(interceptor = BlockSystemInput, content = content)
            }
        } else {
            NoPersonalizedLearning(content)
        }
    }
}

/**
 * Hides the soft keyboard of [view]'s window through the `InputMethodManager`: works even when no Compose text-input
 * session is active any more (right after [InAppKeyboardInput] switched back to the in-app keyboard).
 */
fun hideSystemKeyboard(view: View) {
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(view.windowToken, 0)
}

/** Never forwards the request: no input method is ever started for the field. */
@OptIn(ExperimentalComposeUiApi::class)
private object BlockSystemInput : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = awaitCancellation()
}

/** Text fields call `show()` on focus and tap; with the in-app keyboard that must do nothing. */
private class NoShowKeyboardController(private val real: SoftwareKeyboardController?) : SoftwareKeyboardController {
    override fun show() = Unit

    override fun hide() {
        real?.hide()
    }
}
