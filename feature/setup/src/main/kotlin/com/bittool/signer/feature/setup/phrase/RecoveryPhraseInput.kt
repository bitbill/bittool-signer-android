package com.bittool.signer.feature.setup.phrase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.ExcludeFromAutofill
import com.bittool.signer.core.designsystem.security.NoPersonalizedLearning
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.feature.setup.backup.RecoveryScreenshotWarning
import com.bittool.signer.feature.setup.flow.PhraseAnalysis
import com.bittool.signer.core.l10n.R as L10n

/**
 * The import editor (iOS `RecoveryPhraseInput`, `Features/Setup/RecoveryPhraseViews.swift:182-218`; setup-backup §7.3):
 * a plain multiline field, at least 132 dp high, body monospaced and left to right in every language, on the card
 * colour with radius 16, placeholder "Enter your words separated by spaces".
 *
 * Input traits (port of iOS `RecoveryInputViewTests`): a text keyboard (CJK input methods must stay usable, so no
 * password type and no "no suggestions" flag), no capitalisation, no autocorrection, no personalised learning
 * (incognito keyboard), no autofill content type and the hosting window excluded from Autofill. Pasting works through
 * the system menu; Copy and Cut are left out of it, so typed recovery words never reach the clipboard (ARCHITECTURE
 * §4.4; iOS offers them). The text is never masked. While it holds text the view is accessibility-sensitive (gaps G5), and a
 * screenshot raises the "may contain" warning without clearing anything. Named "Recovery phrase" for TalkBack, tagged
 * `recovery.input.visible`.
 */
@Composable
internal fun RecoveryPhraseEditor(state: TextFieldState, onFocusChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val label = stringResource(L10n.string.recovery_phrase_2)
    val placeholder = stringResource(L10n.string.enter_your_words_separated_by_spaces)
    RecoveryScreenshotWarning()
    AccessibilitySensitive(active = state.text.isNotEmpty())
    ExcludeFromAutofill()
    NoPersonalizedLearning {
        BasicTextField(
            state = state,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .filterTextContextMenuComponents { it.key !in ClipboardWritingItems }
                .testTag(EDITOR_TAG),
            textStyle = typography.body.monospaced().ltr().copy(color = colors.label),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(colors.accent),
            decorator = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = EDITOR_MIN_HEIGHT)
                        .background(colors.card, SignerShapes.callout)
                        .padding(horizontal = 14.dp, vertical = 16.dp)
                        // The field's name, read after its text (a merged label, not a content description: TalkBack
                        // would read a description instead of the words).
                        .semantics { text = AnnotatedString(label) },
                ) {
                    if (state.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = typography.body.monospaced(),
                            color = colors.tertiaryLabel,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * The live status above the editor (iOS `RecoveryPhraseStatus`, `RecoveryPhraseViews.swift:222-270`; setup-backup
 * §7.2): "Recovery phrase" and, beside it when it fits (below it otherwise), the word count, the detected language and
 * the first unknown words. The checksum is checked on Continue, not here. One element, `recovery.status`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecoveryPhraseStatus(analysis: PhraseAnalysis, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val unknown = analysis.unknownWords.isNotEmpty()
    val icon = when {
        unknown -> SignerIcons.ErrorFilled
        analysis.looksComplete -> SignerIcons.CheckCircleFilled
        else -> SignerIcons.SpaceBar
    }
    val iconTint = when {
        unknown -> colors.danger
        analysis.looksComplete -> colors.success
        else -> colors.secondaryLabel
    }
    FlowRow(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("recovery.status"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(L10n.string.recovery_phrase_2), style = typography.subheadline.semibold(), color = colors.label, modifier = Modifier.padding(end = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            BasicText(
                text = statusText(analysis),
                style = typography.footnote.copy(color = if (unknown) colors.dangerOnTint else colors.secondaryLabel),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = typography.footnote.fontSize * 0.8f, maxFontSize = typography.footnote.fontSize),
            )
        }
    }
}

/** iOS `RecoveryPhraseStatus.status(_:)`: unknown words, the empty hint, or the count and (when meaningful) the language. */
@Composable
internal fun statusText(analysis: PhraseAnalysis): String {
    if (analysis.unknownWords.isNotEmpty()) {
        return stringResource(L10n.string.unknown_x, analysis.unknownWords.joinToString(", ") { "“$it”" })
    }
    val count = when (analysis.wordCount) {
        0 -> return stringResource(L10n.string.n_12_24_words_any_bip39_language)
        1 -> stringResource(L10n.string.n_1_word)
        else -> stringResource(L10n.string.x_words, analysis.wordCount)
    }
    val language = analysis.language ?: return count
    if (language == Bip39Language.English && !analysis.looksComplete) return count
    // The joiner and the endonym are never translated.
    return count + " · " + language.displayName
}

/**
 * Word completions for the word being typed, sitting on the keyboard (iOS `RecoveryPhraseAssistant` in the keyboard
 * toolbar, `RecoveryPhraseViews.swift:273-312`; setup-backup §7.4, §15.5): up to five chips in the detected language's
 * spelling (accents included, "Insert %@", `suggestion.<word>`), or a hint, then "Done", which closes the keyboard.
 */
@Composable
internal fun RecoverySuggestionBar(candidates: Bip39Candidates, onInsert: (String) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when (candidates) {
                is Bip39Candidates.Words -> LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(candidates.words, key = { it }) { word ->
                        SuggestionChip(word, onClick = { onInsert(word) })
                    }
                }
                Bip39Candidates.ChineseHint -> Hint(stringResource(L10n.string.each_chinese_word_is_one_character))
                Bip39Candidates.TypeAWord -> Hint(stringResource(L10n.string.type_a_word_to_see_suggestions))
                Bip39Candidates.NoMatch -> Hint(stringResource(L10n.string.no_matching_word))
                Bip39Candidates.Hidden -> Unit
            }
        }
        TextAction(stringResource(L10n.string.done), onClick = onDone, emphasized = true, modifier = Modifier.testTag("recovery.keyboardDone"))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = SignerTheme.typography.footnote, color = SignerTheme.colors.secondaryLabel, maxLines = 2, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun SuggestionChip(word: String, onClick: () -> Unit) {
    val colors = SignerTheme.colors
    val description = stringResource(L10n.string.insert_x, word)
    Text(
        text = word,
        style = SignerTheme.typography.subheadline.monospaced().semibold().ltr(),
        color = colors.label,
        maxLines = 1,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(SignerShapes.capsule)
            .background(colors.raised)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("suggestion.$word")
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

internal const val EDITOR_TAG = "recovery.input.visible"

/** Context-menu items that would put recovery words on the clipboard (or offer Autofill). */
private val ClipboardWritingItems = setOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.CutKey, TextContextMenuKeys.AutofillKey)
private val EDITOR_MIN_HEIGHT = 132.dp
