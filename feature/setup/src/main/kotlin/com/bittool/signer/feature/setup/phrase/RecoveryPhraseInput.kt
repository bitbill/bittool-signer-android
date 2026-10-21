package com.bittool.signer.feature.setup.phrase

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.ExcludeFromAutofill
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.ui.keyboard.InAppKeyboardChip
import com.bittool.signer.core.ui.keyboard.InAppKeyboardInput
import com.bittool.signer.core.ui.keyboard.KeyboardSuggestions
import com.bittool.signer.core.ui.password.secretTextMenu
import com.bittool.signer.feature.setup.backup.RecoveryScreenshotWarning
import com.bittool.signer.feature.setup.flow.PhraseAnalysis
import com.bittool.signer.feature.setup.ui.iconSizeFor
import com.bittool.signer.core.l10n.R as L10n

/**
 * The import editor (iOS `RecoveryPhraseInput`, `Features/Setup/RecoveryPhraseViews.swift:182-218`; setup-backup §7.3):
 * a plain multiline field, at least 132 dp high, body monospaced and left to right in every language, on the card
 * colour with radius 16, placeholder "Enter your words separated by spaces".
 *
 * Input traits (port of iOS `RecoveryInputViewTests`): a text keyboard (CJK input methods must stay usable, so no
 * password type and no "no suggestions" flag), no capitalisation, no autocorrection, no personalised learning
 * (incognito keyboard), no autofill content type and the hosting window excluded from Autofill. Pasting works through
 * the system menu, which offers only Paste and Select All ([secretTextMenu]): typed recovery words never reach the
 * clipboard (ARCHITECTURE §4.4; iOS offers Copy) nor another app's text action such as "Translate". The text is never
 * masked. While it holds text the view is accessibility-sensitive (gaps G5), and a
 * screenshot raises the "may contain" warning without clearing anything. Named "Recovery phrase" for TalkBack, tagged
 * `recovery.input.visible`.
 *
 * Right to left (QA setup-07): the field is laid out left to right as a whole, not only its text. A text field sizes
 * its text to the words typed and its box places it at the start, which in Arabic is the right edge: the words sat
 * flush right with a ragged left edge and the caret started on the right. Every BIP39 list is written left to right,
 * so the words and the caret now start at the left edge (iOS: `UITextView` aligns an LTR paragraph naturally, to the
 * left). The placeholder is a translated sentence: it keeps the interface direction and sits at its start, as the iOS
 * overlay does (`ZStack(alignment: .topLeading)`, the right edge in Arabic).
 *
 * Android keyboard trust (setup-backup §15.8; iOS refuses third-party keyboards, Android cannot): with [inAppKeyboard]
 * the editor is typed on BitTool's own `Bip39Keyboard` and has **no input-method connection at all**
 * ([InAppKeyboardInput]): no keyboard app, the system's or a third party's, ever sees the words. Caret, selection,
 * Paste / Select All and physical keyboards keep working. Without it (the person chose the system keyboard for a
 * Chinese, Japanese or Korean phrase) the traits above apply. Switching recreates the field: [focusRequester] focuses
 * the new one.
 */
@Composable
internal fun RecoveryPhraseEditor(
    state: TextFieldState,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    inAppKeyboard: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val label = stringResource(L10n.string.recovery_phrase_2)
    val placeholder = stringResource(L10n.string.enter_your_words_separated_by_spaces)
    RecoveryScreenshotWarning()
    AccessibilitySensitive(active = state.text.isNotEmpty())
    ExcludeFromAutofill()
    val interfaceDirection = LocalLayoutDirection.current
    InAppKeyboardInput(inAppKeyboard) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BasicTextField(
                state = state,
                modifier = modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .secretTextMenu()
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
                            // A translated sentence: in the interface direction, from the interface's start edge.
                            CompositionLocalProvider(LocalLayoutDirection provides interfaceDirection) {
                                Text(
                                    placeholder,
                                    style = typography.body.monospaced(),
                                    color = colors.tertiaryLabel,
                                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                                )
                            }
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * The live status above the editor (iOS `RecoveryPhraseStatus`, `RecoveryPhraseViews.swift:222-270`; setup-backup
 * §7.2): "Recovery phrase" and, beside it when it fits (below it otherwise), the word count, the detected language and
 * the first unknown words. The checksum is checked on Continue, not here. One element, `recovery.status`.
 *
 * Like iOS `ViewThatFits`: side by side only when the whole status fits beside the label at its full size; otherwise
 * it goes below the label with the full width. There it may take two lines and shrink to 80 % (iOS: one line at 80 %,
 * then truncated) and ends in an ellipsis if it still does not fit: never cut off silently (QA setup-05, where large
 * text dropped "language" from "12–24 words · any BIP39 language").
 */
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
    Layout(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("recovery.status"),
        content = {
            Text(stringResource(L10n.string.recovery_phrase_2), style = typography.subheadline.semibold(), color = colors.label)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSizeFor(typography.footnote, 1.23f)))
                BasicText(
                    text = statusText(analysis),
                    style = typography.footnote.copy(color = if (unknown) colors.dangerOnTint else colors.secondaryLabel),
                    maxLines = STATUS_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = typography.footnote.fontSize * 0.8f, maxFontSize = typography.footnote.fontSize),
                    modifier = Modifier.testTag(STATUS_TEXT_TAG),
                )
            }
        },
        measurePolicy = StatusLayout,
    )
}

/**
 * Label and status of [RecoveryPhraseStatus]: side by side (status at the end, centred on the label) when the status
 * fits there at its natural width, else stacked 4 dp apart.
 */
private object StatusLayout : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val (label, status) = measurables
        val width = constraints.maxWidth
        val gap = STATUS_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labelPlaceable = label.measure(loose)
        val sideBySide = constraints.hasBoundedWidth &&
            labelPlaceable.width + gap + status.maxIntrinsicWidth(Constraints.Infinity) <= width
        return if (sideBySide) {
            val statusPlaceable = status.measure(loose.copy(maxWidth = width - labelPlaceable.width - gap))
            val height = maxOf(labelPlaceable.height, statusPlaceable.height)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                statusPlaceable.placeRelative(width - statusPlaceable.width, (height - statusPlaceable.height) / 2)
            }
        } else {
            val statusPlaceable = status.measure(loose)
            val stackGap = STATUS_STACK_GAP.roundToPx()
            val layoutWidth = if (constraints.hasBoundedWidth) width else maxOf(labelPlaceable.width, statusPlaceable.width)
            layout(layoutWidth, labelPlaceable.height + stackGap + statusPlaceable.height) {
                labelPlaceable.placeRelative(0, 0)
                statusPlaceable.placeRelative(0, labelPlaceable.height + stackGap)
            }
        }
    }
}

private val STATUS_GAP = 8.dp
private val STATUS_STACK_GAP = 4.dp
private const val STATUS_MAX_LINES = 2
internal const val STATUS_TEXT_TAG = "recovery.status.text"

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
 * Word completions for the word being typed, sitting on the system keyboard (iOS `RecoveryPhraseAssistant` in the
 * keyboard toolbar, `RecoveryPhraseViews.swift:273-312`; setup-backup §7.4, §15.5): up to five chips in the detected
 * language's spelling (accents included, "Insert %@", `suggestion.<word>`), or a hint, then "Done", which closes the
 * keyboard. With BitTool's own keyboard the same completions and hints sit in that keyboard's top row instead
 * ([toKeyboardSuggestions]); this bar is for the system keyboard only, and there [onInAppKeyboard] adds the "BitTool
 * keyboard" chip first, which switches back.
 *
 * The chips scroll under a fading edge on the side where more follow, and end [SUGGESTION_DONE_GAP] before "Done"
 * (iOS: the scroll view, a spacer, then "Done"), so a chip that does not fit is never cut mid-word against "Done"
 * (QA setup-10).
 */
@Composable
internal fun RecoverySuggestionBar(
    candidates: Bip39Candidates,
    onInsert: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onInAppKeyboard: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when (candidates) {
                is Bip39Candidates.Words -> {
                    val list = rememberLazyListState()
                    LazyRow(
                        state = list,
                        modifier = Modifier.fadingEdges(list).testTag(SUGGESTIONS_TAG),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The switch back scrolls away with the chips, so they keep the room.
                        if (onInAppKeyboard != null) item(key = SWITCH_BACK_KEY) { InAppKeyboardChip(onClick = onInAppKeyboard) }
                        items(candidates.words, key = { it }) { word ->
                            SuggestionChip(word, onClick = { onInsert(word) })
                        }
                    }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onInAppKeyboard != null) InAppKeyboardChip(onClick = onInAppKeyboard)
                    when (candidates) {
                        Bip39Candidates.ChineseHint -> Hint(stringResource(L10n.string.each_chinese_word_is_one_character), Modifier.weight(1f, fill = false))
                        Bip39Candidates.TypeAWord -> Hint(stringResource(L10n.string.type_a_word_to_see_suggestions), Modifier.weight(1f, fill = false))
                        Bip39Candidates.NoMatch -> Hint(stringResource(L10n.string.no_matching_word), Modifier.weight(1f, fill = false))
                        else -> Unit
                    }
                }
            }
        }
        Spacer(Modifier.width(SUGGESTION_DONE_GAP))
        TextAction(stringResource(L10n.string.done), onClick = onDone, emphasized = true, modifier = Modifier.testTag("recovery.keyboardDone"))
    }
}

/**
 * Fades the content out over [SUGGESTION_FADE] at each edge where [state] can scroll further, the reading-direction
 * end first (mirrored right to left). Drawn in the draw phase only.
 */
private fun Modifier.fadingEdges(state: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = SUGGESTION_FADE.toPx().coerceAtMost(size.width / 2f)
            val rtl = layoutDirection == LayoutDirection.Rtl
            // The reading-direction end and start, as left/right edges of the row.
            val fadeRight = if (rtl) state.canScrollBackward else state.canScrollForward
            val fadeLeft = if (rtl) state.canScrollForward else state.canScrollBackward
            if (fadeRight) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (fadeLeft) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

private val SUGGESTION_DONE_GAP = 8.dp
private val SUGGESTION_FADE = 24.dp
internal const val SUGGESTIONS_TAG = "recovery.suggestions"

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SignerTheme.typography.footnote, color = SignerTheme.colors.secondaryLabel, maxLines = 2, modifier = modifier.padding(horizontal = 4.dp))
}

/** The [RecoverySuggestionBar] content as the in-app keyboard's top row shows it (same completions, same hints). */
@Composable
internal fun Bip39Candidates.toKeyboardSuggestions(): KeyboardSuggestions = when (this) {
    is Bip39Candidates.Words -> remember(words) { KeyboardSuggestions.Words(words) }
    Bip39Candidates.ChineseHint -> KeyboardSuggestions.Hint(stringResource(L10n.string.each_chinese_word_is_one_character))
    Bip39Candidates.TypeAWord -> KeyboardSuggestions.Hint(stringResource(L10n.string.type_a_word_to_see_suggestions))
    Bip39Candidates.NoMatch -> KeyboardSuggestions.Hint(stringResource(L10n.string.no_matching_word))
    Bip39Candidates.Hidden -> KeyboardSuggestions.None
}

private const val SWITCH_BACK_KEY = "switch-back"

/**
 * One completion, a capsule (iOS `Palette.raised` on the keyboard toolbar). The bar it sits on is the page colour,
 * which in light mode is the raised colour itself: there the chip is the card colour, so it still reads as a button
 * (QA setup-03). Dark keeps the raised grey on the black page.
 */
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
            .background(suggestionChipColor(colors))
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("suggestion.$word")
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** The fill of a suggestion chip on the page-coloured bar (see [SuggestionChip]). */
internal fun suggestionChipColor(colors: SignerColors): Color = if (colors.isDark) colors.raised else colors.card

internal const val EDITOR_TAG = "recovery.input.visible"

private val EDITOR_MIN_HEIGHT = 132.dp
