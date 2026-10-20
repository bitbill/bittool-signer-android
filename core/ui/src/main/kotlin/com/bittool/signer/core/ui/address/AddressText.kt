package com.bittool.signer.core.ui.address

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import kotlinx.coroutines.delay

/**
 * A full address in monospaced groups (iOS `AddressText`, `Design/AddressText.swift:43-62`): groups of [groupSize]
 * joined by single spaces (a `0x` prefix stays on the first group); the **first and last groups semibold** in the
 * label colour, the middle ones regular at 78 % ("swapped addresses usually differ there first"). Left to right in
 * every language, wrapping only between groups; never ellipsized. TalkBack reads the groups separated by commas.
 * Copying always uses the original [address].
 */
@Composable
fun AddressText(
    address: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SignerTheme.typography.body,
    groupSize: Int = 4,
    textAlign: TextAlign? = null,
) {
    val label = SignerTheme.colors.label
    val groups = remember(address, groupSize) { AddressFormatter.groups(address, groupSize) }
    val text = remember(groups, label) { groupedAddress(groups, label) }
    Text(
        text = text,
        modifier = modifier.semantics { contentDescription = groups.joinToString(", ") },
        style = style.monospaced().ltr().copy(hyphens = Hyphens.None),
        color = label,
        textAlign = textAlign ?: TextAlign.Unspecified,
    )
}

/** The styled text of [AddressText] (pure, unit-tested). */
internal fun groupedAddress(groups: List<String>, label: Color): AnnotatedString = buildAnnotatedString {
    groups.forEachIndexed { index, group ->
        if (index > 0) append(' ')
        val edge = index == 0 || index == groups.lastIndex
        withStyle(
            if (edge) SpanStyle(fontWeight = FontWeight.SemiBold, color = label) else SpanStyle(fontWeight = FontWeight.Normal, color = label.copy(alpha = 0.78f)),
        ) { append(group) }
    }
}

/**
 * A long hash or identifier (iOS `HashText`, `Design/AddressText.swift:65-76`): upper-cased like a master fingerprint,
 * in groups of 8, caption monospaced, secondary, selectable, left to right. Used for "Request fingerprint".
 */
@Composable
fun HashText(value: String, modifier: Modifier = Modifier) {
    val shown = remember(value) { hashGroups(value) }
    SelectionContainer(modifier) {
        Text(
            text = shown,
            style = SignerTheme.typography.caption.monospaced().ltr(),
            color = SignerTheme.colors.secondaryLabel,
        )
    }
}

/** `value.uppercased()` grouped by 8 with single spaces (pure). */
internal fun hashGroups(value: String): String = AddressFormatter.groups(value.uppercase(), 8).joinToString(" ")

/**
 * An address with a copy button (iOS `CopyableAddress`, `Design/AddressText.swift:79-109`): [AddressText] filling the
 * width and a 30 dp copy glyph (48 dp target) that turns into a check mark for 1.5 s after copying, with the success
 * haptic; labels "Copy address" → "Copied". A long press offers "Copy Address" (no check mark, no haptic, like the iOS
 * context menu). No toast. Copies through [SignerClipboard] (expires after 120 s). [onCopied] runs after a button copy.
 */
@Composable
fun CopyableAddress(
    address: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SignerTheme.typography.body,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    val haptics = rememberSignerHaptics()
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.copy_address)
    LaunchedEffect(copied) {
        if (copied) {
            delay(SignerMotion.COPIED_MILLIS)
            copied = false
        }
    }
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onLongPress = { menu = true }) }
                .semantics { customActions = listOf(CustomAccessibilityAction(menuLabel) { SignerClipboard.copy(context, address); true }) },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AddressText(address, Modifier.weight(1f), style)
            CopyGlyphButton(
                copied = copied,
                label = stringResource(if (copied) R.string.copied else R.string.copy_address_2),
                onClick = {
                    SignerClipboard.copy(context, address)
                    copied = true
                    haptics.success()
                    onCopied()
                },
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(menuLabel) },
                leadingIcon = { Icon(SignerIcons.Copy, contentDescription = null) },
                onClick = {
                    menu = false
                    SignerClipboard.copy(context, address)
                },
            )
        }
    }
}

/**
 * Non-address text with a copy button (iOS `CopyableText`, `Features/Settings/WalletDirectoryView.swift:88-113`): one
 * monospaced, selectable line (sites, the e-mail address, the repository) and the same copy glyph. Once copied it stays
 * a check mark (iOS keeps it); [copyLabel] is the button's label until then ("Copied" afterwards). No haptic.
 */
@Composable
fun CopyableText(
    text: String,
    copyLabel: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SignerTheme.typography.footnote,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(text, style = style.monospaced().ltr(), color = SignerTheme.colors.label)
        }
        CopyGlyphButton(
            copied = copied,
            label = if (copied) stringResource(R.string.copied) else copyLabel,
            onClick = {
                SignerClipboard.copy(context, text)
                copied = true
                onCopied()
            },
        )
    }
}

/** The 30 dp copy glyph in a 48 dp target, swapping to a check mark with the symbol-replace transition. */
@Composable
internal fun CopyGlyphButton(copied: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = SignerTheme.colors.accent
    IconButton(onClick = onClick, modifier = modifier.testTag(COPY_BUTTON_TAG).semantics { contentDescription = label }) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                (scaleIn(tween(SignerMotion.SYMBOL_REPLACE_MILLIS), 0.6f) + fadeIn(tween(SignerMotion.SYMBOL_REPLACE_MILLIS))) togetherWith
                    (scaleOut(tween(SignerMotion.SYMBOL_REPLACE_MILLIS), 0.6f) + fadeOut(tween(SignerMotion.SYMBOL_REPLACE_MILLIS)))
            },
            label = "copyGlyph",
        ) { done ->
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Icon(if (done) SignerIcons.Check else SignerIcons.Copy, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Test tag of every copy glyph button (tests address it inside larger components). */
const val COPY_BUTTON_TAG = "copyButton"
