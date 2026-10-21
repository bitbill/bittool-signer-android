package com.bittool.signer.core.ui.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bittool.signer.core.l10n.R as L10n

/**
 * What the keyboard's top row shows for the word being typed: the editor's own completions ([Words]), one of its hints
 * ([Hint]: "Type a word to see suggestions", "No matching word", "Each Chinese word is one character"), or nothing.
 */
@Immutable
sealed interface KeyboardSuggestions {
    /**
     * Completions in list order, in their canonical spelling (accents included). Each is a chip read "Insert %@" and
     * tagged `suggestion.<word>`, like the editor's suggestion bar. Derived from typed text: [toString] is redacted.
     */
    @Immutable
    class Words(val words: List<String>) : KeyboardSuggestions {
        override fun equals(other: Any?): Boolean = other is Words && other.words == words
        override fun hashCode(): Int = words.hashCode()
        override fun toString(): String = "Words(${words.size}, <redacted>)"
    }

    /** A translated hint in place of the chips. */
    @Immutable
    data class Hint(val text: String) : KeyboardSuggestions

    /** Nothing (the word typed is complete and nothing longer starts with it). */
    data object None : KeyboardSuggestions
}

/**
 * BitTool Signer's own recovery-phrase keyboard (port of Ownbit 5.0's `Bip39Keyboard`, itself the old Android
 * `Bip39KeyboardView`): an air-gapped signer must not let a keyboard app see the recovery phrase, and with this keyboard
 * typing it never involves an input method at all. The editor it types into is hosted by [InAppKeyboardInput], so it
 * has no input-method connection while this keyboard is up.
 *
 * Layout, left to right in every language (an Arabic interface keeps the QWERTY order, like the system's Latin
 * layouts): the suggestion row, then `qwertyuiop` / `asdfghjkl` / paste · `zxcvbnm` · backspace / system keyboard ·
 * space · Done, in lower case — a–z covers the English, Spanish, French, Italian, Czech and Portuguese word lists,
 * which the editor matches without accents. The tray runs under the navigation bar; the keys stay at most 720 dp wide
 * and get shorter on short windows (landscape). Labels follow the font size up to [Bip39KeyboardDefaults.LABEL_FONT_SCALE_CAP]
 * (the rows keep their height). Every key's whole cell, gaps included, is its touch target.
 *
 * Behaviour: a pressed key changes colour, a letter shows its preview above it (drawn in this window, so it is as
 * secure as the window: no popup), every press plays the keyboard-tap haptic through the view (the system's touch
 * feedback setting decides; no sound is played), backspace deletes on touch-down and repeats, faster, while held.
 * Keys never take the focus, so the editor keeps its caret and selection. TalkBack reads every key by name and
 * activates it with a double tap. While it is shown the window is marked accessibility-sensitive (API 34+): only
 * accessibility tools see the keys being pressed.
 *
 * Edits go straight into [state] ([Bip39KeyboardEdits]); nothing is kept elsewhere, logged or copied. [onSuggestion]
 * receives a chip's word (the editor's "Insert" action), [onSwitchToSystemKeyboard] the globe key (ask first:
 * [SwitchToSystemKeyboardDialog]), [onDone] the Done key (hide the keyboard).
 */
@Composable
fun Bip39Keyboard(
    state: TextFieldState,
    suggestions: KeyboardSuggestions,
    onSuggestion: (String) -> Unit,
    onSwitchToSystemKeyboard: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    colors: Bip39KeyboardColors = Bip39KeyboardDefaults.colors(),
) {
    val view = LocalView.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val metrics = rememberKeyboardMetrics()
    val interfaceDirection = LocalLayoutDirection.current
    val tick: () -> Unit = remember(view) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            Unit
        }
    }
    val change: ((CharSequence, TextRange) -> KeyboardEdit?) -> Unit = remember(state) {
        { edit -> edit(state.text, state.selection)?.let(state::applyKeyboardEdit) }
    }
    AccessibilitySensitive(active = true)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .background(colors.tray)
                .drawBehind { drawLine(colors.divider, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx() / 2f) }
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(bottom = metrics.bottomPadding)
                .semantics { isTraversalGroup = true; testTag = Bip39KeyboardTags.KEYBOARD },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = Bip39KeyboardDefaults.MaxKeysWidth)
                    .fillMaxWidth()
                    .padding(horizontal = metrics.trayPadding),
            ) {
                SuggestionRow(suggestions, interfaceDirection, colors, metrics, onPick = { word -> tick(); onSuggestion(word) })
                val letter: @Composable RowScope.(Char, Float, Alignment, PreviewEdge) -> Unit = { c, weight, alignment, edge ->
                    LetterKey(c, weight, alignment, edge, colors, metrics, onPress = tick) {
                        change { text, selection -> Bip39KeyboardEdits.insert(text, selection, c.toString()) }
                    }
                }
                KeyRow(metrics) {
                    ROWS[0].forEachIndexed { i, c ->
                        val edge = when (i) {
                            0 -> PreviewEdge.Start
                            ROWS[0].lastIndex -> PreviewEdge.End
                            else -> PreviewEdge.Center
                        }
                        letter(c, 1f, Alignment.Center, edge)
                    }
                }
                KeyRow(metrics) {
                    // The half-key indents belong to "a" and "l": no dead zone at the row's ends.
                    ROWS[1].forEachIndexed { i, c ->
                        when (i) {
                            0 -> letter(c, 1.5f, Alignment.CenterEnd, PreviewEdge.Center)
                            ROWS[1].lastIndex -> letter(c, 1.5f, Alignment.CenterStart, PreviewEdge.Center)
                            else -> letter(c, 1f, Alignment.Center, PreviewEdge.Center)
                        }
                    }
                }
                KeyRow(metrics) {
                    val pasteLabel = stringResource(L10n.string.paste_from_clipboard)
                    Key(
                        weight = 1.5f, style = KeyStyle.Function, tag = Bip39KeyboardTags.PASTE, description = pasteLabel,
                        colors = colors, metrics = metrics, onPress = tick,
                        onActivate = {
                            scope.launch {
                                val clip = runCatching {
                                    clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                                }.getOrNull()
                                change { text, selection -> Bip39KeyboardEdits.paste(text, selection, clip) }
                            }
                        },
                    ) { KeyIcon(SignerIcons.Paste, colors.functionContent) }
                    ROWS[2].forEach { c -> letter(c, 1f, Alignment.Center, PreviewEdge.Center) }
                    Key(
                        weight = 1.5f, style = KeyStyle.Function, tag = Bip39KeyboardTags.BACKSPACE,
                        description = stringResource(L10n.string.delete), colors = colors, metrics = metrics, onPress = tick,
                        repeating = true,
                        onActivate = { change(Bip39KeyboardEdits::deleteBackward) },
                    ) { KeyIcon(Icons.AutoMirrored.Rounded.Backspace, colors.functionContent) }
                }
                KeyRow(metrics) {
                    val systemLabel = stringResource(L10n.string.keyboard_system)
                    Key(
                        weight = 3.5f, style = KeyStyle.Function, tag = Bip39KeyboardTags.SYSTEM, description = null,
                        colors = colors, metrics = metrics, onPress = tick, onActivate = onSwitchToSystemKeyboard,
                    ) {
                        Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            KeyIcon(SignerIcons.Language, colors.functionContent, size = 18.dp)
                            Spacer(Modifier.width(5.dp))
                            FittedLabel(systemLabel, SignerTheme.typography.subheadline.cappedFontScale(), colors.functionContent, Modifier.weight(1f, fill = false))
                        }
                    }
                    Key(
                        weight = 3.5f, style = KeyStyle.Letter, tag = Bip39KeyboardTags.SPACE,
                        description = stringResource(L10n.string.keyboard_space), colors = colors, metrics = metrics, onPress = tick,
                        onActivate = { change { text, selection -> Bip39KeyboardEdits.insert(text, selection, " ") } },
                    ) { KeyIcon(SignerIcons.SpaceBar, colors.keyContent) }
                    Key(
                        weight = 3f, style = KeyStyle.Action, tag = Bip39KeyboardTags.DONE, description = null,
                        colors = colors, metrics = metrics, onPress = tick, onActivate = onDone,
                    ) {
                        FittedLabel(
                            stringResource(L10n.string.done),
                            SignerTheme.typography.body.semibold().cappedFontScale(),
                            colors.actionContent,
                            Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "BitTool keyboard": shown above the system keyboard while the recovery phrase is typed with it, switches back to
 * [Bip39Keyboard]. A tinted capsule with a keyboard glyph, at least 48 dp tall for touch, tagged
 * [Bip39KeyboardTags.SWITCH_BACK].
 */
@Composable
fun InAppKeyboardChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val style = SignerTheme.typography.subheadline.semibold()
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(SignerShapes.capsule)
            .background(colors.accent.copy(alpha = 0.14f).compositeOver(colors.page))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { testTag = Bip39KeyboardTags.SWITCH_BACK }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Keyboard, contentDescription = null, tint = colors.accentOnTint, modifier = Modifier.size(18.dp))
        Text(stringResource(L10n.string.keyboard_bittool), style = style, color = colors.accentOnTint, maxLines = 1)
    }
}

/** Test tags of [Bip39Keyboard] (letters: [letter]). */
object Bip39KeyboardTags {
    const val KEYBOARD: String = "keyboard"
    const val PASTE: String = "keyboard.paste"
    const val BACKSPACE: String = "keyboard.backspace"
    const val SYSTEM: String = "keyboard.system"
    const val SPACE: String = "keyboard.space"
    const val DONE: String = "keyboard.done"
    const val SWITCH_BACK: String = "keyboard.inApp"

    /** The key that types [c]. */
    fun letter(c: Char): String = "keyboard.key.$c"
}

/** Colours of [Bip39Keyboard] ([Bip39KeyboardDefaults.colors]). */
@Immutable
class Bip39KeyboardColors(
    val tray: Color,
    val divider: Color,
    val key: Color,
    val keyPressed: Color,
    val keyContent: Color,
    val keyShadow: Color,
    val functionKey: Color,
    val functionKeyPressed: Color,
    val functionContent: Color,
    val action: Color,
    val actionPressed: Color,
    val actionContent: Color,
    val chip: Color,
    val chipPressed: Color,
    val chipContent: Color,
    val hint: Color,
    val previewBorder: Color,
)

object Bip39KeyboardDefaults {
    /** Labels grow with the font size up to this factor (font scale 2.0 must not overflow a key). */
    const val LABEL_FONT_SCALE_CAP: Float = 1.3f

    /** The key area's maximum width (tablets, landscape): centred, the tray stays full width. */
    val MaxKeysWidth: Dp = 720.dp

    /**
     * From the design-system palette. Light: a keyboard grey tray (`fill` over the page), white letter keys (`card`),
     * function keys one `fill` step darker, Done in the accent. Dark: the tray is `card`, letter keys the segmented
     * thumb grey, function keys `fill` over the tray. Suggestion chips are capsules as on the editor's bar.
     */
    @Composable
    @ReadOnlyComposable
    fun colors(colors: SignerColors = SignerTheme.colors): Bip39KeyboardColors {
        val tray = if (colors.isDark) colors.card else colors.fill.compositeOver(colors.page)
        val key = if (colors.isDark) colors.segmentThumb else colors.card
        val function = colors.fill.compositeOver(tray)
        return Bip39KeyboardColors(
            tray = tray,
            divider = colors.separator,
            key = key,
            keyPressed = function,
            keyContent = colors.label,
            keyShadow = if (colors.isDark) Color.Black.copy(alpha = 0.6f) else colors.separator,
            functionKey = function,
            functionKeyPressed = key,
            functionContent = colors.label,
            action = colors.accent,
            actionPressed = colors.accent.copy(alpha = 0.75f).compositeOver(tray),
            actionContent = colors.onAccent,
            chip = if (colors.isDark) SignerColors.DarkElevated.raised else colors.card,
            chipPressed = function,
            chipContent = colors.label,
            hint = colors.secondaryLabel,
            previewBorder = colors.separator,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Metrics

/** Sizes of one keyboard, chosen from the window height ([keyboardMetrics]). */
@Immutable
internal class KeyboardMetrics(
    val suggestionRow: Dp,
    /** A key row's height, gap included (the whole height is touchable). */
    val row: Dp,
    val rowGap: Dp,
    val keyGap: Dp,
    val trayPadding: Dp,
    val bottomPadding: Dp,
) {
    val key: Dp get() = row - rowGap

    /** The letter preview: a little taller than the key, but inside the keyboard above the first row. */
    val previewHeight: Dp get() = minOf(key + 6.dp, suggestionRow + rowGap / 2 - 2.dp)
}

/**
 * The keyboard for a window [windowHeight] tall: full size from 640 dp (phones in portrait), shorter rows down to
 * 400 dp (phones in landscape), so the page keeps room for the editor.
 */
internal fun keyboardMetrics(windowHeight: Dp): KeyboardMetrics {
    val t = ((windowHeight - COMPACT_HEIGHT) / (REGULAR_HEIGHT - COMPACT_HEIGHT)).coerceIn(0f, 1f)
    return KeyboardMetrics(
        suggestionRow = lerp(38.dp, 46.dp, t),
        row = lerp(40.dp, 50.dp, t),
        rowGap = lerp(6.dp, 8.dp, t),
        keyGap = 6.dp,
        trayPadding = 3.dp,
        bottomPadding = lerp(2.dp, 6.dp, t),
    )
}

private val COMPACT_HEIGHT = 400.dp
private val REGULAR_HEIGHT = 640.dp

@Composable
@ReadOnlyComposable
private fun rememberKeyboardMetrics(): KeyboardMetrics = keyboardMetrics(LocalWindowInfo.current.containerDpSize.height)

/**
 * This style with its font size (and line height) growing with the font scale only up to [cap] times the design size:
 * at font scale 2.0 a key label stays readable without overflowing its key.
 */
@Composable
@ReadOnlyComposable
internal fun TextStyle.cappedFontScale(cap: Float = Bip39KeyboardDefaults.LABEL_FONT_SCALE_CAP): TextStyle {
    if (!fontSize.isSp) return this
    val density = LocalDensity.current
    fun capped(size: TextUnit): TextUnit {
        if (!size.isSp) return size
        val scaled = with(density) { size.toDp() }
        val limit = (size.value * cap).dp
        return if (scaled <= limit) size else with(density) { limit.toSp() }
    }
    return copy(fontSize = capped(fontSize), lineHeight = capped(lineHeight))
}

// ---------------------------------------------------------------------------------------------------------------------
// Rows and keys

private val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/** Backspace repeat: first repeat after [REPEAT_DELAY_MS], then every [REPEAT_INTERVAL_MS], accelerating. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 70L
private const val REPEAT_MIN_INTERVAL_MS = 35L

private val KeyShape = RoundedCornerShape(8.dp)
private val KEY_RADIUS = 8.dp

private enum class KeyStyle { Letter, Function, Action }

/** Where a letter's preview sits: centred over the key, or kept inside the keyboard at the row's ends. */
private enum class PreviewEdge { Start, Center, End }

@Composable
private fun KeyRow(metrics: KeyboardMetrics, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(metrics.row), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun RowScope.LetterKey(
    letter: Char,
    weight: Float,
    alignment: Alignment,
    edge: PreviewEdge,
    colors: Bip39KeyboardColors,
    metrics: KeyboardMetrics,
    onPress: () -> Unit,
    onActivate: () -> Unit,
) {
    val label = letter.toString()
    val measurer = rememberTextMeasurer()
    val previewStyle = SignerTheme.typography.title1.cappedFontScale()
    val preview = remember(measurer, previewStyle, label) { measurer.measure(label, previewStyle) }
    Key(
        weight = weight,
        style = KeyStyle.Letter,
        tag = Bip39KeyboardTags.letter(letter),
        description = null,
        colors = colors,
        metrics = metrics,
        onPress = onPress,
        onActivate = onActivate,
        // The visible key keeps a letter's width; the rest of a wider cell ("a", "l") is touch area.
        visualFraction = 1f / weight,
        visualAlignment = alignment,
        preview = KeyPreview(preview, edge),
    ) {
        Text(label, style = SignerTheme.typography.title2.cappedFontScale(), color = colors.keyContent, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Immutable
private class KeyPreview(val layout: TextLayoutResult, val edge: PreviewEdge)

/**
 * One key: the whole cell takes touches (no dead gaps between keys); the visible key is inset by half the gaps. Fires
 * on release inside the key ([repeating]: on touch-down, then repeatedly while held). Never focusable. TalkBack: a
 * button named by [description] or by its text, activated by [onActivate].
 */
@Composable
private fun RowScope.Key(
    weight: Float,
    style: KeyStyle,
    tag: String,
    description: String?,
    colors: Bip39KeyboardColors,
    metrics: KeyboardMetrics,
    onPress: () -> Unit,
    onActivate: () -> Unit,
    repeating: Boolean = false,
    visualFraction: Float = 1f,
    visualAlignment: Alignment = Alignment.Center,
    preview: KeyPreview? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val latestPress by rememberUpdatedState(onPress)
    val latestActivate by rememberUpdatedState(onActivate)
    val (fill, pressedFill) = when (style) {
        KeyStyle.Letter -> colors.key to colors.keyPressed
        KeyStyle.Function -> colors.functionKey to colors.functionKeyPressed
        KeyStyle.Action -> colors.action to colors.actionPressed
    }
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .focusProperties { canFocus = false }
            .pointerInput(repeating) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        pressed = true
                        latestPress()
                        val repeater = if (repeating) {
                            latestActivate()
                            launch {
                                delay(REPEAT_DELAY_MS)
                                var interval = REPEAT_INTERVAL_MS
                                while (true) {
                                    latestPress()
                                    latestActivate()
                                    delay(interval)
                                    interval = (interval - 5).coerceAtLeast(REPEAT_MIN_INTERVAL_MS)
                                }
                            }
                        } else {
                            null
                        }
                        try {
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                if (!repeating) latestActivate()
                            }
                        } finally {
                            repeater?.cancel()
                            pressed = false
                        }
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (description != null) contentDescription = description
                onClick {
                    latestActivate()
                    true
                }
                testTag = tag
            },
    ) {
        Box(
            Modifier
                .align(visualAlignment)
                .fillMaxWidth(visualFraction)
                .fillMaxHeight()
                .padding(horizontal = metrics.keyGap / 2, vertical = metrics.rowGap / 2)
                .then(if (preview != null) Modifier.keyPreview(pressed, preview, colors, metrics) else Modifier)
                .keyShadow(colors.keyShadow)
                .clip(KeyShape)
                .background(if (pressed) pressedFill else fill),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun KeyIcon(icon: ImageVector, tint: Color, size: Dp = 22.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** The 1 dp darker edge under a key: the raised look of a system keyboard. */
private fun Modifier.keyShadow(color: Color): Modifier = drawBehind {
    val r = KEY_RADIUS.toPx()
    drawRoundRect(color, topLeft = Offset(0f, 1.dp.toPx()), size = size, cornerRadius = CornerRadius(r, r))
}

/**
 * While [pressed], the letter enlarged in a balloon above the key (over the row above it), in the key's colour with a
 * soft shadow and a hairline. Drawn by the key itself, in this window: no popup window exists, so the preview is under
 * the activity's FLAG_SECURE like everything else, and nothing reaches the accessibility tree.
 */
private fun Modifier.keyPreview(pressed: Boolean, preview: KeyPreview, colors: Bip39KeyboardColors, metrics: KeyboardMetrics): Modifier =
    drawWithContent {
        drawContent()
        if (!pressed) return@drawWithContent
        val grow = 6.dp.toPx()
        val width = maxOf(size.width + 2 * grow, size.width * 1.4f)
        val height = metrics.previewHeight.toPx()
        val left = when (preview.edge) {
            PreviewEdge.Start -> 0f
            PreviewEdge.Center -> (size.width - width) / 2f
            PreviewEdge.End -> size.width - width
        }
        val top = -height - 2.dp.toPx()
        val radius = CornerRadius(KEY_RADIUS.toPx() + 2.dp.toPx())
        drawRoundRect(Color.Black.copy(alpha = 0.16f), Offset(left, top + 2.dp.toPx()), Size(width, height), radius)
        drawRoundRect(colors.key, Offset(left, top), Size(width, height), radius)
        drawRoundRect(colors.previewBorder, Offset(left, top), Size(width, height), radius, style = Stroke(1.dp.toPx() / 2f))
        val text = preview.layout
        drawText(
            text,
            color = colors.keyContent,
            topLeft = Offset(left + (width - text.size.width) / 2f, top + (height - text.size.height) / 2f),
        )
    }

// ---------------------------------------------------------------------------------------------------------------------
// Suggestion row

@Composable
private fun SuggestionRow(
    suggestions: KeyboardSuggestions,
    interfaceDirection: LayoutDirection,
    colors: Bip39KeyboardColors,
    metrics: KeyboardMetrics,
    onPick: (String) -> Unit,
) {
    // The same height with or without chips, so the keys never jump.
    Box(Modifier.fillMaxWidth().height(metrics.suggestionRow), contentAlignment = Alignment.CenterStart) {
        when (suggestions) {
            is KeyboardSuggestions.Words -> {
                // A new prefix starts at the first chip again.
                val list = remember(suggestions) { LazyListState() }
                LazyRow(
                    state = list,
                    modifier = Modifier.fillMaxHeight().fadingEdges(list),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(suggestions.words, key = { it }) { word -> SuggestionChip(word, colors, onClick = { onPick(word) }) }
                }
            }
            is KeyboardSuggestions.Hint -> CompositionLocalProvider(LocalLayoutDirection provides interfaceDirection) {
                // A translated sentence: in the interface direction, from its start edge.
                Text(
                    suggestions.text,
                    style = SignerTheme.typography.footnote.cappedFontScale(),
                    color = colors.hint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            KeyboardSuggestions.None -> Unit
        }
    }
}

/** One completion: the whole row height is the touch target, the capsule is drawn inside it. Never focusable. */
@Composable
private fun SuggestionChip(word: String, colors: Bip39KeyboardColors, onClick: () -> Unit) {
    val description = stringResource(L10n.string.insert_x, word)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .focusProperties { canFocus = false }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                testTag = "suggestion.$word"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = word,
            style = SignerTheme.typography.subheadline.monospaced().semibold().ltr().cappedFontScale(),
            color = colors.chipContent,
            maxLines = 1,
            modifier = Modifier
                .clip(SignerShapes.capsule)
                .background(if (pressed) colors.chipPressed else colors.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** Fades the chips out over 24 dp at each end where more can be scrolled into view (draw phase only). */
private fun Modifier.fadingEdges(state: LazyListState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = 24.dp.toPx().coerceAtMost(size.width / 2f)
            if (state.canScrollForward) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollBackward) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

// ---------------------------------------------------------------------------------------------------------------------
// Labels

/**
 * A key label that shrinks (down to 11 dp on one line) until it fits its key, then wraps onto two lines (down to
 * 10 dp): "System keyboard" is long in some languages (Russian «Системная клавиатура»). Sizes are compared as drawn,
 * so the floor holds at every font scale. Laid out in its own direction.
 */
@Composable
private fun FittedLabel(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        val base = style.contentDirection()
        val fit: Pair<TextUnit, Int> = remember(text, base, maxWidth, maxHeight, density) {
            fun sized(dp: Float): TextStyle {
                val size = with(density) { dp.dp.toSp() }
                return base.copy(fontSize = size, lineHeight = size * LABEL_LINE_HEIGHT)
            }
            fun fits(dp: Float, lines: Int): Boolean {
                val layout = measurer.measure(
                    text,
                    sized(dp),
                    maxLines = lines,
                    softWrap = lines > 1,
                    constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(0)),
                )
                return !layout.hasVisualOverflow && layout.size.width <= maxWidth && layout.size.height <= maxHeight
            }
            val start = with(density) { base.fontSize.toDp() }.value
            var size = start
            while (size >= MIN_ONE_LINE_DP) {
                if (fits(size, 1)) return@remember sized(size).fontSize to 1
                size -= 0.5f
            }
            size = start
            while (size > MIN_LABEL_DP) {
                if (fits(size, 2)) return@remember sized(size).fontSize to 2
                size -= 0.5f
            }
            sized(minOf(start, MIN_LABEL_DP)).fontSize to 2
        }
        Text(
            text = text,
            style = base.copy(fontSize = fit.first, lineHeight = fit.first * LABEL_LINE_HEIGHT),
            color = color,
            maxLines = fit.second,
            softWrap = fit.second > 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private const val LABEL_LINE_HEIGHT = 1.2f
private const val MIN_ONE_LINE_DP = 11f
private const val MIN_LABEL_DP = 10f
