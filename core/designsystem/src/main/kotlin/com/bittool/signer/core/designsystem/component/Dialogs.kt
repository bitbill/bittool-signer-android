package com.bittool.signer.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.ElevatedSurfaceLevel
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.designsystem.window.HideOverlayWindowsEffect
import com.bittool.signer.core.designsystem.window.appExposesTestTags
import com.bittool.signer.core.designsystem.window.rememberSecureFlagPolicy
import com.bittool.signer.core.designsystem.window.rememberWindowGateReleased
import com.bittool.signer.core.designsystem.window.windowTestTags
import kotlinx.coroutines.launch

/**
 * One button of a [SignerAlertDialog]. [destructive] draws it in danger (iOS `role: .destructive`: "Reject Request",
 * "Clear Activity", "Delete"). [testTag] carries the e2e id. [onObscuredTouch], when set, makes the button refuse touches
 * that came through another app's overlay and calls it instead (decisive confirmations, ARCHITECTURE §4.8). Actions do
 * not dismiss the dialog by themselves: the caller's state does.
 */
@Immutable
data class DialogAction(
    val text: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val onObscuredTouch: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/**
 * The app's alert (iOS `.alert` / `.confirmationDialog`, design-system §5.20): Material 3 `AlertDialog` on the elevated
 * card, [title] in title3 semibold, the optional message [text] and optional custom [content] (a password field, a
 * choice list) scrolling between title and buttons, [confirm] on the trailing side (semibold) and the optional
 * [dismiss] (the cancel action) before it.
 *
 * Window rules: not shown until `LocalWindowGateOpen` releases it (a dialog requested while App Lock is up appears after
 * unlock; one already shown stays), its window hides foreign overlays (API 31+), and with [secure] the window is
 * `FLAG_SECURE` even where the activity is not (dialogs are separate windows: any dialog that can show a secret passes
 * `secure = true`), except in the debug screenshot tour ([rememberSecureFlagPolicy]). Modal: a tap outside does
 * nothing; Back and the system dismiss call [onDismissRequest] (the cancel action) unless [dismissOnBack] is false
 * (busy). In debuggable builds the window publishes its test tags as resource
 * ids itself (`windowTestTags`): the root's flag does not reach a separate window.
 */
@Composable
fun SignerAlertDialog(
    title: String,
    text: String?,
    confirm: DialogAction,
    dismiss: DialogAction? = null,
    secure: Boolean = false,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBack: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (!rememberWindowGateReleased()) return
    val exposeTestTags = appExposesTestTags()
    val securePolicy = rememberSecureFlagPolicy(secure)
    ElevatedSurfaceLevel {
        val colors = SignerTheme.colors
        val typography = SignerTheme.typography
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = { DialogButton(confirm, emphasized = true) },
            dismissButton = dismiss?.let { { DialogButton(it, emphasized = false) } },
            // Material applies the modifier to the content root inside the dialog's window.
            modifier = modifier.windowTestTags(exposeTestTags),
            title = {
                HideOverlayWindowsEffect()
                Text(title, style = typography.title3.semibold(), color = colors.label)
            },
            text = if (text != null || content != null) {
                {
                    Column(
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (text != null) Text(text, style = typography.body, color = colors.secondaryLabel)
                        content?.invoke(this)
                    }
                }
            } else {
                null
            },
            containerColor = colors.card,
            titleContentColor = colors.label,
            textContentColor = colors.secondaryLabel,
            tonalElevation = 0.dp,
            properties = DialogProperties(
                dismissOnBackPress = dismissOnBack,
                dismissOnClickOutside = false,
                securePolicy = securePolicy,
            ),
        )
    }
}

@Composable
private fun DialogButton(action: DialogAction, emphasized: Boolean) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier
            .then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(action.onObscuredTouch?.let { Modifier.obscuredTouchGuard(it) } ?: Modifier),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (action.destructive) colors.danger else colors.accent,
            disabledContentColor = colors.tertiaryLabel,
        ),
    ) {
        Text(action.text, style = if (emphasized) typography.body.semibold() else typography.body)
    }
}

/** The content scope of a [SignerBottomSheet]: a column plus [dismiss], which animates the sheet away first. */
interface SignerSheetScope : ColumnScope {
    /** Hides the sheet with its animation, then calls the sheet's `onDismissRequest` (a Done / Cancel button). */
    fun dismiss()
}

/**
 * The app's sheet (iOS `.sheet`, design-system §5.20, §9.4): Material 3 `ModalBottomSheet` on the elevated page colour
 * with a drag handle and no tonal tint; fully expanded unless [skipPartiallyExpanded] is false (iOS `.medium` detent,
 * e.g. Security Tips). While not [dismissible] (busy: authorizing a signature, deleting) neither a swipe, a tap on the
 * scrim nor Back closes it (iOS `interactiveDismissDisabled`).
 *
 * Same window rules as [SignerAlertDialog]: waits for `LocalWindowGateOpen`, hides foreign overlays on API 31+, and with
 * [secure] the sheet window is `FLAG_SECURE` (paste, authorize, recovery cards; not in the debug screenshot tour,
 * [rememberSecureFlagPolicy]), and in debuggable builds the content publishes its test tags as resource ids.
 * [testTag] tags the sheet content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignerBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    secure: Boolean = false,
    dismissible: Boolean = true,
    testTag: String? = null,
    content: @Composable SignerSheetScope.() -> Unit,
) {
    if (!rememberWindowGateReleased()) return
    val canDismiss by rememberUpdatedState(dismissible)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = skipPartiallyExpanded,
        confirmValueChange = { it != SheetValue.Hidden || canDismiss },
    )
    val scope = rememberCoroutineScope()
    val exposeTestTags = appExposesTestTags()
    val securePolicy = rememberSecureFlagPolicy(secure)
    ElevatedSurfaceLevel {
        val colors = SignerTheme.colors
        ModalBottomSheet(
            onDismissRequest = { if (canDismiss) currentOnDismiss() },
            modifier = modifier,
            sheetState = state,
            containerColor = colors.page,
            contentColor = colors.label,
            tonalElevation = 0.dp,
            dragHandle = { BottomSheetDefaults.DragHandle(color = colors.tertiaryLabel) },
            properties = ModalBottomSheetProperties(
                securePolicy = securePolicy,
                shouldDismissOnBackPress = dismissible,
                shouldDismissOnClickOutside = dismissible,
            ),
        ) {
            HideOverlayWindowsEffect()
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowTestTags(exposeTestTags)
                    .then(testTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                val sheetScope = remember(this, state) {
                    SignerSheetScopeImpl(this) {
                        scope.launch { state.hide() }.invokeOnCompletion {
                            if (!state.isVisible) currentOnDismiss()
                        }
                    }
                }
                sheetScope.content()
            }
        }
    }
}

private class SignerSheetScopeImpl(column: ColumnScope, private val onDismiss: () -> Unit) :
    SignerSheetScope, ColumnScope by column {
    override fun dismiss() = onDismiss()
}
