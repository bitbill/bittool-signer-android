package com.bittool.signer.core.ui.payload

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.utf8Size
import com.bittool.signer.core.ui.clipboard.SignerClipboard
import com.bittool.signer.core.ui.text.currentLocale
import com.bittool.signer.core.ui.text.formatInteger
import kotlinx.coroutines.delay

/**
 * The collapsed developer view of what a QR code carries (iOS `PayloadDisclosure`, `Design/PayloadDisclosure.swift`):
 * a tinted toggle row ([title] in subheadline semibold and a chevron that turns 90° with `.snappy(0.25)`; state
 * "Shown" / "Hidden"), and when expanded each of [notes] (caption, secondary), the payload (caption2 monospaced, full
 * width, left to right, **not selectable** — copying goes through the app clipboard so it expires), the truncation note
 * and "Copy Payload" → "Copied" for 1.5 s. Long payloads show the first [PayloadText.DISPLAY_LIMIT] characters (copy
 * includes everything) and get break opportunities inside long runs (see [PayloadText]).
 *
 * Test tags: `<tag>Toggle`, `<tag>` (the payload text), `<tag>Copy`.
 */
@Composable
fun PayloadDisclosure(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    notes: List<String> = emptyList(),
    tag: String = "payload",
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val reduceMotion = SignerTheme.reduceMotion
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, SignerMotion.disclosure(reduceMotion), label = "chevron")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shownLabel = stringResource(if (expanded) R.string.shown else R.string.hidden)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = shownLabel }
                .testTag(tag + "Toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = typography.subheadline.semibold(), color = colors.accent, modifier = Modifier.weight(1f))
            // The chevron is mirrored in RTL, so it turns the other way to end up pointing down.
            Icon(
                SignerIcons.ChevronRight,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp).rotate(if (rtl) -rotation else rotation),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(SignerMotion.disclosure(reduceMotion)) + fadeIn(),
            exit = shrinkVertically(SignerMotion.disclosure(reduceMotion)) + fadeOut(),
        ) {
            PayloadBody(text, notes, tag)
        }
    }
}

@Composable
private fun PayloadBody(text: String, notes: List<String>, tag: String) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val context = LocalContext.current
    val locale = currentLocale()
    val shown = remember(text) { PayloadText.display(text) }
    val total = remember(text) { if (shown.truncated) Graphemes.count(text) else 0 }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(SignerMotion.COPIED_MILLIS)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        notes.forEach { Text(it, style = typography.caption, color = colors.secondaryLabel) }
        Text(
            text = shown.text,
            style = typography.caption2.monospaced().ltr(),
            color = colors.label,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        if (shown.truncated) {
            Text(
                text = stringResource(
                    R.string.showing_the_first_x_of_x_characters_copy_includes,
                    formatInteger(PayloadText.DISPLAY_LIMIT.toLong(), locale),
                    formatInteger(total.toLong(), locale),
                ),
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button) {
                    // A payload too large for the clipboard is not confirmed as copied.
                    if (SignerClipboard.copy(context, text)) copied = true
                }
                .testTag(tag + "Copy"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnimatedContent(
                targetState = copied,
                transitionSpec = { fadeIn(tween(SignerMotion.SYMBOL_REPLACE_MILLIS)) togetherWith fadeOut(tween(SignerMotion.SYMBOL_REPLACE_MILLIS)) },
                label = "copyPayload",
            ) { done ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (done) SignerIcons.Check else SignerIcons.Copy, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(if (done) R.string.copied else R.string.copy_payload),
                        style = typography.footnote.semibold(),
                        color = colors.accent,
                    )
                }
            }
        }
    }
}

/**
 * The payload text as [PayloadDisclosure] lays it out (iOS `PayloadDisclosure.swift:24-44`; pure, unit-tested):
 * - over [DISPLAY_LIMIT] characters, only the first [DISPLAY_LIMIT] plus `"\n…"`;
 * - when the shown text exceeds 2,000 UTF-8 bytes, every line longer than 120 characters gets a zero-width space
 *   after each run of 64 non-space characters (a space resets the run), so wrapping a PSBT's Base64 stays cheap.
 * Characters are grapheme clusters, as Swift counts them. Copying always uses the original text.
 */
object PayloadText {
    const val DISPLAY_LIMIT = 30_000
    private const val BREAK_BYTES = 2_000
    private const val LONG_LINE = 120
    private const val RUN = 64
    const val ZERO_WIDTH_SPACE = '​'

    data class Shown(val text: String, val truncated: Boolean)

    fun display(text: String): Shown {
        val truncated = Graphemes.exceeds(text, DISPLAY_LIMIT)
        val shown = if (truncated) Graphemes.prefix(text, DISPLAY_LIMIT) + "\n…" else text
        if (shown.utf8Size() <= BREAK_BYTES) return Shown(shown, truncated)
        val out = StringBuilder(shown.length + shown.length / RUN)
        shown.split('\n').forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            if (!Graphemes.exceeds(line, LONG_LINE)) {
                out.append(line)
                return@forEachIndexed
            }
            var run = 0
            fun step(isSpace: Boolean) {
                run = if (isSpace) 0 else run + 1
                if (run == RUN) {
                    out.append(ZERO_WIDTH_SPACE)
                    run = 0
                }
            }
            if (line.all { it.code < 0x80 && it != '\r' }) {
                // Payloads are ASCII (JSON, hex, Base64): every UTF-16 unit is one character, no segmentation needed.
                for (character in line) {
                    out.append(character)
                    step(character == ' ')
                }
            } else {
                for (character in Graphemes.split(line)) {
                    out.append(character)
                    step(character == " ")
                }
            }
        }
        return Shown(out.toString(), truncated)
    }
}
