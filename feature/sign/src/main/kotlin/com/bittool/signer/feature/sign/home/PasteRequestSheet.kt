package com.bittool.signer.feature.sign.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.feature.sign.common.RevealedErrorCallout
import com.bittool.signer.feature.sign.common.SheetHeader
import com.bittool.signer.feature.sign.common.SignTags
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Paste Request" (inventory row 49; iOS `PasteRequestSheet`, `Features/Sign/ScanTab.swift:263-325`): a full sheet with
 * "Cancel", the instruction, a monospaced editor (`signing.payload`, no autocorrect or capitalisation), "Paste from
 * Clipboard" (replaces the text), the import error (the keyboard goes down and the error comes into view under the
 * request), and "Review Request"
 * (`signing.decode`) pinned above the keyboard, enabled once the text is not blank. Every dismissal (Cancel, swipe,
 * Back) discards the text. Once the request is reviewed ([PasteUiState.closing]) the sheet slides away by itself, as
 * iOS `dismiss()` does, instead of vanishing.
 */
@Composable
internal fun PasteRequestSheet(
    state: PasteUiState,
    text: TextFieldState,
    canSubmit: Boolean,
    onDismiss: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    onSubmit: () -> Unit,
) {
    SignerBottomSheet(onDismissRequest = onDismiss, secure = true, testTag = SignTags.PASTE_SHEET) {
        val sheet = this
        LaunchedEffect(state.closing) {
            if (state.closing) sheet.dismiss()
        }
        // A window of its own: SignerBottomSheet publishes its test tags as resource ids (e2e, gaps G1.8).
        Column {
            SheetHeader(title = stringResource(R.string.paste_request), onCancel = { sheet.dismiss() })
            PasteRequestBody(state, text, onPasteFromClipboard, Modifier.weight(1f, fill = false))
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.review_request),
                    onClick = onSubmit,
                    busy = state.busy,
                    enabled = canSubmit,
                    modifier = Modifier.testTag(SignTags.DECODE),
                )
            }
        }
    }
}

/** The sheet's scrolling part (also drawn by the screenshot tests). */
@Composable
internal fun ColumnScope.PasteRequestBody(
    state: PasteUiState,
    text: TextFieldState,
    onPasteFromClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SignerMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.paste_a_psbt_base64_or_hex_or_a_ur_signing_request),
            style = typography.subheadline,
            color = colors.secondaryLabel,
        )
        BasicTextField(
            state = text,
            modifier = Modifier.fillMaxWidth().testTag(SignTags.PAYLOAD),
            textStyle = typography.footnote.monospaced().ltr().copy(color = colors.label),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(colors.accent),
            decorator = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .background(colors.card, SignerShapes.callout)
                        .padding(10.dp),
                ) { inner() }
            },
        )
        Row(
            modifier = Modifier
                .heightIn(min = SignerMetrics.minTouchTarget)
                .clickable(role = Role.Button, onClick = onPasteFromClipboard)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SignerIcons.Paste, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.paste_from_clipboard), style = typography.subheadline.semibold(), color = colors.accent)
        }
        // Under the editor like iOS; brought into view, since the keyboard usually covers this end of the sheet. Above
        // the keyboard the sheet shows little more than the editor, so revealing the error there scrolled the typed
        // text and the caret out of view (QA regression-05): the keyboard goes down first, then the error comes into
        // view under the request. A tap on the editor brings the keyboard back.
        state.error?.let { error ->
            val keyboard = LocalSoftwareKeyboardController.current
            val ime = WindowInsets.ime
            val density = LocalDensity.current
            RevealedErrorCallout(
                text = error,
                beforeReveal = {
                    keyboard?.hide()
                    withTimeoutOrNull(KEYBOARD_HIDE_TIMEOUT_MILLIS) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
                },
            )
        }
    }
}

/** How long the error waits for the keyboard to slide away before it is brought into view anyway. */
private const val KEYBOARD_HIDE_TIMEOUT_MILLIS = 600L
