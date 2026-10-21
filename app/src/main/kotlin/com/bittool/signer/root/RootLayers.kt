package com.bittool.signer.root

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.brand.LaunchView
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.EmptyState
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.ui.text.asString

/**
 * The privacy cover (iOS `PrivacyShieldView`, Services/PrivacyShield.swift:68-84, drawn in-tree like RootView.swift:31):
 * identical to the launch screen, so a glance at an inactive app never shows wallet content; while the screen is
 * recorded or mirrored it says why. Instant (no transition). It takes every touch. Snapshot protection itself is
 * `FLAG_SECURE` (drawing in `onPause` often lands after the Recents thumbnail, app-shell.md §15.8); this is the visible
 * part, also in the lock window.
 */
@Composable
fun PrivacyCover(visible: Boolean, captured: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    LaunchView(
        captured = captured,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .testTag(RootTags.PRIVACY_COVER),
    )
}

/**
 * "Wallets Unavailable" (iOS `LoadFailureView`, RootView.swift:92-113; app-shell.md §4.2): the encrypted files are
 * untouched; "Try Again" reloads, showing progress and refusing a second tap meanwhile. The reload's error also appears
 * in "Unable to Continue", as on iOS (ARCHITECTURE §2.1: parity).
 */
@Composable
fun LoadFailureScreen(retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(RootTags.LOAD_FAILURE),
    ) {
        // Centred when it fits; scrolls on a short window or with large text instead of cutting "Try Again" off.
        val viewport = maxHeight
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                icon = SignerIcons.StorageAlert,
                title = stringResource(R.string.wallets_unavailable),
                description = stringResource(R.string.your_encrypted_wallet_files_are_unchanged_unlock_this),
                action = {
                    PrimaryButton(
                        text = stringResource(R.string.try_again),
                        icon = SignerIcons.Refresh,
                        busy = retrying,
                        enabled = !retrying,
                        onClick = onRetry,
                        modifier = Modifier.widthIn(max = 280.dp).testTag(RootTags.RETRY),
                    )
                },
            )
        }
    }
}

/**
 * The app-wide failure alert (iOS RootView.swift:66-68): title "Unable to Continue", the message verbatim (already the
 * localised `UiText`), one "OK" that clears it. A `SignerAlertDialog`, so while App Lock is up it waits for the gate.
 *
 * The message keeps its own direction (iOS `Text(store.error)`, natural direction; backlog B25): store and engine text
 * may be English, or carry an address, in an Arabic interface, and a right-to-left paragraph would move its punctuation
 * and numbers. Compose lays a plain `String` out by its first strong character today, but annotated or selectable text
 * takes the layout direction (QA round 1 sign-02), so the direction is explicit: drawn like the dialog's own message,
 * with `contentDirection()` as `Callout(verbatim)` does.
 */
@Composable
fun UnableToContinueDialog(error: UiText, onDismiss: () -> Unit) {
    val message = error.asString()
    SignerAlertDialog(
        title = stringResource(R.string.unable_to_continue),
        text = null,
        confirm = DialogAction(text = stringResource(R.string.ok), testTag = RootTags.ERROR_OK, onClick = onDismiss),
        onDismissRequest = onDismiss,
    ) {
        Text(
            message,
            style = SignerTheme.typography.body.contentDirection(),
            color = SignerTheme.colors.secondaryLabel,
            modifier = Modifier.testTag(RootTags.ERROR_MESSAGE),
        )
    }
}

/** Test tags of the root layers (Android-only; the iOS suite finds these by their text). */
object RootTags {
    const val ROOT = "root"
    const val PRIVACY_COVER = "privacy.cover"
    const val LOAD_FAILURE = "root.loadFailure"
    const val RETRY = "root.retry"
    const val ERROR_OK = "root.error.ok"
    const val ERROR_MESSAGE = "root.error.message"
    const val SETUP = "setup.overlay"
}
